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
 * Implements {@code engine_newPayloadWithWitnessV5}: same request/response shape as {@code
 * engine_newPayloadV5} but the success response additionally carries an EIP-8025 execution witness
 * for the imported block.
 *
 * <p>The witness is assembled immediately after the block is imported by reading the trie log and
 * block access list produced during execution (both available via {@link
 * org.hyperledger.besu.ethereum.BlockProcessingOutputs}). If the witness cannot be built — for
 * example because the world-state archive is not path-based — an {@link IllegalStateException} is
 * thrown so that callers receive a clear error rather than a silently empty witness.
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

/**
   * Builds the witness and wraps it together with the standard VALID payload status into an {@link
   * EnginePayloadWithWitnessResult}. Throws {@link IllegalStateException} if the witness is absent
   * or has an empty {@code state} list, ensuring callers never receive an empty witness silently.
   */
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

  /**
   * Attempts to build the EIP-8025 witness for the block identified by {@code blockHash}. Returns
   * {@code null} if either the block header or its parent header is not yet available in the
   * blockchain, or if {@link BonsaiExecutionWitnessBuilder} returns empty (e.g. no trie log).
   */
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
