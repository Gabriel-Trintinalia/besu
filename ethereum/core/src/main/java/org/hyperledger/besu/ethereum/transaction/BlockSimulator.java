/*
 * Copyright contributors to Besu.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package org.hyperledger.besu.ethereum.transaction;

import static org.hyperledger.besu.ethereum.mainnet.feemarket.ExcessBlobGasCalculator.calculateExcessBlobGasForParent;
import static org.hyperledger.besu.ethereum.transaction.BlockStateCallChain.normalizeBlockStateCalls;
import static org.hyperledger.besu.ethereum.transaction.BlockStateOverrider.applyStateOverrides;

import org.hyperledger.besu.datatypes.BlobGas;
import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.ethereum.core.Block;
import org.hyperledger.besu.ethereum.core.BlockBody;
import org.hyperledger.besu.ethereum.core.BlockHeader;
import org.hyperledger.besu.ethereum.core.BlockHeaderBuilder;
import org.hyperledger.besu.ethereum.core.BlockHeaderFunctions;
import org.hyperledger.besu.ethereum.core.Difficulty;
import org.hyperledger.besu.ethereum.core.MiningConfiguration;
import org.hyperledger.besu.ethereum.core.MutableWorldState;
import org.hyperledger.besu.ethereum.core.ParsedExtraData;
import org.hyperledger.besu.ethereum.core.Transaction;
import org.hyperledger.besu.ethereum.core.TransactionReceipt;
import org.hyperledger.besu.ethereum.mainnet.BodyValidation;
import org.hyperledger.besu.ethereum.mainnet.ImmutableTransactionValidationParams;
import org.hyperledger.besu.ethereum.mainnet.MainnetBlockHeaderFunctions;
import org.hyperledger.besu.ethereum.mainnet.MainnetTransactionProcessor;
import org.hyperledger.besu.ethereum.mainnet.MiningBeneficiaryCalculator;
import org.hyperledger.besu.ethereum.mainnet.ProtocolSchedule;
import org.hyperledger.besu.ethereum.mainnet.ProtocolSpec;
import org.hyperledger.besu.ethereum.mainnet.TransactionValidationParams;
import org.hyperledger.besu.ethereum.mainnet.feemarket.BaseFeeMarket;
import org.hyperledger.besu.ethereum.mainnet.feemarket.FeeMarket;
import org.hyperledger.besu.ethereum.transaction.exceptions.BlockSimulationException;
import org.hyperledger.besu.ethereum.trie.diffbased.bonsai.cache.NoopBonsaiCachedMerkleTrieLoader;
import org.hyperledger.besu.ethereum.trie.diffbased.bonsai.worldview.BonsaiWorldState;
import org.hyperledger.besu.ethereum.worldstate.WorldStateArchive;
import org.hyperledger.besu.evm.tracing.OperationTracer;
import org.hyperledger.besu.evm.worldstate.WorldUpdater;
import org.hyperledger.besu.plugin.data.BlockOverrides;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.BiFunction;

import com.google.common.annotations.VisibleForTesting;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;

/**
 * Simulates the execution of a block, processing transactions and applying state overrides. This
 * class is responsible for simulating the execution of a block, which involves processing
 * transactions and applying state overrides. It provides a way to test and validate the behavior of
 * a block without actually executing it on the blockchain. The simulator takes into account various
 * factors, such as the block header, transaction calls, and state overrides, to simulate the
 * execution of the block. It returns a list of simulation results, which include the final block
 * header, transaction receipts, and other relevant information.
 */
public class BlockSimulator {

  private static final ImmutableTransactionValidationParams STRICT_VALIDATION_PARAMS =
      ImmutableTransactionValidationParams.builder()
          .from(TransactionValidationParams.transactionSimulator())
          .build();

  private static final ImmutableTransactionValidationParams SIMULATION_PARAMS =
      ImmutableTransactionValidationParams.builder()
          .from(TransactionValidationParams.transactionSimulator())
          .isAllowExceedingBalance(true)
          .build();

  private final TransactionSimulator transactionSimulator;
  private final WorldStateArchive worldStateArchive;
  private final ProtocolSchedule protocolSchedule;
  private final MiningConfiguration miningConfiguration;

