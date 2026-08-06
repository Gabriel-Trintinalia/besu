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
package org.hyperledger.besu.evm.witness;

import org.hyperledger.besu.datatypes.Address;

import java.util.Set;

/**
 * Tracks EIP-8025 witness metadata during transaction execution: contract code reads, pre-state
 * code reads, and the oldest BLOCKHASH ancestor accessed. Lives in the {@code evm} module so EVM
 * operations can call it without a cross-module dependency.
 */
public interface WitnessTracker {

  /**
   * Records that the given account's contract code was read for execution (e.g. call target,
   * EIP-7702 delegation designator, or EXTCODESIZE/EXTCODECOPY target).
   *
   * @param address the address whose code was read
   */
  void addCodeRead(Address address);

  /**
   * Records that the given account's <em>pre-state</em> contract code was read — e.g. an EIP-7702
   * authority whose code is read while applying its authorization. These reads are never filtered
   * by in-block code changes.
   *
   * @param address the address whose pre-state code was read
   */
  void addPreStateCodeRead(Address address);

  /**
   * Records that the given block number was accessed via BLOCKHASH. Updates the oldest-ancestor
   * window so the witness includes all headers from this block up to the current block.
   *
   * @param blockNumber the block number accessed via BLOCKHASH
   */
  void addOldestAncestor(long blockNumber);

  /**
   * Returns the addresses whose code was recorded as read-for-execution.
   *
   * @return the code-read addresses
   */
  Set<Address> getCodeReads();

  /**
   * Returns the addresses whose pre-state code was recorded.
   *
   * @return the pre-state code-read addresses
   */
  Set<Address> getPreStateCodeReads();

  /**
   * Returns the oldest block number whose header must appear in the witness, or {@link
   * Long#MAX_VALUE} if no BLOCKHASH was observed.
   *
   * @return the oldest accessed ancestor block number
   */
  long getOldestAccessedAncestor();
}
