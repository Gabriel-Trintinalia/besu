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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.hyperledger.besu.ethereum.core.BlockHeader;
import org.hyperledger.besu.plugin.data.BlockOverrides;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BlockStateCallChainTest {
  private BlockHeader mockBlockHeader;
  private BlockStateCallChain blockStateCallChain;

  @BeforeEach
  void setUp() {
    mockBlockHeader = mock(BlockHeader.class);
    when(mockBlockHeader.getNumber()).thenReturn(1L);
    when(mockBlockHeader.getTimestamp()).thenReturn(1000L);
    blockStateCallChain = new BlockStateCallChain(mockBlockHeader, 10);
  }

  /** Tests that a BlockStateCall is added to the chain correctly. */
  @Test
  void shouldAddBlockStateCallToChain() {
    blockStateCallChain.add(createBlockStateCall(2L, 1012L));
    List<BlockStateCall> blockStateCalls = blockStateCallChain.getBlockStateCalls();
    assertEquals(1, blockStateCalls.size());
    assertEquals(2L, blockStateCalls.getFirst().getBlockOverrides().getBlockNumber().orElseThrow());
    assertEquals(1012L, blockStateCalls.getFirst().getBlockOverrides().getTimestamp().orElseThrow());
  }

  /** Tests that gaps between block numbers are filled correctly when adding a BlockStateCall. */
  @Test
  void shouldFillGapsBetweenBlockNumbers() {
    blockStateCallChain.add(createBlockStateCall(4L  , 1036L));
    List<BlockStateCall> blockStateCalls = blockStateCallChain.getBlockStateCalls();
    assertEquals(3, blockStateCalls.size());
    assertEquals(2L, blockStateCalls.get(0).getBlockOverrides().getBlockNumber().orElseThrow());
    assertEquals(1012L, blockStateCalls.get(0).getBlockOverrides().getTimestamp().orElseThrow());
    assertEquals(3L, blockStateCalls.get(1).getBlockOverrides().getBlockNumber().orElseThrow());
    assertEquals(1024L, blockStateCalls.get(1).getBlockOverrides().getTimestamp().orElseThrow());
    assertEquals(4L, blockStateCalls.get(2).getBlockOverrides().getBlockNumber().orElseThrow());
    assertEquals(1036L, blockStateCalls.get(2).getBlockOverrides().getTimestamp().orElseThrow());
  }

  /**
   * Tests that the block number is updated correctly if it is not present in the BlockStateCall.
   */
  @Test
  void shouldUpdateBlockNumberIfNotPresent() {
    blockStateCallChain.add(createBlockStateCall(null, 1012L));
    List<BlockStateCall> blockStateCalls = blockStateCallChain.getBlockStateCalls();
    assertEquals(1, blockStateCalls.size());
    assertEquals(2L, blockStateCalls.getFirst().getBlockOverrides().getBlockNumber().orElseThrow());
  }

  /** Tests that the timestamp is updated correctly if it is not present in the BlockStateCall. */
  @Test
  void shouldUpdateTimestampIfNotPresent() {
    blockStateCallChain.add(createBlockStateCall (2L, null));
    List<BlockStateCall> blockStateCalls = blockStateCallChain.getBlockStateCalls();
    assertEquals(1, blockStateCalls.size());
    assertEquals(1012L, blockStateCalls.getFirst().getBlockOverrides().getTimestamp().orElseThrow());
  }

  /**
   * Tests that a list of BlockStateCalls is normalized correctly by filling gaps and setting block
   * numbers and timestamps.
   */
  @Test
  void shouldNormalizeBlockStateCalls() {
    List<BlockStateCall> blockStateCalls = new ArrayList<>();
    blockStateCalls.add(createBlockStateCall(3L, 1024L));
    blockStateCalls.add(createBlockStateCall(5L, 1048L));

    var normalizedCalls =
        BlockStateCallChain.normalizeBlockStateCalls(
          blockStateCalls,
            mockBlockHeader);

    assertEquals(4, normalizedCalls.size());
    assertEquals(2L, normalizedCalls.get(0).getBlockOverrides().getBlockNumber().orElseThrow());
    assertEquals(1012L, normalizedCalls.get(0).getBlockOverrides().getTimestamp().orElseThrow());
    assertEquals(3L, normalizedCalls.get(1).getBlockOverrides().getBlockNumber().orElseThrow());
    assertEquals(1024L, normalizedCalls.get(1).getBlockOverrides().getTimestamp().orElseThrow());
    assertEquals(4L, normalizedCalls.get(2).getBlockOverrides().getBlockNumber().orElseThrow());
    assertEquals(1036L, normalizedCalls.get(2).getBlockOverrides().getTimestamp().orElseThrow());
    assertEquals(5L, normalizedCalls.get(3).getBlockOverrides().getBlockNumber().orElseThrow());
    assertEquals(1048L, normalizedCalls.get(3).getBlockOverrides().getTimestamp().orElseThrow());
  }

  /**
   * Tests that an exception is thrown when a BlockStateCall with a block number less than or equal
   * to the last processed block number is added.
   */
  @Test
  void shouldThrowExceptionForInvalidBlockNumber() {
    IllegalArgumentException exception =
        assertThrows(
            IllegalArgumentException.class,
            () -> blockStateCallChain.add(createBlockStateCall(1L, 1012L)));
    assertEquals(
        "Block number cannot be less or equal than the last processed block number",
        exception.getMessage());
  }

  /**
   * Tests that an exception is thrown when a BlockStateCall with a timestamp less than or equal to
   * the last processed timestamp is added.
   */
  @Test
  void shouldThrowExceptionForInvalidTimestamp() {
    IllegalArgumentException exception =
        assertThrows(
            IllegalArgumentException.class,
            () -> blockStateCallChain.add(createBlockStateCall(2L, 1000L)));
    assertEquals(
        "Timestamp cannot be less or equal than the last processed timestamp",
        exception.getMessage());
  }

  /**
   * Tests that the chain is normalized by adding intermediate blocks and then fails when adding the
   * last call due to an invalid timestamp.
   */
  @Test
  void shouldNormalizeChainAndFailOnInvalidTimestamp() {
    blockStateCallChain.add(createBlockStateCall(3L, 1100L));
    IllegalArgumentException exception =
        assertThrows(
            IllegalArgumentException.class,
            () -> blockStateCallChain.add(createBlockStateCall(5L, 1012L)));
    assertEquals(
        "Timestamp cannot be less or equal than the last processed timestamp",
        exception.getMessage());
  }

  /** Tests that an exception is thrown when the maximum number of BlockStateCalls is exceeded. */
  @Test
  void shouldThrowExceptionWhenExceedingMaxBlocks() {
    BlockStateCall blockStateCall = createBlockStateCall(11L, null);
    IllegalArgumentException exception =
        assertThrows(IllegalArgumentException.class, () -> blockStateCallChain.add(blockStateCall));

    assertEquals("Cannot add more than 10 BlockStateCalls", exception.getMessage());
  }

  @Test
  void shouldThrowExceptionWhenNormalizeBlockStateCallsExceedsMaxBlockCallSize() {
    List<BlockStateCall> blockStateCalls = new ArrayList<>();
    blockStateCalls.add(createBlockStateCall(101L, 1609459212L));
    blockStateCalls.add(createBlockStateCall(357L, 1609459248L));
    blockStateCalls.add(createBlockStateCall(102L, 1609459224L));

    assertThrows(
        IllegalArgumentException.class,
        () -> BlockStateCallChain.normalizeBlockStateCalls(blockStateCalls, mockBlockHeader));
  }

  private BlockStateCall createBlockStateCall(final Long blockNumber, final Long timestamp) {
    BlockOverrides blockOverrides =
        BlockOverrides.builder().blockNumber(blockNumber).timestamp(timestamp).build();
    return new BlockStateCall(Collections.emptyList(), blockOverrides, null);
  }
}
