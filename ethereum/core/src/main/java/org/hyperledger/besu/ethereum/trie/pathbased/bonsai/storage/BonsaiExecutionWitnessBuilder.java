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
import org.hyperledger.besu.ethereum.mainnet.WitnessOperationTracer;
import org.hyperledger.besu.ethereum.core.BlockHeader;
import org.hyperledger.besu.ethereum.core.MutableWorldState;
import org.hyperledger.besu.ethereum.mainnet.block.access.list.BlockAccessList;
import org.hyperledger.besu.ethereum.rlp.RLP;
import org.hyperledger.besu.ethereum.trie.pathbased.bonsai.NoOpBonsaiCachedWorldStorageManager;
import org.hyperledger.besu.ethereum.trie.pathbased.bonsai.cache.CodeCache;
import org.hyperledger.besu.ethereum.trie.pathbased.bonsai.cache.NoopBonsaiCachedMerkleTrieLoader;
import org.hyperledger.besu.ethereum.trie.pathbased.bonsai.worldview.BonsaiWorldState;
import org.hyperledger.besu.ethereum.trie.pathbased.bonsai.worldview.BonsaiWorldStateUpdateAccumulator;
import org.hyperledger.besu.ethereum.trie.pathbased.common.provider.PathBasedWorldStateProvider;
import org.hyperledger.besu.ethereum.worldstate.WorldStateArchive;
import org.hyperledger.besu.ethereum.trie.pathbased.common.trielog.NoOpTrieLogManager;
import org.hyperledger.besu.ethereum.trie.pathbased.common.worldview.WorldStateConfig;
import org.hyperledger.besu.evm.internal.EvmConfiguration;
import org.hyperledger.besu.metrics.noop.NoOpMetricsSystem;
import org.hyperledger.besu.plugin.services.trielogs.TrieLog;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.apache.tuweni.bytes.Bytes;

/**
 * Builds the EIP-8025 execution witness (state trie nodes, contract codes, and ancestor headers)
 * for a single block from a Bonsai world state and trie log. Used by both {@code
 * debug_executionWitness} and reference-test tooling so that both paths emit identical output.
 */
public class BonsaiExecutionWitnessBuilder {

  public record Witness(List<String> state, List<String> codes, List<String> headers) {}

  private final PathBasedWorldStateProvider pathBased;
  private final Blockchain blockchain;

  public BonsaiExecutionWitnessBuilder(
      final WorldStateArchive worldStateArchive, final Blockchain blockchain) {
    if (!(worldStateArchive instanceof PathBasedWorldStateProvider p)) {
      throw new IllegalStateException("execution witness requires a PathBasedWorldStateProvider");
    }
    this.pathBased = p;
    this.blockchain = blockchain;
  }

  /**
   * Hybrid witness builder: uses {@link BlockProcessingOutputs} (TrieLog + BAL) for the {@code
   * state} and {@code headers} fields (same accuracy as the existing path), but uses the {@link
   * WitnessOperationTracer}'s code-address set for {@code codes}. This correctly includes system
   * contracts that were called during pre-execution (EIP-2935, EIP-7002, etc.) but whose codes the
   * TrieLog+BAL path was previously missing because {@link
   * org.hyperledger.besu.ethereum.mainnet.systemcall.SystemCallProcessor} used
   * {@code OperationTracer.NO_TRACING}.
   */
  public Witness buildWitness(
      final BlockHeader blockHeader,
      final Optional<BlockProcessingOutputs> maybeOutputs,
      final WitnessOperationTracer tracer) {
    final TrieLog trieLog =
        pathBased
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
        pathBased
            .getWorldState(withBlockHeaderAndNoUpdateNodeHead(parentHeader))
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "parent world state unavailable for " + parentHeader.getHash()));

    if (!(worldState instanceof BonsaiWorldState ws)) {
      throw new IllegalStateException("parent world state is not a BonsaiWorldState");
    }

    try (ws) {
      final Optional<BlockAccessList> maybeBlockAccessList =
          maybeOutputs.flatMap(BlockProcessingOutputs::getBlockAccessList);
      final Map<Address, Set<StorageSlotKey>> touchedSlots =
          resolveAccounts(trieLog, maybeBlockAccessList);
      final List<String> state = buildTrieNodes(blockHeader, trieLog, ws, touchedSlots);
      final List<String> codes = buildCodes(ws, tracer.getCodeAddresses());
      final List<String> headers = buildHeaders(blockchain, tracer.getAccessedAncestors());
      return new Witness(state, codes, headers);
    } catch (final IllegalStateException e) {
      throw e;
    } catch (final Exception e) {
      throw new IllegalStateException(
          "failed to build execution witness for " + blockHeader.getHash(), e);
    }
  }


  /**
   * Returns address → touched storage slots for witness construction.
   *
   * <p>Always starts from the trie log (captures all changed accounts, including gas payers not
   * present in the BAL). When a {@link BlockAccessList} is present (Amsterdam+) it is merged on
   * top to include read-only slots that are not recorded in the trie log.
   */
  private Map<Address, Set<StorageSlotKey>> resolveAccounts(
      final TrieLog trieLog, final Optional<BlockAccessList> maybeBal) {
    if (maybeBal.isPresent()) {
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
    // accessedAncestors
    long oldestAncestor = accessedAncestors.keySet().stream().min(Long::compare).orElseThrow();

    // for oldestAncestor until parent of blockHeader, get header for each accessed ancestor number and hash
    List<String> result = new ArrayList<>();
    for (long number = oldestAncestor; number < blockchain.getChainHeadBlockNumber(); number++) {
      BlockHeader header = blockchain.getBlockHeader(number).orElseThrow();
      result.add(RLP.encode(header::writeTo).toHexString());
    }
    return result;
  }
}
