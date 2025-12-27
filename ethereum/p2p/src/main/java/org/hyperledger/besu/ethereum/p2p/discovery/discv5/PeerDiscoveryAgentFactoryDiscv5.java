package org.hyperledger.besu.ethereum.p2p.discovery.discv5;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import org.ethereum.beacon.discovery.schema.NodeRecordFactory;
import org.hyperledger.besu.cryptoservices.NodeKey;
import org.hyperledger.besu.ethereum.p2p.config.NetworkingConfiguration;
import org.hyperledger.besu.ethereum.p2p.discovery.PeerDiscoveryAgent;
import org.hyperledger.besu.ethereum.p2p.discovery.PeerDiscoveryAgentFactory;
import org.hyperledger.besu.ethereum.p2p.rlpx.RlpxAgent;
import org.hyperledger.besu.plugin.services.MetricsSystem;

import io.vertx.core.Vertx;
import org.ethereum.beacon.discovery.DiscoverySystem;
import org.ethereum.beacon.discovery.DiscoverySystemBuilder;
import org.ethereum.beacon.discovery.schema.NodeRecord;


/**
 * Minimal factory for DiscV5 PeerDiscoveryAgent using DiscoverySystemBuilder.
 */
public final class PeerDiscoveryAgentFactoryDiscv5
  implements PeerDiscoveryAgentFactory {


  private final List<NodeRecord> bootnodes;


  private final Vertx vertx;
  private final NodeKey nodeKey;
  private final NetworkingConfiguration config;
  private final MetricsSystem metricsSystem;

  public PeerDiscoveryAgentFactoryDiscv5(
    final Vertx vertx,
    final NodeKey nodeKey,
    final NetworkingConfiguration config,
    final MetricsSystem metricsSystem) {
    this.vertx = vertx;
    this.nodeKey = nodeKey;
    this.config = config;
    this.metricsSystem = metricsSystem;

    this.bootnodes =
      config.getDiscovery().getBootnodes().stream()
        .map(q->  NodeRecordFactory.DEFAULT.fromEnr(q.toURI()))
        .toList();

  }

  @Override
  public PeerDiscoveryAgent create(final RlpxAgent ignored) {

    // Scheduler for internal tasks
    final ScheduledExecutorService scheduler =
      Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "discv5-scheduler"));

    // Build the local NodeRecord from NodeKey and config
    final NodeRecord localNodeRecord =
      NodeRecord.fromValues(
        nodeKey.getPublicKey().toArray(),
        config.getDiscovery().getAdvertisedAddress(),
        config.getDiscovery().getDiscoveryPort(),
        config.getDiscovery().getTcpPort());

    // Use the builder to create the DiscoverySystem
    final DiscoverySystem discoverySystem =
      new DiscoverySystemBuilder()
        .localNodeRecord(localNodeRecord)
        .secretKey(SecretKey.create(nodeKey.getPrivateKey().toArray()))
        .listen(config.getDiscovery().getBindAddress(), config.getDiscovery().getDiscoveryPort())
        .schedulers(org.ethereum.beacon.discovery.scheduler.Schedulers.createDefault())
        .build();

    // Wrap the discovery system in the PeerDiscoveryAgent adapter
    return new DiscV5PeerDiscoveryAgent(
      discoverySystem, discoverySystem.getDiscoveryManager().getDiscoveryService());
  }
}
