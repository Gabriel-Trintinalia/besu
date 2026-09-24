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
import org.hyperledger.besu.datatypes.Hash;

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
   * execution witness generation. Not part of the EIP-7928 block access list: the code-read hooks
   * only share its per-frame plumbing, and are no-ops unless the block is processed for a witness.
   *
   * <p>Mirrors EELS {@code get_code}: a read whose code hash has already been written earlier in
   * the transaction (see {@link #addCodeWrite}) is satisfied from those writes and not recorded, as
   * is a read of empty code. Reads satisfied by writes of earlier transactions in the block are
   * dropped when the transaction's view is applied to the block.
   *
   * @param address the address whose code was read
   * @param codeHash the hash of the code read, as it was at the time of the read
   */
  void addCodeRead(final Address address, final Hash codeHash);

  /**
   * Records that code with the given hash was written during this transaction — a successful
   * CREATE/CREATE2 code deposit, or an EIP-7702 delegation designator — for EIP-8025 execution
   * witness generation, mirroring EELS {@code code_writes}. Keyed by hash, not address: a later
   * read of any account holding the same code is satisfied from the write, since a stateless
   * verifier already has those bytes from the block body. Writes of empty code are ignored.
   *
   * <p>Writes are journaled and undone by {@link #rollbackCodeWrites} when the writing frame, or an
   * enclosing one, reverts.
   *
   * @param codeHash the hash of the code written
   */
  void addCodeWrite(final Hash codeHash);

  /**
   * Undoes the code writes recorded after the given undo mark, when a frame reverts. Code reads and
   * account/slot accesses are deliberately not undone.
   *
   * @param mark the undo mark to roll back to
   */
  void rollbackCodeWrites(final long mark);

  /** Clears all tracked access list entries. */
  void clear();
}
