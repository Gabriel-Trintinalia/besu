/*
 * Copyright contributors to Hyperledger Besu.
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
package org.hyperledger.besu.ethereum.api.jsonrpc.internal.methods.engine;

import org.hyperledger.besu.consensus.merge.blockcreation.MergeMiningCoordinator;
import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.ethereum.BlockProcessingOutputs;
import org.hyperledger.besu.ethereum.BlockProcessingResult;
import org.hyperledger.besu.ethereum.ProtocolContext;
import org.hyperledger.besu.ethereum.api.jsonrpc.RpcMethod;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.results.EnginePayloadWithWitnessResult;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.results.ExecutionWitnessResult;
import org.hyperledger.besu.ethereum.core.BlockHeader;
import org.hyperledger.besu.ethereum.eth.manager.EthPeers;
import org.hyperledger.besu.ethereum.mainnet.ProtocolSchedule;
import org.hyperledger.besu.ethereum.trie.pathbased.bonsai.storage.witness.BonsaiExecutionWitnessBuilder;
import org.hyperledger.besu.plugin.services.MetricsSystem;

import java.util.Collections;
import java.util.Map;
import java.util.Optional;

import io.vertx.core.Vertx;

/**
 * Same params as {@code engine_newPayloadV5}; response additionally carries the EIP-8025 execution
 * witness for the imported block. Uses the trie-log path via {@link
 * BonsaiExecutionWitnessBuilder#tryBuildForBlock}, reading the persisted trie log and the BLOCKHASH
 * ancestors captured in {@link BlockProcessingOutputs#getAccessedBlockHashes()}.
 */
public class EngineNewPayloadWithWitnessV5 extends EngineNewPayloadV5 {

  public EngineNewPayloadWithWitnessV5(
      final Vertx vertx,
      final ProtocolSchedule timestampSchedule,
      final ProtocolContext protocolContext,
      final MergeMiningCoordinator mergeCoordinator,
      final EthPeers ethPeers,
      final EngineCallListener engineCallListener,
      final MetricsSystem metricsSystem) {
    super(
        vertx,
        timestampSchedule,
        protocolContext,
        mergeCoordinator,
        ethPeers,
        engineCallListener,
        metricsSystem);
  }

  @Override
  public String getName() {
    return RpcMethod.ENGINE_NEW_PAYLOAD_WITH_WITNESS_V5.getMethodName();
  }

  @Override
  protected Object buildPayloadResult(
      final EngineStatus status,
      final Hash latestValidHash,
      final BlockProcessingResult executionResult) {
    final ExecutionWitnessResult witness = buildWitness(latestValidHash, executionResult);
    return new EnginePayloadWithWitnessResult(status, latestValidHash, Optional.empty(), witness);
  }

  private ExecutionWitnessResult buildWitness(
      final Hash blockHash, final BlockProcessingResult executionResult) {
    final Optional<BlockHeader> maybeBlock =
        protocolContext.getBlockchain().getBlockHeader(blockHash);
    if (maybeBlock.isEmpty()) {
      return null;
    }
    final BlockHeader blockHeader = maybeBlock.get();
    final Optional<BlockHeader> maybeParent =
        protocolContext.getBlockchain().getBlockHeader(blockHeader.getParentHash());
    if (maybeParent.isEmpty()) {
      return null;
    }

    final Map<Long, Hash> accessedBlockHashes =
        executionResult
            .getYield()
            .map(BlockProcessingOutputs::getAccessedBlockHashes)
            .orElse(Map.of());

    final long oldestAccessedAncestor =
        accessedBlockHashes.isEmpty()
            ? blockHeader.getNumber() - 1
            : Collections.min(accessedBlockHashes.keySet());

    return new BonsaiExecutionWitnessBuilder()
        .tryBuildForBlock(
            blockHeader,
            maybeParent.get(),
            protocolContext.getWorldStateArchive(),
            protocolContext.getBlockchain(),
            oldestAccessedAncestor)
        .map(w -> new ExecutionWitnessResult(w.state(), w.codes(), w.headers()))
        .orElse(null);
  }
}
