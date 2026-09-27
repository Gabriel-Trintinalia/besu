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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.hyperledger.besu.ethereum.BlockProcessingOutputs;
import org.hyperledger.besu.ethereum.BlockProcessingResult;
import org.hyperledger.besu.ethereum.ProtocolContext;
import org.hyperledger.besu.ethereum.chain.Blockchain;
import org.hyperledger.besu.ethereum.core.Block;
import org.hyperledger.besu.ethereum.mainnet.BlockProcessor;
import org.hyperledger.besu.ethereum.mainnet.block.access.list.BlockAccessList;
import org.hyperledger.besu.ethereum.trie.pathbased.bonsai.worldview.BonsaiWorldState;
import org.hyperledger.besu.ethereum.trie.pathbased.bonsai.worldview.accumulator.BonsaiWorldStateUpdateAccumulator;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SequentialFallbackBlockProcessorTest {

  private final BlockProcessor parallelProcessor = mock(BlockProcessor.class);
  private final BlockProcessor sequentialProcessor = mock(BlockProcessor.class);
  private final SequentialFallbackBlockProcessor processor =
      new SequentialFallbackBlockProcessor(parallelProcessor, sequentialProcessor);

  private final ProtocolContext protocolContext = mock(ProtocolContext.class);
  private final Blockchain blockchain = mock(Blockchain.class);
  private final BonsaiWorldState worldState = mock(BonsaiWorldState.class);
  private final BonsaiWorldStateUpdateAccumulator accumulator =
      mock(BonsaiWorldStateUpdateAccumulator.class);
  private final Block block = mock(Block.class, RETURNS_DEEP_STUBS);
  private final Optional<BlockAccessList> blockAccessList =
      Optional.of(mock(BlockAccessList.class));

  private final BlockProcessingResult success =
      new BlockProcessingResult(Optional.of(mock(BlockProcessingOutputs.class)));
  private final BlockProcessingResult failure = new BlockProcessingResult("parallel failed");

  @BeforeEach
  void setUp() {
    doReturn(accumulator).when(worldState).updater();
  }

  @Test
  void returnsParallelResultWhenItSucceeds() {
    when(parallelProcessor.processBlock(
            protocolContext, blockchain, worldState, block, blockAccessList))
        .thenReturn(success);

    final BlockProcessingResult result =
        processor.processBlock(protocolContext, blockchain, worldState, block, blockAccessList);

    assertThat(result).isSameAs(success);
    verifyNoInteractions(sequentialProcessor, accumulator);
  }

  @Test
  void resetsWorldStateAndProcessesSequentiallyWhenParallelFails() {
    when(parallelProcessor.processBlock(
            protocolContext, blockchain, worldState, block, blockAccessList))
        .thenReturn(failure);
    when(sequentialProcessor.processBlock(
            protocolContext, blockchain, worldState, block, blockAccessList))
        .thenReturn(success);

    final BlockProcessingResult result =
        processor.processBlock(protocolContext, blockchain, worldState, block, blockAccessList);

    assertThat(result).isSameAs(success);
    verify(accumulator).reset();
  }

  @Test
  void returnsSequentialFailureWhenBothFail() {
    final BlockProcessingResult sequentialFailure = new BlockProcessingResult("sequential failed");
    when(parallelProcessor.processBlock(
            protocolContext, blockchain, worldState, block, blockAccessList))
        .thenReturn(failure);
    when(sequentialProcessor.processBlock(
            protocolContext, blockchain, worldState, block, blockAccessList))
        .thenReturn(sequentialFailure);

    final BlockProcessingResult result =
        processor.processBlock(protocolContext, blockchain, worldState, block, blockAccessList);

    assertThat(result).isSameAs(sequentialFailure);
  }

  @Test
  void processesWithoutBlockAccessListInParallel() {
    when(parallelProcessor.processBlock(
            protocolContext, blockchain, worldState, block, Optional.empty()))
        .thenReturn(success);

    final BlockProcessingResult result =
        processor.processBlock(protocolContext, blockchain, worldState, block);

    assertThat(result).isSameAs(success);
    verifyNoInteractions(sequentialProcessor);
  }
}
