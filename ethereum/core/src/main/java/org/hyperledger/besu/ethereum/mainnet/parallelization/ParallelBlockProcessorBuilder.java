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

import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.ethereum.mainnet.AbstractBlockProcessor.TransactionReceiptFactory;
import org.hyperledger.besu.ethereum.mainnet.BalConfiguration;
import org.hyperledger.besu.ethereum.mainnet.BlockProcessingMetrics;
import org.hyperledger.besu.ethereum.mainnet.BlockProcessor;
import org.hyperledger.besu.ethereum.mainnet.MainnetBlockProcessor;
import org.hyperledger.besu.ethereum.mainnet.MainnetTransactionProcessor;
import org.hyperledger.besu.ethereum.mainnet.MiningBeneficiaryCalculator;
import org.hyperledger.besu.ethereum.mainnet.ProtocolSchedule;
import org.hyperledger.besu.ethereum.mainnet.ProtocolSpecBuilder;
import org.hyperledger.besu.plugin.services.MetricsSystem;

import java.util.concurrent.Executor;

/**
 * Builds the block processor used when parallel transaction processing is enabled: a {@link
 * ParallelExecutionBlockProcessor} with a sequential {@link MainnetBlockProcessor} to fall back to,
 * combined by a {@link SequentialFallbackBlockProcessor}.
 */
public class ParallelBlockProcessorBuilder implements ProtocolSpecBuilder.BlockProcessorBuilder {

  private static final Executor executor = BlockProcessingExecutors.cpuExecutor();

  private final MetricsSystem metricsSystem;

  public ParallelBlockProcessorBuilder(final MetricsSystem metricsSystem) {
    this.metricsSystem = metricsSystem;
  }

  @Override
  public BlockProcessor apply(
      final MainnetTransactionProcessor transactionProcessor,
      final TransactionReceiptFactory transactionReceiptFactory,
      final Wei blockReward,
      final MiningBeneficiaryCalculator miningBeneficiaryCalculator,
      final boolean skipZeroBlockRewards,
      final ProtocolSchedule protocolSchedule,
      final BalConfiguration balConfiguration) {
    // Both processors serve the same chain, so they record into the same metrics.
    final BlockProcessingMetrics blockProcessingMetrics = new BlockProcessingMetrics(metricsSystem);
    final BlockProcessor parallelProcessor =
        new ParallelExecutionBlockProcessor(
            transactionProcessor,
            transactionReceiptFactory,
            blockReward,
            miningBeneficiaryCalculator,
            skipZeroBlockRewards,
            protocolSchedule,
            balConfiguration,
            metricsSystem,
            blockProcessingMetrics,
            new ParallelTransactionPreprocessing(transactionProcessor, executor, balConfiguration));
    final BlockProcessor sequentialProcessor =
        new MainnetBlockProcessor(
            transactionProcessor,
            transactionReceiptFactory,
            blockReward,
            miningBeneficiaryCalculator,
            skipZeroBlockRewards,
            protocolSchedule,
            balConfiguration,
            blockProcessingMetrics);
    return new SequentialFallbackBlockProcessor(parallelProcessor, sequentialProcessor);
  }
}