  public BlockSimulator(
      final WorldStateArchive worldStateArchive,
      final ProtocolSchedule protocolSchedule,
      final TransactionSimulator transactionSimulator,
      final MiningConfiguration miningConfiguration) {
    this.worldStateArchive = worldStateArchive;
    this.protocolSchedule = protocolSchedule;
    this.miningConfiguration = miningConfiguration;
    this.transactionSimulator = transactionSimulator;
  }

  /**
   * Processes a list of BlockStateCalls sequentially, collecting the results.
   *
   * @param header The block header for all simulations.
   * @param blockSimulationParameter The BlockSimulationParameter containing the block state calls.
   * @return A list of BlockSimulationResult objects from processing each BlockStateCall.
   */
  public List<BlockSimulationResult> process(
      final BlockHeader header, final BlockSimulationParameter blockSimulationParameter) {
    try (final MutableWorldState ws = getWorldState(header)) {
      return process(header, blockSimulationParameter, ws);
    } catch (IllegalArgumentException | BlockSimulationException e) {
      throw e;
    } catch (final Exception e) {
      throw new RuntimeException("Error simulating block", e);
    }
  }

  /**
   * Processes a list of BlockStateCalls sequentially, collecting the results.
   *
   * @param blockHeader The block header for all simulations.
   * @param simulationParameter The BlockSimulationParameter containing the block state calls.
   * @param worldState The initial MutableWorldState to start with.
   * @return A list of BlockSimulationResult objects from processing each BlockStateCall.
   */
  public List<BlockSimulationResult> process(
      final BlockHeader blockHeader,
      final BlockSimulationParameter simulationParameter,
      final MutableWorldState worldState) {
    List<BlockSimulationResult> results = new ArrayList<>();

    // Fill gaps between blocks and set the correct block number and timestamp
    List<BlockStateCall> blockStateCalls =
        normalizeBlockStateCalls(simulationParameter.getBlockStateCalls(), blockHeader);

    BlockHeader currentBlockHeader = blockHeader;
    for (BlockStateCall stateCall : blockStateCalls) {
      BlockSimulationResult result =
          processBlockStateCall(
              currentBlockHeader, stateCall, worldState, simulationParameter.isValidation());
      results.add(result);
      currentBlockHeader = result.getBlock().getHeader();
    }
    return results;
  }

  /**
   * Processes a single BlockStateCall, simulating the block execution.
   *
   * @param baseBlockHeader The block header for the simulation.
   * @param blockStateCall The BlockStateCall to process.
   * @param ws The MutableWorldState to use for the simulation.
   * @return A BlockSimulationResult from processing the BlockStateCall.
   */
  private BlockSimulationResult processBlockStateCall(
      final BlockHeader baseBlockHeader,
      final BlockStateCall blockStateCall,
      final MutableWorldState ws,
      final boolean shouldValidate) {

    blockStateCall.getStateOverrideMap().ifPresent(overrides -> applyStateOverrides(overrides, ws));

    BlockOverrides blockOverrides = blockStateCall.getBlockOverrides();
    ProtocolSpec protocolSpec =
        protocolSchedule.getForNextBlockHeader(
            baseBlockHeader, blockOverrides.getTimestamp().orElseThrow());

    BlockHeader overridenBaseblockHeader =
        overrideBlockHeader(baseBlockHeader, protocolSpec, blockOverrides, shouldValidate);

    // Create the transaction processor with precompile address overrides
    MainnetTransactionProcessor transactionProcessor =
        new SimulationTransactionProcessorFactory(protocolSchedule)
            .getTransactionProcessor(
                overridenBaseblockHeader, blockStateCall.getStateOverrideMap());

    BlockCallSimulationResult simulatorResults =
        processTransactions(
            overridenBaseblockHeader,
            blockStateCall,
            ws,
            protocolSpec,
            shouldValidate,
            transactionProcessor);

    return createFinalBlock(overridenBaseblockHeader, simulatorResults, blockOverrides, ws);
  }

