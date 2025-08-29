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
import org.hyperledger.besu.ethereum.mainnet.ProtocolSpec;
import org.hyperledger.besu.ethereum.trie.pathbased.common.provider.WorldStateQueryParams;
import org.hyperledger.besu.evm.worldstate.WorldUpdater;
import org.hyperledger.besu.plugin.services.BlockReplayService;
import org.hyperledger.besu.plugin.services.tracer.BlockAwareOperationTracer;

import java.util.List;
import java.util.function.Consumer;
import java.util.stream.LongStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Default implementation of the {@link BlockReplayService}.
 *
 * <p>This service replays blocks within a given range, allowing inspection of the execution process
 * using an {@link BlockAwareOperationTracer}. It can be used for debugging, tracing, or analyzing
 * block execution results in the context of the blockchain state.
 *
 * <p>The replay mechanism ensures that:
 *
 * <ul>
 *   <li>The correct {@link ProtocolSpec} is applied per block, according to the {@link
 *       ProtocolSchedule}.
 *   <li>The associated {@link MutableWorldState} is retrieved for each block header.
 *   <li>Optional user-defined actions can be applied before and after block processing using {@link
 *       Consumer} callbacks on the {@link WorldUpdater}.
 * </ul>
 */
public class BlockReplayServiceImpl implements BlockReplayService {
  private static final Logger LOG = LoggerFactory.getLogger(BlockReplayServiceImpl.class);

  private final BlockchainQueries blockchainQueries;
  private final ProtocolSchedule protocolSchedule;
  private final ProtocolContext protocolContext;

  /**
   * Creates a new {@link BlockReplayServiceImpl}.
   *
   * @param blockchainQueries Provides blockchain access and queries.
   * @param protocolSchedule The schedule of protocol specifications per block.
   * @param protocolContext The protocol context, including blockchain and world state.
   */
  public BlockReplayServiceImpl(
      final BlockchainQueries blockchainQueries,
      final ProtocolSchedule protocolSchedule,
      final ProtocolContext protocolContext) {
    this.blockchainQueries = blockchainQueries;
    this.protocolSchedule = protocolSchedule;
    this.protocolContext = protocolContext;
  }

  /**
   * Replays a range of blocks and traces their execution.
   *
   * <p>For each block in the range:
   *
   * <ol>
   *   <li>Retrieves the block from the blockchain.
   *   <li>Resolves the appropriate {@link ProtocolSpec}.
   *   <li>Fetches the {@link MutableWorldState} for the block's header.
   *   <li>Invokes {@code beforeTracing} on the world state updater.
   *   <li>Processes the block using the {@link BlockProcessor} with the provided {@code tracer}.
   *   <li>Invokes {@code afterTracing} on the world state updater.
   * </ol>
   *
   * @param fromBlockNumber the beginning of the range (inclusive)
   * @param toBlockNumber the end of the range (inclusive)
   * @param beforeTracing a function executed on the {@link WorldUpdater} before tracing
   * @param afterTracing a function executed on the {@link WorldUpdater} after tracing
   * @param tracer an instance of {@link BlockAwareOperationTracer} used to trace execution
   * @throws IllegalArgumentException if the tracer is {@code null} or a block is missing
   */
  @Override
  public void trace(
      final long fromBlockNumber,
      final long toBlockNumber,
      final Consumer<WorldUpdater> beforeTracing,
      final Consumer<WorldUpdater> afterTracing,
      final BlockAwareOperationTracer tracer) {
    checkArgument(tracer != null);
    LOG.debug("Tracing from block {} to block {}", fromBlockNumber, toBlockNumber);
    final Blockchain blockchain = blockchainQueries.getBlockchain();
    final List<Block> blocks =
        LongStream.rangeClosed(fromBlockNumber, toBlockNumber)
            .mapToObj(
                number ->
                    blockchain
                        .getBlockByNumber(number)
                        .orElseThrow(() -> new RuntimeException("Block not found " + number)))
            .toList();

    // Process each block in the range
    for (final Block block : blocks) {
      LOG.trace("Tracing block {}", block.getHash());
      MutableWorldState worldState = getWorldState(block.getHeader());
      beforeTracing.accept(worldState.updater());
      protocolSchedule
          .getByBlockHeader(block.getHeader())
          .getBlockProcessor()
          .processBlock(protocolContext, blockchain, worldState, block, tracer);
      afterTracing.accept(worldState.updater());
    }
  }

  /**
   * Retrieves the {@link MutableWorldState} for a given block header.
   *
   * @param header The block header for which to retrieve the world state.
   * @return The mutable world state associated with the provided block header.
   * @throws IllegalArgumentException if the world state is not available for the given block
   *     header.
   */
  private MutableWorldState getWorldState(final BlockHeader header) {
    final WorldStateQueryParams worldStateQueryParams =
        WorldStateQueryParams.withBlockHeaderAndNoUpdateNodeHead(header);
    return protocolContext
        .getWorldStateArchive()
        .getWorldState(worldStateQueryParams)
        .orElseThrow(
            () ->
                new IllegalArgumentException(
                    "World state not available for block number (block hash): "
                        + header.toLogString()));
  }
}
