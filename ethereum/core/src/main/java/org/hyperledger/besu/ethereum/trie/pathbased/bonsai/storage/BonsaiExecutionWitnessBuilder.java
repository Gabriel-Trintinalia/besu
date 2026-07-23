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
import org.hyperledger.besu.ethereum.chain.Blockchain;
import org.hyperledger.besu.ethereum.core.BlockHeader;
import org.hyperledger.besu.plugin.services.worldstate.MutableWorldState;
import org.hyperledger.besu.ethereum.mainnet.WitnessOperationTracer;
import org.hyperledger.besu.ethereum.mainnet.block.access.list.BlockAccessList;
import org.hyperledger.besu.ethereum.rlp.RLP;
import org.hyperledger.besu.ethereum.trie.pathbased.bonsai.worldview.BonsaiWorldState;
import org.hyperledger.besu.ethereum.trie.pathbased.bonsai.worldview.accumulator.BonsaiWorldStateUpdateAccumulator;
import org.hyperledger.besu.ethereum.trie.pathbased.bonsai.worldview.cache.NoOpBonsaiWorldStateCacheManager;
import org.hyperledger.besu.ethereum.trie.pathbased.common.code.PathBasedCodeCache;
import org.hyperledger.besu.ethereum.trie.pathbased.common.provider.PathBasedWorldStateProvider;
import org.hyperledger.besu.ethereum.trie.pathbased.common.trielog.NoOpTrieLogManager;
import org.hyperledger.besu.ethereum.trie.pathbased.common.worldview.WorldStateConfig;
import org.hyperledger.besu.ethereum.worldstate.WorldStateArchive;
import org.hyperledger.besu.evm.internal.EvmConfiguration;
import org.hyperledger.besu.metrics.noop.NoOpMetricsSystem;
import org.hyperledger.besu.plugin.services.trielogs.TrieLog;
import org.hyperledger.besu.ethereum.trie.pathbased.bonsai.worldview.accumulator.preload.NoOpBonsaiCachedMerkleTrieLoader;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.tuweni.bytes.Bytes;

/**
 * Builds the EIP-8025 execution witness (state trie nodes, contract codes, and ancestor headers)
 * for a single block from a Bonsai world state and trie log. Used by both {@code
 * debug_executionWitness} and reference-test tooling so that both paths emit identical output.
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
   * Builds the EIP-8025 execution witness for {@code blockHeader}. Uses the TrieLog and BAL from
   * block processing for the {@code state} field, and the {@link WitnessOperationTracer}'s
   * code-address set for the {@code codes} field (which correctly includes system-contract codes
   * that a TrieLog-only approach would miss).
   */
  public Witness buildWitness(
      final BlockHeader blockHeader,
      final Optional<BlockAccessList> maybeBlockAccessList,
      final WitnessOperationTracer tracer) {
    final TrieLog trieLog =
        worldStateProvider
            .getTrieLogManager()
            .getTrieLogLayer(blockHeader.getHash())
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "trie log missing for block " + blockHeader.getHash()));

    final BlockHeader parentHeader =
        blockchain
            .getBlockHeader(blockHeader.getParentHash())
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "Parent header not found: " + blockHeader.getParentHash()));

    final MutableWorldState worldState =
        worldStateProvider
            .getWorldState(withBlockHeaderAndNoUpdateNodeHead(parentHeader))
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "parent world state unavailable for " + parentHeader.getHash()));

    if (!(worldState instanceof BonsaiWorldState ws)) {
      throw new IllegalStateException("parent world state is not a BonsaiWorldState");
    }

    try (worldState) {
      final List<String> state = buildTrieNodes(blockHeader, trieLog, ws, maybeBlockAccessList);
      final List<String> codes = buildCodes(ws, tracer.getCodeAddresses());
      final List<String> headers =
          buildHeaders(blockchain, tracer.getOldestAccessedAncestor(), blockHeader.getNumber());
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
   * access and the subsequent {@code rollForward} + {@code persist}.
   */
  private List<String> buildTrieNodes(
      final BlockHeader blockHeader,
      final TrieLog trieLog,
      final BonsaiWorldState worldView,
      final Optional<BlockAccessList> maybeBal) {

    final PathBasedCodeCache codeCache = new PathBasedCodeCache();
    final BonsaiWorldStateWitnessStorage witnessStorage =
        new BonsaiWorldStateWitnessStorage(
            new NoOpMetricsSystem(), worldView.getWorldStateStorage());
    final BonsaiWorldState witnessWorldState =
        new BonsaiWorldState(
            witnessStorage,
            new NoOpBonsaiCachedMerkleTrieLoader(),
            new NoOpBonsaiWorldStateCacheManager(
                witnessStorage, EvmConfiguration.DEFAULT, codeCache),
            new NoOpTrieLogManager(),
            EvmConfiguration.DEFAULT,
            WorldStateConfig.newBuilder().build(),
            codeCache);

    final BonsaiWorldStateUpdateAccumulator updater =
        (BonsaiWorldStateUpdateAccumulator) witnessWorldState.updater();

    // Prefer BAL when present and fall back to TrieLog alone for pre-Amsterdam blocks.
    if (maybeBal.isPresent()) {
      maybeBal
          .get()
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
    } else {
      trieLog
          .getAccountChanges()
          .forEach(
              (address, __) -> {
                updater.getAccount(address);
                trieLog
                    .getStorageChanges(address)
                    .keySet()
                    .forEach(slot -> updater.getStorageValueByStorageSlotKey(address, slot));
              });
    }

    updater.rollForward(trieLog);
    updater.commit();
    witnessWorldState.persist(blockHeader);

    return witnessStorage.getTrieNodes().stream().map(Bytes::toHexString).sorted().toList();
  }

  /**
   * Returns the RLP-encoded contract bytecodes for all {@code addresses} that have non-empty code
   * in the parent world state.
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
   * Returns RLP-encoded headers for every block from {@code oldestAncestor} up to (but not
   * including) the chain head, ordered ascending by block number as required by EIP-8025.
   */
  private List<String> buildHeaders(
      final Blockchain blockchain, final long oldestAncestor, final long blockNumber) {
    final List<String> result = new ArrayList<>();
    for (long number = oldestAncestor; number < blockNumber; number++) {
      result.add(
          RLP.encode(blockchain.getBlockHeader(number).orElseThrow()::writeTo).toHexString());
    }
    return result;
  }
}
