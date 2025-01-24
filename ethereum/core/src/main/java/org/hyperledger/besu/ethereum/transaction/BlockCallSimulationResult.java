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

import static org.hyperledger.besu.evm.processor.SimulationMessageCallProcessor.SIMULATION_TRANSFER_ADDRESS;

import org.hyperledger.besu.datatypes.TransactionType;
import org.hyperledger.besu.ethereum.core.MutableWorldState;
import org.hyperledger.besu.ethereum.core.Transaction;
import org.hyperledger.besu.ethereum.core.TransactionReceipt;
import org.hyperledger.besu.ethereum.mainnet.AbstractBlockProcessor;
import org.hyperledger.besu.ethereum.mainnet.ProtocolSpec;
import org.hyperledger.besu.ethereum.processing.TransactionProcessingResult;
import org.hyperledger.besu.evm.log.Log;
import org.hyperledger.besu.evm.worldstate.WorldState;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * This class represents the results of simulating block calls. It maintains a list of simulation
 * results and tracks the cumulative gas used.
 */
public class BlockCallSimulationResult {

  private final List<TransactionSimulatorResultWithMetadata>
      transactionSimulatorResultWithMetadata = new ArrayList<>();
  private long cumulativeGasUsed = 0;
  private final AbstractBlockProcessor.TransactionReceiptFactory transactionReceiptFactory;
  private final long blockGasLimit;

  /**
   * Constructs a new BlockCallSimulationResults instance.
   *
   * @param protocolSpec the protocolSpec
   * @param blockGasLimit the gas limit for the block
   */
  public BlockCallSimulationResult(
      final ProtocolSpec protocolSpec,
      final long blockGasLimit) {
    this.transactionReceiptFactory = createTransactionReceiptFactory(protocolSpec);
    this.blockGasLimit = blockGasLimit;
  }

  /**
   * Returns an unmodifiable list of block call simulation results.
   *
   * @return an unmodifiable list of {@link BlockCallSimulationResult}
   */
  public List<TransactionSimulatorResultWithMetadata> getTransactionSimulatorResultWithMetadata() {
    return Collections.unmodifiableList(transactionSimulatorResultWithMetadata);
  }

  /**
   * Returns the remaining gas available for the block.
   *
   * @return the remaining gas
   */
  public long getRemainingGas() {
    return Math.max(blockGasLimit - cumulativeGasUsed, 0);
  }

  /**
   * Returns the cumulative gas used so far.
   *
   * @return the cumulative gas used
   */
  public long getCumulativeGasUsed() {
    return cumulativeGasUsed;
  }

  /**
   * Adds a new transaction simulation result to the list of block call simulation results. Updates
   * the cumulative gas used and creates a transaction receipt.
   *
   * @param result the result of the transaction simulation
   * @param ws the mutable world state after the transaction
   */
  public void add(final TransactionSimulatorResult result, final MutableWorldState ws) {
    long gasUsedByTransaction = result.result().getEstimateGasUsedByTransaction();
    cumulativeGasUsed += gasUsedByTransaction;

    // Create a transaction receipt with logs that are not transfer logs
    final TransactionReceipt transactionReceipt =
        transactionReceiptFactory.create(
            result.transaction().getType(), result.result(), ws, cumulativeGasUsed);

    transactionSimulatorResultWithMetadata.add(
        new TransactionSimulatorResultWithMetadata(result, transactionReceipt, cumulativeGasUsed));
  }

  /**
   * Returns a list of transactions from the simulation results.
   *
   * @return a list of transactions
   */
  public List<Transaction> getTransactions() {
    return transactionSimulatorResultWithMetadata.stream()
        .map(result -> result.result().transaction())
        .collect(Collectors.toList());
  }

  /**
   * Returns a list of transaction receipts from the simulation results.
   *
   * @return a list of transaction receipts
   */
  public List<TransactionReceipt> getReceipts() {
    return transactionSimulatorResultWithMetadata.stream()
        .map(TransactionSimulatorResultWithMetadata::receipt)
        .collect(Collectors.toList());
  }

  /**
   * Returns a list of transaction simulation results.
   *
   * @return a list of transaction simulation results
   */
  public List<TransactionSimulatorResult> getTransactionSimulationResults() {
    return transactionSimulatorResultWithMetadata.stream()
        .map(TransactionSimulatorResultWithMetadata::result)
        .collect(Collectors.toList());
  }

  /**
   * Creates a new transaction receipt factory that filters out transfer logs.
   *
   * @param protocolSpec the protocol spec
   * @return a new transaction receipt factory
   */
  private AbstractBlockProcessor.TransactionReceiptFactory createTransactionReceiptFactory(
    final ProtocolSpec protocolSpec) {
    return new SimulationTransactionReceiptFactory(protocolSpec.getTransactionReceiptFactory());
  }

  /** This record represents a single block call simulation result. */
  public record TransactionSimulatorResultWithMetadata(
      TransactionSimulatorResult result, TransactionReceipt receipt, long cumulativeGasUsed) {}

  /** Overrides the transaction receipt factory to filter out transfer logs. */
  private record SimulationTransactionReceiptFactory(AbstractBlockProcessor.TransactionReceiptFactory delegate)
      implements AbstractBlockProcessor.TransactionReceiptFactory {

    /**
     * Creates a new transaction receipt with logs that are not transfer logs.
     *
     * @param transactionType the transaction type
     * @param result the transaction processing result
     * @param worldState the world state after the transaction
     * @param gasUsed the gas used by the transaction
     * @return a new transaction receipt
     */
    @Override
      public TransactionReceipt create(
        final TransactionType transactionType,
        final TransactionProcessingResult result,
        final WorldState worldState,
        final long gasUsed) {
        TransactionReceipt receipt = delegate.create(transactionType, result, worldState, gasUsed);
        return new TransactionReceipt(
          transactionType,
          receipt.getStatus(),
          receipt.getCumulativeGasUsed(),
          receipt.getLogsList().stream()
            .filter(log -> !log.getLogger().equals(SIMULATION_TRANSFER_ADDRESS))
            .toList(),
          Optional.empty());
      }
    }
}
