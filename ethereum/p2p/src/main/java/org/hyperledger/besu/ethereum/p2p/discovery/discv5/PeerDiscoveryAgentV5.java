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
package org.hyperledger.besu.ethereum.p2p.discovery.discv5;

import org.hyperledger.besu.ethereum.forkid.ForkIdManager;
import org.hyperledger.besu.ethereum.p2p.config.DiscoveryConfiguration;
import org.hyperledger.besu.ethereum.p2p.config.NetworkingConfiguration;
import org.hyperledger.besu.ethereum.p2p.discovery.DiscoveryPeer;
import org.hyperledger.besu.ethereum.p2p.discovery.DiscoveryPeerFactory;
import org.hyperledger.besu.ethereum.p2p.discovery.NodeRecordManager;
import org.hyperledger.besu.ethereum.p2p.discovery.PeerDiscoveryAgent;
import org.hyperledger.besu.ethereum.p2p.peers.Peer;
import org.hyperledger.besu.ethereum.p2p.peers.PeerId;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

import org.ethereum.beacon.discovery.MutableDiscoverySystem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * DiscV5 implementation of {@link PeerDiscoveryAgent} backed by a mutable Ethereum Discovery v5
 * {@link MutableDiscoverySystem}.
 */
public final class PeerDiscoveryAgentV5 implements PeerDiscoveryAgent {
  private static final Logger LOG = LoggerFactory.getLogger(PeerDiscoveryAgentV5.class);

  private final MutableDiscoverySystem discoverySystem;
  private final ForkIdManager forkIdManager;

  private boolean stopped = false;

  private final DiscoveryConfiguration config;
  private final NodeRecordManager nodeRecordManager;

  /**
   * Creates a new DiscV5 peer discovery agent.
   *
   * @param discoverySystem the underlying mutable DiscV5 discovery system
   * @param config the networking configuration
   * @param forkIdManager manager used to validate peer fork compatibility
   * @param nodeRecordManager manager responsible for local node record updates
   */
  public PeerDiscoveryAgentV5(
      final MutableDiscoverySystem discoverySystem,
      final NetworkingConfiguration config,
      final ForkIdManager forkIdManager,
      final NodeRecordManager nodeRecordManager) {
    this.discoverySystem = discoverySystem;
    this.forkIdManager = forkIdManager;
    this.config = config.getDiscovery();
    this.nodeRecordManager = nodeRecordManager;
  }

  @Override
  public CompletableFuture<Integer> start(final int tcpPort) {
    if (!isEnabled()) {
      return CompletableFuture.completedFuture(0);
    }
    LOG.info("Starting DiscV5 Peer Discovery Agent on TCP port {}", tcpPort);
    return discoverySystem.start().thenApply(v -> tcpPort);
  }

  /**
   * Stops the DiscV5 discovery system.
   *
   * @return a completed future once the discovery system has been stopped
   */
  @Override
  public CompletableFuture<?> stop() {
    LOG.info("Stopping DiscV5 Peer Discovery Agent");
    discoverySystem.stop();
    stopped = true;
    return CompletableFuture.completedFuture(null);
  }

  /**
   * Updates the local node record if discovery is disabled.
   *
   * <p>When DiscV5 discovery is enabled, node record updates are handled internally by the
   * discovery system.
   */
  @Override
  public void updateNodeRecord() {
    if (isEnabled()) {
      return;
    }
    nodeRecordManager.updateNodeRecord();
  }

  /**
   * Checks whether a discovered peer is compatible with the local fork ID.
   *
   * @param peer the discovered peer to validate
   * @return {@code true} if the peer is compatible or does not advertise a fork ID
   */
  @Override
  public boolean checkForkId(final DiscoveryPeer peer) {
    return peer.getForkId().map(forkIdManager::peerCheck).orElse(true);
  }

  /**
   * Streams peers discovered by the DiscV5 discovery system.
   *
   * @return a stream of discovered peers
   */
  @Override
  public Stream<DiscoveryPeer> streamDiscoveredPeers() {
    return discoverySystem.streamLiveNodes().map(DiscoveryPeerFactory::fromNodeRecord);
  }

  /**
   * Removes a peer from the discovery system.
   *
   * @param peerId the identifier of the peer to drop
   */
  @Override
  public void dropPeer(final PeerId peerId) {
    discoverySystem.deleteNodeRecord(peerId.getId());
  }

  /**
   * Indicates whether peer discovery is enabled via configuration.
   *
   * @return {@code true} if discovery is enabled
   */
  @Override
  public boolean isEnabled() {
    return config.isEnabled();
  }

  /**
   * Indicates whether the discovery agent has been stopped.
   *
   * @return {@code true} if the agent has been stopped
   */
  @Override
  public boolean isStopped() {
    return stopped;
  }

  /**
   * Adds a peer to the discovery system.
   *
   * @param peer the peer to add
   */
  @Override
  public void addPeer(final Peer peer) {
    peer.getNodeRecord().ifPresent(discoverySystem::addNodeRecord);
  }

  /**
   * Looks up a peer by its identifier.
   *
   * @param peerId the peer identifier
   * @return the peer if known to the discovery system
   */
  @Override
  public Optional<Peer> getPeer(final PeerId peerId) {
    return discoverySystem.lookupNode(peerId.getId()).map(DiscoveryPeerFactory::fromNodeRecord);
  }
}
