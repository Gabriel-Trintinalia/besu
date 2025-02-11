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
package org.hyperledger.besu.ethereum.transaction;

import static org.apache.tuweni.bytes.Bytes32.leftPad;

import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.evm.account.MutableAccount;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.internal.Words;
import org.hyperledger.besu.evm.log.Log;
import org.hyperledger.besu.evm.log.LogTopic;
import org.hyperledger.besu.evm.operation.Operation;
import org.hyperledger.besu.evm.tracing.OperationTracer;

import java.util.ArrayList;
import java.util.List;

import com.google.common.collect.ImmutableList;
import org.apache.tuweni.bytes.Bytes;

public class EthTransferLogOperationTracer implements OperationTracer {
  private final List<Log> traceTransfers = new ArrayList<>();
  public static final Address SIMULATION_TRANSFER_ADDRESS =
      Address.fromHexString("0xeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee");
  public static final Bytes SIMULATION_TRANSFER_TOPIC =
      Bytes.fromHexString("ddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef");

  @Override
  public void traceContextEnter(final MessageFrame frame) {
    if (frame.getValue().compareTo(Wei.ZERO) > 0
        && !frame.getRecipientAddress().equals(frame.getSenderAddress())) {
      emitTransferLogs(frame.getSenderAddress(), frame.getRecipientAddress(), frame.getValue());
    }
  }

  @Override
  public void tracePreExecution(final MessageFrame frame) {
    // Emit log if self-destruct
    if (frame.getCurrentOperation().getOpcode() == 0xFF) {
      emitSelfDestructLog(frame);
    }
  }

  @Override
  public void tracePostExecution(
      final MessageFrame frame, final Operation.OperationResult operationResult) {
    // do nothing for now
  }

  @Override
  public void traceContextExit(final MessageFrame frame) {
    if (frame.getState() == MessageFrame.State.COMPLETED_FAILED) {
      traceTransfers.clear();
    }
  }

  @SuppressWarnings("UnusedMethod")
  void emitSelfDestructLog(final MessageFrame frame) {
    final Address beneficiaryAddress = Words.toAddress(frame.getStackItem(0));
    final Address originatorAddress = frame.getRecipientAddress();
    final MutableAccount originatorAccount = frame.getWorldUpdater().getAccount(originatorAddress);
    final Wei originatorBalance = originatorAccount.getBalance();
    emitTransferLogs(frame.getRecipientAddress(), beneficiaryAddress, originatorBalance);
  }

  private void emitTransferLogs(final Address sender, final Address recipient, final Wei value) {
    final ImmutableList.Builder<LogTopic> builder = ImmutableList.builderWithExpectedSize(3);
    builder.add(LogTopic.create(SIMULATION_TRANSFER_TOPIC));
    builder.add(LogTopic.create(leftPad(sender)));
    builder.add(LogTopic.create(leftPad(recipient)));
    traceTransfers.add(
        new org.hyperledger.besu.evm.log.Log(SIMULATION_TRANSFER_ADDRESS, value, builder.build()));
  }

  public List<Log> getLogs() {
    return traceTransfers;
  }
}
