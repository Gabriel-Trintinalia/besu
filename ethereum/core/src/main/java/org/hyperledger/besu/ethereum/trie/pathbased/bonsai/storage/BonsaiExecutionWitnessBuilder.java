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
package org.hyperledger.besu.ethereum.trie.pathbased.bonsai.storage;

import static org.hyperledger.besu.ethereum.trie.pathbased.common.provider.WorldStateQueryParams.withBlockHeaderAndNoUpdateNodeHead;

import org.hyperledger.besu.datatypes.AccountValue;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.ethereum.chain.Blockchain;
import org.hyperledger.besu.ethereum.core.BlockHeader;
import org.hyperledger.besu.ethereum.rlp.RLP;
import org.hyperledger.besu.ethereum.trie.pathbased.bonsai.cache.CodeCache;
import org.hyperledger.besu.ethereum.trie.pathbased.bonsai.cache.NoOpBonsaiCachedWorldStorageManager;
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

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

import org.apache.tuweni.bytes.Bytes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Builds the EIP-8025 execution witness (state trie nodes, contract codes, access keys, and
 * ancestor headers) for a single block from a Bonsai world state and trie log. Used by both {@code
 * debug_executionWitness} and reference-test tooling so that both paths emit identical output.
 */
public class BonsaiExecutionWitnessBuilder {

  private static final Logger LOG = LoggerFactory.getLogger(BonsaiExecutionWitnessBuilder.class);

  public record Witness(
      List<String> state, List<String> codes, List<String> keys, List<String> headers) {}

  private final MetricsSystem metricsSystem;

  public BonsaiExecutionWitnessBuilder(final MetricsSystem metricsSystem) {
    this.metricsSystem = metricsSystem;
  }

  /**
   * Builds the witness for {@code blockHeader} given the accessed ancestor map captured during
   * execution. The map must include the parent header (every {@code BlockHashLookup} that tracks
   * accesses pre-populates the parent at construction) plus any ancestor reached while serving
   * {@code BLOCKHASH}.
   */
  public Witness build(
      final BlockHeader blockHeader,
      final TrieLog trieLog,
      final BonsaiWorldState parentWorldState,
      final Blockchain blockchain,
      final Map<Long, Hash> accessedAncestors) {
    final List<String> state = buildTrieNodes(blockHeader, trieLog, parentWorldState);
    final List<String> codes = buildCodes(trieLog, parentWorldState);
    final List<String> keys = buildKeys(trieLog);
    final List<String> headers = buildHeaders(blockchain, accessedAncestors);
    return new Witness(state, codes, keys, headers);
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
      final Map<Long, Hash> accessedAncestors) {
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
          build(blockHeader, maybeTrieLog.get(), parent, blockchain, accessedAncestors));
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

  private List<String> buildKeys(final TrieLog trieLog) {
    return trieLog.getAccountChanges().keySet().stream()
        .flatMap(
            addr ->
                Stream.concat(
                    Stream.of(addr.toHexString()),
                    trieLog.getStorageChanges(addr).keySet().stream()
                        .filter(k -> k.getSlotKey().isPresent())
                        .map(k -> k.getSlotKey().orElseThrow().toHexString())))
        .distinct()
        .sorted()
        .toList();
  }

  private List<String> buildHeaders(
      final Blockchain blockchain, final Map<Long, Hash> accessedAncestors) {
    // EIP-8025: the headers list contains every ancestor whose hash was observed during block
    // execution — at minimum the parent (always present in the accessed map) and any block
    // resolved while serving BLOCKHASH. Order: ascending by block number.
    return new TreeSet<>(accessedAncestors.keySet())
        .stream()
            .map(
                number -> {
                  final Hash hash = accessedAncestors.get(number);
                  return blockchain
                      .getBlockHeader(hash)
                      .orElseThrow(
                          () ->
                              new IllegalStateException(
                                  "missing block header for accessed ancestor "
                                      + number
                                      + " ("
                                      + hash
                                      + ")"));
                })
            .map(h -> RLP.encode(h::writeTo).toHexString())
            .toList();
  }
}
