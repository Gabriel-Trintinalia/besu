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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.tuweni.bytes.Bytes32;

/**
 * Determines which contract bytecodes and ancestor block headers a stateless executor needs to
 * re-execute a block from scratch, producing the {@code codes} and {@code headers} fields of an
 * EIP-8025 execution witness.
 *
 * <h2>Stateless execution model</h2>
 *
 * <p>A stateless executor receives the block, a state witness (trie nodes proving every touched
 * account and storage slot), a list of contract bytecodes ({@code codes}), and a list of ancestor
 * block headers ({@code headers}). It has no local database. Every piece of data it needs must be
 * derivable from those four inputs. This tracer decides exactly which bytecodes and headers are
 * necessary.
 *
 * <h2>Why bytecodes must be explicit</h2>
 *
 * <p>The state witness proves account <em>fields</em> (nonce, balance, storageRoot, codeHash) via
 * Merkle paths. It does not contain the bytecode itself — only its hash. A stateless executor
 * knows <em>that</em> an account has code, but cannot reconstruct the instructions without the
 * actual bytecode. The {@code codes} list supplies these bytecodes so the executor can:
 *
 * <ol>
 *   <li>Load and run the bytecode of every contract it calls (MESSAGE_CALL frames).
 *   <li>Serve EXTCODESIZE / EXTCODECOPY without a local database.
 *   <li>Read EIP-7702 delegation designators ({@code 0xef0100<address>}) to resolve call targets
 *       and compute delegation resolution gas.
 *   <li>Verify intrinsic gas for EIP-7702 transactions, which depends on whether each authority
 *       already held a delegation designator (or any non-empty code) before the block started.
 * </ol>
 *
 * <h2>Sources of required bytecodes (the {@code codes} field)</h2>
 *
 * <p>This tracer collects addresses via four hooks:
 *
 * <dl>
 *   <dt>{@link #traceStartTransaction} — transaction sender, EIP-7702 authorities
 *   <dd>
 *       <p><b>Sender:</b> If the sender account has non-empty code (e.g. it is a 7702-delegated
 *       EOA carrying a delegation designator), the stateless executor must be able to read that
 *       code to resolve the delegation during the transaction's own execution frame.
 *
 *       <p><b>EIP-7702 authorities:</b> For each authorization in the transaction's authorization
 *       list, the EIP-7702 intrinsic gas formula charges extra if the authority already holds
 *       non-empty code at the start of the block. A stateless executor re-running the intrinsic
 *       gas check must therefore have the authority's <em>pre-block</em> bytecode available.
 *       Because delegation processing (which may clear an authority's code) runs <em>before</em>
 *       {@code traceStartTransaction} is called, we read authority codes from the parent-state
 *       snapshot saved at {@link #traceStartBlock} rather than from the current world view.
 *
 *   <dt>{@link #traceContextEnter} — MESSAGE_CALL frame entry
 *   <dd>
 *       <p>Each time the EVM enters a new message-call frame the bytecode at
 *       {@code frame.getContractAddress()} is about to be executed. The stateless executor must
 *       have that bytecode to run the frame.
 *
 *       <p>For EIP-7702 delegated accounts the contract address holds a delegation designator
 *       ({@code 0xef0100<T>}) rather than real code. The EVM transparently executes the bytecode
 *       of target {@code T} instead. The stateless executor must therefore also have {@code T}'s
 *       bytecode available.
 *
 *   <dt>{@link #tracePreExecution} / {@link #tracePostExecution} — EXTCODESIZE, EXTCODECOPY,
 *       CALL-family soft failures
 *   <dd>
 *       <p><b>EXTCODESIZE (0x3B) and EXTCODECOPY (0x3C):</b> These opcodes read the bytecode of
 *       an <em>external</em> account without entering a new frame. The stateless executor needs
 *       the bytecode to serve them. We defer adding the address until {@code tracePostExecution}
 *       to exclude OOG aborts (if the opcode ran out of gas the code was never accessed).
 *       EXTCODEHASH (0x3F) is intentionally excluded: the verifier can derive the hash from the
 *       account's {@code codeHash} field in the state witness without needing the full bytecode.
 *
 *       <p><b>CALL / CALLCODE / DELEGATECALL / STATICCALL — soft failures:</b>
 *       {@code AbstractCallOperation.execute()} reads the target account (to determine delegation
 *       and delegation-resolution gas) before the balance and call-depth checks. A call that fails
 *       these later checks ("soft failure") never creates a child frame, so {@code
 *       traceContextEnter} is never invoked — yet the bytecode was accessed. We detect whether the
 *       account was read using a gas-accounting signal:
 *       <ul>
 *         <li>Non-delegated target: {@code haltReason == null} (no OOG at the pre-read checks)
 *             means the account was read and its code must be in the witness.
 *         <li>Delegated target ("alice"): a more precise gas-delta method distinguishes whether
 *             the OOG fired before or after the delegation-resolution step that reads alice's code.
 *             When alice's delegation target {@code T} was cold and is now warm, the resolution
 *             step ran. When {@code T} was already warm, we compare the reported gas cost to the
 *             pre-computed static cost (alice-access + memory expansion + value transfer) to
 *             determine whether resolution ran.
 *       </ul>
 *       The delegation target {@code T} is added only when the call completed without an
 *       exceptional halt ({@code haltReason == null}), because only then did the EVM record
 *       {@code T} in the Block Access List and continue toward execution.
 * </dl>
 *
 * <h2>Sources of required ancestor headers (the {@code headers} field)</h2>
 *
 * <p>The BLOCKHASH opcode and the EIP-2935 history contract both expose ancestor block hashes to
 * running contracts. A stateless executor needs the corresponding headers to verify those hashes.
 * The parent header is always required (the executor needs it to validate the block). Additional
 * ancestors are recorded whenever a successful BLOCKHASH opcode pushes a non-zero result.
 *
 * <h2>Timing constraint: EIP-7702 delegation processing precedes tracing</h2>
 *
 * <p>EIP-7702 set-code transactions apply their authorization list <em>before</em> EVM execution
 * begins. By the time {@link #traceStartTransaction} fires, any authority whose authorization
 * succeeded (including clearings where a new delegation overwrites an existing one) already has
 * its post-delegation code in the world state. To see the <em>pre-block</em> code — which is what
 * the intrinsic gas formula observed — we must use the parent-state snapshot captured in
 * {@link #traceStartBlock}.
 */
