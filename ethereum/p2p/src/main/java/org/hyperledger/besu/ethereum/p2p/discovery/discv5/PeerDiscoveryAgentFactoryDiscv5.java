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

import org.hyperledger.besu.cryptoservices.NodeKey;
import org.hyperledger.besu.ethereum.p2p.config.NetworkingConfiguration;
import org.hyperledger.besu.ethereum.p2p.discovery.PeerDiscoveryAgent;
import org.hyperledger.besu.ethereum.p2p.discovery.PeerDiscoveryAgentFactory;
import org.hyperledger.besu.ethereum.p2p.rlpx.RlpxAgent;
import org.hyperledger.besu.plugin.services.MetricsSystem;

import java.math.BigInteger;
import java.util.List;

import io.vertx.core.Vertx;
import org.apache.tuweni.crypto.SECP256K1;
import org.ethereum.beacon.discovery.DiscoverySystem;
import org.ethereum.beacon.discovery.DiscoverySystemBuilder;
import org.ethereum.beacon.discovery.schema.NodeRecord;
import org.ethereum.beacon.discovery.schema.NodeRecordBuilder;
import org.ethereum.beacon.discovery.schema.NodeRecordFactory;

/** Minimal factory for DiscV5 PeerDiscoveryAgent using DiscoverySystemBuilder. */
public final class PeerDiscoveryAgentFactoryDiscv5 implements PeerDiscoveryAgentFactory {
  private final List<NodeRecord> bootnodes;
  private final NetworkingConfiguration config;

  public PeerDiscoveryAgentFactoryDiscv5(
      final Vertx vertx,
      final NodeKey nodeKey,
      final NetworkingConfiguration config,
      final MetricsSystem metricsSystem) {
    this.config = config;
    String bootnodeEnr =
        "enr:-KO4QCB7phKoyuJHYX3myxZ58bDOw6tEe1_Gz2MgkjtKgorwdnSVEAeeXWdWDyeQJg9KvebAabkTt3PBiSO-0rviPeKGAZXyMQf6g2V0aMfGhCOqE1GAgmlkgnY0gmlwhEFtZtiJc2VjcDI1NmsxoQL4oDtXKF2VqiKzI8v8IKj6_soLMb-JFZEQ3I7gJ_YER4RzbmFwwIN0Y3CCdl-DdWRwgnZf";
    this.bootnodes = List.of(NodeRecordFactory.DEFAULT.fromEnr(bootnodeEnr));
  }

  @Override
  public PeerDiscoveryAgent create(final RlpxAgent ignored) {

    final DiscoverySystemBuilder discoverySystemBuilder = new DiscoverySystemBuilder();

    discoverySystemBuilder.listen(
        config.getDiscovery().getBindHost(), config.getDiscovery().getBindPort());

    final SECP256K1.SecretKey localNodePrivateKey =
        SECP256K1.SecretKey.fromInteger(BigInteger.valueOf(768654645654L));

    // Use a fixed sequence number for simplicity
    final int seqNo = 1;

    final NodeRecordBuilder nodeRecordBuilder =
        new NodeRecordBuilder()
            .secretKey(localNodePrivateKey)
            .seq(seqNo)
            .address(
                config.getDiscovery().getAdvertisedHost(),
                config.getDiscovery().getBindPort(),
                config.getDiscovery().getBindPort());

    final DiscoverySystem discoverySystem =
        discoverySystemBuilder
            .secretKey(localNodePrivateKey)
            .bootnodes(bootnodes)
            .localNodeRecord(nodeRecordBuilder.build())
            // .newAddressHandler(maybeUpdateNodeRecordHandler)
            // .localNodeRecordListener(this::localNodeRecordUpdated)
            // .addressAccessPolicy(
            //   discoConfig.areSiteLocalAddressesEnabled()
            //    ? AddressAccessPolicy.ALLOW_ALL
            //   : address -> !address.getAddress().isSiteLocalAddress())
            .build();

    return new DiscV5PeerDiscoveryAgent(discoverySystem, config);
  }
}
