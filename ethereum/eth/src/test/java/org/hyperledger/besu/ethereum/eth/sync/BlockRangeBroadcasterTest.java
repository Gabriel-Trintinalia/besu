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
package org.hyperledger.besu.ethereum.eth.sync;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.ethereum.chain.Blockchain;
import org.hyperledger.besu.ethereum.eth.manager.EthContext;
import org.hyperledger.besu.ethereum.eth.manager.EthMessages;
import org.hyperledger.besu.ethereum.eth.manager.EthPeer;
import org.hyperledger.besu.ethereum.eth.manager.EthPeers;
import org.hyperledger.besu.ethereum.eth.messages.BlockRangeUpdateMessage;
import org.hyperledger.besu.ethereum.eth.messages.EthProtocolMessages;
import org.hyperledger.besu.ethereum.p2p.rlpx.connections.PeerConnection;

import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public class BlockRangeBroadcasterTest {

  @Mock private Blockchain blockchain;
  @Mock private EthContext ethContext;
  @Mock private EthPeers ethPeers;
  @Mock private EthPeer ethPeerWithSupport;
  @Mock private EthPeer ethPeerWithoutSupport;

  private BlockRangeBroadcaster blockRangeBroadcaster;

  @BeforeEach
  public void setup() {
    when(ethContext.getEthMessages()).thenReturn(mock(EthMessages.class));
    blockRangeBroadcaster = spy(new BlockRangeBroadcaster(ethContext, blockchain));
  }

  @Test
  public void shouldBroadcastBlockRange() throws PeerConnection.PeerNotConnected {
    setupPeers(ethPeerWithSupport);
    when(ethPeerWithSupport.hasSupportForMessage(EthProtocolMessages.BLOCK_RANGE_UPDATE))
        .thenReturn(true);
    broadcastBlockRange();
    verify(ethPeerWithSupport, times(1)).send(any(BlockRangeUpdateMessage.class));
  }

  @Test
  public void shouldSendBlockRangeOnlyToEth69Peers() throws PeerConnection.PeerNotConnected {
    setupPeers(ethPeerWithoutSupport, ethPeerWithSupport);
    when(ethPeerWithSupport.hasSupportForMessage(EthProtocolMessages.BLOCK_RANGE_UPDATE))
        .thenReturn(true);
    when(ethPeerWithoutSupport.hasSupportForMessage(EthProtocolMessages.BLOCK_RANGE_UPDATE))
        .thenReturn(false);
    broadcastBlockRange();
    verify(ethPeerWithoutSupport, never()).send(any(BlockRangeUpdateMessage.class));
    verify(ethPeerWithSupport, times(1)).send(any(BlockRangeUpdateMessage.class));
  }

  private void setupPeers(final EthPeer... peers) {
    when(ethContext.getEthPeers()).thenReturn(ethPeers);
    when(ethPeers.streamAvailablePeers()).thenReturn(Stream.of(peers));
  }

  private void broadcastBlockRange() {
    long startBlockNumber = 0L;
    long endBlockNumber = 1L;
    Hash endBlockHash = Hash.ZERO;
    blockRangeBroadcaster.broadcastBlockRange(startBlockNumber, endBlockNumber, endBlockHash);
  }
}
