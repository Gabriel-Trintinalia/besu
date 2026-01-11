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
package org.hyperledger.besu.ethereum.p2p.discovery;

import org.hyperledger.besu.crypto.SignatureAlgorithmFactory;
import org.hyperledger.besu.ethereum.p2p.discovery.discv4.internal.DiscoveryPeerV4;
import org.hyperledger.besu.ethereum.p2p.discovery.discv5.DiscoveryPeerV5;
import org.hyperledger.besu.ethereum.p2p.peers.EnodeURLImpl;
import org.hyperledger.besu.plugin.data.EnodeURL;

import java.net.InetAddress;
import java.util.Arrays;
import java.util.Optional;

import org.apache.tuweni.bytes.Bytes;
import org.ethereum.beacon.discovery.schema.EnrField;
import org.ethereum.beacon.discovery.schema.NodeRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DiscoveryPeerFactory {
  private static final Logger LOG = LoggerFactory.getLogger(DiscoveryPeerFactory.class);

  public static DiscoveryPeer fromEnode(final EnodeURL enode) {
    return DiscoveryPeerV4.fromEnode(enode);
  }

  public static Optional<DiscoveryPeer> fromNodeRecord(final NodeRecord nodeRecord) {
    try {
      final Bytes ipBytes = (Bytes) nodeRecord.get(EnrField.IP_V4);
      EnodeURL peerEnode;
      Bytes keyBytes = (Bytes) nodeRecord.get(EnrField.PKEY_SECP256K1);
      // convert 33 bytes compressed public key to uncompressed using Bouncy Castle
      var curve = SignatureAlgorithmFactory.getInstance().getCurve();
      var ecPoint = curve.getCurve().decodePoint(keyBytes.toArrayUnsafe());
      // uncompressed public key is 65 bytes, first byte is 0x04.
      var encodedPubKey = ecPoint.getEncoded(false);
      var nodeId = Bytes.of(Arrays.copyOfRange(encodedPubKey, 1, encodedPubKey.length));
      peerEnode =
          EnodeURLImpl.builder()
              .nodeId(nodeId)
              .ipAddress(InetAddress.getByAddress(ipBytes.toArrayUnsafe()))
              .listeningPort(nodeRecord.containsKey(EnrField.TCP) ? (int) nodeRecord.get(EnrField.TCP) : 0)
              .discoveryPort(nodeRecord.containsKey(EnrField.UDP) ? (int) nodeRecord.get(EnrField.UDP) : 0)
              .build();
      DiscoveryPeer peer = DiscoveryPeerV5.fromEnode(peerEnode);
      peer.setNodeRecord(nodeRecord);
      return Optional.of(peer);
    } catch (Exception e) {
      LOG.warn("Failed to create enode from node record: {}",  nodeRecord.toString());
      return Optional.empty();
    }
  }
}
