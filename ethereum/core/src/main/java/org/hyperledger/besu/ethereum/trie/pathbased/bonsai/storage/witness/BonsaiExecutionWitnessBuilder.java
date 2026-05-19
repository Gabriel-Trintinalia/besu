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
package org.hyperledger.besu.ethereum.trie.pathbased.bonsai.storage.witness;

import static org.hyperledger.besu.ethereum.trie.pathbased.common.provider.WorldStateQueryParams.withBlockHeaderAndNoUpdateNodeHead;

import org.hyperledger.besu.datatypes.AccountValue;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.ethereum.chain.Blockchain;
import org.hyperledger.besu.ethereum.core.BlockHeader;
import org.hyperledger.besu.ethereum.rlp.RLP;
import org.hyperledger.besu.ethereum.trie.pathbased.bonsai.NoOpBonsaiCachedWorldStorageManager;
import org.hyperledger.besu.ethereum.trie.pathbased.bonsai.cache.CodeCache;
import org.hyperledger.besu.ethereum.trie.pathbased.bonsai.cache.NoopBonsaiCachedMerkleTrieLoader;
import org.hyperledger.besu.ethereum.trie.pathbased.bonsai.worldview.BonsaiWorldState;
import org.hyperledger.besu.ethereum.trie.pathbased.bonsai.worldview.BonsaiWorldStateUpdateAccumulator;
import org.hyperledger.besu.ethereum.trie.pathbased.common.provider.PathBasedWorldStateProvider;
import org.hyperledger.besu.ethereum.trie.pathbased.common.trielog.NoOpTrieLogManager;
import org.hyperledger.besu.ethereum.trie.pathbased.common.worldview.WorldStateConfig;
import org.hyperledger.besu.ethereum.worldstate.WorldStateArchive;
import org.hyperledger.besu.evm.internal.EvmConfiguration;
import org.hyperledger.besu.plugin.services.MetricsSystem;
import org.hyperledger.besu.plugin.services.trielogs.TrieLog;
import org.hyperledger.besu.plugin.services.worldstate.MutableWorldState;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.tuweni.bytes.Bytes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Builds the EIP-8025 execution witness (state trie nodes, contract codes, and ancestor headers)
 * for a single block from a Bonsai world state and trie log. Used by both {@code
 * debug_executionWitness} and reference-test tooling so that both paths emit identical output.
 */
public class BonsaiExecutionWitnessBuilder {

  private static final Logger LOG = LoggerFactory.getLogger(BonsaiExecutionWitnessBuilder.class);

  public record Witness(List<String> state, List<String> codes, List<String> headers) {}

  private final MetricsSystem metricsSystem;

  public BonsaiExecutionWitnessBuilder(final MetricsSystem metricsSystem) {
    this.metricsSystem = metricsSystem;
  }

  /**
   * Builds the witness for {@code blockHeader}. Headers are built as a contiguous chain from {@code
   * oldestAccessedAncestor} up to (and including) the parent, following parentHash links.
   */
  public Witness build(
      final BlockHeader blockHeader,
      final TrieLog trieLog,
      final BonsaiWorldState parentWorldState,
      final Blockchain blockchain,
      final long oldestAccessedAncestor) {
    final List<String> state = buildTrieNodes(blockHeader, trieLog, parentWorldState);
    final List<String> codes = buildCodes(trieLog, parentWorldState);
    final List<String> headers = buildHeaders(blockchain, blockHeader, oldestAccessedAncestor);
    return new Witness(state, codes, headers);
  }

