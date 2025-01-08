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
package org.hyperledger.besu.ethereum.api.jsonrpc.internal.methods;

import static org.hyperledger.besu.ethereum.api.jsonrpc.internal.response.RpcErrorType.BLOCK_NOT_FOUND;

import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.ethereum.api.jsonrpc.RpcMethod;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.JsonRpcRequestContext;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.exception.InvalidJsonRpcParameters;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.parameters.BlockParameterOrBlockHash;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.parameters.BlockStateCallsParameter;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.parameters.JsonBlockStateCall;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.parameters.JsonRpcParameter.JsonRpcParameterException;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.response.JsonRpcError;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.response.JsonRpcErrorResponse;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.response.JsonRpcSuccessResponse;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.response.RpcErrorType;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.results.BlockStateCallResult;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.results.CallProcessingResult;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.results.TransactionCompleteResult;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.results.TransactionResult;
import org.hyperledger.besu.ethereum.api.query.BlockchainQueries;
import org.hyperledger.besu.ethereum.api.query.TransactionWithMetadata;
import org.hyperledger.besu.ethereum.core.Block;
import org.hyperledger.besu.ethereum.core.BlockHeader;
import org.hyperledger.besu.ethereum.core.MiningConfiguration;
import org.hyperledger.besu.ethereum.mainnet.ProtocolSchedule;
import org.hyperledger.besu.ethereum.transaction.BlockSimulationResult;
import org.hyperledger.besu.ethereum.transaction.BlockSimulator;
import org.hyperledger.besu.ethereum.transaction.TransactionSimulator;

import java.util.List;
import java.util.stream.Collectors;

public class EthSimulateV1 extends AbstractBlockParameterOrBlockHashMethod {
  private final BlockSimulator blockSimulator;

  public EthSimulateV1(
      final BlockchainQueries blockchainQueries,
      final ProtocolSchedule protocolSchedule,
      final TransactionSimulator transactionSimulator,
      final MiningConfiguration miningConfiguration) {
    super(blockchainQueries);
    this.blockSimulator =
        new BlockSimulator(
            blockchainQueries.getWorldStateArchive(),
            protocolSchedule,
            transactionSimulator,
            miningConfiguration);
  }

  @Override
  public String getName() {
    return RpcMethod.ETH_SIMULATE_V1.getMethodName();
  }

  @Override
  protected BlockParameterOrBlockHash blockParameterOrBlockHash(
      final JsonRpcRequestContext request) {
    try {
      return request.getRequiredParameter(1, BlockParameterOrBlockHash.class);
    } catch (JsonRpcParameterException e) {
      throw new InvalidJsonRpcParameters(
          "Invalid block or block hash parameters (index 1)", RpcErrorType.INVALID_BLOCK_PARAMS, e);
    }
  }

  @Override
  protected Object resultByBlockHash(final JsonRpcRequestContext request, final Hash blockHash) {
    return blockchainQueries
        .get()
        .getBlockHeaderByHash(blockHash)
        .map(header -> resultByBlockHeader(request, header))
        .orElseGet(() -> errorResponse(request, BLOCK_NOT_FOUND));
  }

  @Override
  protected Object resultByBlockHeader(
      final JsonRpcRequestContext request, final BlockHeader header) {
    try {
      var blockStateCalls = getBlockStateCalls(request);
      var simulationResults = blockSimulator.process(header, blockStateCalls);
      return new JsonRpcSuccessResponse(
          request.getRequest().getId(), createResponse(simulationResults));
    } catch (JsonRpcParameterException e) {
      throw new RuntimeException(e);
    }
  }

  private List<JsonBlockStateCall> getBlockStateCalls(final JsonRpcRequestContext request)
      throws JsonRpcParameterException {
    BlockStateCallsParameter parameter =
        request.getRequiredParameter(0, BlockStateCallsParameter.class);
    return parameter.getBlockStateCalls();
  }

  private JsonRpcErrorResponse errorResponse(
      final JsonRpcRequestContext request, final RpcErrorType rpcErrorType) {
    return new JsonRpcErrorResponse(request.getRequest().getId(), new JsonRpcError(rpcErrorType));
  }

  private Object createResponse(final List<BlockSimulationResult> simulationResult) {
    return simulationResult.stream()
        .map(
            result -> {
              Block block = result.getBlock();
              List<TransactionWithMetadata> txs =
                  block.getBody().getTransactions().stream()
                      .map(
                          transaction ->
                              new TransactionWithMetadata(
                                  transaction,
                                  block.getHeader().getNumber(),
                                  block.getHeader().getBaseFee(),
                                  block.getHash(),
                                  block.getBody().getTransactions().indexOf(transaction)))
                      .toList();
              var transactionResults =
                  result.getTransactionSimulations().stream()
                      .map(this::createTransactionProcessingResult)
                      .toList();
              List<TransactionResult> transactionHashes =
                  txs.stream().map(TransactionCompleteResult::new).collect(Collectors.toList());
              return new BlockStateCallResult(
                  block.getHeader(),
                  transactionHashes,
                  List.of(),
                  transactionResults,
                  block.calculateSize(),
                  block.getBody().getWithdrawals());
            })
        .toList();
  }

  private CallProcessingResult createTransactionProcessingResult(
      final org.hyperledger.besu.ethereum.transaction.TransactionSimulatorResult simulatorResult) {
    return new CallProcessingResult(
        simulatorResult.result().isSuccessful() ? 1 : 0,
        simulatorResult.result().getOutput(),
        simulatorResult.result().getEstimateGasUsedByTransaction(),
        null, // TODO ADD ERROR
        List.of()); // TODO ADD LOG
  }
}
