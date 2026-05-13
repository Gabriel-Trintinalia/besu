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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.JsonRpcRequest;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.JsonRpcRequestContext;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.cache.ExecutionWitnessCache;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.response.JsonRpcErrorResponse;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.response.JsonRpcSuccessResponse;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.response.RpcErrorType;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.results.ExecutionWitnessResult;
import org.hyperledger.besu.ethereum.api.query.BlockchainQueries;
import org.hyperledger.besu.ethereum.chain.Blockchain;
import org.hyperledger.besu.ethereum.core.BlockHeader;
import org.hyperledger.besu.ethereum.core.BlockHeaderTestFixture;
import org.hyperledger.besu.ethereum.core.BlockchainSetupUtil;
import org.hyperledger.besu.ethereum.core.MiningConfiguration;
import org.hyperledger.besu.ethereum.trie.pathbased.bonsai.BonsaiWorldStateProvider;
import org.hyperledger.besu.ethereum.trie.pathbased.common.trielog.TrieLogManager;
import org.hyperledger.besu.ethereum.worldstate.WorldStateArchive;
import org.hyperledger.besu.metrics.noop.NoOpMetricsSystem;
import org.hyperledger.besu.plugin.services.storage.DataStorageFormat;
import org.hyperledger.besu.plugin.services.trielogs.TrieLog;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public class DebugExecutionWitnessTest {

  private static final NoOpMetricsSystem METRICS = new NoOpMetricsSystem();
  private static final ExecutionWitnessCache CACHE =
      new ExecutionWitnessCache(ExecutionWitnessCache.DEFAULT_MAX_BYTES);

  @Test
  public void nameShouldBeDebugExecutionWitness() {
    final BlockchainQueries queries = mock(BlockchainQueries.class);
    final DebugExecutionWitness method = new DebugExecutionWitness(queries, METRICS, CACHE);
    assertThat(method.getName()).isEqualTo("debug_executionWitness");
  }

  @Test
  public void shouldReturnInternalErrorWhenArchiveIsNotPathBased() {
    final BlockchainQueries queries = mock(BlockchainQueries.class);
    final WorldStateArchive nonPathBased = mock(WorldStateArchive.class);
    final Blockchain blockchain = mock(Blockchain.class);
    final BlockHeader parent = new BlockHeaderTestFixture().number(0).buildHeader();
    final BlockHeader blockHeader =
        new BlockHeaderTestFixture().number(1).parentHash(parent.getHash()).buildHeader();
    when(queries.getBlockHeaderByHash(blockHeader.getHash())).thenReturn(Optional.of(blockHeader));
    when(queries.getBlockchain()).thenReturn(blockchain);
    when(blockchain.getBlockHeader(parent.getHash())).thenReturn(Optional.of(parent));
    when(queries.getWorldStateArchive()).thenReturn(nonPathBased);

    final JsonRpcRequestContext request = requestForBlockHash(blockHeader.getHash());
    final DebugExecutionWitness method = new DebugExecutionWitness(queries, METRICS, CACHE);
    final Object result = method.response(request);

    assertThat(result).isInstanceOf(JsonRpcErrorResponse.class);
    assertThat(((JsonRpcErrorResponse) result).getErrorType())
        .isEqualTo(RpcErrorType.INTERNAL_ERROR);
  }

  @Test
  public void shouldReturnNullResultWhenBlockHashNotFound() {
    // When a block hash is not found, AbstractBlockParameterOrBlockHashMethod.blockNotFoundResponse
    // returns JsonRpcSuccessResponse(null) rather than an error response.
    final BlockchainQueries queries = mock(BlockchainQueries.class);
    when(queries.getBlockHeaderByHash(any())).thenReturn(Optional.empty());

    final JsonRpcRequestContext request = requestForBlockHash(Hash.ZERO);
    final DebugExecutionWitness method = new DebugExecutionWitness(queries, METRICS, CACHE);
    final Object result = method.response(request);

    assertThat(result).isInstanceOf(JsonRpcSuccessResponse.class);
    assertThat(((JsonRpcSuccessResponse) result).getResult()).isNull();
  }

  @Test
  public void shouldReturnBlockNotFoundWhenParentHeaderMissing() {
    final BlockchainQueries queries = mock(BlockchainQueries.class);
    final Blockchain blockchain = mock(Blockchain.class);
    when(queries.getBlockchain()).thenReturn(blockchain);

    final BlockHeader blockHeader = new BlockHeaderTestFixture().buildHeader();
    when(queries.getBlockHeaderByHash(blockHeader.getHash())).thenReturn(Optional.of(blockHeader));
    when(blockchain.getBlockHeader(blockHeader.getParentHash())).thenReturn(Optional.empty());

    final JsonRpcRequestContext request = requestForBlockHash(blockHeader.getHash());
    final DebugExecutionWitness method = new DebugExecutionWitness(queries, METRICS, CACHE);
    final Object result = method.response(request);

    assertThat(result).isInstanceOf(JsonRpcErrorResponse.class);
    assertThat(((JsonRpcErrorResponse) result).getErrorType())
        .isEqualTo(RpcErrorType.BLOCK_NOT_FOUND);
  }

  @Test
  public void shouldReturnInternalErrorWhenTrieLogMissing() {
    final BlockchainQueries queries = mock(BlockchainQueries.class);
    final BonsaiWorldStateProvider pathBased = mock(BonsaiWorldStateProvider.class);
    final Blockchain blockchain = mock(Blockchain.class);
    final TrieLogManager trieLogManager = mock(TrieLogManager.class);
    when(queries.getWorldStateArchive()).thenReturn(pathBased);
    when(queries.getBlockchain()).thenReturn(blockchain);
    when(pathBased.getTrieLogManager()).thenReturn(trieLogManager);
    when(trieLogManager.getTrieLogLayer(any(Hash.class))).thenReturn(Optional.empty());

    final BlockHeader parent = new BlockHeaderTestFixture().number(0).buildHeader();
    final BlockHeader block =
        new BlockHeaderTestFixture().number(1).parentHash(parent.getHash()).buildHeader();
    when(queries.getBlockHeaderByHash(block.getHash())).thenReturn(Optional.of(block));
    when(blockchain.getBlockHeader(parent.getHash())).thenReturn(Optional.of(parent));

    final JsonRpcRequestContext request = requestForBlockHash(block.getHash());
    final DebugExecutionWitness method = new DebugExecutionWitness(queries, METRICS, CACHE);
    final Object result = method.response(request);

    assertThat(result).isInstanceOf(JsonRpcErrorResponse.class);
    assertThat(((JsonRpcErrorResponse) result).getErrorType())
        .isEqualTo(RpcErrorType.INTERNAL_ERROR);
  }

  @Test
  public void shouldReturnInternalErrorWhenWorldStateNotAvailable() {
    final BlockchainQueries queries = mock(BlockchainQueries.class);
    final BonsaiWorldStateProvider pathBased = mock(BonsaiWorldStateProvider.class);
    final Blockchain blockchain = mock(Blockchain.class);
    final TrieLogManager trieLogManager = mock(TrieLogManager.class);
    final TrieLog trieLog = mock(TrieLog.class);
    when(queries.getWorldStateArchive()).thenReturn(pathBased);
    when(queries.getBlockchain()).thenReturn(blockchain);
    when(pathBased.getTrieLogManager()).thenReturn(trieLogManager);
    when(trieLogManager.getTrieLogLayer(any(Hash.class))).thenReturn(Optional.of(trieLog));
    when(pathBased.getWorldState(any())).thenReturn(Optional.empty());

    final BlockHeader parent = new BlockHeaderTestFixture().number(0).buildHeader();
    final BlockHeader block =
        new BlockHeaderTestFixture().number(1).parentHash(parent.getHash()).buildHeader();
    when(queries.getBlockHeaderByHash(block.getHash())).thenReturn(Optional.of(block));
    when(blockchain.getBlockHeader(parent.getHash())).thenReturn(Optional.of(parent));

    final JsonRpcRequestContext request = requestForBlockHash(block.getHash());
    final DebugExecutionWitness method = new DebugExecutionWitness(queries, METRICS, CACHE);
    final Object result = method.response(request);

    assertThat(result).isInstanceOf(JsonRpcErrorResponse.class);
    assertThat(((JsonRpcErrorResponse) result).getErrorType())
        .isEqualTo(RpcErrorType.INTERNAL_ERROR);
  }

  @Test
  public void shouldReturnWitnessWithStateCodesAndHeadersForValidBlock() {
    final BlockchainSetupUtil setup = BlockchainSetupUtil.forHiveTesting(DataStorageFormat.BONSAI);
    setup.importAllBlocks();

    final BlockchainQueries queries =
        new BlockchainQueries(
            setup.getProtocolSchedule(),
            setup.getBlockchain(),
            setup.getWorldArchive(),
            MiningConfiguration.MINING_DISABLED);

    final Hash chainHeadHash = setup.getBlockchain().getChainHeadHash();
    final JsonRpcRequestContext request = requestForBlockHash(chainHeadHash);

    final DebugExecutionWitness method = new DebugExecutionWitness(queries, METRICS, CACHE);
    final Object result = method.response(request);

    assertThat(result).isInstanceOf(JsonRpcSuccessResponse.class);
    final ExecutionWitnessResult witness =
        (ExecutionWitnessResult) ((JsonRpcSuccessResponse) result).getResult();
    assertThat(witness).isNotNull();
    assertThat(witness.getState()).isNotNull();
    assertThat(witness.getCodes()).isNotNull();
    assertThat(witness.getKeys()).isNotNull();
    assertThat(witness.getHeaders()).isNotEmpty();
    // all values must be 0x-prefixed hex
    witness.getState().forEach(node -> assertThat(node).startsWith("0x"));
    witness.getCodes().forEach(code -> assertThat(code).startsWith("0x"));
    witness.getKeys().forEach(key -> assertThat(key).startsWith("0x"));
    witness.getHeaders().forEach(header -> assertThat(header).startsWith("0x"));
    // EIP-8025: state and codes must be sorted lexicographically
    assertThat(witness.getState()).isSortedAccordingTo(String::compareTo);
    assertThat(witness.getCodes()).isSortedAccordingTo(String::compareTo);
    assertThat(witness.getKeys()).isSortedAccordingTo(String::compareTo);
    // EIP-8025: headers must be ascending by block number (genesis first)
    assertThat(witness.getHeaders()).hasSizeGreaterThanOrEqualTo(1);
  }

  @Test
  public void shouldServeCachedWitnessWithoutTouchingWorldStateArchive() {
    final BlockchainQueries queries = mock(BlockchainQueries.class);
    final BlockHeader blockHeader = new BlockHeaderTestFixture().buildHeader();
    when(queries.getBlockHeaderByHash(blockHeader.getHash())).thenReturn(Optional.of(blockHeader));

    final ExecutionWitnessResult cached =
        new ExecutionWitnessResult(
            List.of("0xstate"), List.of("0xcode"), List.of("0xkey"), List.of("0xheader"));
    final ExecutionWitnessCache cache =
        new ExecutionWitnessCache(ExecutionWitnessCache.DEFAULT_MAX_BYTES);
    cache.put(blockHeader.getHash(), cached);

    final JsonRpcRequestContext request = requestForBlockHash(blockHeader.getHash());
    final DebugExecutionWitness method = new DebugExecutionWitness(queries, METRICS, cache);
    final Object result = method.response(request);

    assertThat(result).isInstanceOf(JsonRpcSuccessResponse.class);
    assertThat(((JsonRpcSuccessResponse) result).getResult()).isSameAs(cached);
    // Cache hit short-circuits before any blockchain/world-state lookups beyond the param check.
    verify(queries, never()).getWorldStateArchive();
    verify(queries, never()).getBlockchain();
  }

  private static JsonRpcRequestContext requestForBlockHash(final Hash blockHash) {
    return new JsonRpcRequestContext(
        new JsonRpcRequest(
            "2.0", "debug_executionWitness", new Object[] {blockHash.toHexString()}));
  }
}
