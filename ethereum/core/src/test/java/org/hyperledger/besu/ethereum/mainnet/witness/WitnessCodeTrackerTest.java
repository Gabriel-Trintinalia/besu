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

import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.ethereum.mainnet.block.access.list.AccessLocationTracker;
import org.hyperledger.besu.ethereum.mainnet.block.access.list.BlockAccessList;
import org.hyperledger.besu.ethereum.mainnet.block.access.list.PartialBlockAccessView;
import org.hyperledger.besu.ethereum.mainnet.witness.WitnessCodeTracker.CodeAccesses;
import org.hyperledger.besu.ethereum.mainnet.witness.WitnessCodeTracker.CodeRead;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.apache.tuweni.bytes.Bytes;
import org.junit.jupiter.api.Test;

/** EIP-8025 code-read tracking, mirroring the EELS get_code/set_code state. */
class WitnessCodeTrackerTest {

  private static final Address ADDR_1 =
      Address.fromHexString("0x1000000000000000000000000000000000000001");
  private static final Address ADDR_2 =
      Address.fromHexString("0x2000000000000000000000000000000000000002");
  private static final Hash CODE_A = Hash.hash(Bytes.of(0x00));
  private static final Hash CODE_B = Hash.hash(Bytes.of(0x01));

  @Test
  void emptyCodeReadIsNotRecorded() {
    final WitnessCodeTracker tracker = new WitnessCodeTracker();
    tracker.addCodeRead(ADDR_1, Hash.EMPTY);

    assertThat(tracker.accesses().reads()).isEmpty();
  }

  @Test
  void readOfCodeWrittenEarlierInTransactionIsNotRecorded() {
    final WitnessCodeTracker tracker = new WitnessCodeTracker();
    tracker.addCodeWrite(CODE_A);
    tracker.addCodeRead(ADDR_1, CODE_A);
    tracker.addCodeRead(ADDR_2, CODE_B);

    assertThat(tracker.accesses().reads()).containsExactly(new CodeRead(ADDR_2, CODE_B));
  }

  @Test
  void readBeforeWriteOfSameCodeIsKept() {
    final WitnessCodeTracker tracker = new WitnessCodeTracker();
    tracker.addCodeRead(ADDR_1, CODE_A);
    tracker.addCodeWrite(CODE_A);

    assertThat(tracker.accesses().reads()).containsExactly(new CodeRead(ADDR_1, CODE_A));
  }

  @Test
  void rolledBackCodeWriteNoLongerSatisfiesReads() {
    final WitnessCodeTracker tracker = new WitnessCodeTracker();
    final long mark = tracker.codeWriteMark();
    tracker.addCodeWrite(CODE_A);
    tracker.rollbackCodeWrites(mark);
    tracker.addCodeRead(ADDR_1, CODE_A);

    assertThat(tracker.accesses().writes()).isEmpty();
    assertThat(tracker.accesses().reads()).containsExactly(new CodeRead(ADDR_1, CODE_A));
  }

  @Test
  void accumulatorDropsReadsOfCodeWrittenByEarlierTransactions() {
    final WitnessCodeAccumulator accumulator = new WitnessCodeAccumulator();
    accumulator.apply(new CodeAccesses(Set.of(), Set.of(CODE_A)));
    accumulator.apply(
        new CodeAccesses(
            Set.of(new CodeRead(ADDR_1, CODE_A), new CodeRead(ADDR_2, CODE_B)), Set.of()));

    assertThat(accumulator.toWitnessCodeReads().codeReads()).containsExactly(ADDR_2);
  }

  @Test
  void accumulatorKeepsReadsOfCodeWrittenByLaterTransactions() {
    final WitnessCodeAccumulator accumulator = new WitnessCodeAccumulator();
    accumulator.apply(new CodeAccesses(Set.of(new CodeRead(ADDR_1, CODE_A)), Set.of()));
    accumulator.apply(new CodeAccesses(Set.of(), Set.of(CODE_A)));

    assertThat(accumulator.toWitnessCodeReads().codeReads()).containsExactly(ADDR_1);
  }

  @Test
  void witnessBuilderCollectsCodeReadsFromItsTrackers() {
    final BlockAccessList.BlockAccessListBuilder builder =
        BlockAccessList.builderWithWitnessCodeReads();
    final AccessLocationTracker tracker = builder.createTransactionAccessLocationTracker(0);
    tracker.addCodeRead(ADDR_1, CODE_A);
    builder.apply(
        new PartialBlockAccessView(
            List.of(), 1, tracker.getWitnessCodeTracker().map(WitnessCodeTracker::accesses)));

    assertThat(builder.getWitnessCodeReads())
        .hasValueSatisfying(reads -> assertThat(reads.codeReads()).containsExactly(ADDR_1));
  }

  @Test
  void plainBuilderTracksNoWitnessCodeReads() {
    final BlockAccessList.BlockAccessListBuilder builder = BlockAccessList.builder();
    final AccessLocationTracker tracker = builder.createTransactionAccessLocationTracker(0);
    tracker.addCodeRead(ADDR_1, CODE_A);

    assertThat(tracker.getWitnessCodeTracker()).isEmpty();
    builder.apply(
        new PartialBlockAccessView(
            List.of(),
            1,
            Optional.of(new CodeAccesses(Set.of(new CodeRead(ADDR_1, CODE_A)), Set.of()))));
    assertThat(builder.getWitnessCodeReads()).isEmpty();
  }
}
