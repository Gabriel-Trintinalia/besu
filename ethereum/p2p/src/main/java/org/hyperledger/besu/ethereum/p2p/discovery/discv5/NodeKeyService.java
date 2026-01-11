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

import org.hyperledger.besu.crypto.SECPPublicKey;
import org.hyperledger.besu.crypto.SignatureAlgorithm;
import org.hyperledger.besu.crypto.SignatureAlgorithmFactory;
import org.hyperledger.besu.cryptoservices.NodeKey;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;

public class NodeKeyService implements org.ethereum.beacon.discovery.crypto.NodeKeyService {
  private final SignatureAlgorithm signatureAlgorithm = SignatureAlgorithmFactory.getInstance();

  private final NodeKey nodeKey;

  public NodeKeyService(final NodeKey nodeKey) {
    this.nodeKey = nodeKey;
  }

  @Override
  public Bytes deriveECDHKeyAgreement(final Bytes bytes) {
    SECPPublicKey publicKey = signatureAlgorithm.createPublicKey(bytes);
    return nodeKey.calculateECDHKeyAgreement(publicKey);
  }

  @Override
  public Bytes sign(final Bytes32 bytes32) {
    Bytes signature = nodeKey.sign(bytes32).encodedBytes();
    return signature.slice(0, 64);
  }

  @Override
  public Bytes deriveCompressedPublicKeyFromPrivate() {
    return Bytes.wrap(
        signatureAlgorithm.publicKeyAsEcPoint(nodeKey.getPublicKey()).getEncoded(true));
  }
}
