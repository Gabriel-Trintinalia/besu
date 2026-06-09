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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.hyperledger.besu.consensus.merge.blockcreation.MergeMiningCoordinator;
import org.hyperledger.besu.ethereum.BlockProcessingOutputs;
import org.hyperledger.besu.ethereum.BlockProcessingResult;
import org.hyperledger.besu.ethereum.ProtocolContext;
import org.hyperledger.besu.ethereum.api.jsonrpc.RpcMethod;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.response.JsonRpcErrorResponse;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.response.JsonRpcResponse;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.response.RpcErrorType;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.results.EngineExecutionWitnessResult;
import org.hyperledger.besu.ethereum.chain.MutableBlockchain;
import org.hyperledger.besu.ethereum.core.BlockHeader;
import org.hyperledger.besu.ethereum.core.BlockHeaderTestFixture;
import org.hyperledger.besu.ethereum.eth.manager.EthPeers;
import org.hyperledger.besu.ethereum.mainnet.ProtocolSchedule;
import org.hyperledger.besu.ethereum.mainnet.ProtocolSpec;
import org.hyperledger.besu.ethereum.trie.pathbased.common.provider.PathBasedWorldStateProvider;
import org.hyperledger.besu.ethereum.trie.pathbased.common.trielog.TrieLogManager;
import org.hyperledger.besu.ethereum.worldstate.WorldStateArchive;
import org.hyperledger.besu.evm.gascalculator.GasCalculator;
import org.hyperledger.besu.metrics.noop.NoOpMetricsSystem;
import org.hyperledger.besu.plugin.services.trielogs.TrieLog;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.vertx.core.Vertx;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class EngineNewPayloadWithWitnessV5Test {
  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Test
  void nameIsEngineNewPayloadWithWitnessV5() {
    final EngineNewPayloadWithWitnessV5 method = newMethod(mock(ProtocolContext.class));
    assertThat(method.getName())
        .isEqualTo(RpcMethod.ENGINE_NEW_PAYLOAD_WITH_WITNESS_V5.getMethodName());
  }

  @Test
  void buildValidResponseReturnsErrorWhenWitnessCannotBeBuilt() {
    final BlockHeader header = new BlockHeaderTestFixture().number(1).buildHeader();

    final MutableBlockchain blockchain = mock(MutableBlockchain.class);
    when(blockchain.getBlockHeader(header.getHash())).thenReturn(Optional.of(header));

    final ProtocolContext protocolContext = mock(ProtocolContext.class);
    when(protocolContext.getBlockchain()).thenReturn(blockchain);
    when(protocolContext.getWorldStateArchive()).thenReturn(mock(WorldStateArchive.class));

    final EngineNewPayloadWithWitnessV5 method = newMethod(protocolContext);
    final BlockProcessingResult result =
        new BlockProcessingResult(
            Optional.of(
                new BlockProcessingOutputs(
                    null, java.util.List.of(), Optional.empty(), Optional.empty(), 0L, Map.of())));

    final JsonRpcResponse response = method.buildValidResponse("1", header.getHash(), result);
    assertThat(response).isInstanceOf(JsonRpcErrorResponse.class);
    assertThat(((JsonRpcErrorResponse) response).getErrorType())
        .isEqualTo(RpcErrorType.INTERNAL_ERROR);
  }

  @Test
  void buildValidResponseReturnsErrorWhenParentHeaderMissing() {
    final BlockHeader header = new BlockHeaderTestFixture().number(1).buildHeader();
    final TrieLogManager trieLogManager = mock(TrieLogManager.class);
    when(trieLogManager.getTrieLogLayer(header.getHash()))
        .thenReturn(Optional.of(mock(TrieLog.class)));

    final PathBasedWorldStateProvider pathBasedProvider = mock(PathBasedWorldStateProvider.class);
    when(pathBasedProvider.getTrieLogManager()).thenReturn(trieLogManager);

    final MutableBlockchain blockchain = mock(MutableBlockchain.class);
    when(blockchain.getBlockHeader(header.getHash())).thenReturn(Optional.of(header));
    when(blockchain.getBlockHeader(header.getParentHash())).thenReturn(Optional.empty());

    final ProtocolContext protocolContext = mock(ProtocolContext.class);
    when(protocolContext.getBlockchain()).thenReturn(blockchain);
    when(protocolContext.getWorldStateArchive()).thenReturn(pathBasedProvider);

    final EngineNewPayloadWithWitnessV5 method = newMethod(protocolContext);
    final BlockProcessingResult result =
        new BlockProcessingResult(
            Optional.of(
                new BlockProcessingOutputs(
                    null, java.util.List.of(), Optional.empty(), Optional.empty(), 0L, Map.of())));

    final JsonRpcResponse response = method.buildValidResponse("1", header.getHash(), result);
    assertThat(response).isInstanceOf(JsonRpcErrorResponse.class);
    assertThat(((JsonRpcErrorResponse) response).getErrorType())
        .isEqualTo(RpcErrorType.INTERNAL_ERROR);
  }

  @Test
  void buildValidResponseReturnsErrorWhenBlockHeaderNotPersisted() {
    final BlockHeader header = new BlockHeaderTestFixture().number(1).buildHeader();

    final MutableBlockchain blockchain = mock(MutableBlockchain.class);
    when(blockchain.getBlockHeader(header.getHash())).thenReturn(Optional.empty());

    final ProtocolContext protocolContext = mock(ProtocolContext.class);
    when(protocolContext.getBlockchain()).thenReturn(blockchain);

    final EngineNewPayloadWithWitnessV5 method = newMethod(protocolContext);
    final BlockProcessingResult result =
        new BlockProcessingResult(
            Optional.of(
                new BlockProcessingOutputs(
                    null, java.util.List.of(), Optional.empty(), Optional.empty(), 0L, Map.of())));

    final JsonRpcResponse response = method.buildValidResponse("1", header.getHash(), result);
    assertThat(response).isInstanceOf(JsonRpcErrorResponse.class);
    assertThat(((JsonRpcErrorResponse) response).getErrorType())
        .isEqualTo(RpcErrorType.INTERNAL_ERROR);
  }

  private static EngineNewPayloadWithWitnessV5 newMethod(final ProtocolContext protocolContext) {
    final GasCalculator gasCalculator = mock(GasCalculator.class);
    final ProtocolSpec protocolSpec = mock(ProtocolSpec.class);
    lenient().when(protocolSpec.getGasCalculator()).thenReturn(gasCalculator);

    final ProtocolSchedule schedule = mock(ProtocolSchedule.class);
    when(schedule.milestoneFor(org.mockito.ArgumentMatchers.any())).thenReturn(Optional.empty());
    lenient()
        .when(schedule.getByBlockHeader(org.mockito.ArgumentMatchers.any()))
        .thenReturn(protocolSpec);
    return new EngineNewPayloadWithWitnessV5(
        mock(Vertx.class),
        schedule,
        protocolContext,
        mock(MergeMiningCoordinator.class),
        mock(EthPeers.class),
        mock(EngineCallListener.class),
        new NoOpMetricsSystem());
  }

  @Test
  void encodeEmptyWitness() {
    final EngineExecutionWitnessResult result =
        new EngineExecutionWitnessResult(List.of(), List.of(), List.of());

    assertThat(result.getValue()).isEqualTo("0xc3c0c0c0");
  }

  @Test
  void encodeMultipleItems() {
    final EngineExecutionWitnessResult result =
        new EngineExecutionWitnessResult(
            List.of("0xaaaa", "0xbbbbbb"), List.of("0x6001"), List.of("0xf902"));

    assertThat(result.getValue()).isEqualTo("0xd0c382f902c3826001c782aaaa83bbbbbb");
  }

  @Test
  void jacksonSerializesAsHexStringNotObject() throws Exception {
    final EngineExecutionWitnessResult result =
        new EngineExecutionWitnessResult(List.of(), List.of(), List.of());

    final String json = MAPPER.writeValueAsString(result);

    assertThat(json).startsWith("\"0x");
    assertThat(json).doesNotContain("{");
  }
}
