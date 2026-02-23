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
 * Captures the outcome of dispatching a {@link PeerTask} to a single peer in parallel mode.
 *
 * @param peer the peer that handled the request
 * @param result the decoded response, or {@code null} on failure
 * @param latencyMs round-trip latency in milliseconds
 * @param bytes number of bytes in the raw response message
 * @param isValid whether the response passed validation
 */
record PeerDispatchResult<T>(EthPeer peer, T result, long latencyMs, int bytes, boolean isValid) {}
