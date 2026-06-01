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

import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.datatypes.StorageSlotKey;
import org.hyperledger.besu.ethereum.BlockProcessingOutputs;
import org.hyperledger.besu.ethereum.chain.Blockchain;
import org.hyperledger.besu.ethereum.core.BlockHeader;
import org.hyperledger.besu.ethereum.mainnet.block.access.list.BlockAccessList;
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
import org.hyperledger.besu.metrics.noop.NoOpMetricsSystem;
import org.hyperledger.besu.plugin.services.trielogs.TrieLog;
import org.hyperledger.besu.plugin.services.worldstate.MutableWorldState;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
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

  /**
   * Builds the witness directly from a pre-resolved parent world state and trie log. The {@code
   * maybeOutputs} supplies the accessed-ancestor map (used to populate {@code headers}) and the
   * block access list (used by {@link #resolveAccounts} to determine touched slots).
   */
  public Witness build(
      final BlockHeader blockHeader,
      final TrieLog trieLog,
      final BonsaiWorldState parentWorldState,
      final Blockchain blockchain,
      final Optional<BlockProcessingOutputs> maybeOutputs) {
    final Map<Long, Hash> accessedAncestors =
        maybeOutputs.map(BlockProcessingOutputs::getAccessedAncestors).orElse(Map.of());
    final Optional<BlockAccessList> maybeBlockAccessList =
        maybeOutputs.flatMap(BlockProcessingOutputs::getBlockAccessList);
    final Map<Address, Set<StorageSlotKey>> touchedSlots =
        resolveAccounts(trieLog, maybeBlockAccessList);
    final List<String> state = buildTrieNodes(blockHeader, trieLog, parentWorldState, touchedSlots);
    final List<String> codes = buildCodes(parentWorldState, touchedSlots.keySet());
    final List<String> headers = buildHeaders(blockchain, accessedAncestors);
    return new Witness(state, codes, headers);
  }

  /**
   * Resolves the path-based archive, trie log, and parent world state for {@code blockHeader} and
   * builds the witness. Returns empty when the archive is not path-based, the trie log is absent,
   * the parent state is unavailable, or the build itself throws — callers handle the empty case as
   * they choose (engine API: omit witness from response; debug RPC: error response).
   */
  public Optional<Witness> buildWitness(
      final BlockHeader blockHeader,
      final BlockHeader parentHeader,
      final WorldStateArchive worldStateArchive,
      final Blockchain blockchain,
      final Optional<BlockProcessingOutputs> maybeOutputs) {
    // Witness building requires a path-based (Bonsai) archive; other archive types are unsupported.
    if (!(worldStateArchive instanceof PathBasedWorldStateProvider pathBased)) {
      return Optional.empty();
    }
    // Guard against missing trie log; without it the witness cannot be built
    final Optional<TrieLog> maybeTrieLog =
        pathBased.getTrieLogManager().getTrieLogLayer(blockHeader.getHash());
    if (maybeTrieLog.isEmpty()) {
      return Optional.empty();
    }
    // The parent world state is needed as the base for replaying the trie log and extracting codes.
    final Optional<MutableWorldState> maybeParent =
        pathBased.getWorldState(withBlockHeaderAndNoUpdateNodeHead(parentHeader));
    // Guard against unexpected absence of parent state; witness building cannot proceed without it.
    if (maybeParent.isEmpty() || !(maybeParent.get() instanceof BonsaiWorldState parent)) {
      return Optional.empty();
    }
    try (parent) {
      return Optional.of(build(blockHeader, maybeTrieLog.get(), parent, blockchain, maybeOutputs));
    } catch (final Exception e) {
      LOG.warn("failed to build execution witness for {}: {}", blockHeader.getHash(), e.toString());
      return Optional.empty();
    }
  }

  /**
   * Returns address → touched storage slots for witness construction.
   *
   * <p>When a {@link BlockAccessList} is present (Amsterdam+) it is preferred over the trie log.
   * There are known discrepancies between the two sources that cause ~4 000 zkevm reference-test
   * failures when relying on the trie log alone; the root cause is still under investigation. Using
   * the BAL passes 99.99% of those tests and allows users to start experimenting with
   * Besu-generated witnesses while the trie log issue is investigated in a follow-up PR.
   *
   * <p>TODO: investigate trie log discrepancies and remove the BAL dependency once resolved.
   */
  private Map<Address, Set<StorageSlotKey>> resolveAccounts(
      final TrieLog trieLog, final Optional<BlockAccessList> maybeBal) {
    if (maybeBal.isPresent()) {
      // BAL preferred over trie log — see Javadoc above for context.
      final Map<Address, Set<StorageSlotKey>> result = new LinkedHashMap<>();
      maybeBal
          .get()
          .accountChanges()
          .forEach(
              ac -> {
                final Set<StorageSlotKey> slots = new LinkedHashSet<>();
                ac.storageReads().forEach(sr -> slots.add(sr.slot()));
                ac.storageChanges().forEach(sc -> slots.add(sc.slot()));
                result.put(ac.address(), slots);
              });
      return result;
    }
    // Fallback for pre-Amsterdam forks where no BAL is available.
    final Map<Address, Set<StorageSlotKey>> result = new LinkedHashMap<>();
    trieLog
        .getAccountChanges()
        .forEach(
            (address, __) ->
                result.put(
                    address, new LinkedHashSet<>(trieLog.getStorageChanges(address).keySet())));
    return result;
  }

  /**
   * Collects the trie nodes required to prove the given {@code touchedSlots} set. A throw-away
   * {@link BonsaiWorldStateWitnessStorage} wraps the parent storage so that every trie-node read
   * issued during state access and the subsequent {@code rollForward} + {@code persist} is
   * intercepted and recorded. Returns the collected nodes as sorted hex strings.
   */
  private List<String> buildTrieNodes(
      final BlockHeader blockHeader,
      final TrieLog trieLog,
      final BonsaiWorldState worldView,
      final Map<Address, Set<StorageSlotKey>> touchedSlots) {

    final BonsaiWorldStateWitnessStorage witnessStorage =
        new BonsaiWorldStateWitnessStorage(
            new NoOpMetricsSystem(), worldView.getWorldStateStorage());
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

    touchedSlots.forEach(
        (address, slots) -> {
          updater.getAccount(address);
          slots.forEach(slot -> updater.getStorageValueByStorageSlotKey(address, slot));
        });

    updater.rollForward(trieLog);
    updater.commit();
    witnessWorldState.persist(blockHeader);

    return witnessStorage.getTrieNodes().stream().map(Bytes::toHexString).sorted().toList();
  }

  /**
   * Returns the RLP-encoded contract bytecodes for all {@code addresses} that have non-empty code
   * in the parent world state. Lookups run in parallel; results are deduplicated and sorted.
   */
  private List<String> buildCodes(final BonsaiWorldState worldView, final Set<Address> addresses) {
    final var resultSet = ConcurrentHashMap.<String>newKeySet();
    addresses.parallelStream()
        .forEach(
            address -> {
              final var account = worldView.get(address);
              if (account != null && !account.getCodeHash().equals(Hash.EMPTY)) {
                worldView
                    .getCode(address, account.getCodeHash())
                    .ifPresent(bytes -> resultSet.add(bytes.toHexString()));
              }
            });
    return resultSet.stream().sorted().toList();
  }

  /**
   * Returns RLP-encoded block headers for every ancestor whose hash was observed during block
   * execution. At minimum the parent header is always present; additional entries are added for any
   * ancestor resolved while serving {@code BLOCKHASH}. Headers are ordered ascending by block
   * number as required by EIP-8025.
   */
  private List<String> buildHeaders(
      final Blockchain blockchain, final Map<Long, Hash> accessedAncestors) {
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
