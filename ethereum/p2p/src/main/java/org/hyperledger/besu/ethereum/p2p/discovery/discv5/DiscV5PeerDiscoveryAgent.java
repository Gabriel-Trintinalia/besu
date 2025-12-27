package org.hyperledger.besu.ethereum.p2p.discovery.discv5;

import org.hyperledger.besu.ethereum.p2p.discovery.DiscoveryPeer;
import org.hyperledger.besu.ethereum.p2p.discovery.PeerDiscoveryAgent;
import org.hyperledger.besu.ethereum.p2p.peers.Peer;
import org.hyperledger.besu.ethereum.p2p.peers.PeerId;



import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Minimal Discovery v5 based implementation of {@link PeerDiscoveryAgent}.
 *
 * This implementation:
 * - delegates lifecycle to DiscoverySystem
 * - treats discovery as signal-only
 * - does not own connection or bonding semantics
 */
public final class DiscV5PeerDiscoveryAgent implements PeerDiscoveryAgent {

  private final DiscoverySystem discoverySystem;
  private final DiscoveryService discoveryService;

  /**
   * Local cache of discovered peers.
   * DiscV5 itself is session-based and does not expose a peer table.
   */
  private final Map<PeerId, Peer> discoveredPeers = new ConcurrentHashMap<>();

  private volatile boolean stopped = true;

  public DiscV5PeerDiscoveryAgent(
    final DiscoverySystem discoverySystem,
    final DiscoveryService discoveryService) {
    this.discoverySystem = discoverySystem;
    this.discoveryService = discoveryService;
  }

  @Override
  public CompletableFuture<Integer> start(final int tcpPort) {
    stopped = false;

    return discoverySystem.start()
      .thenApply(
        unused -> {
          registerDiscoveryCallbacks();
          return tcpPort;
        });
  }

  @Override
  public CompletableFuture<?> stop() {
    stopped = true;
    discoveredPeers.clear();
    return discoverySystem.stop();
  }

  @Override
  public void updateNodeRecord() {
    discoveryService.updateNodeRecord();
  }

  /**
   * DiscV5 does not use fork IDs directly.
   * This is intentionally permissive and expected to be refined.
   */
  @Override
  public boolean checkForkId(final DiscoveryPeer peer) {
    return true;
  }

  @Override
  public Stream<DiscoveryPeer> streamDiscoveredPeers() {
    return discoveredPeers.values().stream()
      .map(DiscoveryPeer::fromPeer);
  }

  @Override
  public void dropPeer(final PeerId peerId) {
    discoveredPeers.remove(peerId);
  }

  @Override
  public boolean isEnabled() {
    return !stopped;
  }

  @Override
  public boolean isStopped() {
    return stopped;
  }

  /**
   * DiscV5 has no bonding concept.
   * This method is intentionally a no-op.
   */
  @Override
  public void bond(final Peer peer) {
    // no-op by design
  }

  @Override
  public Optional<Peer> getPeer(final PeerId peerId) {
    return Optional.ofNullable(discoveredPeers.get(peerId));
  }

  /**
   * Registers minimal callbacks to ingest discovered nodes.
   */
  private void registerDiscoveryCallbacks() {
    discoveryService.addNodeDiscoveredListener(this::onNodeDiscovered);
  }

  private void onNodeDiscovered(final NodeRecord nodeRecord) {
    final Peer peer = Peer.fromNodeRecord(nodeRecord);
    discoveredPeers.put(peer.getId(), peer);
  }
}
