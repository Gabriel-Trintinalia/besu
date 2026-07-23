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

import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.datatypes.Transaction;
import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.gascalculator.GasCalculator;
import org.hyperledger.besu.evm.internal.Words;
import org.hyperledger.besu.evm.operation.Operation.OperationResult;
import org.hyperledger.besu.evm.worldstate.CodeDelegationHelper;
import org.hyperledger.besu.evm.worldstate.WorldView;
import org.hyperledger.besu.plugin.data.BlockBody;
import org.hyperledger.besu.plugin.data.BlockHeader;
import org.hyperledger.besu.plugin.data.ProcessableBlockHeader;
import org.hyperledger.besu.plugin.services.tracer.BlockAwareOperationTracer;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.apache.tuweni.bytes.Bytes32;

/**
 * Collects the contract bytecodes ({@code codes}) and ancestor block headers ({@code headers}) that
 * a stateless executor needs to re-execute a block, as defined by EIP-8025.
 */
public class WitnessOperationTracer implements BlockAwareOperationTracer {

  private final GasCalculator gasCalculator;
  private final Set<Address> codeAddresses = new LinkedHashSet<>();
  // Oldest block number (inclusive) whose header the stateless executor must receive. Initialized
  // to the parent block number (always required) and extended left by each successful BLOCKHASH.
  private long oldestAccessedAncestor = Long.MAX_VALUE;

  /**
   * @param gasCalculator the gas calculator for the fork being executed.
   */
  public WitnessOperationTracer(final GasCalculator gasCalculator) {
    this.gasCalculator = gasCalculator;
  }

  // Parent-state snapshot saved at traceStartBlock. Used in traceStartTransaction to read EIP-7702
  // authority codes as they existed before the block started.
  private WorldView parentWorldView = null;

  // Block number from a BLOCKHASH opcode captured in tracePreExecution; consumed and reset in
  // tracePostExecution. -1 means no BLOCKHASH is pending.
  private long pendingBlockHashNumber = -1;

  // Snapshot of call-time state needed in tracePostExecution to decide whether
  // AbstractCallOperation.execute() read the delegation target account.
  //
  // The EVM reads alice's code (and thus the delegation designator pointing at T) during the
  // delegation-resolution gas step. We detect whether that step ran using two signals:
  //   If T was cold before the opcode and is warm afterward → resolution step ran.
  //   If T was already warm, compare reported gas cost to the pre-computed static cost.
  private record PendingDelegationInfo(
      Address aliceAddress,
      Address delegationTarget,
      boolean targetWasWarm,
      long pendingStaticCost) {}

  private final IdentityHashMap<MessageFrame, PendingDelegationInfo> pendingCallDelegations =
      new IdentityHashMap<>();

  // Address pending from the last EXTCODESIZE, EXTCODECOPY, or non-delegated CALL opcode in a
  // frame. Deferred to tracePostExecution to exclude OOG aborts.
  private final IdentityHashMap<MessageFrame, Address> pendingCodeAddr = new IdentityHashMap<>();

  @Override
  public boolean isSystemCallTracingEnabled() {
    return true;
  }

  @Override
  public void traceStartBlock(
      final WorldView worldView,
      final BlockHeader blockHeader,
      final BlockBody blockBody,
      final Address miningBeneficiary) {
    parentWorldView = worldView;
    recordParentHeader(blockHeader);
  }

  @Override
  public void traceStartBlock(
      final WorldView worldView,
      final ProcessableBlockHeader processableBlockHeader,
      final Address miningBeneficiary) {
    parentWorldView = worldView;
    recordParentHeader(processableBlockHeader);
  }

  private void recordParentHeader(final ProcessableBlockHeader header) {
    // Parent is unconditionally required: the stateless executor needs it to verify the block's
    // parentHash field and to seed the EIP-2935 history contract.
    oldestAccessedAncestor = header.getNumber() - 1;
  }

  /** Records sender code and EIP-7702 authority pre-block codes needed for intrinsic gas replay. */
  @Override
  public void traceStartTransaction(final WorldView worldView, final Transaction transaction) {
    final Address sender = transaction.getSender();
    final var senderAccount = worldView.get(sender);
    if (senderAccount != null && !senderAccount.getCodeHash().equals(Hash.EMPTY)) {
      codeAddresses.add(sender);
    }

    // If the transaction directly targets a delegated account, add alice's address now.
    // This handles the OOG-at-delegation-charge case where traceContextEnter is never called.
    transaction
        .getTo()
        .ifPresent(
            to -> {
              final var toAccount = worldView.get(to);
              if (toAccount != null
                  && CodeDelegationHelper.hasCodeDelegation(toAccount.getCode())) {
                codeAddresses.add(to);
              }
            });

    // EIP-7702: record authority pre-block codes for intrinsic gas replay.
    final WorldView authorityView = parentWorldView != null ? parentWorldView : worldView;
    for (final var delegation : transaction.getCodeDelegationList().orElse(List.of())) {
      delegation
          .authorizer()
          .ifPresent(
              auth -> {
                final var authAccount = authorityView.get(auth);
                if (authAccount != null && !authAccount.getCodeHash().equals(Hash.EMPTY)) {
                  codeAddresses.add(auth);
                }
              });
    }
  }

