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

import static org.hyperledger.besu.ethereum.api.jsonrpc.internal.methods.ExecutionEngineJsonRpcMethod.EngineStatus.VALID;

import org.hyperledger.besu.consensus.merge.blockcreation.MergeMiningCoordinator;
import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.ethereum.BlockProcessingResult;
import org.hyperledger.besu.ethereum.ProtocolContext;
import org.hyperledger.besu.ethereum.api.jsonrpc.RpcMethod;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.results.EnginePayloadWithWitnessResult;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.results.ExecutionWitnessResult;
import org.hyperledger.besu.ethereum.eth.manager.EthPeers;
import org.hyperledger.besu.ethereum.mainnet.ProtocolSchedule;
import org.hyperledger.besu.ethereum.trie.pathbased.bonsai.storage.BonsaiExecutionWitnessBuilder;
import org.hyperledger.besu.plugin.services.MetricsSystem;

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
  protected Object buildValidPayloadResult(
      final Hash latestValidHash, final BlockProcessingResult executionResult) {
    final ExecutionWitnessResult witness = buildWitness(latestValidHash, executionResult);
    if (witness == null || witness.getState().isEmpty()) {
      throw new IllegalStateException(
          "failed to build execution witness for block " + latestValidHash);
    }
    return new EnginePayloadWithWitnessResult(VALID, latestValidHash, Optional.empty(), witness);
  }

  private ExecutionWitnessResult buildWitness(
      final Hash blockHash, final BlockProcessingResult executionResult) {

    return protocolContext
        .getBlockchain()
        .getBlockHeader(blockHash)
        .flatMap(
            blockHeader ->
                protocolContext
                    .getBlockchain()
                    .getBlockHeader(blockHeader.getParentHash())
                    .flatMap(
                        parentHeader ->
                            new BonsaiExecutionWitnessBuilder()
                                .buildWitness(
                                    blockHeader,
                                    parentHeader,
                                    protocolContext.getWorldStateArchive(),
                                    protocolContext.getBlockchain(),
                                    executionResult.getYield())
                                .map(
                                    w ->
                                        new ExecutionWitnessResult(
                                            w.state(), w.codes(), w.headers()))))
        .orElse(null);
  }
}
