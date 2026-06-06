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
import org.hyperledger.besu.evm.frame.MessageFrame;
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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import org.apache.tuweni.bytes.Bytes32;

/**
 * Collects all EVM state access needed to build an EIP-8025 execution witness in a single pass
 * over the operation trace, without relying on the BlockAccessList or TrieLog.
 *
 * <p>Two collections are built:
 *
 * <ul>
 *   <li>{@link #getCodeAddresses()} — addresses whose bytecode must appear in the witness {@code
 *       codes} list (MESSAGE_CALL targets, EXTCODECOPY sources, EIP-7702 delegation targets
 *       resolved at frame entry or via soft-failure CALL, and EIP-7702 authorities whose
 *       pre-existing designation code is needed for intrinsic gas verification).
 *   <li>{@link #getAccessedAncestors()} — block-number→hash pairs for all ancestors that must
 *       appear in the witness {@code headers} list (always includes the parent; extended by BLOCKHASH
 *       opcode results).
 * </ul>
 */
public class WitnessOperationTracer implements BlockAwareOperationTracer {

  private final Set<Address> codeAddresses = new LinkedHashSet<>();
  private final Map<Long, Hash> accessedAncestors = new LinkedHashMap<>();

  // Transient: block number from BLOCKHASH pre-execution; consumed in tracePostExecution
  private long pendingBlockHashNumber = -1;

  // EIP-2929 Berlin+ warm/cold access costs and CALL value transfer cost.
  private static final long WARM_ACCESS_COST = 100L;
  private static final long COLD_ACCESS_COST = 2600L;
  private static final long CALL_VALUE_TRANSFER_GAS_COST = 9_000L;

  // Holds info needed in tracePostExecution to decide whether the delegation target's code was
  // accessed (i.e., whether getAccount(alice) was called in AbstractCallOperation.execute()).
  private record PendingDelegationInfo(
      Address aliceAddress,       // the call target that has a delegation designator
      Address delegationTarget,   // T — where alice delegates to
      boolean targetWasWarm,      // T's warm state BEFORE AbstractCallOperation ran
      long pendingStaticCost) {}  // staticCost = aliceAccessCost + memExpansion (no child gas)

  // Keyed by frame identity so nested calls don't interfere.
  private final IdentityHashMap<MessageFrame, PendingDelegationInfo> pendingCallDelegations =
      new IdentityHashMap<>();

  // EXTCODESIZE / EXTCODECOPY: target address recorded in tracePreExecution,
  // added to codeAddresses in tracePostExecution only when the opcode completed without OOG.
  private final IdentityHashMap<MessageFrame, Address> pendingExtcodeAddr =
      new IdentityHashMap<>();

  // Non-delegated CALL targets: AbstractCallOperation.execute() reads the account (getAccount(to))
  // BEFORE the balance/depth check. So even for soft failures (insufficient balance, max depth)
  // the target's code was accessed and must appear in the witness. We track the target here and
  // add it in tracePostExecution when haltReason == null (i.e., not an OOG at check 1/2).
  // Delegated calls are already handled by pendingCallDelegations; only non-delegated go here.
  private final IdentityHashMap<MessageFrame, Address> pendingNonDelegCallAddr =
      new IdentityHashMap<>();

  // --- Block lifecycle ---

  @Override
  public void traceStartBlock(
      final WorldView worldView,
      final BlockHeader blockHeader,
      final BlockBody blockBody,
      final Address miningBeneficiary) {
    recordParentAndMiner(blockHeader);
  }

  @Override
  public void traceStartBlock(
      final WorldView worldView,
      final ProcessableBlockHeader processableBlockHeader,
      final Address miningBeneficiary) {
    recordParentAndMiner(processableBlockHeader);
  }

  private void recordParentAndMiner(
      final ProcessableBlockHeader header) {
    accessedAncestors.put(header.getNumber() - 1, header.getParentHash());
  }

  @Override
  public void traceStartTransaction(final WorldView worldView, final Transaction transaction) {
    // Sender: add sender's code if non-empty (delegation designator included, but NOT target).
    final Address sender = transaction.getSender();
    final var senderAccount = worldView.get(sender);
    if (senderAccount != null && !senderAccount.getCodeHash().equals(Hash.EMPTY)) {
      codeAddresses.add(sender);
    }

    // EIP-7702: for each authorization authority whose code is currently a delegation designator,
    // add the authority so its designation code appears in the witness for intrinsic gas
    // verification. Only add the authority itself — never the delegation target (targets appear
    // in codes only when their account is accessed for state proof, not for execution).
    transaction
        .getCodeDelegationList()
        .ifPresent(
            list ->
                list.forEach(
                    delegation ->
                        delegation
                            .authorizer()
                            .ifPresent(
                                auth -> {
                                  final var authAccount = worldView.get(auth);
                                  if (authAccount == null
                                      || authAccount.getCodeHash().equals(Hash.EMPTY)) {
                                    return;
                                  }
                                  if (CodeDelegationHelper.hasCodeDelegation(
                                      authAccount.getCode())) {
                                    codeAddresses.add(auth);
                                  }
                                })));
  }

