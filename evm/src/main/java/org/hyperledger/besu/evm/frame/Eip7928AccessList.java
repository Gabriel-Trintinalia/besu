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
 * Interface for tracking accessed accounts and storage slots during transaction execution for the
 * purpose of generating EIP-7928 Block Access Lists.
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

  /**
   * Records that the given account's contract code was read during execution, for EIP-8025
   * execution witness generation. Shares the same accessed-locations plumbing as the EIP-7928 block
   * access list rather than a separate tracker.
   *
   * @param address the address whose code was read
   */
  void addCodeRead(final Address address);

  /**
   * Records that the given account's contract code was read during EIP-7702 SET_CODE authorization
   * processing, before EVM execution begins, for EIP-8025 execution witness generation.
   *
   * @param address the address whose code was read during authorization
   */
  void addAuthorizationCodeRead(final Address address);

  /** Clears all tracked access list entries. */
  void clear();
}
