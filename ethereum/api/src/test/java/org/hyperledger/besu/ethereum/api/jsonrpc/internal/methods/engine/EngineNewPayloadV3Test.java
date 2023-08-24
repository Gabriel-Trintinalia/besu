/*
 * Copyright Hyperledger Besu Contributors.
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
import static org.hyperledger.besu.ethereum.api.jsonrpc.internal.methods.ExecutionEngineJsonRpcMethod.EngineStatus.INVALID;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.util.Collections;
import org.hyperledger.besu.datatypes.BlobGas;
import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.ethereum.BlockProcessingOutputs;
import org.hyperledger.besu.ethereum.BlockProcessingResult;
import org.hyperledger.besu.ethereum.api.jsonrpc.RpcMethod;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.JsonRpcRequest;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.JsonRpcRequestContext;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.parameters.EnginePayloadParameter;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.response.JsonRpcError;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.response.JsonRpcResponse;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.response.RpcErrorType;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.results.EnginePayloadStatusResult;
import org.hyperledger.besu.ethereum.core.BlockHeader;
import org.hyperledger.besu.ethereum.core.BlockHeaderTestFixture;
import org.hyperledger.besu.ethereum.core.Deposit;
import org.hyperledger.besu.ethereum.core.Transaction;
import org.hyperledger.besu.ethereum.core.Withdrawal;
import org.hyperledger.besu.ethereum.core.encoding.TransactionDecoder;
import org.hyperledger.besu.ethereum.mainnet.BodyValidation;
import org.hyperledger.besu.ethereum.mainnet.ValidationResult;
import org.hyperledger.besu.evm.gascalculator.CancunGasCalculator;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.MockedStatic;
import org.mockito.MockedStatic.Verification;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public class EngineNewPayloadV3Test extends EngineNewPayloadV2Test {

  private static final String DEFAULT_PARENT_BEACON_ROOT = "0x0000000000000000000000000000000000000000000000000000000000000000";

  public EngineNewPayloadV3Test() {}

  @Override
  @Test
  public void shouldReturnExpectedMethodName() {
    assertThat(method.getName()).isEqualTo("engine_newPayloadV3");
  }

  @BeforeEach
  @Override
  public void before() {
    super.before();
    maybeParentBeaconBlockRoot = Optional.of(Bytes32.ZERO);
    this.method =
        new EngineNewPayloadV3(
            vertx,
            protocolSchedule,
            protocolContext,
            mergeCoordinator,
            ethPeers,
            engineCallListener);
    lenient().when(protocolSpec.getGasCalculator()).thenReturn(new CancunGasCalculator());
  }

  @Test
  public void shouldInvalidVersionedHash_whenShortVersionedHash() {
    final Bytes shortHash = Bytes.fromHexString("0x" + "69".repeat(31));

    final EnginePayloadParameter payload = mock(EnginePayloadParameter.class);
    when(payload.getTimestamp()).thenReturn(cancunHardfork.milestone());
    when(payload.getExcessBlobGas()).thenReturn("99");
    when(payload.getBlobGasUsed()).thenReturn(9l);

    final JsonRpcResponse badParam =
        method.response(
            new JsonRpcRequestContext(
                new JsonRpcRequest(
                    "2.0",
                    RpcMethod.ENGINE_NEW_PAYLOAD_V3.getMethodName(),
                    new Object[] {
                      payload,
                      List.of(shortHash.toHexString()),
                        DEFAULT_PARENT_BEACON_ROOT
                    })));
    final EnginePayloadStatusResult res = fromSuccessResp(badParam);
    assertThat(res.getStatusAsString()).isEqualTo(INVALID.name());
    assertThat(res.getError()).isEqualTo("Invalid versionedHash");
  }

  @Test
  public void shouldReturnInvalid_missing_TransactionVersionedHash() {
    Bytes shortHash = Bytes.fromHexString("0x0100000000000000000000000000000000000000000000000000000000000000");
    BlockHeader mockHeader =
        setupValidPayload(
            new BlockProcessingResult(Optional.of(new BlockProcessingOutputs(null, List.of()))),
            Optional.empty(),
            Optional.empty());


    Transaction blobTransaction = mock(Transaction.class);

    try (MockedStatic<TransactionDecoder> utilities = mockStatic(TransactionDecoder.class)) {
      utilities.when(() -> TransactionDecoder.decodeOpaqueBytes(any(Bytes.class)))
          .thenReturn(blobTransaction);

      lenient()
          .when(blockchain.getBlockHeader(mockHeader.getParentHash()))
          .thenReturn(Optional.of(mock(BlockHeader.class)));

      var resp = resp(mockEnginePayload(mockHeader, List.of("0xblob")) ,
          List.of(shortHash.toHexString()));
      EnginePayloadStatusResult res = fromSuccessResp(resp);
      assertThat(res.getStatusAsString()).isEqualTo(INVALID.name());
      assertThat(res.getError()).isEqualTo("Invalid versionedHash");
    }
  }


  @Test
  public void shouldReturnInvalidParameterWhenVersionedHashesIsNull() {
    EnginePayloadParameter payload = mock(EnginePayloadParameter.class);
    JsonRpcResponse badParam =
        method.response(
            new JsonRpcRequestContext(
                new JsonRpcRequest(
                    "2.0",
                    RpcMethod.ENGINE_NEW_PAYLOAD_V3.getMethodName(),
                    new Object[] {
                      payload,
                      null,
                        DEFAULT_PARENT_BEACON_ROOT
                    })));
    JsonRpcError res = fromErrorResp(badParam);
    assertThat(res.getCode()).isEqualTo(RpcErrorType.INVALID_PARAMS.getCode());
    assertThat(res.getData()).isEqualTo("Missing required json rpc parameter at index 1");
  }

  @Test
  public void shouldReturnInvalidParameterWhenParentBeaconRootIsNull() {
    EnginePayloadParameter payload = mock(EnginePayloadParameter.class);
    JsonRpcResponse badParam =
        method.response(
            new JsonRpcRequestContext(
                new JsonRpcRequest(
                    "2.0",
                    RpcMethod.ENGINE_NEW_PAYLOAD_V3.getMethodName(),
                    new Object[] {payload, List.of(), null})));
    JsonRpcError res = fromErrorResp(badParam);
    assertThat(res.getCode()).isEqualTo(RpcErrorType.INVALID_PARAMS.getCode());
    assertThat(res.getData()).isEqualTo("Missing required json rpc parameter at index 2");
  }

  @Test
  public void shouldValidVersionedHash_whenListIsEmpty() {
    final BlockHeader mockHeader =
        setupValidPayload(
            new BlockProcessingResult(Optional.of(new BlockProcessingOutputs(null, List.of()))),
            Optional.empty(),
            Optional.empty());
    final EnginePayloadParameter payload =
        mockEnginePayload(mockHeader, Collections.emptyList(), null, null);

    ValidationResult<RpcErrorType> res =
        method.validateParameters(
            payload,
            Optional.of(List.of()),
            Optional.of("0x0000000000000000000000000000000000000000000000000000000000000000"));
    assertThat(res.isValid()).isTrue();
  }

  @Override
  protected BlockHeader createBlockHeader(
      final Optional<List<Withdrawal>> maybeWithdrawals,
      final Optional<List<Deposit>> maybeDeposits) {
    BlockHeader parentBlockHeader =
        new BlockHeaderTestFixture()
            .baseFeePerGas(Wei.ONE)
            .timestamp(super.cancunHardfork.milestone())
            .buildHeader();

    when(blockchain.getBlockHeader(parentBlockHeader.getBlockHash()))
        .thenReturn(Optional.of(parentBlockHeader));
    when(protocolContext.getBlockchain()).thenReturn(blockchain);
    BlockHeader mockHeader =
        new BlockHeaderTestFixture()
            .baseFeePerGas(Wei.ONE)
            .parentHash(parentBlockHeader.getParentHash())
            .number(parentBlockHeader.getNumber() + 1)
            .timestamp(parentBlockHeader.getTimestamp() + 12)
            .withdrawalsRoot(maybeWithdrawals.map(BodyValidation::withdrawalsRoot).orElse(null))
            .depositsRoot(maybeDeposits.map(BodyValidation::depositsRoot).orElse(null))
            .excessBlobGas(BlobGas.ZERO)
            .blobGasUsed(0L)
            .parentBeaconBlockRoot(
                maybeParentBeaconBlockRoot.isPresent() ? maybeParentBeaconBlockRoot : null)
            .buildHeader();
    return mockHeader;
  }

  protected JsonRpcResponse resp(final EnginePayloadParameter payload, final List<String> hashes) {
    String parentBeaconBlockRoot = maybeParentBeaconBlockRoot.get().toHexString();
    Object[] params = new Object[] {payload, hashes, parentBeaconBlockRoot};
    return method.response(
        new JsonRpcRequestContext(new JsonRpcRequest("2.0", this.method.getName(), params)));
  }

  @Override
  protected JsonRpcResponse resp(final EnginePayloadParameter payload) {
    String parentBeaconBlockRoot = maybeParentBeaconBlockRoot.get().toHexString();
    Object[] params = new Object[] {payload, List.of(), parentBeaconBlockRoot};
    return method.response(
        new JsonRpcRequestContext(new JsonRpcRequest("2.0", this.method.getName(), params)));
  }

  @Override
  @Test
  public void shouldReturnValidIfProtocolScheduleIsEmpty() {
    // no longer the case, blob validation requires a protocol schedule
  }
}
