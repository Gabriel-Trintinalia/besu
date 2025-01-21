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

import org.hyperledger.besu.ethereum.core.BlockHeader;
import org.hyperledger.besu.plugin.data.BlockOverrides;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * A class that manages a chain of BlockStateCalls. It fills gaps between blocks and sets the
 * correct block number and timestamp when they are not set.
 */
public class BlockStateCallChain {

  private static final long MAX_BLOCK_CALL_SIZE = 256; // Define the maximum block number

  private final long maxBlockCallSize;

  private final List<BlockStateCall> blockStateCalls;
  private long lastAddedBlockNumber;
  private long lastAddedTimestamp;

  /**
   * Constructs a BlockStateCallChain with the initial block header.
   *
   * @param header the initial block header
   */
  BlockStateCallChain(final BlockHeader header) {
    this.blockStateCalls = new ArrayList<>();
    this.lastAddedBlockNumber = header.getNumber();
    this.lastAddedTimestamp = header.getTimestamp();
    this.maxBlockCallSize = MAX_BLOCK_CALL_SIZE;
  }

  /**
   * Constructs a BlockStateCallChain with the initial block header.
   *
   * @param header the initial block header
   */
  BlockStateCallChain(final BlockHeader header, final long maxBlockCallSize) {
    this.blockStateCalls = new ArrayList<>();
    this.lastAddedBlockNumber = header.getNumber();
    this.lastAddedTimestamp = header.getTimestamp();
    this.maxBlockCallSize = maxBlockCallSize;
  }

  /**
   * Adds a BlockStateCall to the chain, filling gaps and setting the correct block number and
   * timestamp.
   *
   * @param blockStateCall the BlockStateCall to add
   */
  void add(final BlockStateCall blockStateCall) {
    if (blockStateCalls.size() >= maxBlockCallSize) {
      throw new IllegalArgumentException(
          "Cannot add more than " + maxBlockCallSize + " BlockStateCalls");
    }
    fillGaps(blockStateCall);
    updateBlockNumber(blockStateCall);
    updateTimestamps(blockStateCall);
    lastAddedBlockNumber = blockStateCall.getBlockOverrides().getBlockNumber().orElseThrow();
    blockStateCalls.add(blockStateCall);
  }

  /**
   * Fills gaps between the last processed block number and the block number of the given
   * BlockStateCall.
   *
   * @param blockStateCall the BlockStateCall to add
   */
  private void fillGaps(final BlockStateCall blockStateCall) {
    blockStateCall
        .getBlockOverrides()
        .getBlockNumber()
        .ifPresent(
            targetBlockNumber -> {
              if (blockStateCalls.size() + targetBlockNumber > maxBlockCallSize) {
                throw new IllegalArgumentException(
                    "Cannot add more than " + maxBlockCallSize + " BlockStateCalls");
              }
              List<BlockStateCall> intermediateBlocks =
                  generateIntermediateBlocks(targetBlockNumber);
              blockStateCalls.addAll(intermediateBlocks);
              if (!intermediateBlocks.isEmpty()) {
                BlockStateCall lastIntermediateBlock = intermediateBlocks.getLast();
                lastAddedBlockNumber =
                    lastIntermediateBlock.getBlockOverrides().getBlockNumber().orElseThrow();
                lastAddedTimestamp =
                    lastIntermediateBlock.getBlockOverrides().getTimestamp().orElseThrow();
              }
            });
  }

  /**
   * Updates the block number of the given BlockStateCall. If the block number is not present, it
   * sets it to the last processed block number plus 1. Ensures the block number is greater than the
   * last processed block number.
   *
   * @param blockStateCall the BlockStateCall to update
   */
  private void updateBlockNumber(final BlockStateCall blockStateCall) {
    var blockOverrides = blockStateCall.getBlockOverrides();
    long blockNumber = blockOverrides.getBlockNumber().orElseGet(this::getNextBlockNumber);

    if (blockNumber <= lastAddedBlockNumber) {
      throw new IllegalArgumentException(
          "Block number cannot be less or equal than the last processed block number");
    }
    blockOverrides.setBlockNumber(blockNumber);
    lastAddedBlockNumber = blockNumber;
  }

