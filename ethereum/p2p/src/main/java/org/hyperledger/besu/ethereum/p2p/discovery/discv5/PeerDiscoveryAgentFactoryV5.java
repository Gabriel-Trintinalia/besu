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

import org.hyperledger.besu.crypto.SignatureAlgorithm;
import org.hyperledger.besu.crypto.SignatureAlgorithmFactory;
import org.hyperledger.besu.cryptoservices.NodeKey;
import org.hyperledger.besu.ethereum.forkid.ForkIdManager;
import org.hyperledger.besu.ethereum.p2p.config.NetworkingConfiguration;
import org.hyperledger.besu.ethereum.p2p.discovery.DiscoveryPeer;
import org.hyperledger.besu.ethereum.p2p.discovery.NodeRecordManager;
import org.hyperledger.besu.ethereum.p2p.discovery.PeerDiscoveryAgent;
import org.hyperledger.besu.ethereum.p2p.discovery.PeerDiscoveryAgentFactory;
import org.hyperledger.besu.ethereum.p2p.rlpx.RlpxAgent;

import java.util.List;

import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import io.vertx.core.Vertx;
import org.ethereum.beacon.discovery.DiscoverySystemBuilder;
import org.ethereum.beacon.discovery.MutableDiscoverySystem;
import org.ethereum.beacon.discovery.schema.NodeRecord;

/** Minimal factory for DiscV5 PeerDiscoveryAgent using DiscoverySystemBuilder. */
@SuppressWarnings("UnusedVariable")
public final class PeerDiscoveryAgentFactoryV5 implements PeerDiscoveryAgentFactory {
  private final List<NodeRecord> bootnodes;
  private final NetworkingConfiguration config;

  private final NodeRecordManager nodeRecordManager;

  private static final Supplier<SignatureAlgorithm> SIGNATURE_ALGORITHM =
      Suppliers.memoize(SignatureAlgorithmFactory::getInstance);

  private final NodeKey nodeKey;
  private final ForkIdManager forkIdManager;

  public PeerDiscoveryAgentFactoryV5(
      final Vertx vertx,
      final NodeKey nodeKey,
      final NetworkingConfiguration config,
      final NodeRecordManager nodeRecordManager,
      final ForkIdManager forkIdManager) {
    this.config = config;
    this.nodeKey = nodeKey;
    this.forkIdManager = forkIdManager;
    this.nodeRecordManager = nodeRecordManager;
    this.bootnodes = BootnodesV5.getBootnodes();
  }

  @Override
  public PeerDiscoveryAgent create(final RlpxAgent ignored) {

    final DiscoverySystemBuilder discoverySystemBuilder = new DiscoverySystemBuilder();
    nodeRecordManager.initializeLocalNode(
        config.getDiscovery().getAdvertisedHost(),
        config.getDiscovery().getBindPort(),
        config.getDiscovery().getBindPort());

    NodeRecord localNodeRecord =
        nodeRecordManager
            .getLocalNode()
            .flatMap(DiscoveryPeer::getNodeRecord)
            .orElseThrow(() -> new IllegalStateException("Local node record not initialized"));

    NodeKeyService nodeKeyService = new NodeKeyService(nodeKey);
    final MutableDiscoverySystem discoverySystem =
        discoverySystemBuilder
            .listen(config.getDiscovery().getBindHost(), config.getDiscovery().getBindPort())
            .bootnodes(bootnodes)
            // .newAddressHandler(maybeUpdateNodeRecordHandler)
            .localNodeRecordListener(new NodeRecordListener(nodeRecordManager))
            .localNodeRecord(localNodeRecord)
            .nodeKeyService(nodeKeyService)

            // .addressAccessPolicy(
            //   discoConfig.areSiteLocalAddressesEnabled()
            //    ? AddressAccessPolicy.ALLOW_ALL
            //   : address -> !address.getAddress().isSiteLocalAddress())
            .buildMutable();
    return new PeerDiscoveryAgentV5(discoverySystem, config, forkIdManager, nodeRecordManager);
  }
}