public class WitnessOperationTracer implements BlockAwareOperationTracer {

  private final GasCalculator gasCalculator;
  private final Set<Address> codeAddresses = new LinkedHashSet<>();
  private final Map<Long, Hash> accessedAncestors = new LinkedHashMap<>();

  /**
   * @param gasCalculator the gas calculator for the fork being executed; used to compute memory
   *     expansion costs when detecting whether a delegated-CALL's resolution step ran.
   */
  public WitnessOperationTracer(final GasCalculator gasCalculator) {
    this.gasCalculator = gasCalculator;
  }

  /**
   * Parent-state snapshot saved at {@link #traceStartBlock}. Used in {@link
   * #traceStartTransaction} to read EIP-7702 authority codes as they existed before the block
   * started. Delegation processing runs before tracing begins, so the current world view may
   * already reflect cleared or overwritten delegation designators.
   */
  private WorldView parentWorldView = null;

  /**
   * Block number from a BLOCKHASH opcode captured in {@link #tracePreExecution}; consumed and
   * reset in {@link #tracePostExecution}. {@code -1} means no BLOCKHASH is pending.
   */
  private long pendingBlockHashNumber = -1;

  /**
   * Snapshot of call-time state needed in {@link #tracePostExecution} to decide whether
   * {@code AbstractCallOperation.execute()} read the delegation target account.
   *
   * <p>The EVM reads alice's code (and thus the delegation designator pointing at T) during the
   * delegation-resolution gas step, which comes after the static-cost check but before the
   * call-depth / balance check. We detect whether that step ran using two signals:
   *
   * <ul>
   *   <li>If T was cold before the opcode and is warm afterward, the resolution step ran (it
   *       warms T as a side effect) → alice's code was read.
   *   <li>If T was already warm, its warm/cold state doesn't change. We fall back to comparing
   *       the reported gas cost to the pre-computed static cost: the static cost covers alice's
   *       access + memory expansion + value transfer, but not the delegation-resolution cost.
   *       {@code gasCost > staticCost} implies the resolution step ran.
   * </ul>
   */
  private record PendingDelegationInfo(
      Address aliceAddress,    // call target that carries a delegation designator
      Address delegationTarget, // T — the address alice delegates to
      boolean targetWasWarm,   // warm/cold state of T before the opcode ran
      long pendingStaticCost)  // aliceAccessCost + memExpansion + valueTransferCost (no child gas)
  {}

