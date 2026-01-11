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

public final class PeerDiscoveryAgentV5 implements PeerDiscoveryAgent {
  private static final Logger LOG = LoggerFactory.getLogger(PeerDiscoveryAgentV5.class);

  private final MutableDiscoverySystem discoverySystem;
  private final ForkIdManager forkIdManager;

  private boolean stopped = false;

  private final DiscoveryConfiguration config;
  private final NodeRecordManager nodeRecordManager;

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

  @Override
  public CompletableFuture<?> stop() {
    LOG.info("Stopping DiscV5 Peer Discovery Agent");
    discoverySystem.stop();
    stopped = true;
    return CompletableFuture.completedFuture(null);
  }

  @Override
  public void updateNodeRecord() {
    if (isEnabled()) {
      return;
    }
    nodeRecordManager.updateNodeRecord();
  }

  @Override
  public boolean checkForkId(final DiscoveryPeer peer) {
    return peer.getForkId().map(forkIdManager::peerCheck).orElse(true);
  }

  @Override
  public Stream<DiscoveryPeer> streamDiscoveredPeers() {
    return discoverySystem.streamLiveNodes()
      .map(DiscoveryPeerFactory::fromNodeRecord)
      .flatMap(Optional::stream);
  }

  @Override
  public void dropPeer(final PeerId peerId) {
    discoverySystem.deleteNodeRecord(peerId.getId());
  }

  @Override
  public boolean isEnabled() {
    return config.isEnabled();
  }

  @Override
  public boolean isStopped() {
    return stopped;
  }

  @Override
  public void addPeer(final Peer peer) {
    peer.getNodeRecord().ifPresent(discoverySystem::addNodeRecord);
  }

  @Override
  public Optional<Peer> getPeer(final PeerId peerId) {
    return discoverySystem.lookupNode(peerId.getId())
      .flatMap(DiscoveryPeerFactory::fromNodeRecord);
  }
}
