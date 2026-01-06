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
import org.ethereum.beacon.discovery.DiscoverySystem;
import org.ethereum.beacon.discovery.DiscoverySystemBuilder;
import org.ethereum.beacon.discovery.schema.NodeRecord;
import org.ethereum.beacon.discovery.schema.NodeRecordFactory;

/** Minimal factory for DiscV5 PeerDiscoveryAgent using DiscoverySystemBuilder. */
@SuppressWarnings("UnusedVariable")
public final class PeerDiscoveryAgentFactoryDiscv5 implements PeerDiscoveryAgentFactory {
  private final List<NodeRecord> bootnodes;
  private final NetworkingConfiguration config;

  private final NodeRecordManager nodeRecordManager;

  private static final Supplier<SignatureAlgorithm> SIGNATURE_ALGORITHM =
      Suppliers.memoize(SignatureAlgorithmFactory::getInstance);

  private final NodeKey nodeKey;

  public PeerDiscoveryAgentFactoryDiscv5(
      final Vertx vertx,
      final NodeKey nodeKey,
      final NetworkingConfiguration config,
      final NodeRecordManager nodeRecordManager) {
    this.config = config;
    this.nodeKey = nodeKey;
    String bootnodeEnr =
        "enr:-Ke4QAr53_PK_q75A4VrRNlF0tTb5xpP-mu5tXfCxrpxQZbIcb1zmdZDCLNAjtTIoaM8ALvOQxbJHWsoa1qYsNvXb_GGAZD-7swXg2V0aMvKhMuiocCEaV2wV4JpZIJ2NIJpcISpm6kbiXNlY3AyNTZrMaEC0AjXyvYbypXWyL5lrz8inZKt-wFlVDbYv6BTupOctIiEc25hcMCDdGNwgnpHg3VkcIJ6Rw";
    this.bootnodes = List.of(NodeRecordFactory.DEFAULT.fromEnr(bootnodeEnr));
    this.nodeRecordManager = nodeRecordManager;
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

    NodeKeyServiceDiscV5 nodeKeyService = new NodeKeyServiceDiscV5(nodeKey);
    final DiscoverySystem discoverySystem =
        discoverySystemBuilder
            .listen(config.getDiscovery().getBindHost(), config.getDiscovery().getBindPort())
            .bootnodes(bootnodes)
            // .newAddressHandler(maybeUpdateNodeRecordHandler)
            .localNodeRecordListener(nodeRecordManager)
            .localNodeRecord(localNodeRecord)
            .nodeKeyService(nodeKeyService)

            // .addressAccessPolicy(
            //   discoConfig.areSiteLocalAddressesEnabled()
            //    ? AddressAccessPolicy.ALLOW_ALL
            //   : address -> !address.getAddress().isSiteLocalAddress())
            .build();
    return new DiscV5PeerDiscoveryAgent(discoverySystem, config);
  }
}
