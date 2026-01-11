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

import org.hyperledger.besu.ethereum.p2p.discovery.discv4.Endpoint;
import org.hyperledger.besu.ethereum.p2p.discovery.discv4.internal.DiscoveryPeerV4;
import org.hyperledger.besu.plugin.data.EnodeURL;

public class DiscoveryPeerV5 extends DiscoveryPeerV4 {
  private DiscoveryPeerV5(final EnodeURL enode, final Endpoint endpoint) {
    super(enode, endpoint);
  }

  public static DiscoveryPeerV5 fromEnode(final EnodeURL enode) {
    return new DiscoveryPeerV5(enode, Endpoint.fromEnode(enode));
  }

  @Override
  public boolean isReady() {
    // In DiscV5, a peer is considered ready if it has a valid NodeRecord
    return getNodeRecord().isPresent() && getEnodeURL().isListening();
  }
}
