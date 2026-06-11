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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.hyperledger.besu.consensus.merge.blockcreation.MergeMiningCoordinator;
import org.hyperledger.besu.ethereum.BlockProcessingOutputs;
import org.hyperledger.besu.ethereum.BlockProcessingResult;
import org.hyperledger.besu.ethereum.BlockValidator;
import org.hyperledger.besu.ethereum.ProtocolContext;
import org.hyperledger.besu.ethereum.api.jsonrpc.RpcMethod;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.parameters.EnginePayloadParameter;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.response.JsonRpcErrorResponse;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.response.JsonRpcResponse;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.response.JsonRpcSuccessResponse;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.response.RpcErrorType;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.results.EngineExecutionWitnessResult;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.results.EnginePayloadWithWitnessResult;
import org.hyperledger.besu.ethereum.chain.MutableBlockchain;
import org.hyperledger.besu.ethereum.core.Block;
import org.hyperledger.besu.ethereum.core.BlockBody;
import org.hyperledger.besu.ethereum.core.BlockHeader;
import org.hyperledger.besu.ethereum.core.BlockHeaderTestFixture;
import org.hyperledger.besu.ethereum.core.BlockchainSetupUtil;
import org.hyperledger.besu.ethereum.eth.manager.EthPeers;
import org.hyperledger.besu.ethereum.mainnet.HeaderValidationMode;
import org.hyperledger.besu.ethereum.mainnet.ProtocolSchedule;
import org.hyperledger.besu.ethereum.mainnet.ProtocolSpec;
import org.hyperledger.besu.ethereum.mainnet.WitnessOperationTracer;
import org.hyperledger.besu.ethereum.mainnet.block.access.list.BlockAccessList;
import org.hyperledger.besu.ethereum.worldstate.WorldStateArchive;
import org.hyperledger.besu.evm.gascalculator.GasCalculator;
import org.hyperledger.besu.metrics.noop.NoOpMetricsSystem;
import org.hyperledger.besu.plugin.services.storage.DataStorageFormat;
import org.hyperledger.besu.plugin.services.tracer.BlockAwareOperationTracer;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.vertx.core.Vertx;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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

  // --- executeAndRespond unit tests ---

  @Test
  void executeAndRespond_passesWitnessTracerToRememberBlock() {
    final BlockHeader header = new BlockHeaderTestFixture().number(1).buildHeader();
    final Block block = new Block(header, new BlockBody(List.of(), List.of()));

    final MergeMiningCoordinator coordinator = mock(MergeMiningCoordinator.class);
    final ArgumentCaptor<BlockAwareOperationTracer> tracerCaptor =
        ArgumentCaptor.forClass(BlockAwareOperationTracer.class);
    when(coordinator.rememberBlock(eq(block), any(), tracerCaptor.capture()))
        .thenReturn(BlockProcessingResult.FAILED);

    final ProtocolContext protocolContext = mock(ProtocolContext.class);
    final EngineNewPayloadWithWitnessV5 method = newMethod(protocolContext, coordinator);

    method.executeAndRespond(
        "1",
        mock(EnginePayloadParameter.class),
        block,
        Optional.empty(),
        List.of(),
        header.getParentHash());

    assertThat(tracerCaptor.getValue()).isInstanceOf(WitnessOperationTracer.class);
  }

  @Test
  void executeAndRespond_returnsErrorWhenBlockHeaderNotPersisted() {
    final BlockHeader header = new BlockHeaderTestFixture().number(1).buildHeader();
    final Block block = new Block(header, new BlockBody(List.of(), List.of()));

    final BlockProcessingResult successResult =
        new BlockProcessingResult(
            Optional.of(
                new BlockProcessingOutputs(
                    null, List.of(), Optional.empty(), Optional.empty(), 0L, Map.of())));

    final MergeMiningCoordinator coordinator = mock(MergeMiningCoordinator.class);
    when(coordinator.rememberBlock(eq(block), any(), any(BlockAwareOperationTracer.class)))
        .thenReturn(successResult);

    final MutableBlockchain blockchain = mock(MutableBlockchain.class);
    when(blockchain.getBlockHeader(header.getHash())).thenReturn(Optional.empty());

    final ProtocolContext protocolContext = mock(ProtocolContext.class);
    when(protocolContext.getBlockchain()).thenReturn(blockchain);

    final EngineNewPayloadWithWitnessV5 method = newMethod(protocolContext, coordinator);

    final JsonRpcResponse response =
        method.executeAndRespond(
            "1",
            mock(EnginePayloadParameter.class),
            block,
            Optional.empty(),
            List.of(),
            header.getParentHash());

    assertThat(response).isInstanceOf(JsonRpcErrorResponse.class);
    assertThat(((JsonRpcErrorResponse) response).getErrorType())
        .isEqualTo(RpcErrorType.INTERNAL_ERROR);
  }

  @Test
  void executeAndRespond_returnsErrorWhenWorldStateArchiveIsNotPathBased() {
    final BlockHeader header = new BlockHeaderTestFixture().number(1).buildHeader();
    final Block block = new Block(header, new BlockBody(List.of(), List.of()));

    final BlockProcessingResult successResult =
        new BlockProcessingResult(
            Optional.of(
                new BlockProcessingOutputs(
                    null, List.of(), Optional.empty(), Optional.empty(), 0L, Map.of())));

    final MergeMiningCoordinator coordinator = mock(MergeMiningCoordinator.class);
    when(coordinator.rememberBlock(eq(block), any(), any(BlockAwareOperationTracer.class)))
        .thenReturn(successResult);

    final MutableBlockchain blockchain = mock(MutableBlockchain.class);
    when(blockchain.getBlockHeader(header.getHash())).thenReturn(Optional.of(header));

    final ProtocolContext protocolContext = mock(ProtocolContext.class);
    when(protocolContext.getBlockchain()).thenReturn(blockchain);
    // WorldStateArchive that is NOT PathBasedWorldStateProvider → builder constructor throws
    when(protocolContext.getWorldStateArchive()).thenReturn(mock(WorldStateArchive.class));

    final EngineNewPayloadWithWitnessV5 method = newMethod(protocolContext, coordinator);

    final JsonRpcResponse response =
        method.executeAndRespond(
            "1",
            mock(EnginePayloadParameter.class),
            block,
            Optional.empty(),
            List.of(),
            header.getParentHash());

    assertThat(response).isInstanceOf(JsonRpcErrorResponse.class);
    assertThat(((JsonRpcErrorResponse) response).getErrorType())
        .isEqualTo(RpcErrorType.INTERNAL_ERROR);
  }

  // --- Integration test: witness has real data ---

  @Test
  void executeAndRespond_witnessHasNonEmptyStateAndHeaders() {
    final BlockchainSetupUtil setup = BlockchainSetupUtil.forHiveTesting(DataStorageFormat.BONSAI);
    final int n = setup.blockCount();
    setup.importFirstBlocks(n - 1);
    final Block lastBlock = setup.getBlock(n - 1);

    // Mock coordinator that delegates to the real block validator with the supplied tracer,
    // then persists the block so the TrieLog is available to the witness builder.
    final MergeMiningCoordinator coordinator = mock(MergeMiningCoordinator.class);
    doAnswer(
            invocation -> {
              final Block b = invocation.getArgument(0);
              final Optional<BlockAccessList> bal = invocation.getArgument(1);
              final BlockAwareOperationTracer tracer = invocation.getArgument(2);
              final BlockValidator blockValidator =
                  setup.getProtocolSchedule().getByBlockHeader(b.getHeader()).getBlockValidator();
              final BlockProcessingResult result =
                  blockValidator.validateAndProcessBlock(
                      setup.getProtocolContext(),
                      b,
                      HeaderValidationMode.NONE,
                      HeaderValidationMode.NONE,
                      bal,
                      false,
                      true,
                      tracer);
              result
                  .getYield()
                  .ifPresent(
                      y ->
                          setup
                              .getBlockchain()
                              .storeBlock(
                                  b,
                                  y.getReceipts(),
                                  result
                                      .getYield()
                                      .flatMap(BlockProcessingOutputs::getBlockAccessList)));
              return result;
            })
        .when(coordinator)
        .rememberBlock(any(Block.class), any(), any(BlockAwareOperationTracer.class));

    final EngineNewPayloadWithWitnessV5 method =
        new EngineNewPayloadWithWitnessV5(
            mock(Vertx.class),
            setup.getProtocolSchedule(),
            setup.getProtocolContext(),
            coordinator,
            mock(EthPeers.class),
            mock(EngineCallListener.class),
            new NoOpMetricsSystem());

    final JsonRpcResponse response =
        method.executeAndRespond(
            "1",
            mock(EnginePayloadParameter.class),
            lastBlock,
            Optional.empty(),
            List.of(),
            setup.getBlockchain().getChainHeadHash());

    assertThat(response).isInstanceOf(JsonRpcSuccessResponse.class);
    final EnginePayloadWithWitnessResult payloadResult =
        (EnginePayloadWithWitnessResult) ((JsonRpcSuccessResponse) response).getResult();
    assertThat(payloadResult.getWitness()).isNotNull();
    // A non-empty-witness RLP is longer than the empty encoding "0xc3c0c0c0".
    assertThat(payloadResult.getWitness().getValue().length()).isGreaterThan("0xc3c0c0c0".length());
  }

  // --- Encoding tests ---

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

  // --- Helpers ---

  private static EngineNewPayloadWithWitnessV5 newMethod(final ProtocolContext protocolContext) {
    return newMethod(protocolContext, mock(MergeMiningCoordinator.class));
  }

  private static EngineNewPayloadWithWitnessV5 newMethod(
      final ProtocolContext protocolContext, final MergeMiningCoordinator coordinator) {
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
        coordinator,
        mock(EthPeers.class),
        mock(EngineCallListener.class),
        new NoOpMetricsSystem());
  }
}
