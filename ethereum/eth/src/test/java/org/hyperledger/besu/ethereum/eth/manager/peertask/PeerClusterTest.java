/*
 * Copyright contributors to Hyperledger Besu.
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
package org.hyperledger.besu.ethereum.eth.manager.peertask;

import static org.assertj.core.api.Assertions.assertThat;

import org.hyperledger.besu.ethereum.eth.manager.EthPeer;
import org.hyperledger.besu.ethereum.eth.messages.EthProtocolMessages;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

public class PeerClusterTest {

  @Test
  public void mainSlotFillsUpToMax() {
    PeerCluster cluster = new PeerCluster(EthProtocolMessages.GET_BLOCK_HEADERS);
    EthPeer p1 = Mockito.mock(EthPeer.class);
    EthPeer p2 = Mockito.mock(EthPeer.class);
    EthPeer p3 = Mockito.mock(EthPeer.class);
    EthPeer p4 = Mockito.mock(EthPeer.class);

    cluster.addCandidate(p1);
    cluster.addCandidate(p2);
    cluster.addCandidate(p3);
    cluster.addCandidate(p4); // should not enter main slot (already 3)

    List<EthPeer> mainPeers = cluster.getMainPeers();
    assertThat(mainPeers).hasSize(3);
    assertThat(mainPeers).contains(p1, p2, p3);
    assertThat(mainPeers).doesNotContain(p4);
  }

  @Test
  public void removePeerClearsFromMainAndTracked() {
    PeerCluster cluster = new PeerCluster(EthProtocolMessages.GET_BLOCK_HEADERS);
    EthPeer p1 = Mockito.mock(EthPeer.class);
    cluster.addCandidate(p1);
    assertThat(cluster.getMainPeers()).contains(p1);

    cluster.removePeer(p1);
    assertThat(cluster.getMainPeers()).doesNotContain(p1);
  }

  @Test
  public void selectForDispatchReturnsCorrectCount() {
    PeerCluster cluster = new PeerCluster(EthProtocolMessages.GET_BLOCK_HEADERS);
    EthPeer p1 = Mockito.mock(EthPeer.class);
    EthPeer p2 = Mockito.mock(EthPeer.class);
    EthPeer p3 = Mockito.mock(EthPeer.class);
    cluster.addCandidate(p1);
    cluster.addCandidate(p2);
    cluster.addCandidate(p3);

    EthPeer reserve = Mockito.mock(EthPeer.class);
    List<EthPeer> selected = cluster.selectForDispatch(List.of(reserve));

    // 2 from main + 1 from reserve = 3
    assertThat(selected).hasSize(3);
    assertThat(selected).contains(reserve);
  }

  @Test
  public void reservePeerOutscoringWeakestMainCausesSwap() {
    PeerCluster cluster = new PeerCluster(EthProtocolMessages.GET_BLOCK_HEADERS);
    EthPeer p1 = Mockito.mock(EthPeer.class);
    EthPeer p2 = Mockito.mock(EthPeer.class);
    EthPeer p3 = Mockito.mock(EthPeer.class);
    EthPeer reserve = Mockito.mock(EthPeer.class);

    cluster.addCandidate(p1);
    cluster.addCandidate(p2);
    cluster.addCandidate(p3);

    // Give p1 a mediocre score: valid but slow
    cluster.recordResponse(p1, 900, 100, true);
    // Give p2 and p3 good scores
    cluster.recordResponse(p2, 50, 2000, true);
    cluster.recordResponse(p3, 60, 1800, true);

    // reserve gets a much better score than p1
    cluster.recordResponse(reserve, 10, 5000, true);

    // reserve should have swapped in for p1
    List<EthPeer> mainPeers = cluster.getMainPeers();
    assertThat(mainPeers).contains(reserve);
    assertThat(mainPeers).doesNotContain(p1);
  }

  @Test
  public void selectForDispatchWithNoReserveReturnsTwoMainPeers() {
    PeerCluster cluster = new PeerCluster(EthProtocolMessages.GET_BLOCK_HEADERS);
    EthPeer p1 = Mockito.mock(EthPeer.class);
    EthPeer p2 = Mockito.mock(EthPeer.class);
    cluster.addCandidate(p1);
    cluster.addCandidate(p2);

    List<EthPeer> selected = cluster.selectForDispatch(List.of());
    assertThat(selected).hasSize(2);
  }

  @Test
  public void addingDuplicatePeerIsIdempotent() {
    PeerCluster cluster = new PeerCluster(EthProtocolMessages.GET_BLOCK_HEADERS);
    EthPeer p1 = Mockito.mock(EthPeer.class);
    cluster.addCandidate(p1);
    cluster.addCandidate(p1); // second add is ignored
    assertThat(cluster.getMainPeers()).hasSize(1);
  }
}
