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
import org.hyperledger.besu.ethereum.core.MutableWorldState;
import org.hyperledger.besu.ethereum.core.ProcessableBlockHeader;
import org.hyperledger.besu.ethereum.core.Transaction;
import org.hyperledger.besu.ethereum.privacy.storage.PrivateMetadataUpdater;
import org.hyperledger.besu.ethereum.processing.TransactionProcessingResult;
import org.hyperledger.besu.ethereum.transaction.CallParameter;
import org.hyperledger.besu.ethereum.transaction.TransactionInvalidReason;
import org.hyperledger.besu.ethereum.transaction.TransactionSimulator;
import org.hyperledger.besu.ethereum.transaction.TransactionSimulatorResult;
import org.hyperledger.besu.evm.account.Account;
import org.hyperledger.besu.evm.code.CodeV0;
import org.hyperledger.besu.evm.frame.ExceptionalHaltReason;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.operation.BlockHashOperation;
import org.hyperledger.besu.evm.tracing.OperationTracer;

import java.util.Deque;
import java.util.Optional;

import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.evm.worldstate.WorldUpdater;

public class SystemCallProcessor {
  static final Address SYSTEM_ADDRESS =
      Address.fromHexString("0xfffffffffffffffffffffffffffffffffffffffe");

  final TransactionSimulator transactionSimulator;

  final Address callAddress;

  public SystemCallProcessor(TransactionSimulator transactionSimulator, Address callAddress) {
    this.transactionSimulator = transactionSimulator;
    this.callAddress = callAddress;
  }

  public TransactionProcessingResult processTransaction(
    final WorldUpdater worldState,
    final ProcessableBlockHeader blockHeader,
    final Transaction transaction,
    final Address miningBeneficiary,
    final OperationTracer operationTracer,
    final BlockHashOperation.BlockHashLookup blockHashLookup,
    final Wei blobGasPrice) {

    final WorldUpdater worldUpdater = worldState.updater();

    final Address to = transaction.getTo().get();
    final Optional<Account> maybeContract = Optional.ofNullable(worldState.get(to));

    final MessageFrame initialFrame =
      MessageFrame.builder()
        .maxStackSize(DEFAULT_MAX_STACK_SIZE)
        .worldUpdater(worldUpdater.updater())
        .initialGas(transaction.getGasLimit() )
        .originator(transaction.getSender())
        .gasPrice(Wei.ZERO)
        .blobGasPrice(Wei.ZERO)
        .sender(transaction.getSender())
        .value(transaction.getValue())
        .apparentValue(transaction.getValue())
        .blockValues(blockHeader)
        .completer(__ -> {})
        .miningBeneficiary(miningBeneficiary)
        .blockHashLookup(blockHashLookup)
        .type(MessageFrame.Type.MESSAGE_CALL)
        .address(to)
        .contract(to)
        .inputData(transaction.getPayload())
        .code(CodeV0.EMPTY_CODE)
        .build();

    Deque<MessageFrame> messageFrameStack = initialFrame.getMessageFrameStack();

    if (initialFrame.getCode().isValid()) {
      while (!messageFrameStack.isEmpty()) {
        process(messageFrameStack.peekFirst(), operationTracer);
      }
    } else {
      initialFrame.setState(MessageFrame.State.EXCEPTIONAL_HALT);
      initialFrame.setExceptionalHaltReason(Optional.of(ExceptionalHaltReason.INVALID_CODE));
    }

    if (initialFrame.getState() == MessageFrame.State.COMPLETED_SUCCESS) {
      worldUpdater.commit();
    } else {
      if (initialFrame.getExceptionalHaltReason().isPresent()) {
        validationResult =
          ValidationResult.invalid(
            TransactionInvalidReason.EXECUTION_HALTED,
            initialFrame.getExceptionalHaltReason().get().toString());
      }
    }

  }
  private Bytes process(
      final ProtocolSpec protocolSpec,
      final OperationTracer tracer,
      final MutableWorldState mutableWorldState,
      ProcessableBlockHeader blockHeader) {
    final Transaction transaction = buildTransaction();
    final TransactionValidationParams transactionValidationParams =
        buildTransactionValidationParams();

    var tp = protocolSpec.getTransactionProcessor();



    tp.processTransaction(mutableWorldState.updater(), blockHeader,)

    final Optional<TransactionSimulatorResult> result =
        transactionSimulator.processWithWorldUpdater(
            callParameter, transactionValidationParams, tracer, null, mutableWorldState.updater());

    return result.flatMap(r -> Optional.ofNullable(r.result().getOutput())).orElse(Bytes.EMPTY);
  }

  private TransactionValidationParams buildTransactionValidationParams() {
    return ImmutableTransactionValidationParams.builder()
        .from(TransactionValidationParams.transactionSimulator())
        .build();
  }

  private Transaction buildCallParams() {
    var GasLimit = 30_000_000L;

    final Transaction.Builder transactionBuilder =
      Transaction.builder()
        .nonce(0)
        .gasLimit(GasLimit)
        .to(callAddress)
        .sender(SYSTEM_ADDRESS)
        .value(Wei.ZERO)
        .payload(Bytes.EMPTY)
        .signature(FAKE_SIGNATURE);

    return new CallParameter(
        SYSTEM_ADDRESS, callAddress, GasLimit, Wei.ZERO, Wei.ZERO, Bytes.EMPTY);
  }
}
