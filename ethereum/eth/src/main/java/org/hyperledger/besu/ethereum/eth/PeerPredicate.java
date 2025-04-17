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
package org.hyperledger.besu.ethereum.eth;

import org.hyperledger.besu.ethereum.eth.manager.EthPeer;

import java.util.Set;
import java.util.function.Predicate;

import org.apache.tuweni.bytes.Bytes;

public class PeerPredicate implements Predicate<EthPeer> {

  public static final PeerPredicate ANY_PEER = new PeerPredicate(peer -> true, "ANY_PEER");

  private final String description;
  private final Predicate<EthPeer> predicate;

  public PeerPredicate(Predicate<EthPeer> predicate, String description) {
    this.predicate = predicate;
    this.description = description;
  }

  @Override
  public boolean test(EthPeer t) {
    return predicate.test(t);
  }

  @Override
  public String toString() {
    return description;
  }

  // create a predicate that returns true if the peer has is serving snap
  public static PeerPredicate isServingSnap() {
    return new PeerPredicate(EthPeer::isServingSnap, "IS_SERVING_SNAP");
  }

  public static PeerPredicate hasEstimatedHeight(long requiredHeight) {
    String description = String.format("HAS_ESTIMATED_HEIGHT (%s)", requiredHeight);
    return new PeerPredicate(
        peer -> peer.chainState().getEstimatedHeight() >= requiredHeight, description);
  }

  // Create a predicate that returns true if the peer has not been seen
  public static PeerPredicate hasNotBeenSeen(Set<Bytes> seenPeers) {
    return new PeerPredicate(peer -> !seenPeers.contains(peer.nodeId()), "HAS_NOT_BEEN_SEEN");
  }
}