  /** Keyed by frame identity so concurrent nested calls don't interfere with each other. */
  private final IdentityHashMap<MessageFrame, PendingDelegationInfo> pendingCallDelegations =
      new IdentityHashMap<>();

  /**
   * Address pending from the last EXTCODESIZE, EXTCODECOPY, or non-delegated CALL opcode in a
   * frame. Captured in {@link #tracePreExecution} and committed to {@link #codeAddresses} in
   * {@link #tracePostExecution} only when the opcode did not OOG ({@code haltReason == null}).
   * Opcodes execute sequentially within a frame, so at most one entry exists per frame at a time.
   * Delegated CALL targets are handled separately via {@link #pendingCallDelegations}.
   */
  private final IdentityHashMap<MessageFrame, Address> pendingCodeAddr =
      new IdentityHashMap<>();

  // ---------------------------------------------------------------------------
  // Block lifecycle
  // ---------------------------------------------------------------------------

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

  /**
   * The parent header is unconditionally required: the stateless executor needs it to verify the
   * block's {@code parentHash} field and to seed the EIP-2935 history contract.
   */
  private void recordParentHeader(final ProcessableBlockHeader header) {
    accessedAncestors.put(header.getNumber() - 1, header.getParentHash());
  }

  // ---------------------------------------------------------------------------
  // Transaction start — sender code and EIP-7702 authority codes
  // ---------------------------------------------------------------------------

  /**
   * Records codes that a stateless executor needs before the first opcode of a transaction runs.
   *
   * <p><b>Sender code:</b> If the sender is a 7702-delegated EOA (its account holds a delegation
   * designator {@code 0xef0100<T>}), the stateless executor must be able to read that designator
   * so it can resolve the sender's code pointer when the sender's own frame is executed. Any other
   * non-empty sender code is included for the same reason.
   *
   * <p><b>EIP-7702 authority codes (pre-block state):</b> The EIP-7702 intrinsic gas formula
   * charges {@code PER_EMPTY_ACCOUNT_COST} for each authorization whose authority currently has
   * empty code, and a lower base cost when the authority already carries code. To reproduce this
   * gas calculation, the stateless executor must see the authority's <em>pre-block</em> bytecode.
   *
   * <p>Critically, this method is called <em>after</em> delegation processing has already modified
   * the world state (setting or clearing designation codes). We therefore read authority codes from
   * {@link #parentWorldView} — the snapshot of the world state before any block processing ran —
   * rather than from the {@code worldView} parameter, which reflects post-delegation state.
   */
  @Override
  public void traceStartTransaction(final WorldView worldView, final Transaction transaction) {
    final Address sender = transaction.getSender();
    final var senderAccount = worldView.get(sender);
    if (senderAccount != null && !senderAccount.getCodeHash().equals(Hash.EMPTY)) {
      codeAddresses.add(sender);
    }

    final WorldView authorityView = parentWorldView != null ? parentWorldView : worldView;
    for (final var delegation : transaction.getCodeDelegationList().orElse(List.of())) {
      delegation.authorizer().ifPresent(auth -> {
        final var authAccount = authorityView.get(auth);
        // Any non-empty pre-block code must be in the witness so the stateless executor can
        // reproduce the intrinsic gas check (plain 0x00, non-designator, and designator alike).
        if (authAccount != null && !authAccount.getCodeHash().equals(Hash.EMPTY)) {
          codeAddresses.add(auth);
        }
      });
    }
  }

  // ---------------------------------------------------------------------------
  // Frame entry — executed contract and its 7702 delegation target
  // ---------------------------------------------------------------------------

