package org.hyperledger.besu.ethereum.p2p.discovery;

import org.hyperledger.besu.cryptoservices.NodeKey;
import org.hyperledger.besu.ethereum.chain.Blockchain;
import org.hyperledger.besu.ethereum.p2p.config.NetworkingConfiguration;
import org.hyperledger.besu.ethereum.p2p.discovery.discv4.PeerDiscoveryAgentFactoryDiscv4;
import org.hyperledger.besu.ethereum.p2p.discovery.discv5.PeerDiscoveryAgentFactoryDiscv5;
import org.hyperledger.besu.ethereum.p2p.permissions.PeerPermissions;
import org.hyperledger.besu.ethereum.p2p.rlpx.RlpxAgent;
import org.hyperledger.besu.ethereum.storage.StorageProvider;
import org.hyperledger.besu.nat.NatService;
import org.hyperledger.besu.plugin.services.MetricsSystem;

import java.util.List;

import io.vertx.core.Vertx;

public class DefaultPeerDiscoveryAgentFactory implements PeerDiscoveryAgentFactory {

  private final PeerDiscoveryAgentFactory delegate;

  public DefaultPeerDiscoveryAgentFactory(
    final Vertx vertx,
    final NodeKey nodeKey,
    final NetworkingConfiguration config,
    final PeerPermissions peerPermissions,
    final NatService natService,
    final MetricsSystem metricsSystem,
    final StorageProvider storageProvider,
    final Blockchain blockchain,
    final List<Long> blockNumberForks,
    final List<Long> timestampForks) {

    if (config.getDiscovery().isDiscoveryV5Enabled()) {
      this.delegate =
        new PeerDiscoveryAgentFactoryDiscv5(
          vertx,
          nodeKey,
          config,
          metricsSystem);
    } else {
      this.delegate =
        new PeerDiscoveryAgentFactoryDiscv4(
          vertx,
          nodeKey,
          config,
          peerPermissions,
          natService,
          metricsSystem,
          storageProvider,
          blockchain,
          blockNumberForks,
          timestampForks);
    }
  }

  @Override
  public PeerDiscoveryAgent create(final RlpxAgent rlpxAgent) {
    return delegate.create(rlpxAgent);
  }
}
