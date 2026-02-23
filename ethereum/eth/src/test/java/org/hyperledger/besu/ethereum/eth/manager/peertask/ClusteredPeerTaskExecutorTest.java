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

import org.hyperledger.besu.ethereum.eth.manager.EthPeer;
import org.hyperledger.besu.ethereum.p2p.rlpx.connections.PeerConnection;
import org.hyperledger.besu.ethereum.p2p.rlpx.wire.MessageData;
import org.hyperledger.besu.ethereum.p2p.rlpx.wire.SubProtocol;
import org.hyperledger.besu.metrics.noop.NoOpMetricsSystem;

import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

public class ClusteredPeerTaskExecutorTest {

  private @Mock PeerSelector peerSelector;
  private @Mock PeerTaskRequestSender requestSender;
  private @Mock PeerTask<Object> peerTask;
  private @Mock SubProtocol subprotocol;
  private @Mock MessageData requestMessageData;
  private @Mock PeerClusterManager clusterManager;
  private AutoCloseable mockCloser;

  private ClusteredPeerTaskExecutor executor;

  @BeforeEach
  public void beforeTest() {
    mockCloser = MockitoAnnotations.openMocks(this);
    executor =
        new ClusteredPeerTaskExecutor(
            peerSelector, requestSender, new NoOpMetricsSystem(), clusterManager);
  }

  @AfterEach
  public void afterTest() throws Exception {
    mockCloser.close();
  }

  @Test
  public void winnerIsLargestValidResponse()
      throws PeerConnection.PeerNotConnected,
          ExecutionException,
          InterruptedException,
          TimeoutException,
          InvalidPeerTaskResponseException,
          MalformedRlpFromPeerException {

    EthPeer peer1 = Mockito.mock(EthPeer.class);
    EthPeer peer2 = Mockito.mock(EthPeer.class);
    EthPeer peer3 = Mockito.mock(EthPeer.class);

    MessageData smallResponse = Mockito.mock(MessageData.class);
    MessageData largeResponse = Mockito.mock(MessageData.class);
    MessageData mediumResponse = Mockito.mock(MessageData.class);

    Object smallResult = new Object();
    Object largeResult = new Object();
    Object mediumResult = new Object();

    Mockito.when(smallResponse.getSize()).thenReturn(100);
    Mockito.when(largeResponse.getSize()).thenReturn(5000);
    Mockito.when(mediumResponse.getSize()).thenReturn(1000);

    Mockito.when(peerTask.getRequestMessage()).thenReturn(requestMessageData);
    Mockito.when(peerTask.getSubProtocol()).thenReturn(subprotocol);
    Mockito.when(peerTask.getRetriesWithOtherPeer()).thenReturn(0);
    Mockito.when(peerTask.getRetriesWithSamePeer()).thenReturn(0);
    Mockito.when(requestMessageData.getCode()).thenReturn(3); // GET_BLOCK_HEADERS

    Mockito.when(clusterManager.selectPeersForDispatch(3)).thenReturn(List.of(peer1, peer2, peer3));

    Mockito.when(requestSender.sendRequest(subprotocol, requestMessageData, peer1))
        .thenReturn(smallResponse);
    Mockito.when(requestSender.sendRequest(subprotocol, requestMessageData, peer2))
        .thenReturn(largeResponse);
    Mockito.when(requestSender.sendRequest(subprotocol, requestMessageData, peer3))
        .thenReturn(mediumResponse);

    Mockito.when(peerTask.processResponse(smallResponse)).thenReturn(smallResult);
    Mockito.when(peerTask.processResponse(largeResponse)).thenReturn(largeResult);
    Mockito.when(peerTask.processResponse(mediumResponse)).thenReturn(mediumResult);

    Mockito.when(peerTask.validateResult(smallResult))
        .thenReturn(PeerTaskValidationResponse.RESULTS_VALID_AND_GOOD);
    Mockito.when(peerTask.validateResult(largeResult))
        .thenReturn(PeerTaskValidationResponse.RESULTS_VALID_AND_GOOD);
    Mockito.when(peerTask.validateResult(mediumResult))
        .thenReturn(PeerTaskValidationResponse.RESULTS_VALID_AND_GOOD);

    PeerTaskExecutorResult<Object> result = executor.execute(peerTask);

    Assertions.assertEquals(PeerTaskExecutorResponseCode.SUCCESS, result.responseCode());
    Assertions.assertTrue(result.result().isPresent());
    // Winner should be the peer with the largest response (5000 bytes = largeResult)
    Assertions.assertSame(largeResult, result.result().get());
  }

