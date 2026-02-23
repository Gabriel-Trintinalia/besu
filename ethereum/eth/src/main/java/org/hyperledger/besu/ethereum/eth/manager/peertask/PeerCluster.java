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
package org.hyperledger.besu.ethereum.eth.manager.peertask;

import org.hyperledger.besu.ethereum.eth.manager.EthPeer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;

/**
 * Holds up to {@value #MAX_MAIN_PEERS} main {@link ClusteredPeer}s for a specific ETH message type.
 * Tracks all known peers and performs immediate swaps when a reserve peer outscores the weakest
 * main peer.
 */
class PeerCluster {

  /** Maximum number of peers held in the main slot for this cluster. */
  static final int MAX_MAIN_PEERS = 6;

  /** Number of main peers selected per dispatch (kept below MAX_MAIN_PEERS for resilience). */
  static final int MAIN_PEERS_PER_DISPATCH = 4;

  /** Number of external reserve peers included per dispatch. */
  static final int RESERVE_PEERS_PER_DISPATCH = 2;

  private final int messageCode;
  // Guarded by lock
  private final List<ClusteredPeer> mainPeers = new ArrayList<>(MAX_MAIN_PEERS);
  private final Map<EthPeer, ClusteredPeer> trackedPeers = new HashMap<>();
  private final ReadWriteLock lock = new ReentrantReadWriteLock();

  PeerCluster(final int messageCode) {
    this.messageCode = messageCode;
  }

  int getMessageCode() {
    return messageCode;
  }

  /**
   * Selects peers for dispatch: up to {@value #MAIN_PEERS_PER_DISPATCH} random main peers + up to
   * {@value #RESERVE_PEERS_PER_DISPATCH} random peers from {@code externalReserve}.
   *
   * @param externalReserve connected peers NOT already in this cluster's main slots
   * @return list of selected peers (may be fewer than the target if not enough are available)
   */
  List<EthPeer> selectForDispatch(final List<EthPeer> externalReserve) {
    lock.readLock().lock();
    try {
      List<EthPeer> selected = new ArrayList<>();

      // Pick MAIN_PEERS_PER_DISPATCH of the available main peers randomly
      if (!mainPeers.isEmpty()) {
        List<ClusteredPeer> shuffledMain = new ArrayList<>(mainPeers);
        Collections.shuffle(shuffledMain);
        int take = Math.min(MAIN_PEERS_PER_DISPATCH, shuffledMain.size());
        for (int i = 0; i < take; i++) {
          selected.add(shuffledMain.get(i).getEthPeer());
        }
      }

      // Pick RESERVE_PEERS_PER_DISPATCH random peers from externalReserve
      if (!externalReserve.isEmpty()) {
        List<EthPeer> shuffledReserve = new ArrayList<>(externalReserve);
        Collections.shuffle(shuffledReserve);
        int take = Math.min(RESERVE_PEERS_PER_DISPATCH, shuffledReserve.size());
        for (int i = 0; i < take; i++) {
          selected.add(shuffledReserve.get(i));
        }
      }

      return Collections.unmodifiableList(selected);
    } finally {
      lock.readLock().unlock();
    }
  }

  /**
   * Records a response from a peer, updates its metrics, and swaps it into the main slot if it
   * outscores the weakest current main peer.
   *
   * @param peer the peer that responded
   * @param latencyMs the observed latency in milliseconds
   * @param bytes the size of the response in bytes
   * @param valid whether the response was valid
   */
  void recordResponse(
      final EthPeer peer, final long latencyMs, final int bytes, final boolean valid) {
    lock.writeLock().lock();
    try {
      ClusteredPeer clusteredPeer = trackedPeers.computeIfAbsent(peer, ClusteredPeer::new);
      clusteredPeer.updateMetrics(latencyMs, bytes, valid);

      boolean isMainPeer = mainPeers.stream().anyMatch(cp -> cp.getEthPeer().equals(peer));
      if (!isMainPeer && mainPeers.size() == MAX_MAIN_PEERS) {
        // Find the weakest main peer and compare scores
        Optional<ClusteredPeer> weakest =
            mainPeers.stream().min(Comparator.comparingDouble(ClusteredPeer::getCompositeScore));
        if (clusteredPeer.getCompositeScore() > weakest.get().getCompositeScore()) {
          mainPeers.remove(weakest.get());
          mainPeers.add(clusteredPeer);
        }
      }
    } finally {
      lock.writeLock().unlock();
    }
  }

  /**
   * Adds a peer as a candidate. If the main slot has fewer than {@value #MAX_MAIN_PEERS} peers,
   * adds it there; otherwise just tracks it.
   *
   * @param peer the peer to add
   */
  void addCandidate(final EthPeer peer) {
    lock.writeLock().lock();
    try {
      if (trackedPeers.containsKey(peer)) {
        return;
      }
      ClusteredPeer clusteredPeer = new ClusteredPeer(peer);
      trackedPeers.put(peer, clusteredPeer);
      if (mainPeers.size() < MAX_MAIN_PEERS) {
        mainPeers.add(clusteredPeer);
      }
    } finally {
      lock.writeLock().unlock();
    }
  }

  /**
   * Tracks a peer for reserve use without adding it to the main slot. Used by {@link
   * PeerClusterManager} when this cluster is not one of the peer's assigned main clusters.
   *
   * @param peer the peer to track
   */
  void addTrackedOnly(final EthPeer peer) {
    lock.writeLock().lock();
    try {
      trackedPeers.putIfAbsent(peer, new ClusteredPeer(peer));
    } finally {
      lock.writeLock().unlock();
    }
  }

  /**
   * Removes a peer from the main slot and from tracking.
   *
   * @param peer the peer to remove
   */
  void removePeer(final EthPeer peer) {
    lock.writeLock().lock();
    try {
      trackedPeers.remove(peer);
      mainPeers.removeIf(cp -> cp.getEthPeer().equals(peer));
    } finally {
      lock.writeLock().unlock();
    }
  }

  /**
   * Returns a snapshot of the current main peers.
   *
   * @return list of main EthPeers
   */
  List<EthPeer> getMainPeers() {
    lock.readLock().lock();
    try {
      return mainPeers.stream().map(ClusteredPeer::getEthPeer).collect(Collectors.toList());
    } finally {
      lock.readLock().unlock();
    }
  }
}
