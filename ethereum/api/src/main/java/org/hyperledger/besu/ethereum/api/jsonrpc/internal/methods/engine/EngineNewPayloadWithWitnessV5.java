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
package org.hyperledger.besu.ethereum.api.jsonrpc.internal.methods.engine;

import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.HardforkId;
import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.ethereum.BlockProcessingOutputs;
import org.hyperledger.besu.ethereum.BlockProcessingResult;
import org.hyperledger.besu.ethereum.api.jsonrpc.RpcMethod;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.parameters.ExecutionPayloadV1;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.parameters.ExecutionPayloadV4;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.parameters.NewPayloadRequestParametersV3;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.response.JsonRpcErrorResponse;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.response.JsonRpcResponse;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.response.JsonRpcSuccessResponse;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.response.RpcErrorType;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.results.EngineExecutionWitnessResult;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.results.EnginePayloadWithWitnessResult;
import org.hyperledger.besu.ethereum.core.Block;
import org.hyperledger.besu.ethereum.core.BlockHeader;
import org.hyperledger.besu.ethereum.mainnet.block.access.list.BlockAccessList;
import org.hyperledger.besu.ethereum.mainnet.witness.WitnessCodeTracer;
import org.hyperledger.besu.ethereum.trie.pathbased.bonsai.storage.BonsaiExecutionWitnessBuilder;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Implements {@code engine_newPayloadWithWitnessV5}: the same request/response shape as {@code
 * engine_newPayloadV5}, except that a successful response additionally carries the EIP-8025
 * execution witness for the imported block.
 *
 * <p>The witness is collected during the single import pass rather than by re-executing the block:
 * the block is imported with a {@link WitnessCodeTracer} as its tracer, which collects the EIP-8025
 * code reads, while block processing builds the EIP-7928 block access list (Amsterdam+) the state
 * part is derived from. A plain {@code engine_newPayloadV5} imports without it, so does not pay for
 * that collection.
 */
public final class EngineNewPayloadWithWitnessV5<
        EP extends ExecutionPayloadV4, NPRP extends NewPayloadRequestParametersV3<? extends EP>>
    extends EngineNewPayloadV5<EP, NPRP> {

  private static final Logger LOG = LoggerFactory.getLogger(EngineNewPayloadWithWitnessV5.class);

  public EngineNewPayloadWithWitnessV5(
      final ConstructorArguments constructorArguments,
      final HardforkId minSupportedFork,
      final HardforkId firstUnsupportedFork) {
    super(constructorArguments, minSupportedFork, firstUnsupportedFork);
  }

  @Override
  protected Logger logger() {
    return LOG;
  }

  @Override
  public String getName() {
    return RpcMethod.ENGINE_NEW_PAYLOAD_WITH_WITNESS_V5.getMethodName();
  }

  /** A successful import, together with the code reads its witness tracer collected. */
  private static final class WitnessedImport extends BlockProcessingResult {
    private final Set<Address> codeReads;

    private WitnessedImport(final BlockProcessingResult result, final Set<Address> codeReads) {
      super(result.getYield(), result.getNbParallelizedTransactions());
      this.codeReads = codeReads;
    }
  }

  @Override
  protected BlockProcessingResult rememberBlock(final Block block, final EP executionPayload) {
    final WitnessCodeTracer witnessCodeTracer = new WitnessCodeTracer();
    final BlockProcessingResult result =
        mergeCoordinator.rememberBlock(
            block, Optional.of(executionPayload.getBlockAccessList()), witnessCodeTracer);
    return result.isSuccessful()
        ? new WitnessedImport(result, witnessCodeTracer.codeReads())
        : result;
  }

  @Override
  protected JsonRpcResponse respondWithSuccess(
      final Object requestId,
      final ExecutionPayloadV1 param,
      final BlockHeader newBlockHeader,
      final BlockProcessingResult executionResult) {
    final Hash validHash = newBlockHeader.getHash();
    final Optional<BlockAccessList> blockAccessList =
        executionResult.getYield().flatMap(BlockProcessingOutputs::getBlockAccessList);
    final Optional<Set<Address>> codeReads =
        executionResult instanceof WitnessedImport witnessedImport
            ? Optional.of(witnessedImport.codeReads)
            : Optional.empty();
    if (blockAccessList.isEmpty() || codeReads.isEmpty()) {
      LOG.debug("Witness data unavailable for imported block {}", validHash);
      return new JsonRpcErrorResponse(requestId, RpcErrorType.INTERNAL_ERROR);
    }
    final Map<Long, Hash> accessedAncestors =
        executionResult
            .getYield()
            .map(BlockProcessingOutputs::getAccessedAncestors)
            .orElse(Map.of());

    try {
      final BonsaiExecutionWitnessBuilder.Witness witness =
          new BonsaiExecutionWitnessBuilder(
                  protocolContext.getWorldStateArchive(), protocolContext.getBlockchain())
              .buildWitness(
                  newBlockHeader, blockAccessList.get(), accessedAncestors, codeReads.get());

      if (witness.state().isEmpty()) {
        LOG.debug("Empty witness state for imported block {}", validHash);
        return new JsonRpcErrorResponse(requestId, RpcErrorType.INTERNAL_ERROR);
      }

      return new JsonRpcSuccessResponse(
          requestId,
          new EnginePayloadWithWitnessResult(
              EngineStatus.VALID,
              validHash,
              Optional.empty(),
              new EngineExecutionWitnessResult(
                  witness.state(), witness.codes(), witness.headers())));
    } catch (final IllegalStateException e) {
      LOG.debug("Failed to build execution witness for block {}", validHash, e);
      return new JsonRpcErrorResponse(requestId, RpcErrorType.INTERNAL_ERROR);
    }
  }
}
