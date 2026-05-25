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
import org.hyperledger.besu.datatypes.StorageSlotKey;
import org.hyperledger.besu.ethereum.WitnessHint;
import org.hyperledger.besu.ethereum.chain.Blockchain;
import org.hyperledger.besu.ethereum.core.BlockHeader;
import org.hyperledger.besu.ethereum.rlp.RLP;
import org.hyperledger.besu.ethereum.trie.NodeLoader;
import org.hyperledger.besu.ethereum.trie.pathbased.bonsai.storage.BonsaiWorldStateKeyValueStorage;
import org.hyperledger.besu.ethereum.trie.pathbased.bonsai.worldview.BonsaiWorldState;
import org.hyperledger.besu.ethereum.trie.pathbased.common.provider.PathBasedWorldStateProvider;
import org.hyperledger.besu.ethereum.trie.patricia.StoredMerklePatriciaTrie;
import org.hyperledger.besu.ethereum.worldstate.WorldStateArchive;
import org.hyperledger.besu.plugin.services.trielogs.TrieLog;
import org.hyperledger.besu.plugin.services.worldstate.MutableWorldState;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Builds the EIP-8025 execution witness (state trie nodes, contract codes, and ancestor headers)
 * for a single block from a Bonsai world state. Supports two input sources:
 *
 * <ul>
 *   <li>{@link #buildFromHint} — uses the in-memory {@link WitnessHint} captured during block
 *       processing (no disk I/O for witness data).
 *   <li>{@link #tryBuildForBlock} — falls back to reading the trie log from disk, used by {@code
 *       debug_executionWitness} on already-executed blocks.
 * </ul>
 */
public class BonsaiExecutionWitnessBuilder {

  private static final Logger LOG = LoggerFactory.getLogger(BonsaiExecutionWitnessBuilder.class);

  public record Witness(List<String> state, List<String> codes, List<String> headers) {}

  /**
   * Builds the witness using the in-memory {@link WitnessHint} produced during block execution.
   * Avoids reading the trie log from disk.
   */
  public Optional<Witness> buildFromHint(
      final BlockHeader blockHeader,
      final WitnessHint hint,
      final BlockHeader parentHeader,
      final WorldStateArchive worldStateArchive,
      final Blockchain blockchain,
      final long oldestAccessedAncestor) {
    if (!(worldStateArchive instanceof PathBasedWorldStateProvider pathBased)) {
      return Optional.empty();
    }
    final Optional<MutableWorldState> maybeParent =
        pathBased.getWorldState(withBlockHeaderAndNoUpdateNodeHead(parentHeader));
    if (maybeParent.isEmpty() || !(maybeParent.get() instanceof BonsaiWorldState parent)) {
      return Optional.empty();
    }
    try (parent) {
      final List<String> state =
          buildTrieNodes(hint.priorAccounts(), hint.touchedSlots(), parent);
      final List<String> codes = buildCodes(hint.priorAccounts(), parent);
      final List<String> headers = buildHeaders(blockchain, blockHeader, oldestAccessedAncestor);
      return Optional.of(new Witness(state, codes, headers));
    } catch (final Exception e) {
      LOG.warn("failed to build execution witness for {}: {}", blockHeader.getHash(), e.toString());
      return Optional.empty();
    }
  }

  /**
   * Builds the witness by reading the trie log from disk. Used by {@code debug_executionWitness}
   * on blocks that were already executed (no in-memory hint available).
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

  /**
   * Builds the witness from an explicit trie log and parent world state. Used internally by {@link
   * #tryBuildForBlock} and available for reference-test tooling.
   */
  public Witness build(
      final BlockHeader blockHeader,
      final TrieLog trieLog,
      final BonsaiWorldState parentWorldState,
      final Blockchain blockchain,
      final long oldestAccessedAncestor) {
    final Map<Address, AccountValue> priorAccounts = new HashMap<>();
    trieLog.getAccountChanges().forEach((addr, val) -> priorAccounts.put(addr, val.getPrior()));
    final Map<Address, Set<StorageSlotKey>> touchedSlots = new HashMap<>();
    trieLog
        .getAccountChanges()
        .keySet()
        .forEach(
            addr -> touchedSlots.put(addr, new HashSet<>(trieLog.getStorageChanges(addr).keySet())));

    final List<String> state = buildTrieNodes(priorAccounts, touchedSlots, parentWorldState);
    final List<String> codes = buildCodes(priorAccounts, parentWorldState);
    final List<String> headers = buildHeaders(blockchain, blockHeader, oldestAccessedAncestor);
    return new Witness(state, codes, headers);
  }

  /**
   * Traverses Merkle proof paths in the parent trie for every account and storage slot in the
   * supplied maps, collecting every node on each path. Single-pass — no ephemeral world state, no
   * rollForward, and no persist() call.
   */
  private List<String> buildTrieNodes(
      final Map<Address, AccountValue> priorAccounts,
      final Map<Address, Set<StorageSlotKey>> touchedSlots,
      final BonsaiWorldState parentWorldState) {

    final BonsaiWorldStateKeyValueStorage storage = parentWorldState.getWorldStateStorage();
    final Set<Bytes> witnessNodes = ConcurrentHashMap.newKeySet();

    final NodeLoader accountNodeLoader =
        (location, hash) -> {
          final Optional<Bytes> node = storage.getAccountStateTrieNode(location, hash);
          node.ifPresent(witnessNodes::add);
          return node;
        };

    final StoredMerklePatriciaTrie<Bytes, Bytes> accountTrie =
        new StoredMerklePatriciaTrie<>(
            accountNodeLoader,
            Bytes32.wrap(parentWorldState.getWorldStateRootHash().getBytes()),
            Function.identity(),
            Function.identity());

    priorAccounts.forEach(
        (address, priorAccount) -> {
          final Hash addressHash = Hash.hash(address.getBytes());

          // Traverse the account proof path — every node read is captured into witnessNodes
          accountTrie.get(addressHash.getBytes());

          // Traverse storage proof paths for all slots touched by this account
          if (priorAccount == null
              || Hash.EMPTY_TRIE_HASH.equals(priorAccount.getStorageRoot())) {
            return;
          }
          final Hash storageRoot = priorAccount.getStorageRoot();

          final NodeLoader storageNodeLoader =
              (location, hash) -> {
                final Optional<Bytes> node =
                    storage.getAccountStorageTrieNode(addressHash, location, hash);
                node.ifPresent(witnessNodes::add);
                return node;
              };

          final StoredMerklePatriciaTrie<Bytes, Bytes> storageTrie =
              new StoredMerklePatriciaTrie<>(
                  storageNodeLoader,
                  Bytes32.wrap(storageRoot.getBytes()),
                  Function.identity(),
                  Function.identity());

          final Set<StorageSlotKey> slots = touchedSlots.getOrDefault(address, Set.of());
          slots.forEach(slotKey -> storageTrie.get(slotKey.getSlotHash().getBytes()));
        });

    return witnessNodes.stream().map(Bytes::toHexString).sorted().toList();
  }

  private List<String> buildCodes(
      final Map<Address, AccountValue> priorAccounts, final BonsaiWorldState worldView) {
    final var resultSet = ConcurrentHashMap.<String>newKeySet();
    priorAccounts.entrySet().parallelStream()
        .filter(
            entry ->
                entry.getValue() != null
                    && !entry.getValue().getCodeHash().equals(Hash.EMPTY))
        .forEach(
            entry ->
                worldView
                    .getCode(entry.getKey(), entry.getValue().getCodeHash())
                    .ifPresent(bytes -> resultSet.add(bytes.toHexString())));
    return resultSet.stream().sorted().toList();
  }

  private List<String> buildHeaders(
      final Blockchain blockchain,
      final BlockHeader blockHeader,
      final long oldestAccessedAncestor) {
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
