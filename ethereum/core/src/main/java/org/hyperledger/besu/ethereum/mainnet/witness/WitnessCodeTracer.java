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
import org.hyperledger.besu.datatypes.Transaction;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.worldstate.WorldView;
import org.hyperledger.besu.plugin.services.tracer.BlockAwareOperationTracer;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;

/**
 * Collects the EIP-8025 code reads of a block from the code events the EVM emits ({@link
 * #traceCodeRead}, {@link #traceCodeWrite}), mirroring EELS {@code get_code}/{@code set_code}: a
 * read needs a witness entry unless its code is empty or was written earlier in the block, since a
 * stateless verifier already has written code from the block itself. Writes are keyed by code hash,
 * so any account holding written code is satisfied by the write.
 *
 * <p>Reads survive reverts; writes are undone with the frame, or the failed top-frame preparation,
 * that made them. Undoing only goes back to a mark taken when that frame or transaction started, so
 * the writes of earlier transactions, whose frames have all completed, are never affected.
 *
 * <p>Nothing is inferred: every read and write is reported by the code performing it. Events must
 * arrive in block order, as they do when the block is processed sequentially with this tracer as
 * its explicit tracer. {@link #codeReads()} holds the result once the block is processed.
 */
public class WitnessCodeTracer implements BlockAwareOperationTracer {

  private record FrameMark(MessageFrame frame, long mark) {}

  private final Set<Address> codeReads = new HashSet<>();
  private final UndoSet<Hash> codeWrites = UndoSet.of(new HashSet<>());

  private long preparationMark;
  private final Deque<FrameMark> frameMarks = new ArrayDeque<>();

  /**
   * Returns the accounts whose code the witness has to supply, for everything traced so far: the
   * block's, once it is processed.
   *
   * @return the addresses whose pre-state bytecode the witness needs
   */
  public Set<Address> codeReads() {
    return Set.copyOf(codeReads);
  }

  @Override
  public void tracePrepareTransaction(final WorldView worldView, final Transaction transaction) {
    frameMarks.clear();
    preparationMark = codeWrites.mark();
  }

  @Override
  public void traceContextEnter(final MessageFrame frame) {
    frameMarks.push(new FrameMark(frame, codeWrites.mark()));
  }

  @Override
  public void traceContextExit(final MessageFrame frame) {
    // A top frame whose preparation failed never entered, so it has no mark.
    if (frameMarks.isEmpty() || frameMarks.peek().frame() != frame) {
      return;
    }
    final long mark = frameMarks.pop().mark();
    if (frame.getState() == MessageFrame.State.COMPLETED_FAILED) {
      codeWrites.undo(mark);
    }
  }

  @Override
  public void traceCodeRead(final Address address, final Hash codeHash) {
    if (!Hash.EMPTY.equals(codeHash) && !codeWrites.contains(codeHash)) {
      codeReads.add(address);
    }
  }

  @Override
  public void traceCodeWrite(final Address address, final Hash codeHash) {
    if (!Hash.EMPTY.equals(codeHash)) {
      codeWrites.add(codeHash);
    }
  }

  @Override
  public void traceTransactionPreparationRolledBack() {
    codeWrites.undo(preparationMark);
  }

  @Override
  public boolean isSystemCallTracingEnabled() {
    // System contracts' code is read, so belongs in the witness.
    return true;
  }

  @Override
  public boolean isExtendedTracing() {
    return false;
  }
}
