/*
 * Copyright contributors to Hyperledger Besu.
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
package org.hyperledger.besu.ethereum.mainnet;

import static org.hyperledger.besu.evm.frame.MessageFrame.DEFAULT_MAX_STACK_SIZE;

import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.ethereum.core.ProcessableBlockHeader;
import org.hyperledger.besu.ethereum.processing.TransactionProcessingResult;
import org.hyperledger.besu.evm.account.Account;
import org.hyperledger.besu.evm.code.CodeV0;
import org.hyperledger.besu.evm.frame.ExceptionalHaltReason;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.operation.BlockHashOperation;
import org.hyperledger.besu.evm.processor.MessageCallProcessor;
import org.hyperledger.besu.evm.tracing.OperationTracer;
import org.hyperledger.besu.evm.worldstate.WorldUpdater;

import java.util.Deque;
import java.util.Optional;

import org.apache.tuweni.bytes.Bytes;

public class SystemCallProcessor {
  static final Address SYSTEM_ADDRESS =
      Address.fromHexString("0xfffffffffffffffffffffffffffffffffffffffe");

  public SystemCallProcessor() {}

  public TransactionProcessingResult process(
      final Address callAddress,
      final WorldUpdater worldState,
      final ProcessableBlockHeader blockHeader,
      final OperationTracer operationTracer,
      final BlockHashOperation.BlockHashLookup blockHashLookup,
      final MessageCallProcessor messageCallProcessor) {
    final MessageFrame initialFrame =
        createCallFrame(
            callAddress,
            worldState,
            blockHeader,
            operationTracer,
            blockHashLookup,
            messageCallProcessor);
    process(initialFrame, messageCallProcessor, operationTracer, worldState);
    return null;
  }

  private MessageFrame createCallFrame(
      final Address callAddress,
      final WorldUpdater worldState,
      final ProcessableBlockHeader blockHeader,
      final OperationTracer operationTracer,
      final BlockHashOperation.BlockHashLookup blockHashLookup,
      final MessageCallProcessor messageCallProcessor) {

    final WorldUpdater worldUpdater = worldState.updater();
    final Optional<Account> maybeContract = Optional.ofNullable(worldState.get(callAddress));

    return MessageFrame.builder()
        .type(MessageFrame.Type.MESSAGE_CALL)
        .address(callAddress)
        .contract(callAddress)
        .inputData(Bytes.EMPTY)
        .initialGas(30_000_000L)
        .apparentValue(Wei.ZERO)
        .gasPrice(Wei.ZERO)
        .blobGasPrice(Wei.ZERO)
        .originator(SYSTEM_ADDRESS)
        .sender(SYSTEM_ADDRESS)
        .value(Wei.ZERO)
        .maxStackSize(DEFAULT_MAX_STACK_SIZE)
        .worldUpdater(worldUpdater)
        .blockValues(blockHeader)
        .completer(__ -> {})
        .miningBeneficiary(Address.ZERO)
        .blockHashLookup(blockHashLookup)
        .code(
            maybeContract
                .map(c -> messageCallProcessor.getCodeFromEVM(c.getCodeHash(), c.getCode()))
                .orElse(CodeV0.EMPTY_CODE))
        .build();
  }

  private void process(
      final MessageFrame initialFrame,
      final MessageCallProcessor messageCallProcessor,
      final OperationTracer operationTracer,
      final WorldUpdater worldUpdater) {
    Deque<MessageFrame> messageFrameStack = initialFrame.getMessageFrameStack();

    if (initialFrame.getCode().isValid()) {
      while (!messageFrameStack.isEmpty()) {
        messageCallProcessor.process(messageFrameStack.peekFirst(), operationTracer);
      }
    } else {
      initialFrame.setState(MessageFrame.State.EXCEPTIONAL_HALT);
      initialFrame.setExceptionalHaltReason(Optional.of(ExceptionalHaltReason.INVALID_CODE));
    }

    if (initialFrame.getState() == MessageFrame.State.COMPLETED_SUCCESS) {
      worldUpdater.commit();
    } else {
      if (initialFrame.getExceptionalHaltReason().isPresent()) {
        /* validationResult =
        ValidationResult.invalid(
          TransactionInvalidReason.EXECUTION_HALTED,
          initialFrame.getExceptionalHaltReason().get().toString());*/
      }
    }
  }
}
