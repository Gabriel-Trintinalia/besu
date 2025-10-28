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
package org.hyperledger.besu.services;

import static com.google.common.base.Preconditions.checkArgument;

import org.hyperledger.besu.ethereum.ProtocolContext;
import org.hyperledger.besu.ethereum.api.query.BlockchainQueries;
import org.hyperledger.besu.ethereum.chain.Blockchain;
import org.hyperledger.besu.ethereum.core.Block;
import org.hyperledger.besu.ethereum.core.BlockHeader;
import org.hyperledger.besu.ethereum.core.MutableWorldState;
import org.hyperledger.besu.ethereum.mainnet.BlockProcessor;
import org.hyperledger.besu.ethereum.mainnet.ProtocolSchedule;
import org.hyperledger.besu.ethereum.trie.pathbased.common.provider.WorldStateQueryParams;
import org.hyperledger.besu.evm.worldstate.WorldUpdater;
import org.hyperledger.besu.plugin.data.BlockProcessingResult;
import org.hyperledger.besu.plugin.services.BlockReplayService;
import org.hyperledger.besu.plugin.services.tracer.BlockAwareOperationTracer;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.LongStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Default implementation of {@link BlockReplayService}.
 *
 * <p>Supports replaying and tracing block execution across a range of block numbers.
 */
public class BlockReplayServiceImpl implements BlockReplayService {

  private static final Logger LOG = LoggerFactory.getLogger(BlockReplayServiceImpl.class);

  private final BlockchainQueries blockchainQueries;
  private final ProtocolSchedule protocolSchedule;
  private final ProtocolContext protocolContext;

  public BlockReplayServiceImpl(
      final BlockchainQueries blockchainQueries,
      final ProtocolSchedule protocolSchedule,
      final ProtocolContext protocolContext) {
    this.blockchainQueries = blockchainQueries;
    this.protocolSchedule = protocolSchedule;
    this.protocolContext = protocolContext;
  }

  @Override
  public BlockProcessingResult replay(
      final long blockNumber,
      final Consumer<WorldUpdater> beforeTracing,
      final Consumer<WorldUpdater> afterTracing,
      final BlockAwareOperationTracer tracer) {

    final List<BlockProcessingResult> results =
        replay(blockNumber, blockNumber, beforeTracing, afterTracing, tracer);
    return results.isEmpty() ? null : results.getFirst();
  }

  @Override
  public List<BlockProcessingResult> replay(
      final long fromBlockNumber,
      final long toBlockNumber,
      final Consumer<WorldUpdater> beforeTracing,
      final Consumer<WorldUpdater> afterTracing,
      final BlockAwareOperationTracer tracer) {

    checkArgument(tracer != null, "Tracer must not be null");
    checkArgument(fromBlockNumber <= toBlockNumber, "Invalid block range: from > to");

    LOG.debug("Replaying from block {} to block {}", fromBlockNumber, toBlockNumber);

    final Blockchain blockchain = blockchainQueries.getBlockchain();
    final List<Block> blocks = getBlocks(blockchain, fromBlockNumber, toBlockNumber);

    if (blocks.isEmpty()) {
      LOG.info("No blocks to replay in range {}–{}", fromBlockNumber, toBlockNumber);
      return List.of();
    }

    final MutableWorldState worldState = getParentWorldState(blockchain, blocks.getFirst());
    final WorldUpdater updater = worldState.updater();

    beforeTracing.accept(updater);
    final List<BlockProcessingResult> results = processBlocks(blocks, worldState, tracer);
    afterTracing.accept(updater);

    return results;
  }

  private List<Block> getBlocks(final Blockchain blockchain, final long from, final long to) {
    return LongStream.rangeClosed(from, to)
        .mapToObj(
            number ->
                blockchain
                    .getBlockByNumber(number)
                    .orElseThrow(() -> new IllegalArgumentException("Block not found: " + number)))
        .toList();
  }

  private MutableWorldState getParentWorldState(
      final Blockchain blockchain, final Block firstBlock) {

    final BlockHeader parentHeader =
        blockchain
            .getBlockByHash(firstBlock.getHeader().getParentHash())
            .map(Block::getHeader)
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "Parent block not found for: " + firstBlock.getHeader().toLogString()));

    return getWorldState(parentHeader);
  }

  private List<BlockProcessingResult> processBlocks(
      final List<Block> blocks,
      final MutableWorldState worldState,
      final BlockAwareOperationTracer tracer) {

    final Blockchain blockchain = blockchainQueries.getBlockchain();
    final List<BlockProcessingResult> results = new ArrayList<>(blocks.size());

    for (final Block block : blocks) {
      final BlockProcessor processor =
          protocolSchedule.getByBlockHeader(block.getHeader()).getBlockProcessor();
      final BlockProcessingResult result =
          processor.processBlock(protocolContext, blockchain, worldState, block, tracer);
      results.add(result);
    }

    return results;
  }

  private MutableWorldState getWorldState(final BlockHeader header) {
    final WorldStateQueryParams params =
        WorldStateQueryParams.withBlockHeaderAndNoUpdateNodeHead(header);

    return protocolContext
        .getWorldStateArchive()
        .getWorldState(params)
        .orElseThrow(
            () ->
                new IllegalArgumentException(
                    "World state not available for block: " + header.toLogString()));
  }
}
