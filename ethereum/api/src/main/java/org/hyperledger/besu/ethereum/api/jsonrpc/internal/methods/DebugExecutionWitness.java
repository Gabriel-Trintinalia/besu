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
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.exception.InvalidJsonRpcParameters;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.parameters.BlockParameterOrBlockHash;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.parameters.JsonRpcParameter.JsonRpcParameterException;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.response.JsonRpcErrorResponse;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.response.RpcErrorType;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.results.ExecutionWitnessResult;
import org.hyperledger.besu.ethereum.api.query.BlockchainQueries;
import org.hyperledger.besu.ethereum.chain.Blockchain;
import org.hyperledger.besu.ethereum.core.BlockHeader;
import org.hyperledger.besu.ethereum.trie.pathbased.bonsai.storage.BonsaiExecutionWitnessBuilder;
import org.hyperledger.besu.plugin.services.MetricsSystem;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Reconstructs the EIP-8025 execution witness for a previously-imported block.
 *
 * <p>The {@code headers} list is recovered from the oldest-accessed-ancestor sidecar that was
 * persisted at block import time (see {@code Blockchain.getOldestAccessedAncestor}). Walking the
 * chain backward from the parent down to that block number materializes the contiguous ancestor
 * range; {@code state}, {@code codes}, and {@code keys} come from the persisted trie log via
 * {@link BonsaiExecutionWitnessBuilder}.
 *
 * <p>Returns {@code INTERNAL_ERROR} when no sidecar is found for the requested block. This is
 * expected for blocks imported before the feature shipped and for any path that bypasses the
 * standard import flow.
 */
public class DebugExecutionWitness extends AbstractBlockParameterOrBlockHashMethod {

  private final MetricsSystem metricsSystem;

  public DebugExecutionWitness(
      final BlockchainQueries blockchainQueries, final MetricsSystem metricsSystem) {
    super(blockchainQueries);
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
    final Blockchain blockchain = getBlockchainQueries().getBlockchain();

    final Optional<BlockHeader> maybeBlockHeader =
        getBlockchainQueries().getBlockHeaderByHash(blockHash);
    if (maybeBlockHeader.isEmpty()) {
      return new JsonRpcErrorResponse(request.getRequest().getId(), RpcErrorType.BLOCK_NOT_FOUND);
    }
    final BlockHeader blockHeader = maybeBlockHeader.get();

    final Optional<BlockHeader> maybeParent =
        blockchain.getBlockHeader(blockHeader.getParentHash());
    if (maybeParent.isEmpty()) {
      return new JsonRpcErrorResponse(request.getRequest().getId(), RpcErrorType.BLOCK_NOT_FOUND);
    }
    final BlockHeader parentHeader = maybeParent.get();

    final Optional<Long> oldestAccessed = blockchain.getOldestAccessedAncestor(blockHash);
    if (oldestAccessed.isEmpty()) {
      return new JsonRpcErrorResponse(
          request.getRequest().getId(), RpcErrorType.INTERNAL_ERROR);
    }

    final Map<Long, Hash> accessedAncestors =
        buildAccessedAncestors(parentHeader, oldestAccessed.get(), blockchain);

    return new BonsaiExecutionWitnessBuilder(metricsSystem)
        .tryBuildForBlock(
            blockHeader,
            parentHeader,
            getBlockchainQueries().getWorldStateArchive(),
            blockchain,
            accessedAncestors)
        .<Object>map(
            built -> new ExecutionWitnessResult(built.state(), built.codes(), built.headers()))
        .orElseGet(
            () ->
                new JsonRpcErrorResponse(
                    request.getRequest().getId(), RpcErrorType.INTERNAL_ERROR));
  }

  /**
   * Reconstructs the {@code accessedAncestors} map for a previously-imported block by walking the
   * chain backward from {@code parentHeader} down to {@code oldestAccessedNumber}. The result has
   * the same shape as {@link
   * org.hyperledger.besu.ethereum.BlockProcessingOutputs#getAccessedAncestors()} captured at
   * import time — but built from persisted headers rather than the in-memory
   * {@link org.hyperledger.besu.evm.blockhash.BlockHashLookup}. At most 256 hops (the
   * {@code BLOCKHASH} reach).
   */
  private static Map<Long, Hash> buildAccessedAncestors(
      final BlockHeader parentHeader,
      final long oldestAccessedNumber,
      final Blockchain blockchain) {
    final Map<Long, Hash> ancestors = new HashMap<>();
    BlockHeader cursor = parentHeader;
    while (cursor != null && cursor.getNumber() >= oldestAccessedNumber) {
      ancestors.put(cursor.getNumber(), cursor.getBlockHash());
      if (cursor.getNumber() == oldestAccessedNumber) {
        break;
      }
      cursor = blockchain.getBlockHeader(cursor.getParentHash()).orElse(null);
    }
    return ancestors;
  }
}
