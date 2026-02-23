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
import org.hyperledger.besu.ethereum.eth.manager.EthPeerImmutableAttributes;
import org.hyperledger.besu.ethereum.eth.manager.EthPeers;
import org.hyperledger.besu.ethereum.eth.messages.EthProtocolMessages;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Manages one {@link PeerCluster} per clustered ETH message code. Registers connect/disconnect
 * hooks on {@link EthPeers} to keep clusters up to date as peers come and go.
 */
public class PeerClusterManager {

  /** The ETH message codes that are cluster-managed. */
  static final List<Integer> CLUSTERED_MESSAGE_CODES =
      List.of(
          EthProtocolMessages.GET_BLOCK_HEADERS,
          EthProtocolMessages.GET_BLOCK_BODIES,
          EthProtocolMessages.GET_RECEIPTS,
          EthProtocolMessages.GET_NODE_DATA,
          EthProtocolMessages.GET_POOLED_TRANSACTIONS);

  /**
   * Each peer is eligible for the main slot in at most this many clusters. The remaining clusters
   * track the peer only as a reserve candidate. With 5 clusters, 6 main slots each, and a limit of
   * 3, at least 10 distinct peers are needed to fill all main slots — spreading load across the
   * peer set.
   */
  static final int MAX_MAIN_CLUSTERS_PER_PEER = 3;

  private final Map<Integer, PeerCluster> clusters;
  // Sorted by message code for stable stagger-offset calculations
  private final List<PeerCluster> clusterList;
  private final EthPeers ethPeers;

  public PeerClusterManager(final EthPeers ethPeers) {
    this.ethPeers = ethPeers;
    this.clusters =
        CLUSTERED_MESSAGE_CODES.stream().collect(Collectors.toMap(code -> code, PeerCluster::new));
    this.clusterList =
        clusters.values().stream()
            .sorted(Comparator.comparingInt(PeerCluster::getMessageCode))
            .collect(Collectors.toList());

    ethPeers.subscribeConnect(this::onPeerConnected);
    ethPeers.subscribeDisconnect(this::onPeerDisconnected);
  }

  /**
   * Selects peers for dispatching a request with the given message code ({@value
   * PeerCluster#MAIN_PEERS_PER_DISPATCH} main + {@value PeerCluster#RESERVE_PEERS_PER_DISPATCH}
   * reserve).
   *
   * @param messageCode the ETH request message code
   * @return list of peers to dispatch to (may be empty if no cluster exists)
   */
  public List<EthPeer> selectPeersForDispatch(final int messageCode) {
    PeerCluster cluster = clusters.get(messageCode);
    if (cluster == null) {
      return List.of();
    }
    List<EthPeer> externalReserve = buildExternalReserve(cluster);
    return cluster.selectForDispatch(externalReserve);
  }

  /**
   * Records a peer response into the appropriate cluster.
   *
   * @param messageCode the ETH request message code
   * @param peer the peer that responded
   * @param latencyMs observed latency in milliseconds
   * @param bytes response size in bytes
   * @param valid whether the response was valid
   */
  public void recordResponse(
      final int messageCode,
      final EthPeer peer,
      final long latencyMs,
      final int bytes,
      final boolean valid) {
    PeerCluster cluster = clusters.get(messageCode);
    if (cluster != null) {
      cluster.recordResponse(peer, latencyMs, bytes, valid);
    }
  }

  private List<EthPeer> buildExternalReserve(final PeerCluster cluster) {
    Set<EthPeer> mainSet = new HashSet<>(cluster.getMainPeers());
    return ethPeers
        .streamAvailablePeers()
        .filter(EthPeerImmutableAttributes::isFullyValidated)
        .map(EthPeerImmutableAttributes::ethPeer)
        .filter(p -> !mainSet.contains(p))
        .collect(Collectors.toList());
  }

  private void onPeerConnected(final EthPeer peer) {
    // Stagger main-slot eligibility: each peer is a main candidate in at most
    // MAX_MAIN_CLUSTERS_PER_PEER clusters (chosen by a hash of the peer's stable node ID).
    // All other clusters still track the peer for use as an external reserve.

    int clusterCount = clusterList.size();

    int offset = Math.floorMod(peer.getId().hashCode(), clusterCount);

    for (int i = 0; i < clusterCount; i++) {
      PeerCluster cluster = clusterList.get((offset + i) % clusterCount);
      if (i < MAX_MAIN_CLUSTERS_PER_PEER) {
        cluster.addCandidate(peer);
      } else {
        cluster.addTrackedOnly(peer);
      }
    }
  }

  private void onPeerDisconnected(final EthPeer peer) {
    clusters.values().forEach(c -> c.removePeer(peer));
  }
}
