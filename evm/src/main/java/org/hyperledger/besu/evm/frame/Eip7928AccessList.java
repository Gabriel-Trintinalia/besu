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
package org.hyperledger.besu.evm.frame;

import org.hyperledger.besu.datatypes.Address;

import org.apache.tuweni.units.bigints.UInt256;

/**
 * Observes state accesses made during execution.
 *
 * <p>Two consumers share this channel and apply different rules to it. The EIP-7928 block access
 * list uses the account and slot events; the EIP-8025 execution witness uses the code-read events.
 * They are recorded together because the observation points largely coincide, and because a single
 * channel is threaded through the frame stack and the parallel transaction executors exactly once —
 * a second, parallel channel is what allowed witness collection to silently miss speculatively
 * executed transactions.
 *
 * <p>The code-read methods default to no-ops so implementations that only build a block access list
 * need not know about them.
 */
public interface Eip7928AccessList {

  /**
   * Adds an account address to the access list.
   *
   * <p>Indicates that the given account was accessed (read or written) during execution. Repeated
   * additions of the same address should have no effect.
   *
   * @param address the {@link Address} of the account that was accessed
   */
  void addTouchedAccount(final Address address);

  /**
   * Adds a specific storage slot access for the given account to the access list.
   *
   * <p>Indicates that the specified storage key for the account was accessed. Repeated additions of
   * the same (account, slot) pair should have no effect.
   *
   * @param address the {@link Address} of the account whose storage was accessed
   * @param slotKey the {@link UInt256} key of the storage slot accessed
   */
  void addSlotAccessForAccount(final Address address, final UInt256 slotKey);

  /** Clears all tracked access list entries. */
  void clear();
}
