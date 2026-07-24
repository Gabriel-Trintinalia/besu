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
 * a stateless executor needs to re-execute a block, as defined by <a
 * href="https://eips.ethereum.org/EIPS/eip-8025">EIP-8025</a>.
 *
 * <p>This tracer observes a full block execution (driven through {@code processBlock} with an
 * explicit tracer) and records two things:
 *
 * <ul>
 *   <li><b>Code addresses</b> — every address whose bytecode the block's execution reads, and whose
 *       code must therefore be shipped in the witness {@code codes} list. Sources: transaction
 *       senders with code, called contracts, EXTCODESIZE/EXTCODECOPY targets, EIP-7702 authorities
 *       (pre-block code, for intrinsic-gas replay) and delegation targets.
 *   <li><b>Oldest accessed ancestor</b> — the lowest block number whose header the stateless
 *       executor needs. The parent header is always required; each successful BLOCKHASH that
 *       returns a non-zero hash may extend the range further back.
 * </ul>
 *
 * <p><b>Deferred capture.</b> Code-reading opcodes are captured in two phases: the target address
 * is noted in {@link #tracePreExecution} (while it is still on the stack) but only committed in
 * {@link #tracePostExecution} once the opcode is known not to have halted exceptionally. An opcode
 * that OOGs before charging its access cost never reads the code, so committing eagerly would
 * over-collect and produce witnesses larger than a stateless executor can verify.
 *
 * <p><b>EIP-7702 delegation.</b> Calls to a delegated account ("alice", whose code is a delegation
 * designator pointing at target "T") are the subtle case: the EVM reads alice's designator during
 * the delegation-resolution gas step, which may run even when the call itself later fails. Whether
 * that step ran cannot be observed directly, so it is inferred in {@link #tracePostExecution} from
 * two signals: T transitioning cold→warm across the opcode, or the reported gas cost exceeding the
 * pre-computed static cost (meaning the dynamic resolution charge was applied). When the step ran,
 * alice's code is required; T's code is additionally required only if the opcode completed without
 * a halt. The direct-transaction-target variant (OOG at the delegation charge, before any frame is
 * entered) is handled eagerly in {@link #traceStartTransaction}.
 *
 * <p>Instances are single-use and not thread-safe: create one per block execution, run the block,
 * then read {@link #getCodeAddresses()} and {@link #getOldestAccessedAncestor()} to assemble the
 * witness. System calls (EIP-2935 history, EIP-7002/7251 requests) are traced too — this tracer
 * opts in via {@link #isSystemCallTracingEnabled()} so code read by system-call contracts is
 * collected.
 */
public class WitnessOperationTracer implements BlockAwareOperationTracer {

  private final GasCalculator gasCalculator;
  private final Set<Address> codeAddresses = new LinkedHashSet<>();
  // Oldest block number (inclusive) whose header the stateless executor must receive. Initialized
  // to the parent block number (always required) and extended left by each successful BLOCKHASH.
  private long oldestAccessedAncestor = Long.MAX_VALUE;

  /**
   * Creates a tracer for a single block execution.
   *
   * @param gasCalculator the gas calculator for the fork being executed; used to reproduce the
   *     static cost of call opcodes when inferring EIP-7702 delegation resolution.
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

  /**
   * Records code that transaction *validation* reads, before any EVM frame runs. Three sources:
   *
   * <ul>
   *   <li>the sender's code, read by the EIP-3607 check (a sender with code is only valid when that
   *       code is a delegation designator);
   *   <li>a delegated direct call target, whose designator is read while charging the delegation
   *       access cost;
   *   <li>EIP-7702 authority codes as of the parent block, read while processing the transaction's
   *       authorization list.
   * </ul>
   */
  @Override
  public void traceStartTransaction(final WorldView worldView, final Transaction transaction) {
    // Sender: validation reads the sender's code to apply the EIP-3607 rule (reject senders with
    // code, unless it is a 7702 delegation designator). A stateless executor must replay that
    // check, so any non-empty sender code goes into the witness. Empty-code senders (the normal
    // EOA case) contribute nothing.
    final Address sender = transaction.getSender();
    final var senderAccount = worldView.get(sender);
    if (senderAccount != null && !senderAccount.getCodeHash().equals(Hash.EMPTY)) {
      codeAddresses.add(sender);
    }

    // Direct call to a delegated account ("alice"): her designator is read when the delegation
    // access cost is charged, which happens before the top-level frame is created. If the
    // transaction OOGs on that very charge, no frame is ever entered and traceContextEnter never
    // fires — so the designator read would be missed. Record alice eagerly here; the target T is
    // intentionally NOT recorded, because whether T's code was reached is only known once the
    // frame actually runs (traceContextEnter covers that).
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

    // EIP-7702 authorization list: applying each tuple reads the authority's existing code (it
    // must be empty or a designator for the delegation to apply). The witness proves state
    // against the PARENT block's root, so what must be shipped is each authority's code as it
    // existed before this block — hence parentWorldView, not worldView: an earlier transaction
    // in this same block may already have rewritten the authority's code, and shipping that
    // in-block value would not verify against the parent root. (worldView is only a fallback for
    // uses where traceStartBlock was never invoked.)
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

  /**
   * Records the bytecode of each entered MESSAGE_CALL frame — the primary collection point: any
   * code that actually executes passes through here (top-level transaction calls, child calls,
   * system calls). The pre/post-execution machinery exists only for reads that happen
   * <em>without</em> a frame being entered (EXTCODE*, failed calls, delegation resolution).
   */
  @Override
  public void traceContextEnter(final MessageFrame frame) {
    // CONTRACT_CREATION frames execute initcode supplied by the transaction payload (or by the
    // creating frame), not code loaded from state — nothing to ship for those.
    if (frame.getType() != MessageFrame.Type.MESSAGE_CALL) return;

    // The frame's contract address is whose code slot was read to run this frame.
    final Address contract = frame.getContractAddress();
    codeAddresses.add(contract);

    // EIP-7702: for a delegated account the frame executes the TARGET's bytecode while the frame's
    // contract address stays the delegator's ("alice"). Reaching this point means the delegation
    // was resolved and the target's code loaded, so both codes are needed: alice's designator
    // (recorded above) to replay the resolution, and the target's bytecode (recorded here) to
    // replay the execution.
    final var account = frame.getWorldUpdater().get(contract);
    if (account == null) return;
    final var code = account.getCode();
    if (CodeDelegationHelper.hasCodeDelegation(code)) {
      codeAddresses.add(CodeDelegationHelper.getTargetAddress(code));
    }
  }

  /**
   * Captures target addresses and block numbers before each opcode executes.
   *
   * <p>Nothing is committed here: at pre-execution time the operands are still on the stack but the
   * opcode has not yet charged gas, so we cannot know whether it will actually perform its code
   * read (it may OOG on the access cost first). Each case therefore only snapshots what
   * post-execution needs to decide, keyed by frame where the opcode could interleave with child
   * frames.
   */
  @Override
  public void tracePreExecution(final MessageFrame frame) {
    final int opcode = frame.getCurrentOperation().getOpcode();
    switch (opcode) {
      case 0x3B, 0x3C -> { // EXTCODESIZE, EXTCODECOPY
        // The target address is consumed off the stack by the opcode, so it must be read now.
        // Commit is deferred: if the opcode OOGs on its account-access cost, the code was never
        // read and must not enter the witness.
        if (frame.stackSize() >= 1) {
          pendingCodeAddr.put(frame, Words.toAddress(frame.getStackItem(0)));
        }
      }
      case 0x40 -> { // BLOCKHASH
        // Snapshot the requested block number; whether it maps to a real ancestor (non-zero hash,
        // within the lookback window) is only known from the opcode's output in post-execution.
        // A plain field (not per-frame) suffices: BLOCKHASH spawns no child frame, so its
        // pre/post pair can never interleave with another frame's.
        if (frame.stackSize() >= 1)
          pendingBlockHashNumber = Words.clampedToLong(frame.getStackItem(0));
      }
      case 0xF1, 0xF2, 0xF4, 0xFA -> { // CALL, CALLCODE, DELEGATECALL, STATICCALL
        // CALL/CALLCODE carry a value operand, so their stack layout is one item deeper.
        final int minStack = (opcode == 0xF1 || opcode == 0xF2) ? 7 : 6;
        if (frame.stackSize() >= minStack) {
          final Address alice = Words.toAddress(frame.getStackItem(1));
          final var aliceAccount = frame.getWorldUpdater().get(alice);
          if (aliceAccount != null
              && CodeDelegationHelper.hasCodeDelegation(aliceAccount.getCode())) {
            // EIP-7702: the callee "alice" holds a delegation designator pointing at target T.
            // Resolving the delegation reads alice's code — and that read can happen even if the
            // call later fails, or not happen at all if the opcode OOGs before the resolution
            // charge. Whether it ran is not directly observable, so snapshot the two signals
            // post-execution will use to infer it:
            //   1. T's warmth now — if T is cold here and warm afterwards, only the resolution
            //      step can have warmed it.
            //   2. The opcode's static gas cost, recomputed here with the same inputs the EVM
            //      uses — if the reported cost afterwards exceeds it, the dynamic
            //      delegation-resolution charge was applied. Needed when signal 1 is
            //      inconclusive because T was already warm.
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
            // Non-delegated callee: treat like EXTCODESIZE — record the address, commit in
            // post-execution unless the opcode halted exceptionally. Once the gas checks pass,
            // AbstractCallOperation reads the callee's code even on soft failures (insufficient
            // balance, max call depth), which complete without a halt — so those reads are
            // captured here, while traceContextEnter only sees calls that actually enter a
            // frame. An OOG on the access charge halts the opcode and the read never happens.
            pendingCodeAddr.put(frame, alice);
          }
        }
      }
      default -> {}
    }
  }

  /**
   * Resolves the snapshots taken in {@link #tracePreExecution} now that the opcode's outcome (halt
   * reason, gas cost, resulting stack and warmth) is known, and commits only the accesses that
   * actually happened.
   */
  @Override
  public void tracePostExecution(final MessageFrame frame, final OperationResult operationResult) {
    // BLOCKHASH: commit the ancestor only if the opcode completed and returned a non-zero hash.
    // A zero result means the requested number was out of the lookback window (or >= current
    // block), so no header was accessed and the witness range must not be extended. The pending
    // number is always cleared — post-execution runs for this opcode exactly once.
    if (pendingBlockHashNumber >= 0) {
      if (operationResult.getHaltReason() == null && frame.stackSize() >= 1) {
        final Bytes32 hashBytes = Bytes32.leftPad(frame.getStackItem(0));
        if (!hashBytes.isZero()) {
          oldestAccessedAncestor = Math.min(oldestAccessedAncestor, pendingBlockHashNumber);
        }
      }
      pendingBlockHashNumber = -1;
    }

    // EXTCODESIZE / EXTCODECOPY / non-delegated calls: a null halt reason means the opcode paid
    // its access charge and performed the read. An exceptional halt (OOG on the access cost)
    // means the code was never touched, so the address is dropped.
    final Address pendingAddr = pendingCodeAddr.remove(frame);
    if (pendingAddr != null && operationResult.getHaltReason() == null) {
      codeAddresses.add(pendingAddr);
    }

    // Call to an EIP-7702-delegated account: infer whether the delegation-resolution step ran.
    // That step reads alice's code (the designator), so it alone determines whether alice's
    // bytecode belongs in the witness — independently of whether the call ultimately succeeded.
    final PendingDelegationInfo info = pendingCallDelegations.remove(frame);
    if (info != null) {
      final boolean alicesCodeWasRead;
      if (!info.targetWasWarm() && frame.isAddressWarm(info.delegationTarget())) {
        // Signal 1: the target T went cold -> warm across this opcode. Only the delegation
        // resolution warms T at this point, so the step definitely ran. This is the reliable
        // signal, but it is mute when T was already warm before the opcode.
        alicesCodeWasRead = true;
      } else {
        // Signal 2 (T already warm, or still cold): compare the opcode's reported gas cost with
        // the static cost pre-computed from the same operands. The static cost is everything the
        // opcode charges *before* delegation resolution; any excess means the dynamic
        // resolution charge was applied, i.e. alice's designator was read. Equality means the
        // opcode stopped at (or OOG'd on) the static portion and never reached resolution.
        alicesCodeWasRead = (operationResult.getGasCost() > info.pendingStaticCost());
      }
      if (alicesCodeWasRead) {
        codeAddresses.add(info.aliceAddress());
        // T's code is only read when the call proceeds past resolution into execution. If the
        // opcode halted (e.g. OOG on the resolution charge itself), alice's designator was read
        // but T's code was not.
        if (operationResult.getHaltReason() == null) {
          codeAddresses.add(info.delegationTarget());
        }
      }
    }
  }

  /**
   * Returns the addresses whose bytecode must appear in the witness {@code codes} list, in
   * first-access order.
   *
   * @return an unmodifiable view of the collected code addresses
   */
  public Set<Address> getCodeAddresses() {
    return Collections.unmodifiableSet(codeAddresses);
  }

  /**
   * Returns the oldest block number (inclusive) whose header must appear in the witness {@code
   * headers} list. After a block has been traced this is at most {@code parentNumber}; it moves
   * further back only when BLOCKHASH successfully resolved an older ancestor.
   *
   * @return the oldest required ancestor block number
   */
  public long getOldestAccessedAncestor() {
    return oldestAccessedAncestor;
  }
}
