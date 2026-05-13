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
import org.hyperledger.besu.ethereum.api.jsonrpc.RpcMethod;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.JsonRpcRequestContext;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.cache.ExecutionWitnessCache;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.exception.InvalidJsonRpcParameters;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.parameters.BlockParameterOrBlockHash;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.parameters.JsonRpcParameter.JsonRpcParameterException;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.response.JsonRpcErrorResponse;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.response.RpcErrorType;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.results.ExecutionWitnessResult;
import org.hyperledger.besu.ethereum.api.query.BlockchainQueries;
import org.hyperledger.besu.ethereum.core.BlockHeader;
import org.hyperledger.besu.ethereum.trie.pathbased.bonsai.storage.BonsaiExecutionWitnessBuilder;
import org.hyperledger.besu.plugin.services.MetricsSystem;

import java.util.Map;
import java.util.Optional;

public class DebugExecutionWitness extends AbstractBlockParameterOrBlockHashMethod {

  private final MetricsSystem metricsSystem;
  private final ExecutionWitnessCache witnessCache;

  public DebugExecutionWitness(
      final BlockchainQueries blockchainQueries,
      final MetricsSystem metricsSystem,
      final ExecutionWitnessCache witnessCache) {
    super(blockchainQueries);
    this.metricsSystem = metricsSystem;
    this.witnessCache = witnessCache;
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

    final Optional<ExecutionWitnessResult> cached = witnessCache.get(blockHash);
    if (cached.isPresent()) {
      return cached.get();
    }

    final Optional<BlockHeader> maybeBlockHeader =
        getBlockchainQueries().getBlockHeaderByHash(blockHash);
    if (maybeBlockHeader.isEmpty()) {
      return new JsonRpcErrorResponse(request.getRequest().getId(), RpcErrorType.BLOCK_NOT_FOUND);
    }
    final BlockHeader blockHeader = maybeBlockHeader.get();

    final Optional<BlockHeader> maybeParentHeader =
        getBlockchainQueries().getBlockchain().getBlockHeader(blockHeader.getParentHash());
    if (maybeParentHeader.isEmpty()) {
      return new JsonRpcErrorResponse(request.getRequest().getId(), RpcErrorType.BLOCK_NOT_FOUND);
    }
    final BlockHeader parentHeader = maybeParentHeader.get();

    // Cold-path: rebuild from the trie log. Accessed ancestors are not recoverable without
    // re-execution, so {@code headers} is restricted to the parent — correct for blocks that
    // never invoke BLOCKHASH; callers that need the full set should drive the witness through
    // {@code engine_newPayloadWithWitnessV*} to populate the cache.
    final Map<Long, Hash> ancestorsParentOnly =
        Map.of(parentHeader.getNumber(), parentHeader.getBlockHash());

    return new BonsaiExecutionWitnessBuilder(metricsSystem)
        .tryBuildForBlock(
            blockHeader,
            parentHeader,
            getBlockchainQueries().getWorldStateArchive(),
            getBlockchainQueries().getBlockchain(),
            ancestorsParentOnly)
        .map(
            built ->
                new ExecutionWitnessResult(
                    built.state(), built.codes(), built.keys(), built.headers()))
        .map(
            (final ExecutionWitnessResult result) -> {
              witnessCache.put(blockHash, result);
              return (Object) result;
            })
        .orElseGet(
            () ->
                new JsonRpcErrorResponse(
                    request.getRequest().getId(), RpcErrorType.INTERNAL_ERROR));
  }
}
