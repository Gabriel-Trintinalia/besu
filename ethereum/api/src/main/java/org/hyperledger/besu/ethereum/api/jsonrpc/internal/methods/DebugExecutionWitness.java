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
import org.hyperledger.besu.ethereum.BlockProcessingResult;
import org.hyperledger.besu.ethereum.ProtocolContext;
import org.hyperledger.besu.ethereum.WitnessHint;
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
import org.hyperledger.besu.ethereum.trie.pathbased.bonsai.storage.witness.BonsaiExecutionWitnessBuilder;
import org.hyperledger.besu.ethereum.trie.pathbased.bonsai.storage.witness.BonsaiPostprocessingFunction;

import java.util.Collections;
import java.util.Map;
import java.util.Optional;

/**
 * Reconstructs the EIP-8025 execution witness for a previously-imported block by re-executing it
 * against the parent's world state.
 *
 * <p>Re-execution captures the accessed accounts, storage slots, and {@code BLOCKHASH} ancestors
 * via a {@link BonsaiPostprocessingFunction}; {@code state}, {@code codes}, and {@code headers} are then
 * assembled by {@link BonsaiExecutionWitnessBuilder#buildFromHint}. Re-execution does not advance
 * the chain head or record bad blocks.
 */
public class DebugExecutionWitness extends AbstractBlockParameterOrBlockHashMethod {

  private final ProtocolContext protocolContext;
  private final ProtocolSchedule protocolSchedule;

  public DebugExecutionWitness(
      final BlockchainQueries blockchainQueries,
      final ProtocolContext protocolContext,
      final ProtocolSchedule protocolSchedule) {
    super(blockchainQueries);
    this.protocolContext = protocolContext;
    this.protocolSchedule = protocolSchedule;
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

    final BonsaiPostprocessingFunction postprocessingFunction = new BonsaiPostprocessingFunction();
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
                false,
                postprocessingFunction);

    if (!result.isSuccessful()) {
      return new JsonRpcErrorResponse(reqId, RpcErrorType.INTERNAL_ERROR);
    }

    final Optional<WitnessHint> maybeHint = postprocessingFunction.getWitnessHint();
    final long oldestAccessedAncestor =
        maybeHint
            .map(WitnessHint::accessedBlockHashes)
            .filter(m -> !m.isEmpty())
            .map(m -> Collections.min(m.keySet()))
            .orElse(blockHeader.getNumber() - 1);

    final WitnessHint hint =
        maybeHint.orElseGet(() -> new WitnessHint(Map.of(), Map.of(), Map.of()));

    return new BonsaiExecutionWitnessBuilder()
        .buildFromHint(
            blockHeader,
            hint,
            parentHeader,
            getBlockchainQueries().getWorldStateArchive(),
            blockchain,
            oldestAccessedAncestor)
        .<Object>map(
            built -> new ExecutionWitnessResult(built.state(), built.codes(), built.headers()))
        .orElseGet(() -> new JsonRpcErrorResponse(reqId, RpcErrorType.INTERNAL_ERROR));
  }
}