  /** Records the bytecode of each MESSAGE_CALL contract and its EIP-7702 delegation target. */
  @Override
  public void traceContextEnter(final MessageFrame frame) {
    if (frame.getType() != MessageFrame.Type.MESSAGE_CALL) return;

    final Address contract = frame.getContractAddress();
    codeAddresses.add(contract);

    final var account = frame.getWorldUpdater().get(contract);
    if (account == null) return;
    final var code = account.getCode();
    if (CodeDelegationHelper.hasCodeDelegation(code)) {
      codeAddresses.add(CodeDelegationHelper.getTargetAddress(code));
    }
  }

  /** Captures target addresses and block numbers before each opcode executes, deferred to post. */
  @Override
  public void tracePreExecution(final MessageFrame frame) {
    final int opcode = frame.getCurrentOperation().getOpcode();
    switch (opcode) {
      case 0x3B, 0x3C -> { // EXTCODESIZE, EXTCODECOPY
        if (frame.stackSize() >= 1) {
          pendingCodeAddr.put(frame, Words.toAddress(frame.getStackItem(0)));
        }
      }
      case 0x40 -> { // BLOCKHASH
        if (frame.stackSize() >= 1)
          pendingBlockHashNumber = Words.clampedToLong(frame.getStackItem(0));
      }
      case 0xF1, 0xF2, 0xF4, 0xFA -> { // CALL, CALLCODE, DELEGATECALL, STATICCALL
        final int minStack = (opcode == 0xF1 || opcode == 0xF2) ? 7 : 6;
        if (frame.stackSize() >= minStack) {
          final Address alice = Words.toAddress(frame.getStackItem(1));
          final var aliceAccount = frame.getWorldUpdater().get(alice);
          if (aliceAccount != null
              && CodeDelegationHelper.hasCodeDelegation(aliceAccount.getCode())) {
            final Address T = CodeDelegationHelper.getTargetAddress(aliceAccount.getCode());
            final boolean aliceWasWarm = frame.isAddressWarm(alice);
            final boolean tWasWarm = frame.isAddressWarm(T);
            final int argsBase = (opcode == 0xF1 || opcode == 0xF2) ? 3 : 2;
            final Wei transferValue =
                (opcode == 0xF1 || opcode == 0xF2) ? Wei.wrap(frame.getStackItem(2)) : Wei.ZERO;
            final long staticCost =
                gasCalculator.callOperationStaticGasCost(
                    frame,
                    0L,
                    Words.clampedToLong(frame.getStackItem(argsBase)),
                    Words.clampedToLong(frame.getStackItem(argsBase + 1)),
                    Words.clampedToLong(frame.getStackItem(argsBase + 2)),
                    Words.clampedToLong(frame.getStackItem(argsBase + 3)),
                    transferValue,
                    alice,
                    aliceWasWarm);
            pendingCallDelegations.put(
                frame, new PendingDelegationInfo(alice, T, tWasWarm, staticCost));
          } else {
            pendingCodeAddr.put(frame, alice);
          }
        }
      }
      default -> {}
    }
  }

  /** Commits deferred addresses and headers only when the preceding opcode did not OOG. */
  @Override
  public void tracePostExecution(final MessageFrame frame, final OperationResult operationResult) {
    if (pendingBlockHashNumber >= 0) {
      if (operationResult.getHaltReason() == null && frame.stackSize() >= 1) {
        final Bytes32 hashBytes = Bytes32.leftPad(frame.getStackItem(0));
        if (!hashBytes.isZero()) {
          oldestAccessedAncestor = Math.min(oldestAccessedAncestor, pendingBlockHashNumber);
        }
      }
      pendingBlockHashNumber = -1;
    }

    final Address pendingAddr = pendingCodeAddr.remove(frame);
    if (pendingAddr != null && operationResult.getHaltReason() == null) {
      codeAddresses.add(pendingAddr);
    }

    final PendingDelegationInfo info = pendingCallDelegations.remove(frame);
    if (info != null) {
      final boolean alicesCodeWasRead;
      if (!info.targetWasWarm() && frame.isAddressWarm(info.delegationTarget())) {
        alicesCodeWasRead = true;
      } else {
        alicesCodeWasRead = (operationResult.getGasCost() > info.pendingStaticCost());
      }
      if (alicesCodeWasRead) {
        codeAddresses.add(info.aliceAddress());
        if (operationResult.getHaltReason() == null) {
          codeAddresses.add(info.delegationTarget());
        }
      }
    }
  }

  /** Returns addresses whose bytecode must appear in the witness {@code codes} list. */
  public Set<Address> getCodeAddresses() {
    return Collections.unmodifiableSet(codeAddresses);
  }

  /**
   * Returns the oldest block number whose header must appear in the witness {@code headers} list.
   */
  public long getOldestAccessedAncestor() {
    return oldestAccessedAncestor;
  }
}
