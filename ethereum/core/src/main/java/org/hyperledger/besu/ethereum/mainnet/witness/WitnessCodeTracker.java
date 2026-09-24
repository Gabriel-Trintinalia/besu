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
package org.hyperledger.besu.ethereum.mainnet.witness;

import org.hyperledger.besu.collections.undo.UndoSet;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Hash;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One transaction's EIP-8025 code reads, mirroring the EELS transaction state: {@code code_reads}
 * survive reverts, while {@code code_writes} are journaled and undone with the frame that made
 * them. A read whose code is empty or was already written in the transaction needs no witness
 * entry, as in EELS {@code get_code}.
 *
 * <p>Reached through the transaction's {@code AccessLocationTracker}, so it rides the EIP-7928
 * per-transaction plumbing, but is only attached when the block is processed for a witness.
 */
public class WitnessCodeTracker {

  /**
   * An EIP-8025 code read: the account read and the hash of its code at the time of the read.
   *
   * @param address the account whose code was read
   * @param codeHash the hash of the code read
   */
  public record CodeRead(Address address, Hash codeHash) {}

  /**
   * What one transaction read and wrote, for {@link WitnessCodeAccumulator}.
   *
   * @param reads the reads the transaction's own writes did not satisfy
   * @param writes the hashes of the code the transaction wrote that survived any reverts
   */
  public record CodeAccesses(Set<CodeRead> reads, Set<Hash> writes) {}

  private final Set<CodeRead> codeReads = ConcurrentHashMap.newKeySet();
  private final UndoSet<Hash> codeWrites = UndoSet.of(new HashSet<>());

  public void addCodeRead(final Address address, final Hash codeHash) {
    if (Hash.EMPTY.equals(codeHash) || codeWrites.contains(codeHash)) {
      return;
    }
    codeReads.add(new CodeRead(address, codeHash));
  }

  public void addCodeWrite(final Hash codeHash) {
    if (!Hash.EMPTY.equals(codeHash)) {
      codeWrites.add(codeHash);
    }
  }

  public void rollbackCodeWrites(final long mark) {
    codeWrites.undo(mark);
  }

  /**
   * Returns an undo mark for the code writes, for a later {@link #rollbackCodeWrites} to return to.
   * Used where a rollback is not driven by a message frame, e.g. a failed top-frame preparation.
   *
   * @return the current undo mark
   */
  public long codeWriteMark() {
    return codeWrites.mark();
  }

  public void clear() {
    codeReads.clear();
    codeWrites.clear();
  }

  public CodeAccesses accesses() {
    return new CodeAccesses(Set.copyOf(codeReads), Set.copyOf(codeWrites));
  }
}
