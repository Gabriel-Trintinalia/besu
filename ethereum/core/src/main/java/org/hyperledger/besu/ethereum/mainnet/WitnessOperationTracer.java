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
  // gasCalculator for the fork being executed; used to compute memory expansion costs when
  // detecting whether a delegated-CALL's resolution step ran.
  public WitnessOperationTracer(final GasCalculator gasCalculator) {
    this.gasCalculator = gasCalculator;
  }

  // Parent-state snapshot saved at traceStartBlock. Used in traceStartTransaction to read EIP-7702
  // authority codes as they existed before the block started. Delegation processing runs before
  // tracing begins, so the current world view may already reflect cleared or overwritten codes.
  private WorldView parentWorldView = null;

  // Block number from a BLOCKHASH opcode captured in tracePreExecution; consumed and reset in
  // tracePostExecution. -1 means no BLOCKHASH is pending.
  private long pendingBlockHashNumber = -1;

  // Snapshot of call-time state needed in tracePostExecution to decide whether
  // AbstractCallOperation.execute() read the delegation target account.
  //
  // The EVM reads alice's code (and thus the delegation designator pointing at T) during the
  // delegation-resolution gas step, which comes after the static-cost check but before the
  // call-depth / balance check. We detect whether that step ran using two signals:
  //
  //   If T was cold before the opcode and is warm afterward → resolution step ran (it warms T as a
  //   side effect) → alice's code was read.
  //
  //   If T was already warm its warm/cold state doesn't change. We fall back to comparing the
  //   reported gas cost to the pre-computed static cost: static cost covers alice-access + memory
  //   expansion + value transfer, but not the delegation-resolution cost. gasCost > staticCost
  //   implies the resolution step ran.
  private record PendingDelegationInfo(
      Address aliceAddress, // call target that carries a delegation designator
      Address delegationTarget, // T — the address alice delegates to
      boolean targetWasWarm, // warm/cold state of T before the opcode ran
      long pendingStaticCost) // aliceAccessCost + memExpansion + valueTransferCost (no child gas)
  {}

  // Keyed by frame identity so concurrent nested calls don't interfere with each other.
  private final IdentityHashMap<MessageFrame, PendingDelegationInfo> pendingCallDelegations =
      new IdentityHashMap<>();

  // Address pending from the last EXTCODESIZE, EXTCODECOPY, or non-delegated CALL opcode in a
  // frame. Captured in tracePreExecution and committed to codeAddresses in tracePostExecution only
  // when the opcode did not OOG. Opcodes execute sequentially within a frame so at most one entry
  // exists per frame at a time. Delegated CALL targets are handled via pendingCallDelegations.
  private final IdentityHashMap<MessageFrame, Address> pendingCodeAddr = new IdentityHashMap<>();

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
    // If the sender is a 7702-delegated EOA, the executor must read its delegation designator to
    // resolve the sender's code pointer when the sender's own frame executes. Any other non-empty
    // sender code is included for the same reason.
    final Address sender = transaction.getSender();
    final var senderAccount = worldView.get(sender);
    if (senderAccount != null && !senderAccount.getCodeHash().equals(Hash.EMPTY)) {
      codeAddresses.add(sender);
    }

    // If the transaction directly targets a delegated account (alice has ef0100<T>), add alice's
    // address now. Normally traceContextEnter records it when the top frame begins execution, but
    // in Amsterdam chargeTransactionEntry charges a cold/warm access for T and may OOG — setting
    // the initial frame to EXCEPTIONAL_HALT before process() is called with NOT_STARTED. That
    // skips traceContextEnter entirely, so alice's delegation designator would be absent from the
    // witness. Adding it here is harmless in the non-OOG case (the LinkedHashSet deduplicates).
    // We do NOT add T here: T's bytecode is only needed when execution actually reaches T's code,
    // which traceContextEnter handles on the success path.
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

    // EIP-7702 intrinsic gas charges PER_EMPTY_ACCOUNT_COST for each authorization whose authority
    // has empty pre-block code. To reproduce this, the executor needs the authority's pre-block
    // bytecode. We read from parentWorldView (not worldView) because delegation processing already
    // ran: the current world view may reflect cleared or overwritten designation codes.
    final WorldView authorityView = parentWorldView != null ? parentWorldView : worldView;
    for (final var delegation : transaction.getCodeDelegationList().orElse(List.of())) {
      delegation
          .authorizer()
          .ifPresent(
              auth -> {
                final var authAccount = authorityView.get(auth);
                // Any non-empty pre-block code — plain 0x00, non-designator, and designator alike —
                // must
                // be in the witness so the executor can reproduce the intrinsic gas check.
                if (authAccount != null && !authAccount.getCodeHash().equals(Hash.EMPTY)) {
                  codeAddresses.add(auth);
                }
              });
    }
  }

  /** Records the bytecode of each MESSAGE_CALL contract and its EIP-7702 delegation target. */
  @Override
  public void traceContextEnter(final MessageFrame frame) {
    // CONTRACT_CREATION frames: init code is embedded in the transaction payload and does not need
    // to appear in the codes list; newly deployed runtime code is provided by the transaction.
    if (frame.getType() != MessageFrame.Type.MESSAGE_CALL) return;

    // The contract at frame.getContractAddress() is about to be executed; the executor needs this
    // bytecode to run the frame.
    final Address contract = frame.getContractAddress();
    codeAddresses.add(contract);

    final var account = frame.getWorldUpdater().get(contract);
    if (account == null) return;
    final var code = account.getCode();
    if (CodeDelegationHelper.hasCodeDelegation(code)) {
      // Delegation designator (0xef0100<T>): the EVM transparently executes T's code under the
      // contract's address. The executor needs T's bytecode even though T's frame is not separately
      // visible in the trace.
      codeAddresses.add(CodeDelegationHelper.getTargetAddress(code));
    }
  }

  /** Captures target addresses and block numbers before each opcode executes, deferred to post. */
  @Override
  public void tracePreExecution(final MessageFrame frame) {
    final int opcode = frame.getCurrentOperation().getOpcode();
    switch (opcode) {
      case 0x3B, 0x3C -> { // EXTCODESIZE, EXTCODECOPY
        // These read an external account's bytecode without creating a call frame; the executor
        // needs it. Deferred to tracePostExecution to exclude OOG aborts (if OOG, the code was
        // never read). EXTCODEHASH (0x3F) is excluded: the verifier derives the hash from the
        // codeHash field in the state witness without needing the full bytecode.
        if (frame.stackSize() >= 1) {
          pendingCodeAddr.put(frame, Words.toAddress(frame.getStackItem(0)));
        }
      }
      case 0x40 -> { // BLOCKHASH
        // Words.clampedToLong handles stack values > Long.MAX_VALUE (e.g. 2**64) without throwing;
        // such values always produce a zero BLOCKHASH result and need no header in the witness.
        if (frame.stackSize() >= 1)
          pendingBlockHashNumber = Words.clampedToLong(frame.getStackItem(0));
      }
      case 0xF1, 0xF2, 0xF4, 0xFA -> { // CALL, CALLCODE, DELEGATECALL, STATICCALL
        // AbstractCallOperation.execute() reads the target account before the balance and
        // call-depth
        // checks. If either later check fails ("soft failure") no child frame is created and
        // traceContextEnter is never called — yet the bytecode was accessed. Capture here; decide
        // in tracePostExecution whether the access actually occurred.
        final int minStack = (opcode == 0xF1 || opcode == 0xF2) ? 7 : 6;
        if (frame.stackSize() >= minStack) {
          final Address alice = Words.toAddress(frame.getStackItem(1));
          final var aliceAccount = frame.getWorldUpdater().get(alice);
          if (aliceAccount != null
              && CodeDelegationHelper.hasCodeDelegation(aliceAccount.getCode())) {
            // Alice is delegated. Record state needed to detect whether the delegation-resolution
            // gas step (check 3, which reads alice's code) actually ran.
            final Address T = CodeDelegationHelper.getTargetAddress(aliceAccount.getCode());
            final boolean aliceWasWarm = frame.isAddressWarm(alice);
            final boolean tWasWarm = frame.isAddressWarm(T);
            // Reconstruct the static cost (checks 1+2: alice-access + mem-expansion + value
            // transfer, no child gas); any reported cost above this means check 3 also ran.
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
            // Non-delegated alice: account-read is implied by passing checks 1+2.
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
    // BLOCKHASH: record the block-number → hash pair only when the opcode succeeded and returned a
    // non-zero hash. Zero means the block is outside the 256-ancestor window; no header needed.
    if (pendingBlockHashNumber >= 0) {
      if (operationResult.getHaltReason() == null && frame.stackSize() >= 1) {
        final Bytes32 hashBytes = Bytes32.leftPad(frame.getStackItem(0));
        if (!hashBytes.isZero()) {
          oldestAccessedAncestor = Math.min(oldestAccessedAncestor, pendingBlockHashNumber);
        }
      }
      pendingBlockHashNumber = -1;
    }

    // EXTCODESIZE / EXTCODECOPY / non-delegated CALL: haltReason == null means the account was
    // read. Add the address; for successful calls traceContextEnter also adds it (harmless dup).
    final Address pendingAddr = pendingCodeAddr.remove(frame);
    if (pendingAddr != null && operationResult.getHaltReason() == null) {
      codeAddresses.add(pendingAddr);
    }

    // Delegated CALL: use the gas-delta signal from PendingDelegationInfo to determine whether
    // alice's code was read during the delegation-resolution step (check 3).
    final PendingDelegationInfo info = pendingCallDelegations.remove(frame);
    if (info != null) {
      final boolean alicesCodeWasRead;
      if (!info.targetWasWarm() && frame.isAddressWarm(info.delegationTarget())) {
        // T transitioned cold → warm: delegation-resolution step ran, alice's code was read.
        alicesCodeWasRead = true;
      } else {
        // T was already warm; use gas cost as the signal instead.
        alicesCodeWasRead = (operationResult.getGasCost() > info.pendingStaticCost());
      }
      if (alicesCodeWasRead) {
        codeAddresses.add(info.aliceAddress());
        // T is needed only when the call did not OOG at check 3 — null haltReason means the EVM
        // continued past the balance/depth check, recorded T in the BAL, and headed toward either
        // a soft failure or a real child frame. (traceContextEnter adds T again for the latter;
        // the duplicate insertion into the LinkedHashSet is harmless.)
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
