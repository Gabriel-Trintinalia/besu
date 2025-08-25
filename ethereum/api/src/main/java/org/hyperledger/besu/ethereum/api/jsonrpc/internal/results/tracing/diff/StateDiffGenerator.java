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
package org.hyperledger.besu.ethereum.api.jsonrpc.internal.results.tracing.diff;

import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.processor.TransactionTrace;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.results.tracing.TracingUtils;
import org.hyperledger.besu.ethereum.debug.TraceFrame;
import org.hyperledger.besu.evm.account.Account;
import org.hyperledger.besu.evm.account.MutableAccount;
import org.hyperledger.besu.evm.worldstate.WorldUpdater;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Stream;

import org.apache.tuweni.units.bigints.UInt256;

public class StateDiffGenerator {
  final boolean includeUnchangedAccounts;
  public StateDiffGenerator() {
    this(false);
  }

  public StateDiffGenerator(final boolean includeUnchangedAccounts) {
    this.includeUnchangedAccounts = includeUnchangedAccounts;
  }

  public Stream<StateDiffTrace> generateStateDiff(final TransactionTrace transactionTrace) {
    return generate(transactionTrace, false);
  }

  public Stream<StateDiffTrace> generatePreState(final TransactionTrace transactionTrace) {
    return generate(transactionTrace, true);
  }

  private Stream<StateDiffTrace> generate(
      final TransactionTrace transactionTrace, final boolean includeSpecialAccounts) {

    final List<TraceFrame> traceFrames = transactionTrace.getTraceFrames();
    if (traceFrames.isEmpty()) {
      return Stream.empty();
    }

    // This corresponds to the world state after the TX executed
    // It is two deep because of the way we addressed Spurious Dragon.
    final WorldUpdater transactionUpdater =
        traceFrames.getFirst().getWorldUpdater().parentUpdater().get().parentUpdater().get();
    // This corresponds to the world state prior to the TX execution,
    // Either the initial block state or the state of the prior TX
    final WorldUpdater previousUpdater = transactionUpdater.parentUpdater().get();

    final StateDiffTrace stateDiffResult = new StateDiffTrace();

    transactionUpdater
        .getTouchedAccounts()
        .forEach(
            updatedAccount -> {
              final Account rootAccount = previousUpdater.get(updatedAccount.getAddress());
              final Map<String, DiffNode> storageDiff =
                  calculateStorageDiff(rootAccount, updatedAccount);

              final AccountDiff accountDiff =
                  createAccountDiff(rootAccount, updatedAccount, storageDiff);

              if (includeUnchangedAccounts || accountDiff.hasDifference()) {
                stateDiffResult.put(updatedAccount.getAddress().toHexString(), accountDiff);
              }
            });

    transactionUpdater
        .getDeletedAccountAddresses()
        .forEach(
            addr -> {
              final Account deletedAccount = previousUpdater.get(addr);
              if (deletedAccount != null) {
                stateDiffResult.put(addr.toHexString(), createDeletedAccountDiff(deletedAccount));
              }
            });

    if (includeSpecialAccounts) {
      addSpecialAccounts(transactionTrace, stateDiffResult, previousUpdater, transactionUpdater);
    }
    return Stream.of(stateDiffResult);
  }

  private Map<String, DiffNode> calculateStorageDiff(
      final Account rootAccount, final Account updatedAccount) {
    final Map<String, DiffNode> storageDiff = new TreeMap<>();
    ((MutableAccount) updatedAccount)
        .getUpdatedStorage()
        .forEach(
            (key, newValue) -> {
              final String k = key.toHexString();
              final String newValHex = newValue.toHexString();
              final String oldValHex =
                  rootAccount == null ? null : rootAccount.getStorageValue(key).toHexString();

              if (rootAccount == null && !UInt256.ZERO.equals(newValue)) {
                storageDiff.put(k, new DiffNode(null, newValHex));
              } else if (rootAccount != null && !Objects.equals(oldValHex, newValHex)) {
                storageDiff.put(k, new DiffNode(oldValHex, newValHex));
              }
            });
    return storageDiff;
  }

  private AccountDiff createAccountDiff(
      final Account from, final Account to, final Map<String, DiffNode> storage) {
    return new AccountDiff(
        createDiffNode(from, to, StateDiffGenerator::balanceAsHex),
        createDiffNode(from, to, StateDiffGenerator::codeAsHex),
        createDiffNode(from, to, StateDiffGenerator::codeHashAsHex),
        createDiffNode(from, to, StateDiffGenerator::nonceAsHex),
        storage);
  }

  private AccountDiff createDeletedAccountDiff(final Account deletedAccount) {
    return new AccountDiff(
        createDiffNode(deletedAccount, null, StateDiffGenerator::balanceAsHex),
        createDiffNode(deletedAccount, null, StateDiffGenerator::codeAsHex),
        createDiffNode(deletedAccount, null, StateDiffGenerator::codeHashAsHex),
        createDiffNode(deletedAccount, null, StateDiffGenerator::nonceAsHex),
        Collections.emptyMap());
  }

  private void addSpecialAccounts(
      final TransactionTrace transactionTrace,
      final StateDiffTrace stateDiffResult,
      final WorldUpdater previousUpdater,
      final WorldUpdater transactionUpdater) {

    addAccountIfMissing(
        transactionTrace.getTransaction().getSender(),
        stateDiffResult,
        previousUpdater,
        transactionUpdater);

    transactionTrace
        .getTransaction()
        .getTo()
        .ifPresent(
            to -> addAccountIfMissing(to, stateDiffResult, previousUpdater, transactionUpdater));

    transactionTrace
        .getBlock()
        .map(b -> b.getHeader().getCoinbase())
        .ifPresent(
            coinbase ->
                addAccountIfMissing(
                    coinbase, stateDiffResult, previousUpdater, transactionUpdater));
  }

  private void addAccountIfMissing(
      final Address addr,
      final StateDiffTrace stateDiffResult,
      final WorldUpdater previousUpdater,
      final WorldUpdater transactionUpdater) {

    if (!stateDiffResult.containsKey(addr.toHexString())) {
      stateDiffResult.put(
          addr.toHexString(),
          createAccountDiff(
              previousUpdater.get(addr), transactionUpdater.get(addr), Collections.emptyMap()));
    }
  }

  private DiffNode createDiffNode(
      final Account from, final Account to, final Function<Account, String> func) {
    return new DiffNode(Optional.ofNullable(from).map(func), Optional.ofNullable(to).map(func));
  }

  private static String balanceAsHex(final Account account) {
    return TracingUtils.weiAsHex(account.getBalance());
  }

  private static String codeHashAsHex(final Account account) {
    return account.getCodeHash().toHexString();
  }

  private static String codeAsHex(final Account account) {
    return account.getCode().toHexString();
  }

  private static String nonceAsHex(final Account account) {
    return "0x" + Long.toHexString(account.getNonce());
  }
}
