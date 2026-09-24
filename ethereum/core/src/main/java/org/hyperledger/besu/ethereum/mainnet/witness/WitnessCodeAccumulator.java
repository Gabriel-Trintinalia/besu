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
package org.hyperledger.besu.ethereum.mainnet.witness;

import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.ethereum.WitnessCodeReads;
import org.hyperledger.besu.ethereum.mainnet.witness.WitnessCodeTracker.CodeAccesses;
import org.hyperledger.besu.ethereum.mainnet.witness.WitnessCodeTracker.CodeRead;

import java.util.HashSet;
import java.util.Set;

/**
 * Combines the transactions' {@link CodeAccesses} into the block's EIP-8025 code reads. Mirrors the
 * EELS block-level {@code code_writes}: a read of code an earlier transaction wrote is satisfied
 * from it and needs no witness entry.
 */
public class WitnessCodeAccumulator {

  private final Set<Address> codeReads = new HashSet<>();
  private final Set<Hash> codeWrites = new HashSet<>();

  /**
   * Applies one transaction's accesses. Must be called in transaction order (also when transactions
   * were pre-executed in parallel), so that {@code codeWrites} holds exactly the writes of the
   * transactions before this one.
   *
   * @param accesses the transaction's code accesses
   */
  public void apply(final CodeAccesses accesses) {
    for (final CodeRead read : accesses.reads()) {
      if (!codeWrites.contains(read.codeHash())) {
        codeReads.add(read.address());
      }
    }
    codeWrites.addAll(accesses.writes());
  }

  public WitnessCodeReads toWitnessCodeReads() {
    return new WitnessCodeReads(Set.copyOf(codeReads));
  }
}
