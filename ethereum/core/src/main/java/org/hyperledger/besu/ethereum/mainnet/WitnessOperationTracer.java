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
import org.hyperledger.besu.datatypes.StorageSlotKey;
import org.hyperledger.besu.datatypes.Transaction;
import org.hyperledger.besu.plugin.data.Withdrawal;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.internal.Words;
import org.hyperledger.besu.evm.operation.Operation.OperationResult;
import org.hyperledger.besu.evm.worldstate.WorldView;
import org.hyperledger.besu.plugin.data.BlockBody;
import org.hyperledger.besu.plugin.data.BlockHeader;
import org.hyperledger.besu.plugin.data.ProcessableBlockHeader;
import org.hyperledger.besu.plugin.services.tracer.BlockAwareOperationTracer;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.units.bigints.UInt256;

/**
 * Collects all EVM state access needed to build an EIP-8025 execution witness in a single pass
 * over the operation trace, without relying on the BlockAccessList or TrieLog.
 *
 * <p>Two collections are built:
 *
 * <ul>
 *   <li>{@link #getTouchedAccounts()} — every address whose account trie path or storage trie path
 *       must be proved: accounts whose state actually <em>changes</em> (balance, nonce, code,
 *       storage), or whose storage slots are read. Pure reads of account metadata (BALANCE,
 *       EXTCODESIZE, EXTCODEHASH) do not require account trie proofs per the EIP-8025 witness
 *       policy and are therefore excluded.
 *   <li>{@link #getCodeAddresses()} — addresses whose bytecode must appear in the witness {@code
 *       codes} list (MESSAGE_CALL targets, EXTCODECOPY sources, soft-failed call targets, and the
 *       transaction sender for EIP-7702 delegation designators).
 *   <li>{@link #getAccessedAncestors()} — block-number→hash pairs for all ancestors that must
 *       appear in the witness {@code headers} list (always includes the parent; extended by BLOCKHASH
 *       opcode results).
 * </ul>
 */
public class WitnessOperationTracer implements BlockAwareOperationTracer {

  private final Map<Address, Set<StorageSlotKey>> touchedAccounts = new LinkedHashMap<>();
  private final Set<Address> codeAddresses = new LinkedHashSet<>();
  private final Map<Long, Hash> accessedAncestors = new LinkedHashMap<>();

  // Transient: block number from BLOCKHASH pre-execution; consumed in tracePostExecution
  private long pendingBlockHashNumber = -1;

  // --- Block lifecycle ---

  @Override
  public void traceStartBlock(
      final WorldView worldView,
      final BlockHeader blockHeader,
      final BlockBody blockBody,
      final Address miningBeneficiary) {
    recordParentAndMiner(blockHeader, miningBeneficiary);
  }

  @Override
  public void traceStartBlock(
      final WorldView worldView,
      final ProcessableBlockHeader processableBlockHeader,
      final Address miningBeneficiary) {
    recordParentAndMiner(processableBlockHeader, miningBeneficiary);
  }

  private void recordParentAndMiner(
      final ProcessableBlockHeader header, final Address miningBeneficiary) {
    // EIP-8025: parent header is always required in the witness
    accessedAncestors.put(header.getNumber() - 1, header.getParentHash());
    // Miner's balance changes (receives transaction fees)
    touchAccount(miningBeneficiary);
  }

  @Override
  public void traceEndBlock(final BlockHeader blockHeader, final BlockBody blockBody) {
    // Withdrawal recipients have their ETH balances updated without any EVM execution
    blockBody
        .getWithdrawals()
        .ifPresent(
            withdrawals -> {
              for (final Withdrawal w : withdrawals) {
                touchAccount(w.getAddress());
              }
            });
    // Ommer (uncle) miners receive block rewards outside the EVM trace
    for (final org.hyperledger.besu.plugin.data.BlockHeader ommer : blockBody.getOmmers()) {
      touchAccount(ommer.getCoinbase());
    }
  }

  // --- Transaction lifecycle ---