  @Test
  public void clusterMetricsAreRecordedForAllResponses()
      throws PeerConnection.PeerNotConnected,
          ExecutionException,
          InterruptedException,
          TimeoutException,
          InvalidPeerTaskResponseException,
          MalformedRlpFromPeerException {

    EthPeer peer1 = Mockito.mock(EthPeer.class);
    EthPeer peer2 = Mockito.mock(EthPeer.class);

    MessageData resp1 = Mockito.mock(MessageData.class);
    MessageData resp2 = Mockito.mock(MessageData.class);

    Object result1 = new Object();
    Object result2 = new Object();

    Mockito.when(resp1.getSize()).thenReturn(200);
    Mockito.when(resp2.getSize()).thenReturn(100);

    Mockito.when(peerTask.getRequestMessage()).thenReturn(requestMessageData);
    Mockito.when(peerTask.getSubProtocol()).thenReturn(subprotocol);
    Mockito.when(peerTask.getRetriesWithOtherPeer()).thenReturn(0);
    Mockito.when(peerTask.getRetriesWithSamePeer()).thenReturn(0);
    Mockito.when(requestMessageData.getCode()).thenReturn(3);

    Mockito.when(clusterManager.selectPeersForDispatch(3)).thenReturn(List.of(peer1, peer2));

    Mockito.when(requestSender.sendRequest(subprotocol, requestMessageData, peer1))
        .thenReturn(resp1);
    Mockito.when(requestSender.sendRequest(subprotocol, requestMessageData, peer2))
        .thenReturn(resp2);

    Mockito.when(peerTask.processResponse(resp1)).thenReturn(result1);
    Mockito.when(peerTask.processResponse(resp2)).thenReturn(result2);

    Mockito.when(peerTask.validateResult(result1))
        .thenReturn(PeerTaskValidationResponse.RESULTS_VALID_AND_GOOD);
    Mockito.when(peerTask.validateResult(result2))
        .thenReturn(PeerTaskValidationResponse.RESULTS_VALID_AND_GOOD);

    executor.execute(peerTask);

    // Verify recordResponse was called for both peers
    Mockito.verify(clusterManager, Mockito.times(2))
        .recordResponse(
            Mockito.eq(3),
            Mockito.any(EthPeer.class),
            Mockito.anyLong(),
            Mockito.anyInt(),
            Mockito.anyBoolean());
  }

  @Test
  public void fallsBackToSinglePeerWhenClusterReturnsFewerThanTwoPeers() {
    Mockito.when(peerTask.getRequestMessage()).thenReturn(requestMessageData);
    Mockito.when(requestMessageData.getCode()).thenReturn(3);
    Mockito.when(clusterManager.selectPeersForDispatch(3)).thenReturn(List.of());

    // peerSelector returns nothing → NO_PEER_AVAILABLE
    Mockito.when(peerSelector.getPeer(Mockito.any())).thenReturn(java.util.Optional.empty());
    Mockito.when(peerTask.getRetriesWithOtherPeer()).thenReturn(0);

    PeerTaskExecutorResult<Object> result = executor.execute(peerTask);

    Assertions.assertEquals(PeerTaskExecutorResponseCode.NO_PEER_AVAILABLE, result.responseCode());
  }
}