  // --- Context / call stack ---

  @Override
  public void traceContextEnter(final MessageFrame frame) {
    if (frame.getType() == MessageFrame.Type.MESSAGE_CALL) {
      final Address contract = frame.getContractAddress();
      codeAddresses.add(contract);
      // EIP-7702: when a delegated account's frame is entered, the delegation target's code is
      // also executed. Add the target so its bytecode appears in the witness codes list.
      final var account = frame.getWorldUpdater().get(contract);
      if (account != null) {
        final var code = account.getCode();
        if (CodeDelegationHelper.hasCodeDelegation(code)) {
          codeAddresses.add(CodeDelegationHelper.getTargetAddress(code));
        }
      }
    }
  }

  @Override
  public void tracePreExecution(final MessageFrame frame) {
    final int opcode = frame.getCurrentOperation().getOpcode();
    switch (opcode) {
      case 0x3B, 0x3C -> { // EXTCODESIZE / EXTCODECOPY
        // The target's code must appear in the witness only when the opcode actually reads it
        // (i.e., doesn't OOG). Record here; add in tracePostExecution if halt == null.
        // EXTCODEHASH (0x3F) is intentionally excluded: only the hash is needed for verification,
        // not the full bytecode, so the code does not need to appear in the witness codes list.
        if (frame.stackSize() >= 1) {
          pendingExtcodeAddr.put(frame, Words.toAddress(frame.getStackItem(0)));
        }
      }
      case 0x40 -> { // BLOCKHASH — capture requested block number; hash read in tracePostExecution
        if (frame.stackSize() >= 1) pendingBlockHashNumber = frame.getStackItem(0).toLong();
      }
      case 0xF1, 0xF2, 0xF4, 0xFA -> {
        // CALL/CALLCODE/DELEGATECALL/STATICCALL: AbstractCallOperation.execute() reads the target
        // account BEFORE the balance/depth check (step 6). So the target's code is in the witness
        // for any call that doesn't OOG at checks 1+2 (haltReason == null in tracePostExecution).
        //
        // Delegated targets need a more precise check (the gas-delta method) because delegation
        // resolution gas (check 3) can OOG after the account is read but before BAL recording.
        // Non-delegated targets use the simpler haltReason-based detection.
        final int minStack = (opcode == 0xF1 || opcode == 0xF2) ? 7 : 6;
        if (frame.stackSize() >= minStack) {
          final Address alice = Words.toAddress(frame.getStackItem(1));
          final var aliceAccount = frame.getWorldUpdater().get(alice);
          if (aliceAccount != null
              && CodeDelegationHelper.hasCodeDelegation(aliceAccount.getCode())) {
            // Delegated alice: sophisticated gas-delta check to distinguish check-2 vs check-3 OOG.
            final Address T = CodeDelegationHelper.getTargetAddress(aliceAccount.getCode());
            final boolean aliceWasWarm = frame.isAddressWarm(alice);
            final boolean tWasWarm = frame.isAddressWarm(T);
            final long aliceAccessCost = aliceWasWarm ? WARM_ACCESS_COST : COLD_ACCESS_COST;
            // Berlin staticCost = aliceAccessCost + max(inputMemExp, outputMemExp)
            //   + callValueTransferGasCost (9000) if CALL/CALLCODE and value != 0.
            // For CALL/CALLCODE the value parameter is at stack[2]; args start at stack[3].
            final int argsBase = (opcode == 0xF1 || opcode == 0xF2) ? 3 : 2;
            final long argsOffset = Words.clampedToLong(frame.getStackItem(argsBase));
            final long argsLength = Words.clampedToLong(frame.getStackItem(argsBase + 1));
            final long retOffset = Words.clampedToLong(frame.getStackItem(argsBase + 2));
            final long retLength = Words.clampedToLong(frame.getStackItem(argsBase + 3));
            final long memExp =
                Math.max(
                    memoryExpansionCost(frame, argsOffset, argsLength),
                    memoryExpansionCost(frame, retOffset, retLength));
            final long valueTransferCost =
                ((opcode == 0xF1 || opcode == 0xF2) && !frame.getStackItem(2).isZero())
                    ? CALL_VALUE_TRANSFER_GAS_COST
                    : 0L;
            final long staticCost = aliceAccessCost + memExp + valueTransferCost;
            pendingCallDelegations.put(frame, new PendingDelegationInfo(alice, T, tWasWarm, staticCost));
          } else {
            // Non-delegated alice: simpler detection — haltReason == null means account was read.
            pendingNonDelegCallAddr.put(frame, alice);
          }
        }
      }
      default -> {}
    }
  }