  /**
   * Resolves the path-based archive, trie log, and parent world state for {@code blockHeader} and
   * builds the witness. Returns empty when the archive is not path-based, the trie log is absent,
   * the parent state is unavailable, or the build itself throws — callers handle the empty case as
   * they choose (engine API: omit witness from response; debug RPC: error response).
   */
  public Optional<Witness> tryBuildForBlock(
      final BlockHeader blockHeader,
      final BlockHeader parentHeader,
      final WorldStateArchive worldStateArchive,
      final Blockchain blockchain,
      final long oldestAccessedAncestor) {
    if (!(worldStateArchive instanceof PathBasedWorldStateProvider pathBased)) {
      return Optional.empty();
    }
    final Optional<TrieLog> maybeTrieLog =
        pathBased.getTrieLogManager().getTrieLogLayer(blockHeader.getHash());
    if (maybeTrieLog.isEmpty()) {
      return Optional.empty();
    }
    final Optional<MutableWorldState> maybeParent =
        pathBased.getWorldState(withBlockHeaderAndNoUpdateNodeHead(parentHeader));
    if (maybeParent.isEmpty() || !(maybeParent.get() instanceof BonsaiWorldState parent)) {
      return Optional.empty();
    }
    try (parent) {
      return Optional.of(
          build(blockHeader, maybeTrieLog.get(), parent, blockchain, oldestAccessedAncestor));
    } catch (final Exception e) {
      LOG.warn("failed to build execution witness for {}: {}", blockHeader.getHash(), e.toString());
      return Optional.empty();
    }
  }

  private List<String> buildTrieNodes(
      final BlockHeader blockHeader, final TrieLog trieLog, final BonsaiWorldState worldView) {

    final BonsaiWorldStateWitnessStorage witnessStorage =
        new BonsaiWorldStateWitnessStorage(metricsSystem, worldView.getWorldStateStorage());
    final CodeCache codeCache = new CodeCache();
    final BonsaiWorldState witnessWorldState =
        new BonsaiWorldState(
            witnessStorage,
            new NoopBonsaiCachedMerkleTrieLoader(),
            new NoOpBonsaiCachedWorldStorageManager(
                witnessStorage, EvmConfiguration.DEFAULT, codeCache),
            new NoOpTrieLogManager(),
            EvmConfiguration.DEFAULT,
            WorldStateConfig.newBuilder().build(),
            codeCache);

    final BonsaiWorldStateUpdateAccumulator updater =
        (BonsaiWorldStateUpdateAccumulator) witnessWorldState.updater();

    // Force reads of all accounts and storage touched by the trie log.
    trieLog
        .getAccountChanges()
        .forEach(
            (address, accountChange) -> {
              updater.getAccount(address);
              trieLog
                  .getStorageChanges(address)
                  .forEach(
                      (storageSlotKey, __) ->
                          updater.getStorageValueByStorageSlotKey(address, storageSlotKey));
            });

    updater.rollForward(trieLog);
    updater.commit();
    witnessWorldState.persist(blockHeader);

    return witnessStorage.getTrieNodes().stream().map(Bytes::toHexString).sorted().toList();
  }

  private List<String> buildCodes(final TrieLog trieLog, final BonsaiWorldState worldView) {
    final var resultSet = ConcurrentHashMap.<String>newKeySet();
    trieLog.getAccountChanges().entrySet().parallelStream()
        .filter(
            entry ->
                entry.getValue().getPrior() != null
                    && !entry.getValue().getPrior().getCodeHash().equals(Hash.EMPTY))
        .forEach(
            entry -> {
              final Address address = entry.getKey();
              final AccountValue prior = entry.getValue().getPrior();
              worldView
                  .getCode(address, prior.getCodeHash())
                  .ifPresent(bytes -> resultSet.add(bytes.toHexString()));
            });
    return resultSet.stream().sorted().toList();
  }

  private List<String> buildHeaders(
      final Blockchain blockchain,
      final BlockHeader blockHeader,
      final long oldestAccessedAncestor) {
    // Walk backwards from the parent following parentHash links until we reach the oldest
    // accessed ancestor, producing a contiguous chain rather than only the directly-accessed
    // blocks.
    final List<BlockHeader> chain = new ArrayList<>();
    BlockHeader current =
        blockchain
            .getBlockHeader(blockHeader.getParentHash())
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "missing parent header for block " + blockHeader.getNumber()));
    chain.add(current);

    while (current.getNumber() > oldestAccessedAncestor) {
      final Hash ph = current.getParentHash();
      final long expectedNumber = current.getNumber() - 1;
      current =
          blockchain
              .getBlockHeader(ph)
              .orElseThrow(
                  () ->
                      new IllegalStateException(
                          "missing block header for ancestor " + expectedNumber));
      chain.add(current);
    }

    Collections.reverse(chain);
    return chain.stream().map(h -> RLP.encode(h::writeTo).toHexString()).toList();
  }
}
