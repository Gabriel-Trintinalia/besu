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
package org.hyperledger.besu.ethereum.mainnet.parallelization;

import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.ethereum.core.Transaction;
import org.hyperledger.besu.ethereum.mainnet.BalConfiguration;
import org.hyperledger.besu.ethereum.mainnet.BlockProcessingMetrics;
import org.hyperledger.besu.ethereum.mainnet.MainnetBlockProcessor;
import org.hyperledger.besu.ethereum.mainnet.MainnetTransactionProcessor;
import org.hyperledger.besu.ethereum.mainnet.MiningBeneficiaryCalculator;
import org.hyperledger.besu.ethereum.mainnet.ProtocolSchedule;
import org.hyperledger.besu.ethereum.mainnet.block.access.list.AccessLocationTracker;
import org.hyperledger.besu.ethereum.mainnet.systemcall.BlockProcessingContext;
import org.hyperledger.besu.ethereum.processing.TransactionProcessingResult;
import org.hyperledger.besu.evm.blockhash.BlockHashLookup;
import org.hyperledger.besu.evm.worldstate.WorldUpdater;
import org.hyperledger.besu.metrics.BesuMetricCategory;
import org.hyperledger.besu.plugin.services.MetricsSystem;
import org.hyperledger.besu.plugin.services.metrics.Counter;

import java.util.Optional;

/**
 * Executes a block with its transactions run speculatively in parallel by the given preprocessing,
 * taking each transaction's result from that run when it is still valid and re-executing it
 * otherwise.
 *
 * <p>It has no block-level fallback: a failed block is returned as-is. {@link
 * SequentialFallbackBlockProcessor} adds one on top of it.
 */
public class ParallelExecutionBlockProcessor extends MainnetBlockProcessor {

  private final Optional<Counter> confirmedParallelizedTransactionCounter;
  private final Optional<Counter> conflictingButCachedTransactionCounter;

  public ParallelExecutionBlockProcessor(
      final MainnetTransactionProcessor transactionProcessor,
      final TransactionReceiptFactory transactionReceiptFactory,
      final Wei blockReward,
      final MiningBeneficiaryCalculator miningBeneficiaryCalculator,
      final boolean skipZeroBlockRewards,
      final ProtocolSchedule protocolSchedule,
      final BalConfiguration balConfiguration,
      final MetricsSystem metricsSystem,
      final BlockProcessingMetrics blockProcessingMetrics,
      final PreprocessingFunction parallelPreprocessing) {
    super(
        transactionProcessor,
        transactionReceiptFactory,
        blockReward,
        miningBeneficiaryCalculator,
        skipZeroBlockRewards,
        protocolSchedule,
        balConfiguration,
        blockProcessingMetrics,
        parallelPreprocessing);
    this.confirmedParallelizedTransactionCounter =
        Optional.of(
            metricsSystem.createCounter(
                BesuMetricCategory.BLOCK_PROCESSING,
                "parallelized_transactions_counter",
                "Counter for the number of parallelized transactions during block processing"));

    this.conflictingButCachedTransactionCounter =
        Optional.of(
            metricsSystem.createCounter(
                BesuMetricCategory.BLOCK_PROCESSING,
                "conflicted_transactions_counter",
                "Counter for the number of conflicted transactions during block processing"));
  }

  @Override
  protected TransactionProcessingResult getTransactionProcessingResult(
      final Optional<PreprocessingContext> preProcessingContext,
      final BlockProcessingContext blockProcessingContext,
      final WorldUpdater transactionUpdater,
      final Wei blobGasPrice,
      final Address miningBeneficiary,
      final Transaction transaction,
      final int location,
      final BlockHashLookup blockHashLookup,
      final Optional<AccessLocationTracker> accessLocationTracker) {
    return preProcessingContext
        .flatMap(
            ctx ->
                ctx.processor()
                    .getProcessingResult(
                        blockProcessingContext.getWorldState(),
                        miningBeneficiary,
                        transaction,
                        location,
                        confirmedParallelizedTransactionCounter,
                        conflictingButCachedTransactionCounter))
        .orElseGet(
            () ->
                super.getTransactionProcessingResult(
                    preProcessingContext,
                    blockProcessingContext,
                    transactionUpdater,
                    blobGasPrice,
                    miningBeneficiary,
                    transaction,
                    location,
                    blockHashLookup,
                    accessLocationTracker));
  }
}
