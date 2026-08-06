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
package org.hyperledger.besu.ethereum.mainnet.witness;

import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.evm.witness.WitnessTracker;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Block-level accumulator for EIP-8025 witness metadata. Accumulates code reads, pre-state code
 * reads, and the oldest BLOCKHASH ancestor across all transactions and system calls in a block.
 * Implements {@link WitnessTracker} so it can be passed directly into the execution pipeline
 * without any per-phase wrapper.
 */
public class BlockWitnessAccumulator implements WitnessTracker {

  private final Set<Address> codeReads = new LinkedHashSet<>();
  private final Set<Address> preStateCodeReads = new LinkedHashSet<>();
  private long oldestAccessedAncestor = Long.MAX_VALUE;

  /**
   * Sets the initial oldest-ancestor floor for this block. Must be called once with {@code
   * blockHeader.getNumber() - 1} so the parent header is always included in the witness.
   *
   * @param blockNumber the block number to use as the initial floor
   */
  public void initOldestAncestor(final long blockNumber) {
    oldestAccessedAncestor = Math.min(oldestAccessedAncestor, blockNumber);
  }

  @Override
  public void addCodeRead(final Address address) {
    codeReads.add(address);
  }

  @Override
  public void addPreStateCodeRead(final Address address) {
    preStateCodeReads.add(address);
  }

  @Override
  public void addOldestAncestor(final long blockNumber) {
    oldestAccessedAncestor = Math.min(oldestAccessedAncestor, blockNumber);
  }

  @Override
  public Set<Address> getCodeReads() {
    return codeReads;
  }

  @Override
  public Set<Address> getPreStateCodeReads() {
    return preStateCodeReads;
  }

  @Override
  public long getOldestAccessedAncestor() {
    return oldestAccessedAncestor;
  }
}
