/*
 * Copyright ConsenSys AG.
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
package org.hyperledger.besu.ethereum.core;

import static org.assertj.core.api.Assertions.assertThat;

import org.hyperledger.besu.ethereum.core.encoding.TransactionReceiptDecoder;
import org.hyperledger.besu.ethereum.core.encoding.TransactionReceiptEncoder;
import org.hyperledger.besu.ethereum.rlp.RLP;

import org.apache.tuweni.bytes.Bytes;
import org.junit.jupiter.api.Test;

public class TransactionReceiptTest {

  @Test
  public void toFromRlp() {
    final BlockDataGenerator gen = new BlockDataGenerator();
    final TransactionReceipt receipt = gen.receipt();
    final TransactionReceipt copy =
        TransactionReceiptDecoder.readFrom(
            RLP.input(
                RLP.encode(output -> TransactionReceiptEncoder.writeToForNetwork(receipt, output))),
            false);
    assertThat(copy).isEqualTo(receipt);
  }

  @Test
  public void toFromRlpWithReason() {
    final BlockDataGenerator gen = new BlockDataGenerator();
    final TransactionReceipt receipt = gen.receipt(Bytes.fromHexString("0x1122334455667788"));
    final TransactionReceipt copy =
        TransactionReceiptDecoder.readFrom(
            RLP.input(
                RLP.encode(
                    rlpOut ->
                        TransactionReceiptEncoder.writeToForReceiptTrie(
                            receipt, rlpOut, true, false))));
    assertThat(copy).isEqualTo(receipt);
  }

  @Test
  public void toFromRlpCompacted() {
    final BlockDataGenerator gen = new BlockDataGenerator();
    final TransactionReceipt receipt = gen.receipt(Bytes.fromHexString("0x1122334455667788"));
    final TransactionReceipt copy =
        TransactionReceiptDecoder.readFrom(
            RLP.input(
                RLP.encode(
                    rlpOut ->
                        TransactionReceiptEncoder.writeToForReceiptTrie(
                            receipt, rlpOut, false, true))));
    assertThat(copy).isEqualTo(receipt);
  }

  @Test
  public void toFromRlpCompactedWithReason() {
    final BlockDataGenerator gen = new BlockDataGenerator();
    final TransactionReceipt receipt = gen.receipt(Bytes.fromHexString("0x1122334455667788"));
    final TransactionReceipt copy =
        TransactionReceiptDecoder.readFrom(
            RLP.input(
                RLP.encode(
                    rlpOut ->
                        TransactionReceiptEncoder.writeToForReceiptTrie(
                            receipt, rlpOut, true, true))));
    assertThat(copy).isEqualTo(receipt);
  }

  @Test
  public void uncompactedAndCompactedDecodeToSameReceipt() {
    final BlockDataGenerator gen = new BlockDataGenerator();
    final TransactionReceipt receipt = gen.receipt(Bytes.fromHexString("0x1122334455667788"));
    final Bytes compactedReceipt =
        RLP.encode(
            rlpOut ->
                TransactionReceiptEncoder.writeToForReceiptTrie(receipt, rlpOut, false, true));
    final Bytes unCompactedReceipt =
        RLP.encode(
            rlpOut ->
                TransactionReceiptEncoder.writeToForReceiptTrie(receipt, rlpOut, false, false));
    assertThat(TransactionReceiptDecoder.readFrom(RLP.input(compactedReceipt))).isEqualTo(receipt);
    assertThat(TransactionReceiptDecoder.readFrom(RLP.input(unCompactedReceipt)))
        .isEqualTo(receipt);
  }
}
