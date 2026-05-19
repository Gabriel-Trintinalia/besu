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
import java.util.Optional;

import io.vertx.core.Vertx;

/**
 * Same params as {@code engine_newPayloadV5}; response additionally carries the EIP-8025 execution
 * witness for the imported block. The witness is built inline from the post-import trie log and the
 * accessed-ancestor map captured during execution. The oldest-accessed-ancestor sidecar is written
 * upstream by {@code MergeCoordinator.rememberBlock}, so {@code debug_executionWitness} can
 * subsequently reconstruct the witness without re-executing the block.
 */
public class EngineNewPayloadWithWitnessV5 extends EngineNewPayloadV5 {

  private final MetricsSystem witnessMetricsSystem;

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
    this.witnessMetricsSystem = metricsSystem;
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

    final long oldestAccessedAncestor =
        executionResult
            .getYield()
            .map(BlockProcessingOutputs::getAccessedBlockHashes)
            .filter(m -> !m.isEmpty())
            .map(m -> Collections.min(m.keySet()))
            .orElse(blockHeader.getNumber() - 1);

    return new BonsaiExecutionWitnessBuilder(witnessMetricsSystem)
        .tryBuildForBlock(
            blockHeader,
            maybeParent.get(),
            protocolContext.getWorldStateArchive(),
            protocolContext.getBlockchain(),
            oldestAccessedAncestor)
        .map(built -> new ExecutionWitnessResult(built.state(), built.codes(), built.headers()))
        .orElse(null);
  }
}
