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
package org.hyperledger.besu.ethereum.trie.pathbased.bonsai.storage;

import static org.hyperledger.besu.ethereum.worldstate.WorldStateQueryParams.withBlockHeaderAndNoUpdateNodeHead;

import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.ethereum.WitnessCodeReads;
import org.hyperledger.besu.ethereum.chain.Blockchain;
import org.hyperledger.besu.ethereum.core.BlockHeader;
import org.hyperledger.besu.ethereum.mainnet.block.access.list.BlockAccessList;
import org.hyperledger.besu.ethereum.rlp.RLP;
import org.hyperledger.besu.ethereum.trie.pathbased.bonsai.code.BonsaiCodeCache;
import org.hyperledger.besu.ethereum.trie.pathbased.bonsai.provider.PathBasedWorldStateProvider;
import org.hyperledger.besu.ethereum.trie.pathbased.bonsai.trielog.NoOpTrieLogManager;
import org.hyperledger.besu.ethereum.trie.pathbased.bonsai.worldview.BonsaiWorldState;
import org.hyperledger.besu.ethereum.trie.pathbased.bonsai.worldview.accumulator.BonsaiWorldStateUpdateAccumulator;
import org.hyperledger.besu.ethereum.trie.pathbased.bonsai.worldview.accumulator.preload.NoOpBonsaiCachedMerkleTrieLoader;
import org.hyperledger.besu.ethereum.trie.pathbased.bonsai.worldview.cache.NoOpBonsaiWorldStateCacheManager;
import org.hyperledger.besu.ethereum.worldstate.WorldStateArchive;
import org.hyperledger.besu.evm.internal.EvmConfiguration;
import org.hyperledger.besu.metrics.noop.NoOpMetricsSystem;
import org.hyperledger.besu.plugin.services.trielogs.TrieLog;
import org.hyperledger.besu.plugin.services.worldstate.MutableWorldState;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.common.annotations.VisibleForTesting;
import org.apache.tuweni.bytes.Bytes;

/**
 * Builds the EIP-8025 execution witness (state trie nodes, contract codes, and ancestor headers)
 * for a single block from a Bonsai world state and trie log.
 */
public class BonsaiExecutionWitnessBuilder {

  public record Witness(List<String> state, List<String> codes, List<String> headers) {}

  private final PathBasedWorldStateProvider worldStateProvider;
  private final Blockchain blockchain;

  public BonsaiExecutionWitnessBuilder(
      final WorldStateArchive worldStateArchive, final Blockchain blockchain) {
    if (!(worldStateArchive instanceof PathBasedWorldStateProvider pathBasedWorldStateProvider)) {
      throw new IllegalStateException("execution witness requires a PathBasedWorldStateProvider");
    }
    this.worldStateProvider = pathBasedWorldStateProvider;
    this.blockchain = blockchain;
  }

