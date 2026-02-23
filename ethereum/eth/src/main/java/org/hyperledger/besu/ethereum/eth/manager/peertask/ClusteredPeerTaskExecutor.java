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
import org.hyperledger.besu.ethereum.p2p.rlpx.wire.MessageData;
import org.hyperledger.besu.ethereum.p2p.rlpx.wire.SubProtocol;
import org.hyperledger.besu.plugin.services.MetricsSystem;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Extends {@link PeerTaskExecutor} to dispatch each request concurrently to multiple peers
 * (selected via {@link PeerClusterManager}). A request is considered complete once at least 2 peers
 * respond; the response with the most bytes among valid responses wins.
 *
 * <p>Falls back to the single-peer {@link PeerTaskExecutor#execute} if fewer than 2 cluster peers
 * are available or if all parallel responses are invalid.
 */
public class ClusteredPeerTaskExecutor extends PeerTaskExecutor {

  private static final Logger LOG = LoggerFactory.getLogger(ClusteredPeerTaskExecutor.class);
  private static final long CLUSTER_TIMEOUT_MS = 7_000;

  private final PeerClusterManager clusterManager;
  // Stored separately because the parent field is private
  private final PeerTaskRequestSender requestSender;
  // Shared across all execute() calls to avoid per-request thread churn
  private final ExecutorService dispatchPool = Executors.newCachedThreadPool();

  public ClusteredPeerTaskExecutor(
      final PeerSelector peerSelector,
      final PeerTaskRequestSender requestSender,
      final MetricsSystem metricsSystem,
      final PeerClusterManager clusterManager) {
    super(peerSelector, requestSender, metricsSystem);
    this.requestSender = requestSender;
    this.clusterManager = clusterManager;
  }

  @Override
  public <T> PeerTaskExecutorResult<T> execute(final PeerTask<T> peerTask) {
    int messageCode = peerTask.getRequestMessage().getCode();
    List<EthPeer> selectedPeers = clusterManager.selectPeersForDispatch(messageCode);

    if (selectedPeers.size() < 2) {
      // Not enough cluster peers — fall back to single-peer executor
      return super.execute(peerTask);
    }

    List<CompletableFuture<PeerDispatchResult<T>>> futures =
        selectedPeers.stream()
            .map(
                peer ->
                    CompletableFuture.supplyAsync(
                        () -> dispatchToPeer(peerTask, peer), dispatchPool))
            .collect(Collectors.toList());

    List<PeerDispatchResult<T>> results = awaitAtLeastTwo(futures);

    // Record cluster metrics for all received responses
    results.forEach(
        r ->
            clusterManager.recordResponse(
                messageCode, r.peer(), r.latencyMs(), r.bytes(), r.isValid()));

    // Pick the valid response with the most bytes
    Optional<PeerDispatchResult<T>> winner =
        results.stream()
            .filter(PeerDispatchResult::isValid)
            .max(Comparator.comparingInt(PeerDispatchResult::bytes));

    if (winner.isEmpty()) {
      LOG.debug(
          "Clustered dispatch produced no valid results for message code {}; falling back",
          messageCode);
      return super.execute(peerTask);
    }

    List<EthPeer> usedPeers =
        results.stream().map(PeerDispatchResult::peer).collect(Collectors.toList());
    return new PeerTaskExecutorResult<>(
        Optional.of(winner.get().result()), PeerTaskExecutorResponseCode.SUCCESS, usedPeers);
  }

  /**
   * Dispatches the task to a single peer, capturing timing, byte count, and validation result.
   * Never throws — exceptions are swallowed and result in an invalid {@link PeerDispatchResult}.
   */
  private <T> PeerDispatchResult<T> dispatchToPeer(final PeerTask<T> task, final EthPeer peer) {
    long startMs = System.currentTimeMillis();
    SubProtocol subProtocol = task.getSubProtocol();
    MessageData requestMessageData = task.getRequestMessage();

    try {
      MessageData responseMessageData =
          requestSender.sendRequest(subProtocol, requestMessageData, peer);

      if (responseMessageData == null) {
        peer.recordUselessResponse(task.getClass().getSimpleName());
        return invalid(peer, startMs);
      }

      int bytes = responseMessageData.getSize();
      T result = task.processResponse(responseMessageData);
      PeerTaskValidationResponse validation = task.validateResult(result);
      boolean valid = validation == PeerTaskValidationResponse.RESULTS_VALID_AND_GOOD;

      if (valid) {
        peer.recordUsefulResponse();
      } else {
        if (validation.recordUselessResponse()) {
          peer.recordUselessResponse(task.getClass().getSimpleName());
        }
        validation.getDisconnectReason().ifPresent(peer::disconnect);
      }

      long latencyMs = System.currentTimeMillis() - startMs;
      return new PeerDispatchResult<>(peer, result, latencyMs, bytes, valid);

    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      peer.recordRequestTimeout(subProtocol.getName(), requestMessageData.getCode());
      return invalid(peer, startMs);
    } catch (Exception e) {
      LOG.debug(
          "Clustered dispatch error for {} from peer {}: {}",
          task.getClass().getSimpleName(),
          peer.getLoggableId(),
          e.getMessage());
      return invalid(peer, startMs);
    }
  }

  private <T> PeerDispatchResult<T> invalid(final EthPeer peer, final long startMs) {
    return new PeerDispatchResult<>(peer, null, System.currentTimeMillis() - startMs, 0, false);
  }

  /**
   * Waits for all futures to complete (or {@code timeoutMs} to elapse), then returns all
   * successfully completed results. Using {@code allOf} is simpler than a latch and avoids the
   * callback-registration race; we still bound the wait with the cluster timeout so a slow peer
   * never blocks the caller indefinitely.
   */
  private <T> List<T> awaitAtLeastTwo(final List<CompletableFuture<T>> futures) {
    try {
      CompletableFuture.allOf(futures.toArray(new CompletableFuture<?>[0]))
          .get(ClusteredPeerTaskExecutor.CLUSTER_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    } catch (TimeoutException ignored) {
      // collect whatever finished within the window
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } catch (ExecutionException ignored) {
      // individual failures are captured inside dispatchToPeer; skip here
    }

    return futures.stream()
        .filter(CompletableFuture::isDone)
        .filter(f -> !f.isCompletedExceptionally())
        .map(f -> f.getNow(null))
        .filter(Objects::nonNull)
        .collect(Collectors.toList());
  }
}
