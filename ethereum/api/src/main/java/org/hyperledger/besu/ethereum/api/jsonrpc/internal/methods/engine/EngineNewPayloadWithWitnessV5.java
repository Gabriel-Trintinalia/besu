/*
 * Copyright contributors to Hyperledger Besu.
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
package org.hyperledger.besu.ethereum.api.jsonrpc.internal.methods.engine;

import static org.hyperledger.besu.ethereum.api.jsonrpc.internal.methods.ExecutionEngineJsonRpcMethod.EngineStatus.INVALID;
import static org.hyperledger.besu.ethereum.api.jsonrpc.internal.methods.ExecutionEngineJsonRpcMethod.EngineStatus.VALID;

import org.hyperledger.besu.consensus.merge.blockcreation.MergeMiningCoordinator;
import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.ethereum.BlockProcessingOutputs;
import org.hyperledger.besu.ethereum.BlockProcessingResult;
import org.hyperledger.besu.ethereum.ProtocolContext;
import org.hyperledger.besu.ethereum.api.jsonrpc.RpcMethod;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.parameters.EnginePayloadParameter;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.response.JsonRpcErrorResponse;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.response.JsonRpcResponse;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.response.JsonRpcSuccessResponse;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.response.RpcErrorType;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.results.EngineExecutionWitnessResult;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.results.EnginePayloadWithWitnessResult;
import org.hyperledger.besu.ethereum.core.Block;
import org.hyperledger.besu.ethereum.core.BlockHeader;
import org.hyperledger.besu.ethereum.core.Transaction;
import org.hyperledger.besu.ethereum.eth.manager.EthPeers;
import org.hyperledger.besu.ethereum.mainnet.ProtocolSchedule;
import org.hyperledger.besu.ethereum.mainnet.WitnessOperationTracer;
import org.hyperledger.besu.ethereum.mainnet.block.access.list.BlockAccessList;
import org.hyperledger.besu.ethereum.trie.MerkleTrieException;
import org.hyperledger.besu.ethereum.trie.pathbased.bonsai.storage.BonsaiExecutionWitnessBuilder;
import org.hyperledger.besu.plugin.services.MetricsSystem;
import org.hyperledger.besu.plugin.services.exception.StorageException;

import java.util.List;
import java.util.Optional;

import io.vertx.core.Vertx;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Implements {@code engine_newPayloadWithWitnessV5}: same request/response shape as {@code
 * engine_newPayloadV5} but the success response additionally carries an EIP-8025 execution witness
 * for the imported block.
 */
public class EngineNewPayloadWithWitnessV5 extends EngineNewPayloadV5 {

  private static final Logger LOG = LoggerFactory.getLogger(EngineNewPayloadWithWitnessV5.class);

  public EngineNewPayloadWithWitnessV5(
      final Vertx vertx,
      final ProtocolSchedule timestampSchedule,
      final ProtocolContext protocolContext,
      final MergeMiningCoordinator mergeCoordinator,
      final EthPeers ethPeers,
      final EngineCallListener engineCallListener,
      final MetricsSystem metricsSystem) {
    super(
        vertx,
        timestampSchedule,
        protocolContext,
        mergeCoordinator,
        ethPeers,
        engineCallListener,
        metricsSystem);
  }

  @Override
  public String getName() {
    return RpcMethod.ENGINE_NEW_PAYLOAD_WITH_WITNESS_V5.getMethodName();
  }

  /**
   * Overrides execution to wire a {@link WitnessOperationTracer} directly into block processing so
   * it observes all EVM frames — including system-contract calls (EIP-2935, EIP-4788, EIP-7002,
   * EIP-7251) — during the single import pass. No re-execution needed.
   */
  @Override
  protected JsonRpcResponse executeAndRespond(
      final Object reqId,
      final EnginePayloadParameter blockParam,
      final Block block,
      final Optional<BlockAccessList> maybeBlockAccessList,
      final List<Transaction> blobTransactions,
      final Hash latestValidAncestor) {
    final WitnessOperationTracer witnessTracer =
        new WitnessOperationTracer(
            protocolSchedule.get().getByBlockHeader(block.getHeader()).getGasCalculator());

    final long startTimeNs = System.nanoTime();
    final BlockProcessingResult executionResult =
        mergeCoordinator.rememberBlock(block, maybeBlockAccessList, witnessTracer);

    if (executionResult.isSuccessful()) {
      lastExecutionTimeInNs = System.nanoTime() - startTimeNs;
      logImportedBlockInfo(
          block,
          blobTransactions.stream()
              .map(Transaction::getVersionedHashes)
              .flatMap(Optional::stream)
              .mapToInt(List::size)
              .sum(),
          lastExecutionTimeInNs,
          executionResult.getNbParallelizedTransactions());
      final Hash validHash = block.getHeader().getHash();
      logEnginePayloadResponse(blockParam, validHash, VALID);
      return buildWitnessResponse(reqId, validHash, executionResult, witnessTracer);
    } else {
      if (executionResult.causedBy().isPresent()) {
        final Throwable causedBy = executionResult.causedBy().get();
        if (causedBy instanceof StorageException || causedBy instanceof MerkleTrieException) {
          return new JsonRpcErrorResponse(reqId, RpcErrorType.INTERNAL_ERROR);
        }
      }
      LOG.debug("New payload is invalid: {}", executionResult.errorMessage.get());
      return respondWithInvalid(
          reqId, blockParam, latestValidAncestor, INVALID, executionResult.errorMessage.get());
    }
  }

  private JsonRpcResponse buildWitnessResponse(
      final Object reqId,
      final Hash validHash,
      final BlockProcessingResult executionResult,
      final WitnessOperationTracer witnessTracer) {
    try {
      final BlockHeader blockHeader =
          protocolContext
              .getBlockchain()
              .getBlockHeader(validHash)
              .orElseThrow(() -> new IllegalStateException("Block header not found: " + validHash));

      final BonsaiExecutionWitnessBuilder.Witness witness =
          new BonsaiExecutionWitnessBuilder(
                  protocolContext.getWorldStateArchive(), protocolContext.getBlockchain())
              .buildWitness(
                  blockHeader,
                  executionResult.getYield().flatMap(BlockProcessingOutputs::getBlockAccessList),
                  witnessTracer);
      if (witness.state().isEmpty()) {
        return new JsonRpcErrorResponse(reqId, RpcErrorType.INTERNAL_ERROR);
      }
      return new JsonRpcSuccessResponse(
          reqId,
          new EnginePayloadWithWitnessResult(
              VALID,
              validHash,
              Optional.empty(),
              new EngineExecutionWitnessResult(
                  witness.state(), witness.codes(), witness.headers())));
    } catch (final IllegalStateException e) {
      return new JsonRpcErrorResponse(reqId, RpcErrorType.INTERNAL_ERROR);
    }
  }
}
