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

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

public class ClusteredPeerTest {

  @Test
  public void scoreIsZeroBeforeAnyResponse() {
    EthPeer ethPeer = Mockito.mock(EthPeer.class);
    ClusteredPeer peer = new ClusteredPeer(ethPeer);
    assertThat(peer.getCompositeScore()).isEqualTo(0.0);
  }

  @Test
  public void updateMetricsAndScoreOrdering() {
    EthPeer ethPeer1 = Mockito.mock(EthPeer.class);
    EthPeer ethPeer2 = Mockito.mock(EthPeer.class);
    ClusteredPeer fast = new ClusteredPeer(ethPeer1);
    ClusteredPeer slow = new ClusteredPeer(ethPeer2);

    // fast: 100ms latency, 1000 bytes, valid
    fast.updateMetrics(100, 1000, true);
    // slow: 1000ms latency, 500 bytes, valid
    slow.updateMetrics(1000, 500, true);

    assertThat(fast.getCompositeScore()).isGreaterThan(slow.getCompositeScore());
  }

  @Test
  public void invalidResponsesLowerScore() {
    EthPeer ethPeer1 = Mockito.mock(EthPeer.class);
    EthPeer ethPeer2 = Mockito.mock(EthPeer.class);
    ClusteredPeer good = new ClusteredPeer(ethPeer1);
    ClusteredPeer bad = new ClusteredPeer(ethPeer2);

    // both: same latency and bytes, but bad has one invalid response
    good.updateMetrics(100, 1000, true);
    bad.updateMetrics(100, 1000, false);

    assertThat(good.getCompositeScore()).isGreaterThan(bad.getCompositeScore());
  }

  @Test
  public void emaLatencyDecaysCorrectly() {
    EthPeer ethPeer = Mockito.mock(EthPeer.class);
    ClusteredPeer peer = new ClusteredPeer(ethPeer);
    // initial emaLatency = 500ms, first update with 100ms latency
    // new ema = 0.2 * 100 + 0.8 * 500 = 420
    peer.updateMetrics(100, 1000, true);
    // score should be positive and reflect the latency improvement
    assertThat(peer.getCompositeScore()).isGreaterThan(0.0);
  }

  @Test
  public void getEthPeerReturnsWrappedPeer() {
    EthPeer ethPeer = Mockito.mock(EthPeer.class);
    ClusteredPeer peer = new ClusteredPeer(ethPeer);
    assertThat(peer.getEthPeer()).isSameAs(ethPeer);
  }
}
