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
import org.hyperledger.besu.ethereum.BlockProcessingResult;
import org.hyperledger.besu.ethereum.ProtocolContext;
import org.hyperledger.besu.ethereum.WitnessHint;
import org.hyperledger.besu.ethereum.api.jsonrpc.RpcMethod;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.results.EnginePayloadWithWitnessResult;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.results.ExecutionWitnessResult;
import org.hyperledger.besu.ethereum.core.BlockHeader;
import org.hyperledger.besu.ethereum.eth.manager.EthPeers;
import org.hyperledger.besu.ethereum.mainnet.AbstractBlockProcessor;
import org.hyperledger.besu.ethereum.mainnet.ProtocolSchedule;
import org.hyperledger.besu.ethereum.trie.pathbased.bonsai.storage.witness.BonsaiExecutionWitnessBuilder;
import org.hyperledger.besu.ethereum.trie.pathbased.bonsai.storage.witness.BonsaiPostprocessingFunction;
import org.hyperledger.besu.plugin.services.MetricsSystem;

import java.util.Collections;
import java.util.Optional;

import io.vertx.core.Vertx;

/**
 * Same params as {@code engine_newPayloadV5}; response additionally carries the EIP-8025 execution
 * witness for the imported block. When the in-memory {@link WitnessHint} is present in the block
 * processing outputs the witness is built from it directly (no trie-log disk I/O). Falls back to
 * the trie-log path only if the hint is absent.
 */
public class EngineNewPayloadWithWitnessV5 extends EngineNewPayloadV5 {

  private BonsaiPostprocessingFunction postprocessingFunction;

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
  protected AbstractBlockProcessor.PostprocessingFunction createPostprocessingFunction() {
    postprocessingFunction = new BonsaiPostprocessingFunction();
    return postprocessingFunction;
  }

  @Override
  protected Object buildPayloadResult(
      final EngineStatus status,
      final Hash latestValidHash,
      final BlockProcessingResult executionResult) {
    final ExecutionWitnessResult witness = buildWitness(latestValidHash);
    return new EnginePayloadWithWitnessResult(status, latestValidHash, Optional.empty(), witness);
  }

  private ExecutionWitnessResult buildWitness(final Hash blockHash) {
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

    final BonsaiExecutionWitnessBuilder builder = new BonsaiExecutionWitnessBuilder();
    final Optional<WitnessHint> maybeHint =
        postprocessingFunction != null
            ? postprocessingFunction.getWitnessHint()
            : Optional.empty();

    final long oldestAccessedAncestor =
        maybeHint
            .map(WitnessHint::accessedBlockHashes)
            .filter(m -> !m.isEmpty())
            .map(m -> Collections.min(m.keySet()))
            .orElse(blockHeader.getNumber() - 1);

    final Optional<BonsaiExecutionWitnessBuilder.Witness> maybeWitness =
        maybeHint.isPresent()
            ? builder.buildFromHint(
                blockHeader,
                maybeHint.get(),
                maybeParent.get(),
                protocolContext.getWorldStateArchive(),
                protocolContext.getBlockchain(),
                oldestAccessedAncestor)
            : builder.tryBuildForBlock(
                blockHeader,
                maybeParent.get(),
                protocolContext.getWorldStateArchive(),
                protocolContext.getBlockchain(),
                oldestAccessedAncestor);

    return maybeWitness
        .map(w -> new ExecutionWitnessResult(w.state(), w.codes(), w.headers()))
        .orElse(null);
  }
}