  @Override
  public void traceStartTransaction(final WorldView worldView, final Transaction transaction) {
    // Sender: nonce always incremented, balance always debited — guaranteed state change
    touchAccount(transaction.getSender());
    // EIP-7702: sender may be a delegated EOA whose designation code must be in the witness
    codeAddresses.add(transaction.getSender());
    // Recipient: touch if ETH is transferred at the top-level tx — balance always changes for
    // the `to` address when value > 0 (even if the call reverts, EIP-2929 address pre-warming
    // still marks it as accessed). This covers the common case of a simple ETH transfer.
    if (transaction.getValue() != null
        && transaction.getValue().getAsBigInteger().signum() > 0) {
      transaction.getTo().ifPresent(this::touchAccount);
    }
    // EIP-2930: pre-warmed storage slots need trie proofs if they're accessed
    transaction
        .getAccessList()
        .ifPresent(
            entries ->
                entries.forEach(
                    entry -> {
                      final Address addr = entry.address();
                      entry
                          .storageKeys()
                          .forEach(
                              key ->
                                  touchStorage(
                                      addr,
                                      new StorageSlotKey(UInt256.fromBytes(key))));
                    }));
  }

  // --- Context / call stack ---

  @Override
  public void traceContextEnter(final MessageFrame frame) {
    final Address contract = frame.getContractAddress();
    if (frame.getType() == MessageFrame.Type.MESSAGE_CALL) {
      // Code at the call target must be in the witness
      codeAddresses.add(contract);
      // If ETH is transferred, the recipient's balance changes → account trie proof needed
      if (!frame.getValue().isZero()) {
        touchAccount(contract);
      }
    } else {
      // CONTRACT_CREATION: prove the pre-block state at the creation address (was empty/clean)
      touchAccount(contract);
    }
  }

  // --- Per-opcode tracing ---

  @Override
  public void tracePreExecution(final MessageFrame frame) {
    final int opcode = frame.getCurrentOperation().getOpcode();
    switch (opcode) {
      case 0x3C -> { // EXTCODECOPY — code bytes explicitly copied → witness must include them
        if (frame.stackSize() >= 1) {
          final Address addr = Words.toAddress(frame.getStackItem(0));
          codeAddresses.add(addr);
        }
      }
      case 0x40 -> { // BLOCKHASH — capture requested block number; hash read in tracePostExecution
        if (frame.stackSize() >= 1) pendingBlockHashNumber = frame.getStackItem(0).toLong();
      }
      case 0x54 -> { // SLOAD — storage read; account + slot trie proof needed
        if (frame.stackSize() >= 1) {
          touchStorage(
              frame.getContractAddress(),
              new StorageSlotKey(UInt256.fromBytes(frame.getStackItem(0))));
        }
      }
      case 0x55 -> { // SSTORE — storage write; account + slot trie proof needed
        if (frame.stackSize() >= 1) {
          touchStorage(
              frame.getContractAddress(),
              new StorageSlotKey(UInt256.fromBytes(frame.getStackItem(0))));
        }
      }
      case 0xF1, 0xF2, 0xF4, 0xFA -> {
        // CALL / CALLCODE / DELEGATECALL / STATICCALL — address is on stack[1].
        // Captured here so soft-failed calls (insufficient balance, max depth) are also tracked
        // in codeAddresses even though no child frame is created for them.
        if (frame.stackSize() >= 2) {
          codeAddresses.add(Words.toAddress(frame.getStackItem(1)));
        }
      }
      case 0xFF -> { // SELFDESTRUCT — self's balance drains, beneficiary's balance increases
        touchAccount(frame.getContractAddress());
        if (frame.stackSize() >= 1) {
          touchAccount(Words.toAddress(frame.getStackItem(0))); // beneficiary
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
  }

  // --- Helpers ---

  private void touchAccount(final Address address) {
    touchedAccounts.computeIfAbsent(address, a -> new LinkedHashSet<>());
  }

  private void touchStorage(final Address address, final StorageSlotKey slot) {
    touchedAccounts.computeIfAbsent(address, a -> new LinkedHashSet<>()).add(slot);
  }

  // --- Result accessors ---

  /**
   * Returns the map of accounts whose state trie paths must be proved in the witness. This covers
   * accounts with storage access (reads or writes) and accounts whose balance, nonce, or code
   * changed during the block.
   */
  public Map<Address, Set<StorageSlotKey>> getTouchedAccounts() {
    return Collections.unmodifiableMap(touchedAccounts);
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
}
