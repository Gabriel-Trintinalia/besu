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

import org.hyperledger.besu.ethereum.BlockProcessingResult;
import org.hyperledger.besu.ethereum.ProtocolContext;
import org.hyperledger.besu.ethereum.chain.Blockchain;
import org.hyperledger.besu.ethereum.core.Block;
import org.hyperledger.besu.ethereum.mainnet.BlockProcessor;
import org.hyperledger.besu.ethereum.mainnet.block.access.list.BlockAccessList;
import org.hyperledger.besu.ethereum.trie.pathbased.bonsai.worldview.BonsaiWorldState;
import org.hyperledger.besu.ethereum.trie.pathbased.bonsai.worldview.accumulator.BonsaiWorldStateUpdateAccumulator;
import org.hyperledger.besu.plugin.services.worldstate.MutableWorldState;

import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Processes blocks with a parallel processor, falling back to a sequential one when the parallel
 * run fails.
 */
public class SequentialFallbackBlockProcessor implements BlockProcessor {

  private static final Logger LOG = LoggerFactory.getLogger(SequentialFallbackBlockProcessor.class);

  private final BlockProcessor parallelProcessor;
  private final BlockProcessor sequentialProcessor;

  public SequentialFallbackBlockProcessor(
      final BlockProcessor parallelProcessor, final BlockProcessor sequentialProcessor) {
    this.parallelProcessor = parallelProcessor;
    this.sequentialProcessor = sequentialProcessor;
  }

  @Override
  public BlockProcessingResult processBlock(
      final ProtocolContext protocolContext,
      final Blockchain blockchain,
      final MutableWorldState worldState,
      final Block block) {
    return processBlock(protocolContext, blockchain, worldState, block, Optional.empty());
  }

  @Override
  public BlockProcessingResult processBlock(
      final ProtocolContext protocolContext,
      final Blockchain blockchain,
      final MutableWorldState worldState,
      final Block block,
      final Optional<BlockAccessList> blockAccessList) {
    final BlockProcessingResult blockProcessingResult =
        parallelProcessor.processBlock(
            protocolContext, blockchain, worldState, block, blockAccessList);
    if (blockProcessingResult.isFailed()) {
      // Fallback to non-parallel processing if there is a block processing exception .
      LOG.info(
          "Parallel transaction processing failure. Falling back to non-parallel processing for block #{} ({})",
          block.getHeader().getNumber(),
          block.getHash());
      if (worldState instanceof BonsaiWorldState) {
        ((BonsaiWorldStateUpdateAccumulator) worldState.updater()).reset();
      }
      return sequentialProcessor.processBlock(
          protocolContext, blockchain, worldState, block, blockAccessList);
    }
    return blockProcessingResult;
  }
}
