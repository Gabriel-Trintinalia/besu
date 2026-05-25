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
package org.hyperledger.besu.ethereum.trie.pathbased.bonsai.storage.witness;

import org.hyperledger.besu.datatypes.AccountValue;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.StorageSlotKey;
import org.hyperledger.besu.ethereum.WitnessHint;
import org.hyperledger.besu.ethereum.mainnet.AbstractBlockProcessor;
import org.hyperledger.besu.ethereum.trie.pathbased.bonsai.worldview.BonsaiWorldState;
import org.hyperledger.besu.ethereum.trie.pathbased.bonsai.worldview.BonsaiWorldStateUpdateAccumulator;
import org.hyperledger.besu.evm.blockhash.BlockHashLookup;
import org.hyperledger.besu.plugin.services.worldstate.MutableWorldState;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public class BonsaiPostprocessingFunction implements AbstractBlockProcessor.PostprocessingFunction {

  private Optional<WitnessHint> witnessHint = Optional.empty();

  @Override
  public void accept(final MutableWorldState worldState, final BlockHashLookup blockHashLookup) {
    if (!(worldState instanceof BonsaiWorldState bonsaiWorldState)) {
      return;
    }
    final BonsaiWorldStateUpdateAccumulator acc =
        (BonsaiWorldStateUpdateAccumulator) bonsaiWorldState.getAccumulator();

    final Map<Address, AccountValue> priorAccounts = new HashMap<>();
    acc.getAccountsToUpdate().forEach((addr, val) -> priorAccounts.put(addr, val.getPrior()));

    final Map<Address, Set<StorageSlotKey>> touchedSlots = new HashMap<>();
    acc.getStorageToUpdate()
        .forEach((addr, slotMap) -> touchedSlots.put(addr, new HashSet<>(slotMap.keySet())));

    witnessHint =
        Optional.of(
            new WitnessHint(
                priorAccounts, touchedSlots, blockHashLookup.getAccessedBlockHashes()));
  }

  public Optional<WitnessHint> getWitnessHint() {
    return witnessHint;
  }
}
