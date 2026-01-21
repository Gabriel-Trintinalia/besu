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

import static java.util.Objects.requireNonNullElse;

import org.hyperledger.besu.ethereum.ProtocolContext;
import org.hyperledger.besu.ethereum.api.query.BlockchainQueries;
import org.hyperledger.besu.ethereum.chain.Blockchain;
import org.hyperledger.besu.ethereum.core.Block;
import org.hyperledger.besu.ethereum.core.BlockHeader;
import org.hyperledger.besu.ethereum.core.MutableWorldState;
import org.hyperledger.besu.ethereum.mainnet.BlockProcessor;
import org.hyperledger.besu.ethereum.mainnet.ProtocolSchedule;
import org.hyperledger.besu.ethereum.trie.pathbased.common.provider.WorldStateQueryParams;
import org.hyperledger.besu.plugin.data.BlockProcessingResult;
import org.hyperledger.besu.plugin.services.BlockReplayService;
import org.hyperledger.besu.plugin.services.tracer.BlockAwareOperationTracer;

/** Default {@link BlockReplayService} implementation. */
public class BlockReplayServiceImpl implements BlockReplayService {
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
      final long blockNumber, final BlockAwareOperationTracer tracer) {
    final Blockchain blockchain = blockchainQueries.getBlockchain();
    final Block block = blockchain.getBlockByNumber(blockNumber).orElse(null);
    if (block == null) {
      return null;
    }

    final BlockProcessor processor =
        protocolSchedule.getByBlockHeader(block.getHeader()).getBlockProcessor();

    return processor.processBlock(
        protocolContext,
        blockchain,
        parentWorldState(blockchain, block),
        block,
        requireNonNullElse(tracer, BlockAwareOperationTracer.NO_TRACING));
  }

  private MutableWorldState parentWorldState(final Blockchain blockchain, final Block block) {

    final BlockHeader parentHeader =
        blockchain
            .getBlockByHash(block.getHeader().getParentHash())
            .map(Block::getHeader)
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "Parent block not found for: " + block.getHeader().toLogString()));

    return protocolContext
        .getWorldStateArchive()
        .getWorldState(WorldStateQueryParams.withBlockHeaderAndNoUpdateNodeHead(parentHeader))
        .orElseThrow(
            () ->
                new IllegalArgumentException(
                    "World state not available for block: " + parentHeader.toLogString()));
  }
}
