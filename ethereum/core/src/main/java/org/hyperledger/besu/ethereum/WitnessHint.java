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
package org.hyperledger.besu.ethereum;

import org.hyperledger.besu.datatypes.AccountValue;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.datatypes.StorageSlotKey;

import java.util.Map;
import java.util.Set;

/**
 * Snapshot of the accounts, storage slots, and ancestor block hashes accessed during block
 * execution. Captured from the world-state accumulator and block-hash lookup just before {@code
 * persist()} clears them. Carried through {@link BlockProcessingOutputs} so that witness
 * generation can traverse the parent trie directly without reading the trie log back from disk.
 */
public record WitnessHint(
    /** Prior account value for every account touched during execution (null = account was created). */
    Map<Address, AccountValue> priorAccounts,
    /** Storage slot keys touched per account. */
    Map<Address, Set<StorageSlotKey>> touchedSlots,
    /** Ancestor block numbers and hashes accessed via BLOCKHASH during execution. */
    Map<Long, Hash> accessedBlockHashes) {}