  @Override
  public void tracePostExecution(final MessageFrame frame, final OperationResult operationResult) {
    if (pendingBlockHashNumber >= 0) {
      if (operationResult.getHaltReason() == null && frame.stackSize() >= 1) {
        final Bytes32 hashBytes = Bytes32.leftPad(frame.getStackItem(0));
        if (!hashBytes.isZero()) {
          accessedAncestors.put(pendingBlockHashNumber, Hash.wrap(hashBytes));
        }
      }
      pendingBlockHashNumber = -1;
    }
    // Non-delegated CALL targets: add when haltReason == null (passed gas checks 1+2, account was
    // read). Covers both successful calls (where traceContextEnter also adds the target) and soft
    // failures (insufficient balance, max depth) where no child frame is created.
    final Address nonDelegAddr = pendingNonDelegCallAddr.remove(frame);
    if (nonDelegAddr != null && operationResult.getHaltReason() == null) {
      codeAddresses.add(nonDelegAddr);
    }
    // EXTCODESIZE/EXTCODECOPY: add the target only when the opcode succeeded.
    final Address extcodeAddr = pendingExtcodeAddr.remove(frame);
    if (extcodeAddr != null && operationResult.getHaltReason() == null) {
      codeAddresses.add(extcodeAddr);
    }
    // Detect whether AbstractCallOperation.execute() read the delegation account (alice) — this
    // happens only when both the static-cost AND callOperationGasCost checks passed (checks 1+2).
    // If alice's code was read, add alice to codeAddresses so the witness includes the designator.
    final PendingDelegationInfo info = pendingCallDelegations.remove(frame);
    if (info != null) {
      final boolean alicesCodeWasRead;
      if (!info.targetWasWarm() && frame.isAddressWarm(info.delegationTarget())) {
        // T was cold and is now warm: calculateCodeDelegationResolutionGas ran → alice was read.
        alicesCodeWasRead = true;
      } else {
        // T was already warm (warm_target) — its warm state doesn't change, so we use the gas
        // signal instead. Berlin staticCost = aliceAccessCost + memExpansion. If the OOG fired
        // before getAccount(alice), gasCost == staticCost; after, gasCost > staticCost.
        alicesCodeWasRead = (operationResult.getGasCost() > info.pendingStaticCost());
      }
      if (alicesCodeWasRead) {
        codeAddresses.add(info.aliceAddress());
        // Add T when the CALL completed without an exceptional halt (null haltReason).
        // This covers soft failures (insufficient balance, max depth) where the EVM read alice's
        // code, recorded T in the BAL, but didn't create a child frame. For OOG (INSUFFICIENT_GAS)
        // the BAL recording code never runs and T is not needed. When a child frame IS created,
        // traceContextEnter also adds T; the duplicate is harmless.
        if (operationResult.getHaltReason() == null) {
          codeAddresses.add(info.delegationTarget());
        }
      }
    }
  }

  /**
   * Returns the set of addresses whose bytecode must appear in the witness {@code codes} list.
   */
  public Set<Address> getCodeAddresses() {
    return Collections.unmodifiableSet(codeAddresses);
  }

  /**
   * Returns every block-number → hash pair that must appear in the witness {@code headers} list.
   */
  public Map<Long, Hash> getAccessedAncestors() {
    return Collections.unmodifiableMap(accessedAncestors);
  }

  // Mirrors FrontierGasCalculator.memoryExpansionGasCost for use in tracePreExecution.
  private static long memoryExpansionCost(
      final MessageFrame frame, final long offset, final long length) {
    if (length == 0) return 0L;
    final long newWords = frame.calculateMemoryExpansion(offset, length);
    final long oldWords = frame.memoryWordSize();
    return memoryCost(newWords) - memoryCost(oldWords);
  }

  // Mirrors FrontierGasCalculator.memoryCost: 3*words + words^2/512.
  private static long memoryCost(final long words) {
    if (words == 0) return 0L;
    return 3L * words + words * words / 512L;
  }
}
