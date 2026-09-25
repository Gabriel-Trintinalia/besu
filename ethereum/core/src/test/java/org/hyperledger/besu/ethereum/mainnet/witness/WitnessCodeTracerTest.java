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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.ethereum.core.Transaction;
import org.hyperledger.besu.evm.frame.MessageFrame;

import java.util.List;
import java.util.Set;

import org.apache.tuweni.bytes.Bytes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The EIP-8025 code reads collected from the EVM's code events, across a block. */
class WitnessCodeTracerTest {

  private static final Address ADDR_1 = Address.fromHexString("0x1001");
  private static final Address ADDR_2 = Address.fromHexString("0x1002");
  private static final Hash CODE_A = Hash.hash(Bytes.of(0x00));
  private static final Hash CODE_B = Hash.hash(Bytes.of(0x01));

  private WitnessCodeTracer tracer;
  private final Transaction tx = mock(Transaction.class);

  @BeforeEach
  void setUp() {
    tracer = new WitnessCodeTracer();
  }

  @Test
  void readsOfATransactionAreCollected() {
    tracer.tracePrepareTransaction(null, tx);
    tracer.traceCodeRead(ADDR_1, CODE_A);
    endTransaction(tracer);

    assertThat(tracer.codeReads()).containsExactly(ADDR_1);
  }

  @Test
  void readOfEmptyCodeIsNotCollected() {
    tracer.tracePrepareTransaction(null, tx);
    tracer.traceCodeRead(ADDR_1, Hash.EMPTY);
    endTransaction(tracer);

    assertThat(tracer.codeReads()).isEmpty();
  }

  @Test
  void codeWrittenEarlierInTheTransactionSatisfiesARead() {
    tracer.tracePrepareTransaction(null, tx);
    tracer.traceCodeWrite(ADDR_1, CODE_A);
    tracer.traceCodeRead(ADDR_2, CODE_A);
    tracer.traceCodeRead(ADDR_1, CODE_B);
    endTransaction(tracer);

    assertThat(tracer.codeReads()).containsExactly(ADDR_1);
  }

  @Test
  void readBeforeAWriteOfTheSameCodeIsKept() {
    tracer.tracePrepareTransaction(null, tx);
    tracer.traceCodeRead(ADDR_1, CODE_A);
    tracer.traceCodeWrite(ADDR_1, CODE_A);
    endTransaction(tracer);

    assertThat(tracer.codeReads()).containsExactly(ADDR_1);
  }

  @Test
  void revertOfALaterFrameKeepsAnEarlierTransactionsWrite() {
    tracer.tracePrepareTransaction(null, tx);
    tracer.traceCodeWrite(ADDR_1, CODE_A);
    endTransaction(tracer);
    // The same code written again by a frame that reverts: the earlier write still stands.
    tracer.tracePrepareTransaction(null, tx);
    final MessageFrame top = frame(MessageFrame.State.COMPLETED_FAILED);
    tracer.traceContextEnter(top);
    tracer.traceCodeWrite(ADDR_2, CODE_A);
    tracer.traceContextExit(top);
    tracer.traceCodeRead(ADDR_2, CODE_A);
    endTransaction(tracer);

    assertThat(tracer.codeReads()).isEmpty();
  }

  @Test
  void codeWrittenByAnEarlierTransactionSatisfiesALaterRead() {
    tracer.tracePrepareTransaction(null, tx);
    tracer.traceCodeWrite(ADDR_1, CODE_A);
    endTransaction(tracer);
    tracer.tracePrepareTransaction(null, tx);
    tracer.traceCodeRead(ADDR_2, CODE_A);
    tracer.traceCodeRead(ADDR_1, CODE_B);
    endTransaction(tracer);

    assertThat(tracer.codeReads()).containsExactly(ADDR_1);
  }