  /**
   * Records bytecodes that a stateless executor needs to run a MESSAGE_CALL frame.
   *
   * <p><b>Contract bytecode:</b> The address at {@code frame.getContractAddress()} is about to be
   * executed. The stateless executor must have its bytecode to run the frame.
   *
   * <p><b>EIP-7702 delegation target:</b> If the contract holds a delegation designator
   * ({@code 0xef0100<T>}), the EVM transparently executes {@code T}'s code instead. The stateless
   * executor must therefore also have {@code T}'s bytecode, even though {@code T}'s frame is not
   * separately visible in the trace — it is co-executed under the contract's address.
   *
   * <p>CONTRACT_CREATION frames are excluded: init code is embedded in the transaction payload and
   * does not need to appear in the {@code codes} list; newly deployed runtime code is absent from
   * the pre-block state and is provided by the transaction itself.
   */
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

  // ---------------------------------------------------------------------------
  // Opcode pre-execution — record pending addresses / block numbers
  // ---------------------------------------------------------------------------

  /**
   * Captures the target address or block number that an opcode is about to access, deferring the
   * actual addition to {@link #codeAddresses} / {@link #accessedAncestors} until
   * {@link #tracePostExecution} confirms the opcode did not OOG.
   *
   * <h3>EXTCODESIZE (0x3B) and EXTCODECOPY (0x3C)</h3>
   *
   * <p>These opcodes read the bytecode of an external account without creating a call frame. A
   * stateless executor needs the bytecode to serve them. We record the target here and commit it
   * in {@link #tracePostExecution} only when the opcode succeeds (no OOG halt), because an OOG
   * abort means the bytecode was never actually read.
   *
   * <p>EXTCODEHASH (0x3F) is intentionally excluded: the verifier can derive the hash from the
   * {@code codeHash} field already present in the account's state-witness proof. The full bytecode
   * is not needed.
   *
   * <h3>BLOCKHASH (0x40)</h3>
   *
   * <p>A successful BLOCKHASH returns a non-zero hash only when the requested block is within the
   * most recent 256 ancestors. The stateless executor needs the corresponding block header to
   * verify that hash. We record the requested block number here and capture the returned hash in
   * {@link #tracePostExecution}.
   *
   * <h3>CALL / CALLCODE / DELEGATECALL / STATICCALL (0xF1, 0xF2, 0xF4, 0xFA)</h3>
   *
   * <p>{@code AbstractCallOperation.execute()} reads the target account unconditionally before the
   * balance and call-depth checks. If either of those later checks fails ("soft failure"), no child
   * frame is created and {@link #traceContextEnter} is never called — yet the bytecode was
   * accessed. We must capture the address here and decide in {@link #tracePostExecution} whether
   * the access actually occurred.
   *
   * <p>For non-delegated targets the decision is simple: {@code haltReason == null} means the
   * pre-read gas checks passed and the account was read.
   *
   * <p>For delegated targets (alice carries {@code 0xef0100<T>}) the decision is more nuanced.
   * The account-read happens inside the delegation-resolution gas step (check 3), which runs after
   * the static-cost check (check 2) but before the balance / depth check (check 4). An OOG at
   * check 3 means alice's code was read but {@code T}'s warm/cold status may still have changed.
   * We disambiguate using a gas-delta signal (see {@link PendingDelegationInfo}).
   */
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
        // Words.clampedToLong handles stack values > Long.MAX_VALUE (e.g. 2**64) without throwing;
        // such values always produce a zero BLOCKHASH result and need no header in the witness.
        if (frame.stackSize() >= 1) pendingBlockHashNumber = Words.clampedToLong(frame.getStackItem(0));
      }
      case 0xF1, 0xF2, 0xF4, 0xFA -> { // CALL, CALLCODE, DELEGATECALL, STATICCALL
        final int minStack = (opcode == 0xF1 || opcode == 0xF2) ? 7 : 6;
        if (frame.stackSize() >= minStack) {
          final Address alice = Words.toAddress(frame.getStackItem(1));
          final var aliceAccount = frame.getWorldUpdater().get(alice);
          if (aliceAccount != null
              && CodeDelegationHelper.hasCodeDelegation(aliceAccount.getCode())) {
            // Alice is a delegated account. Capture state needed to detect whether the
            // delegation-resolution gas step (which reads alice's code) actually ran.
            final Address T = CodeDelegationHelper.getTargetAddress(aliceAccount.getCode());
            final boolean aliceWasWarm = frame.isAddressWarm(alice);
            final boolean tWasWarm = frame.isAddressWarm(T);
            // Reconstruct the static cost (checks 1+2); any gas above this signals that
            // check 3 (delegation resolution) also ran.
            final int argsBase = (opcode == 0xF1 || opcode == 0xF2) ? 3 : 2;
            final Wei transferValue = (opcode == 0xF1 || opcode == 0xF2)
                ? Wei.wrap(frame.getStackItem(2)) : Wei.ZERO;
            final long staticCost = gasCalculator.callOperationStaticGasCost(
                frame, 0L,
                Words.clampedToLong(frame.getStackItem(argsBase)),
                Words.clampedToLong(frame.getStackItem(argsBase + 1)),
                Words.clampedToLong(frame.getStackItem(argsBase + 2)),
                Words.clampedToLong(frame.getStackItem(argsBase + 3)),
                transferValue, alice, aliceWasWarm);
            pendingCallDelegations.put(frame, new PendingDelegationInfo(alice, T, tWasWarm, staticCost));
          } else {
            // Non-delegated alice: account-read is implied by passing checks 1+2.
            pendingCodeAddr.put(frame, alice);
          }
        }
      }
      default -> {}
    }
  }

  // ---------------------------------------------------------------------------
  // Opcode post-execution — commit addresses / headers if the opcode succeeded
  // ---------------------------------------------------------------------------

  /**
   * Finalises the additions to {@link #codeAddresses} and {@link #accessedAncestors} that were
   * deferred in {@link #tracePreExecution}.
   *
   * <h3>BLOCKHASH</h3>
   *
   * <p>If the opcode succeeded (no OOG) and returned a non-zero hash, the stateless executor will
   * need the corresponding header to verify that hash. We record the block-number → hash pair. A
   * zero result means the requested block is out of the 256-ancestor window and no header is needed.
   *
   * <h3>Non-delegated CALL targets</h3>
   *
   * <p>A {@code haltReason == null} result indicates the opcode did not OOG at checks 1 or 2,
   * meaning {@code AbstractCallOperation.execute()} read the target account. The stateless executor
   * needs the target's bytecode regardless of whether the call created a child frame (for soft
   * failures) or not (for successful calls, {@link #traceContextEnter} also adds the address, and
   * the duplicate is harmless).
   *
   * <h3>EXTCODESIZE / EXTCODECOPY targets</h3>
   *
   * <p>Same principle: add the address only when the opcode completed without OOG.
   *
   * <h3>Delegated CALL targets (alice and T)</h3>
   *
   * <p>We use the gas-delta signal described in {@link PendingDelegationInfo} to determine whether
   * alice's code was read:
   *
   * <ul>
   *   <li>If T was cold before the opcode and is warm now, the delegation-resolution step ran and
   *       alice's code was read.
   *   <li>If T was already warm, we compare the reported gas cost to {@link
   *       PendingDelegationInfo#pendingStaticCost}: a cost strictly greater than the static cost
   *       means the resolution step also ran.
   * </ul>
   *
   * <p>When alice's code was read, alice is added unconditionally (the stateless executor needs the
   * designator to resolve the delegation). The delegation target T is added only when
   * {@code haltReason == null}: a null halt reason means the EVM proceeded past the balance/depth
   * check and recorded T in the Block Access List. For an OOG at check 3 the BAL recording never
   * runs and T does not need to be in the witness.
   */
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

    final Address pendingAddr = pendingCodeAddr.remove(frame);
    if (pendingAddr != null && operationResult.getHaltReason() == null) {
      codeAddresses.add(pendingAddr);
    }

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

  // ---------------------------------------------------------------------------
  // Result accessors
  // ---------------------------------------------------------------------------

  /**
   * Returns the set of addresses whose bytecode must appear in the witness {@code codes} list so
   * that a stateless executor can re-execute the block without a local database.
   */
  public Set<Address> getCodeAddresses() {
    return Collections.unmodifiableSet(codeAddresses);
  }

  /**
   * Returns every block-number → hash pair that must appear in the witness {@code headers} list.
   * The parent header is always present; additional entries are added for each non-zero BLOCKHASH
   * result observed during execution.
   */
  public Map<Long, Hash> getAccessedAncestors() {
    return Collections.unmodifiableMap(accessedAncestors);
  }

}
