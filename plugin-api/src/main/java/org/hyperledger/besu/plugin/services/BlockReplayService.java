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
package org.hyperledger.besu.plugin.services;

import org.hyperledger.besu.plugin.Unstable;
import org.hyperledger.besu.plugin.data.BlockProcessingResult;
import org.hyperledger.besu.plugin.services.tracer.BlockAwareOperationTracer;

/** A service for replaying blocks in the Ethereum blockchain. */
@Unstable
public interface BlockReplayService extends BesuService {
  /**
   * Replays the block at the specified block number, using the provided tracer to monitor
   * execution.
   *
   * @param blockNumber the number of the block to replay
   * @param tracer the tracer to use during block processing
   * @return the result of processing the block
   */
  BlockProcessingResult replay(final long blockNumber, final BlockAwareOperationTracer tracer);
}
