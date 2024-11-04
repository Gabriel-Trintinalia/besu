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
package org.hyperledger.besu.ethereum.core.encoding;

import org.hyperledger.besu.ethereum.core.BlockHeader;
import org.hyperledger.besu.ethereum.rlp.RLPOutput;

public class BlockHeaderEncoder {

  /**
   * Write an RLP representation.
   *
   * @param out The RLP output to write to
   */
  public static void writeTo(final BlockHeader blockHeader, final RLPOutput out) {
    out.startList();

    out.writeBytes(blockHeader.getBlockHash());
    out.writeBytes(blockHeader.getOmmersHash());
    out.writeBytes(blockHeader.getCoinbase());
    out.writeBytes(blockHeader.getOmmersHash());
    out.writeBytes(blockHeader.getTransactionsRoot());
    out.writeBytes(blockHeader.getReceiptsRoot());
    out.writeBytes(blockHeader.getLogsBloom());
    out.writeUInt256Scalar(blockHeader.getDifficulty());
    out.writeLongScalar(blockHeader.getNumber());
    out.writeLongScalar(blockHeader.getGasLimit());
    out.writeLongScalar(blockHeader.getGasUsed());
    out.writeLongScalar(blockHeader.getTimestamp());
    out.writeBytes(blockHeader.getExtraData());
    out.writeBytes(blockHeader.getMixHashOrPrevRandao());
    out.writeLong(blockHeader.getNonce());
    do {
      if (blockHeader.getBaseFee().isEmpty()) break;
      out.writeUInt256Scalar(blockHeader.getBaseFee().get());

      if (blockHeader.getWithdrawalsRoot().isEmpty()) break;
      out.writeBytes(blockHeader.getWithdrawalsRoot().get());

      if (blockHeader.getExcessBlobGas().isEmpty() || blockHeader.getBlobGasUsed().isEmpty()) break;
      out.writeLongScalar(blockHeader.getBlobGasUsed().get());
      out.writeUInt64Scalar(blockHeader.getExcessBlobGas().get());

      if (blockHeader.getParentBeaconBlockRoot().isEmpty()) break;
      out.writeBytes(blockHeader.getParentBeaconBlockRoot().get());

      if (blockHeader.getRequestsHash().isEmpty()) break;
      out.writeBytes(blockHeader.getRequestsHash().get());

      if (blockHeader.getTargetBlobCount().isEmpty()) break;
      out.writeUInt64Scalar(blockHeader.getTargetBlobCount().get());
    } while (false);
    out.endList();
  }
}
