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

/**
 * Wraps an {@link EthPeer} with cluster-specific performance metrics (EMA latency, EMA bytes,
 * correctness ratio) and computes a composite score used for cluster ranking.
 */
class ClusteredPeer {

  private static final double ALPHA = 0.2; // EMA smoothing factor
  private static final double INITIAL_LATENCY_MS = 500.0; // start conservative

  private final EthPeer ethPeer;
  private double emaLatencyMs = INITIAL_LATENCY_MS;
  private double emaResponseBytes = 0.0;
  private int correctnessCount = 0;
  private int totalResponseCount = 0;

  ClusteredPeer(final EthPeer ethPeer) {
    this.ethPeer = ethPeer;
  }

  /**
   * Updates the EMA metrics for this peer after a response is received.
   *
   * @param latencyMs the observed round-trip latency in milliseconds
   * @param bytes the number of bytes in the response
   * @param valid whether the response was valid and useful
   */
  void updateMetrics(final long latencyMs, final int bytes, final boolean valid) {
    emaLatencyMs = ALPHA * latencyMs + (1 - ALPHA) * emaLatencyMs;
    emaResponseBytes = ALPHA * bytes + (1 - ALPHA) * emaResponseBytes;
    totalResponseCount++;
    if (valid) {
      correctnessCount++;
    }
  }

  /**
   * Computes a composite score for this peer. Higher is better.
   *
   * <p>score = correctnessRatio * emaResponseBytes / max(emaLatencyMs, 1)
   *
   * @return the composite score
   */
  double getCompositeScore() {
    if (totalResponseCount == 0) {
      return 0.0;
    }
    double correctnessRatio = (double) correctnessCount / totalResponseCount;
    return correctnessRatio * emaResponseBytes / Math.max(emaLatencyMs, 1.0);
  }

  EthPeer getEthPeer() {
    return ethPeer;
  }
}
