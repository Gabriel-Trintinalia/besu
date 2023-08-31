/*
 * Copyright Hyperledger Besu Contributors.
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
package org.hyperledger.besu.ethereum.core.encoding;

import static org.hyperledger.besu.ethereum.core.encoding.AccessListTransactionEncoder.writeAccessList;
import static org.hyperledger.besu.ethereum.core.encoding.TransactionEncoder.writeSignatureAndRecoveryId;

import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.ethereum.core.Transaction;
import org.hyperledger.besu.ethereum.core.encoding.TransactionEncoder.Encoder;
import org.hyperledger.besu.ethereum.rlp.RLPOutput;

public class EIP1559TransactionEncoder implements Encoder {

  @Override
  public void encode(final Transaction transaction, final RLPOutput output,
      final EncodingContext context) {
    encodeEIP1559(transaction, output);
  }

  static void encodeEIP1559(final Transaction transaction, final RLPOutput out) {
    out.startList();
    out.writeBigIntegerScalar(transaction.getChainId().orElseThrow());
    out.writeLongScalar(transaction.getNonce());
    out.writeUInt256Scalar(transaction.getMaxPriorityFeePerGas().orElseThrow());
    out.writeUInt256Scalar(transaction.getMaxFeePerGas().orElseThrow());
    out.writeLongScalar(transaction.getGasLimit());
    out.writeBytes(transaction.getTo().map(Bytes::copy).orElse(Bytes.EMPTY));
    out.writeUInt256Scalar(transaction.getValue());
    out.writeBytes(transaction.getPayload());
    writeAccessList(out, transaction.getAccessList());
    writeSignatureAndRecoveryId(transaction, out);
    out.endList();
  }
}
