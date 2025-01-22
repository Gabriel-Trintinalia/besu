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
package org.hyperledger.besu.ethereum.transaction;

import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.StateOverride;
import org.hyperledger.besu.datatypes.StateOverrideMap;
import org.hyperledger.besu.ethereum.core.MutableWorldState;
import org.hyperledger.besu.evm.account.MutableAccount;

import com.google.common.annotations.VisibleForTesting;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.units.bigints.UInt256;

public class BlockStateOverrider {

  /**
   * Applies state overrides to the world state.
   *
   * @param stateOverrideMap The StateOverrideMap containing the state overrides.
   * @param ws The MutableWorldState to apply the overrides to.
   */
  @VisibleForTesting
  protected static void applyStateOverrides(
      final StateOverrideMap stateOverrideMap, final MutableWorldState ws) {
    var updater = ws.updater();
    for (Address accountToOverride : stateOverrideMap.keySet()) {
      final StateOverride override = stateOverrideMap.get(accountToOverride);
      MutableAccount account = updater.getOrCreate(accountToOverride);
      override.getNonce().ifPresent(account::setNonce);
      if (override.getBalance().isPresent()) {
        account.setBalance(override.getBalance().get());
      }
      override.getCode().ifPresent(n -> account.setCode(Bytes.fromHexString(n)));
      override
          .getStateDiff()
          .ifPresent(
              d ->
                  d.forEach(
                      (key, value) ->
                          account.setStorageValue(
                              UInt256.fromHexString(key), UInt256.fromHexString(value))));
    }
    updater.commit();
  }
}