  /**
   * Builds the EIP-8025 execution witness (state trie nodes, codes, headers) for a block. Uses the
   * TrieLog + BAL for {@code state}, the {@link WitnessCodeReads}'s accumulated code-read sets for
   * {@code codes}, and the oldest accessed ancestor in {@code accessedAncestors} for {@code
   * headers}.
   */
  public Witness buildWitness(
      final BlockHeader blockHeader,
      final BlockAccessList blockAccessList,
      final Map<Long, Hash> accessedAncestors,
      final WitnessCodeReads witnessCodeReads) {

    final TrieLog trieLog =
        worldStateProvider
            .getTrieLogManager()
            .getTrieLogLayer(blockHeader.getHash())
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "trie log missing for block " + blockHeader.getHash()));

    final BlockHeader parentHeader = headerByHash(blockHeader.getParentHash());

    try (final MutableWorldState worldState =
        worldStateProvider
            .getWorldState(withBlockHeaderAndNoUpdateNodeHead(parentHeader))
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "parent world state unavailable for " + parentHeader.getHash()))) {

      if (!(worldState instanceof BonsaiWorldState ws)) {
        throw new IllegalStateException("parent world state is not a BonsaiWorldState");
      }
      final List<String> state = buildTrieNodes(blockHeader, trieLog, ws, blockAccessList);
      final List<String> codes = buildCodes(ws, witnessCodeReads.codeReads());
      final long oldestAncestor =
          accessedAncestors.keySet().stream()
              .min(Long::compare)
              .orElse(blockHeader.getNumber() - 1);
      final List<String> headers = buildHeaders(oldestAncestor, blockHeader);
      return new Witness(state, codes, headers);
    } catch (final IllegalStateException e) {
      throw e;
    } catch (final Exception e) {
      throw new IllegalStateException(
          "failed to build execution witness for " + blockHeader.getHash(), e);
    }
  }

  /**
   * Collects the trie nodes required to re-execute the block. A throw-away {@link
   * BonsaiWorldStateWitnessStorage} intercepts every trie-node read issued during account/slot
   * access and the subsequent {@code rollForward} + {@code persist}. Returns nodes as sorted hex
   * strings.
   */
  private List<String> buildTrieNodes(
      final BlockHeader blockHeader,
      final TrieLog trieLog,
      final BonsaiWorldState worldView,
      final BlockAccessList blockAccessList) {

    final BonsaiWorldStateWitnessStorage witnessStorage =
        new BonsaiWorldStateWitnessStorage(
            new NoOpMetricsSystem(), worldView.getWorldStateStorage());
    final BonsaiCodeCache codeCache = new BonsaiCodeCache();
    try (final BonsaiWorldState witnessWorldState =
        new BonsaiWorldState(
            witnessStorage,
            new NoOpBonsaiCachedMerkleTrieLoader(),
            new NoOpBonsaiWorldStateCacheManager(
                witnessStorage, EvmConfiguration.DEFAULT, codeCache),
            new NoOpTrieLogManager(),
            EvmConfiguration.DEFAULT,
            worldStateProvider.getWorldStateSharedSpec(),
            codeCache)) {

      final BonsaiWorldStateUpdateAccumulator updater =
          (BonsaiWorldStateUpdateAccumulator) witnessWorldState.updater();

      blockAccessList
          .accountChanges()
          .forEach(
              ac -> {
                updater.getAccount(ac.address());
                ac.storageReads()
                    .forEach(
                        sr -> updater.getStorageValueByStorageSlotKey(ac.address(), sr.slot()));
                ac.storageChanges()
                    .forEach(
                        sc -> updater.getStorageValueByStorageSlotKey(ac.address(), sc.slot()));
              });

      updater.rollForward(trieLog);
      updater.commit();
      witnessWorldState.persist(blockHeader);

      return witnessStorage.getTrieNodes().stream().map(Bytes::toHexString).sorted().toList();
    }
  }

  /**
   * Returns the pre-state contract bytecodes required by a stateless verifier, deduplicated and
   * sorted, implementing the EIP-8025 {@code get_witness_codes} rule. Empty code is never included.
   *
   * <p>Needs no filtering of its own: as in EELS {@code get_code}, block processing only records a
   * read that code written earlier in the block did not already satisfy (see {@code
   * AccessLocationTracker}), and every recorded read is of code the account held before the block.
   */
  @VisibleForTesting
  List<String> buildCodes(final BonsaiWorldState worldView, final Set<Address> addresses) {
    final Set<String> resultSet = new HashSet<>();
    addresses.forEach(
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
   * Returns RLP-encoded headers for every block from {@code oldestAncestor} up to (but not
   * including) {@code blockNumber} — that is, ending at the parent of the block the witness is
   * being built for, which is not necessarily the chain head. Ordered ascending by block number as
   * required by EIP-8025.
   */
  @VisibleForTesting
  List<String> buildHeaders(final long oldestAncestor, final BlockHeader blockHeader) {
    // The number bounds the walk, the parent hash resolves it: getBlockHeader(long) is
    // canonical-by-height, the wrong ancestry for a block on a fork.
    final Deque<String> result = new ArrayDeque<>();
    Hash hash = blockHeader.getParentHash();
    final long lowerBound = Math.max(0L, oldestAncestor);
    for (long number = blockHeader.getNumber() - 1; number >= lowerBound; number--) {
      final BlockHeader ancestor = headerByHash(hash);
      result.addFirst(RLP.encode(ancestor::writeTo).toHexString()); // addFirst: EIP-8025 ascending
      hash = ancestor.getParentHash();
    }
    return List.copyOf(result);
  }

  private BlockHeader headerByHash(final Hash hash) {
    return blockchain
        .getBlockHeader(hash)
        .orElseThrow(() -> new IllegalStateException("header not found: " + hash));
  }
}