  /**
   * Updates the timestamp of the given BlockStateCall. If the timestamp is not present, it sets it
   * to the last processed timestamp plus 12 seconds. Ensures the timestamp is greater than the last
   * processed timestamp.
   *
   * @param blockStateCall the BlockStateCall to update
   */
  private void updateTimestamps(final BlockStateCall blockStateCall) {
    var blockOverrides = blockStateCall.getBlockOverrides();
    long timestamp = blockOverrides.getTimestamp().orElseGet(this::getNextTimestamp);

    if (timestamp <= lastAddedTimestamp) {
      throw new IllegalArgumentException(
          "Timestamp cannot be less or equal than the last processed timestamp");
    }

    blockOverrides.setTimestamp(timestamp);
    lastAddedTimestamp = timestamp;
  }

  /**
   * Returns the next timestamp to use.
   *
   * @return the next timestamp
   */
  private long getNextBlockNumber() {
    return lastAddedBlockNumber + 1L;
  }

  /**
   * Returns the next timestamp to use.
   *
   * @return the next timestamp
   */
  private long getNextTimestamp() {
    return lastAddedTimestamp + 12L;
  }

  /**
   * Generates intermediate blocks to fill gaps between the current and the specified block number.
   *
   * @param targetBlockNumber the target block number
   * @return a list of intermediate BlockStateCalls
   */
  private List<BlockStateCall> generateIntermediateBlocks(final long targetBlockNumber) {
    List<BlockStateCall> intermediateBlocks = new ArrayList<>();
    long blockNumberDiff = targetBlockNumber - lastAddedBlockNumber;
    for (int i = 1; i < blockNumberDiff; i++) {
      long nextBlockNumber = lastAddedBlockNumber + i;
      long nextTimestamp = lastAddedTimestamp + 12L * i;
      var nextBlockOverrides =
          BlockOverrides.builder().blockNumber(nextBlockNumber).timestamp(nextTimestamp).build();
      intermediateBlocks.add(new BlockStateCall(Collections.emptyList(), nextBlockOverrides, null));
    }
    return intermediateBlocks;
  }

  /**
   * Normalizes a list of BlockStateCalls by filling gaps and setting the correct block number and
   * timestamp.
   *
   * @param blockStateCalls the list of BlockStateCalls to normalize
   * @param header the initial block header
   * @return a normalized list of BlockStateCalls
   */
  public static List<? extends BlockStateCall> normalizeBlockStateCalls(
      final List<? extends BlockStateCall> blockStateCalls, final BlockHeader header) {

    // Find the last present block number
    long lastPresentBlockNumber =
        blockStateCalls.stream()
            .map(blockStateCall -> blockStateCall.getBlockOverrides().getBlockNumber())
            .filter(Optional::isPresent)
            .mapToLong(Optional::get)
            .max()
            .orElse(-1);

    // Check if the last present block number exceeds the allowed range
    if (lastPresentBlockNumber >= header.getNumber() + MAX_BLOCK_CALL_SIZE) {
      throw new IllegalArgumentException(
          "Block number in the BlockStateCalls cannot be greater or equal than the block number in the header plus the maximum block call size");
    }

    BlockStateCallChain chain = new BlockStateCallChain(header);
    for (BlockStateCall blockStateCall : blockStateCalls) {
      chain.add(blockStateCall);
    }
    return chain.getBlockStateCalls();
  }

  /**
   * Returns the list of BlockStateCalls in the chain.
   *
   * @return the list of BlockStateCalls
   */
  List<BlockStateCall> getBlockStateCalls() {
    return new ArrayList<>(blockStateCalls);
  }
}
