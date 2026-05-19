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
package org.hyperledger.besu.ethereum.api.jsonrpc.internal.methods;

import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.ethereum.BlockProcessingOutputs;
import org.hyperledger.besu.ethereum.BlockProcessingResult;
import org.hyperledger.besu.ethereum.ProtocolContext;
import org.hyperledger.besu.ethereum.api.jsonrpc.RpcMethod;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.JsonRpcRequestContext;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.exception.InvalidJsonRpcParameters;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.parameters.BlockParameterOrBlockHash;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.parameters.JsonRpcParameter.JsonRpcParameterException;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.response.JsonRpcErrorResponse;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.response.RpcErrorType;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.results.ExecutionWitnessResult;
import org.hyperledger.besu.ethereum.api.query.BlockchainQueries;
import org.hyperledger.besu.ethereum.chain.Blockchain;
import org.hyperledger.besu.ethereum.core.Block;
import org.hyperledger.besu.ethereum.core.BlockHeader;
import org.hyperledger.besu.ethereum.mainnet.HeaderValidationMode;
import org.hyperledger.besu.ethereum.mainnet.ProtocolSchedule;
import org.hyperledger.besu.ethereum.trie.pathbased.bonsai.storage.BonsaiExecutionWitnessBuilder;
import org.hyperledger.besu.plugin.services.MetricsSystem;

import java.util.Map;
import java.util.Optional;

/**
 * Reconstructs the EIP-8025 execution witness for a previously-imported block by re-executing it
 * against the parent's world state.
 *
 * <p>Re-execution captures the {@code BLOCKHASH}-accessed ancestor set via {@link
 * BlockProcessingOutputs#getAccessedAncestors()}; {@code state}, {@code codes}, and {@code headers}
 * are then assembled by {@link BonsaiExecutionWitnessBuilder} from the persisted trie log + the
 * accessed-ancestors map. The re-execution does not advance the chain head or record bad blocks.
 */
public class DebugExecutionWitness extends AbstractBlockParameterOrBlockHashMethod {

  private final ProtocolContext protocolContext;
  private final ProtocolSchedule protocolSchedule;
  private final MetricsSystem metricsSystem;

  public DebugExecutionWitness(
      final BlockchainQueries blockchainQueries,
      final ProtocolContext protocolContext,
      final ProtocolSchedule protocolSchedule,
      final MetricsSystem metricsSystem) {
    super(blockchainQueries);
    this.protocolContext = protocolContext;
    this.protocolSchedule = protocolSchedule;
    this.metricsSystem = metricsSystem;
  }

  @Override
  public String getName() {
    return RpcMethod.DEBUG_EXECUTION_WITNESS.getMethodName();
  }

  @Override
  protected BlockParameterOrBlockHash blockParameterOrBlockHash(
      final JsonRpcRequestContext request) {
    try {
      return request.getRequiredParameter(0, BlockParameterOrBlockHash.class);
    } catch (JsonRpcParameterException e) {
      throw new InvalidJsonRpcParameters(
          "Invalid block parameter (index 0)", RpcErrorType.INVALID_BLOCK_PARAMS, e);
    }
  }

  @Override
  protected Object resultByBlockHash(final JsonRpcRequestContext request, final Hash blockHash) {
    final Object reqId = request.getRequest().getId();
    final Blockchain blockchain = getBlockchainQueries().getBlockchain();

    final Optional<Block> maybeBlock = blockchain.getBlockByHash(blockHash);
    if (maybeBlock.isEmpty()) {
      return new JsonRpcErrorResponse(reqId, RpcErrorType.BLOCK_NOT_FOUND);
    }
    final Block block = maybeBlock.get();
    final BlockHeader blockHeader = block.getHeader();

    final Optional<BlockHeader> maybeParent =
        blockchain.getBlockHeader(blockHeader.getParentHash());
    if (maybeParent.isEmpty()) {
      return new JsonRpcErrorResponse(reqId, RpcErrorType.BLOCK_NOT_FOUND);
    }
    final BlockHeader parentHeader = maybeParent.get();

    final BlockProcessingResult result =
        protocolSchedule
            .getByBlockHeader(blockHeader)
            .getBlockValidator()
            .validateAndProcessBlock(
                protocolContext,
                block,
                HeaderValidationMode.NONE,
                HeaderValidationMode.NONE,
                blockchain.getBlockAccessList(blockHash),
                false,
                false);

    if (!result.isSuccessful()) {
      return new JsonRpcErrorResponse(reqId, RpcErrorType.INTERNAL_ERROR);
    }

    final Map<Long, Hash> accessedAncestors =
        result.getYield().map(BlockProcessingOutputs::getAccessedAncestors).orElse(Map.of());

    return new BonsaiExecutionWitnessBuilder(metricsSystem)
        .tryBuildForBlock(
            blockHeader,
            parentHeader,
            getBlockchainQueries().getWorldStateArchive(),
            blockchain,
            accessedAncestors)
        .<Object>map(
            built -> new ExecutionWitnessResult(built.state(), built.codes(), built.headers()))
        .orElseGet(() -> new JsonRpcErrorResponse(reqId, RpcErrorType.INTERNAL_ERROR));
  }
}