  protected BlockCallSimulationResult processTransactions(
      final BlockHeader blockHeader,
      final BlockStateCall blockStateCall,
      final MutableWorldState ws,
      final ProtocolSpec protocolSpec,
      final boolean shouldValidate,
      final MainnetTransactionProcessor transactionProcessor) {
    final var transactionReceiptFactory = protocolSpec.getTransactionReceiptFactory();
    TransactionValidationParams transactionValidationParams =
        shouldValidate ? STRICT_VALIDATION_PARAMS : SIMULATION_PARAMS;

    BlockCallSimulationResult blockCallSimulationResult =
        new BlockCallSimulationResult(transactionReceiptFactory, blockHeader.getGasLimit());

    MiningBeneficiaryCalculator miningBeneficiaryCalculator =
        blockStateCall
            .getBlockOverrides()
            .getFeeRecipient()
            .<MiningBeneficiaryCalculator>map(feeRecipient -> header -> feeRecipient)
            .orElseGet(protocolSpec::getMiningBeneficiaryCalculator);

    for (CallParameter callParameter : blockStateCall.getCalls()) {
      final WorldUpdater transactionUpdater = ws.updater();

      long gasLimit =
          transactionSimulator.calculateSimulationGasCap(
              callParameter.getGasLimit(), blockCallSimulationResult.getRemainingGas());

      BiFunction<ProtocolSpec, Optional<BlockHeader>, Wei> blobGasPricePerGasSupplier =
          getBlobGasPricePerGasSupplier(
              blockStateCall.getBlockOverrides(), transactionValidationParams);

      final Optional<TransactionSimulatorResult> transactionSimulatorResult =
          transactionSimulator.processWithWorldUpdater(
              callParameter,
              Optional.empty(), // We have already applied state overrides on block level
              transactionValidationParams,
              OperationTracer.NO_TRACING,
              blockHeader,
              transactionUpdater,
              miningBeneficiaryCalculator,
              gasLimit,
              transactionProcessor,
              blobGasPricePerGasSupplier);

      TransactionSimulatorResult transactionSimulationResult =
          transactionSimulatorResult.orElseThrow(
              () -> new BlockSimulationException("Transaction simulator result is empty"));

      if (transactionSimulationResult.isInvalid()) {
        throw new BlockSimulationException(
            "Transaction simulator result is invalid", transactionSimulationResult);
      }
      transactionUpdater.commit();
      blockCallSimulationResult.add(transactionSimulationResult, ws);
    }
    return blockCallSimulationResult;
  }

  private BlockSimulationResult createFinalBlock(
      final BlockHeader blockHeader,
      final BlockCallSimulationResult results,
      final BlockOverrides blockOverrides,
      final MutableWorldState ws) {

    List<Transaction> transactions = results.getTransactions();
    List<TransactionReceipt> receipts = results.getReceipts();
    List<TransactionSimulatorResult> simulationResults = results.getTransactionSimulationResults();

    BlockHeader finalBlockHeader =
        BlockHeaderBuilder.createDefault()
            .populateFrom(blockHeader)
            .ommersHash(BodyValidation.ommersHash(List.of()))
            .stateRoot(blockOverrides.getStateRoot().orElseGet(ws::rootHash))
            .transactionsRoot(BodyValidation.transactionsRoot(transactions))
            .receiptsRoot(BodyValidation.receiptsRoot(receipts))
            .logsBloom(BodyValidation.logsBloom(receipts))
            .gasUsed(results.getCumulativeGasUsed())
            .withdrawalsRoot(BodyValidation.withdrawalsRoot(List.of()))
            .requestsHash(null)
            .extraData(blockOverrides.getExtraData().orElse(Bytes.EMPTY))
            .blockHeaderFunctions(new BlockSimulationBlockHeaderFunctions(blockOverrides))
            .buildBlockHeader();

    Block block =
        new Block(finalBlockHeader, new BlockBody(transactions, List.of(), Optional.of(List.of())));
    return new BlockSimulationResult(block, receipts, simulationResults);
  }