  @Test
  void writeOfARevertedFrameIsUndone() {
    tracer.tracePrepareTransaction(null, tx);
    final MessageFrame top = frame(MessageFrame.State.COMPLETED_SUCCESS);
    final MessageFrame child = frame(MessageFrame.State.COMPLETED_FAILED);
    tracer.traceContextEnter(top);
    tracer.traceContextEnter(child);
    tracer.traceCodeWrite(ADDR_1, CODE_A);
    tracer.traceContextExit(child);
    tracer.traceCodeRead(ADDR_2, CODE_A);
    tracer.traceContextExit(top);
    endTransaction(tracer);

    assertThat(tracer.codeReads()).containsExactly(ADDR_2);
  }

  @Test
  void writeOfASuccessfulChildIsUndoneWithItsRevertingParent() {
    tracer.tracePrepareTransaction(null, tx);
    final MessageFrame top = frame(MessageFrame.State.COMPLETED_FAILED);
    final MessageFrame child = frame(MessageFrame.State.COMPLETED_SUCCESS);
    tracer.traceContextEnter(top);
    tracer.traceContextEnter(child);
    tracer.traceCodeWrite(ADDR_1, CODE_A);
    tracer.traceContextExit(child);
    tracer.traceContextExit(top);
    endTransaction(tracer);
    tracer.tracePrepareTransaction(null, tx);
    tracer.traceCodeRead(ADDR_2, CODE_A);
    endTransaction(tracer);

    assertThat(tracer.codeReads()).containsExactly(ADDR_2);
  }

  @Test
  void readOfARevertedFrameIsKept() {
    tracer.tracePrepareTransaction(null, tx);
    final MessageFrame top = frame(MessageFrame.State.COMPLETED_FAILED);
    tracer.traceContextEnter(top);
    tracer.traceCodeRead(ADDR_1, CODE_A);
    tracer.traceContextExit(top);
    endTransaction(tracer);

    assertThat(tracer.codeReads()).containsExactly(ADDR_1);
  }

  @Test
  void delegationWritesSurviveATopFrameRevertButNotAPreparationRollback() {
    // A delegation written in preparation survives the top frame's revert...
    tracer.tracePrepareTransaction(null, tx);
    tracer.traceCodeWrite(ADDR_1, CODE_A);
    final MessageFrame top = frame(MessageFrame.State.COMPLETED_FAILED);
    tracer.traceContextEnter(top);
    tracer.traceContextExit(top);
    endTransaction(tracer);
    // ...but not a failed preparation, whose top frame never enters.
    tracer.tracePrepareTransaction(null, tx);
    tracer.traceCodeRead(ADDR_1, CODE_B);
    tracer.traceCodeWrite(ADDR_1, CODE_B);
    tracer.traceTransactionPreparationRolledBack();
    final MessageFrame halted = frame(MessageFrame.State.COMPLETED_FAILED);
    tracer.traceContextReEnter(halted);
    tracer.traceContextExit(halted);
    endTransaction(tracer);
    tracer.tracePrepareTransaction(null, tx);
    tracer.traceCodeRead(ADDR_2, CODE_A);
    tracer.traceCodeRead(ADDR_2, CODE_B);
    endTransaction(tracer);

    assertThat(tracer.codeReads()).containsExactlyInAnyOrder(ADDR_1, ADDR_2);
  }

  @Test
  void systemCallReadsAreCollected() {
    final MessageFrame systemCall = frame(MessageFrame.State.COMPLETED_SUCCESS);
    tracer.traceContextEnter(systemCall);
    tracer.traceCodeRead(ADDR_1, CODE_A);
    tracer.traceContextExit(systemCall);

    assertThat(tracer.codeReads()).containsExactly(ADDR_1);
    assertThat(tracer.isSystemCallTracingEnabled()).isTrue();
    assertThat(tracer.isExtendedTracing()).isFalse();
  }

  private void endTransaction(final WitnessCodeTracer operationTracer) {
    operationTracer.traceEndTransaction(null, tx, true, Bytes.EMPTY, List.of(), 0L, Set.of(), 0L);
  }

  private static MessageFrame frame(final MessageFrame.State exitState) {
    final MessageFrame frame = mock(MessageFrame.class);
    when(frame.getState()).thenReturn(exitState);
    return frame;
  }
}
