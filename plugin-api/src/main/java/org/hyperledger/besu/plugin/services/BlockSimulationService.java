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
package org.hyperledger.besu.plugin.services;

import org.hyperledger.besu.datatypes.BlockOverrides;
import org.hyperledger.besu.datatypes.Transaction;
import org.hyperledger.besu.plugin.data.BlockHeader;
import org.hyperledger.besu.plugin.data.BlockSimulationResult;

import java.util.List;

public interface BlockSimulationService extends BesuService {
  /**
   * Simulate the creation of a block given a parent header, a list of transactions, and a
   * timestamp.
   *
   * @param parentHeader the parent header
   * @param transactions the transactions to include in the block
   * @param blockOverrides the blockSimulationOverride of the block
   * @return the block context
   */
  BlockSimulationResult simulate(
      final BlockHeader parentHeader,
      final List<? extends Transaction> transactions,
      final BlockOverrides blockOverrides);

  BlockSimulationResult simulateAndPersist(
      BlockHeader header, List<? extends Transaction> transactions, BlockOverrides blockOverrides);
}