  /**
   * Applies block header overrides to the block header.
   *
   * @param header The original block header.
   * @param newProtocolSpec The ProtocolSpec for the block.
   * @param blockOverrides The BlockOverrides to apply.
   * @return The modified block header.
   */
  @VisibleForTesting
  protected BlockHeader overrideBlockHeader(
      final BlockHeader header,
      final ProtocolSpec newProtocolSpec,
      final BlockOverrides blockOverrides,
      final boolean shouldValidate) {
    long timestamp = blockOverrides.getTimestamp().orElseThrow();
    long blockNumber = blockOverrides.getBlockNumber().orElseThrow();

    BlockHeaderBuilder builder =
        BlockHeaderBuilder.createDefault()
            .parentHash(header.getHash())
            .timestamp(timestamp)
            .number(blockNumber)
            .coinbase(
                blockOverrides
                    .getFeeRecipient()
                    .orElseGet(() -> miningConfiguration.getCoinbase().orElseThrow()))
            .difficulty(
                blockOverrides.getDifficulty().map(Difficulty::of).orElseGet(header::getDifficulty))
            .gasLimit(
                blockOverrides
                    .getGasLimit()
                    .orElseGet(() -> getNextGasLimit(newProtocolSpec, header, blockNumber)))
            .baseFee(
                blockOverrides
                    .getBaseFeePerGas()
                    .orElseGet(
                        () ->
                            shouldValidate
                                ? getNextBaseFee(newProtocolSpec, header, blockNumber)
                                : Wei.ZERO))
            .extraData(blockOverrides.getExtraData().orElse(Bytes.EMPTY))
            .parentBeaconBlockRoot(Bytes32.ZERO)
            .prevRandao(Bytes32.ZERO);

    blockOverrides.getPrevRandao().ifPresent(builder::prevRandao);
    blockOverrides.getMixHash().ifPresent(builder::mixHash);

    return builder
        .blockHeaderFunctions(new BlockSimulationBlockHeaderFunctions(blockOverrides))
        .buildBlockHeader();
  }

  private BiFunction<ProtocolSpec, Optional<BlockHeader>, Wei> getBlobGasPricePerGasSupplier(
      final BlockOverrides blockOverrides,
      final TransactionValidationParams transactionValidationParams) {
    if (blockOverrides.getBlobBaseFee().isPresent()) {
      return (protocolSchedule, blockHeader) -> blockOverrides.getBlobBaseFee().get();
    }
    return (protocolSpec, maybeParentHeader) -> {
      if (transactionValidationParams.isAllowExceedingBalance()) {
        return Wei.ZERO;
      }
      return protocolSpec
          .getFeeMarket()
          .blobGasPricePerGas(
              maybeParentHeader
                  .map(parent -> calculateExcessBlobGasForParent(protocolSpec, parent))
                  .orElse(BlobGas.ZERO));
    };
  }

  private long getNextGasLimit(
      final ProtocolSpec protocolSpec, final BlockHeader parentHeader, final long blockNumber) {
    return protocolSpec
        .getGasLimitCalculator()
        .nextGasLimit(
            parentHeader.getGasLimit(),
            miningConfiguration.getTargetGasLimit().orElse(parentHeader.getGasLimit()),
            blockNumber);
  }

  private Wei getNextBaseFee(
      final ProtocolSpec protocolSpec, final BlockHeader parentHeader, final long blockNumber) {
    return Optional.of(protocolSpec.getFeeMarket())
        .filter(FeeMarket::implementsBaseFee)
        .map(BaseFeeMarket.class::cast)
        .map(
            feeMarket ->
                feeMarket.computeBaseFee(
                    blockNumber,
                    parentHeader.getBaseFee().orElse(Wei.ZERO),
                    parentHeader.getGasUsed(),
                    feeMarket.targetGasUsed(parentHeader)))
        .orElse(null);
  }

  private SimulationWorldState getWorldState(final BlockHeader blockHeader) {
    final MutableWorldState ws =
        worldStateArchive
            .getMutable(blockHeader, false)
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "Public world state not available for block " + blockHeader.toLogString()));
    return new SimulationWorldState((BonsaiWorldState) ws);
  }

  private static class BlockSimulationBlockHeaderFunctions implements BlockHeaderFunctions {

    private final BlockOverrides blockOverrides;
    private final MainnetBlockHeaderFunctions blockHeaderFunctions =
        new MainnetBlockHeaderFunctions();

    private BlockSimulationBlockHeaderFunctions(final BlockOverrides blockOverrides) {
      this.blockOverrides = blockOverrides;
    }

    @Override
    public Hash hash(final BlockHeader header) {
      return blockOverrides.getBlockHash().orElseGet(() -> blockHeaderFunctions.hash(header));
    }

    @Override
    public ParsedExtraData parseExtraData(final BlockHeader header) {
      return blockHeaderFunctions.parseExtraData(header);
    }
  }

  private static class SimulationWorldState extends BonsaiWorldState {

    private SimulationWorldState(final BonsaiWorldState mutableWorldState) {
      super(mutableWorldState, new NoopBonsaiCachedMerkleTrieLoader());
    }

    @Override
    public Hash rootHash() {
      return calculateRootHash(Optional.empty(), getAccumulator().copy());
    }
  }
}
