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

import java.util.Set;

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
   * Records that the given account's contract code was read <em>for execution</em> — e.g. an
   * EIP-7702 delegation designator read while resolving a call. This is witness-only metadata
   * (EIP-8025) and does not affect the consensus block access list. Default is a no-op for
   * implementations that do not track code reads.
   *
   * @param address the {@link Address} whose code was read for execution
   */
  default void addCodeRead(final Address address) {}

  /**
   * Returns the addresses whose code was recorded as read-for-execution via {@link #addCodeRead}.
   *
   * @return the code-read addresses (empty by default)
   */
  default Set<Address> getCodeReads() {
    return Set.of();
  }

  /**
   * Records that the given account's <em>pre-state</em> contract code was read from the parent
   * state — e.g. an EIP-7702 authority whose code is read while applying its authorization. Unlike
   * {@link #addCodeRead}, these reads are never dropped for an in-block code change, because they
   * genuinely fetched the pre-state code. Witness-only (EIP-8025); default is a no-op.
   *
   * @param address the {@link Address} whose pre-state code was read
   */
  default void addPreStateCodeRead(final Address address) {}

  /**
   * Returns the addresses whose pre-state code was recorded via {@link #addPreStateCodeRead}.
   *
   * @return the pre-state code-read addresses (empty by default)
   */
  default Set<Address> getPreStateCodeReads() {
    return Set.of();
  }

  /** Clears all tracked access list entries. */
  void clear();
}
