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
package org.hyperledger.besu.ethereum.api.jsonrpc.internal.cache;

import static org.assertj.core.api.Assertions.assertThat;

import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.results.ExecutionWitnessResult;

import java.util.List;

import org.junit.jupiter.api.Test;

class ExecutionWitnessCacheTest {

  @Test
  void putThenGetReturnsStoredWitness() {
    final ExecutionWitnessCache cache = new ExecutionWitnessCache(1024);
    final Hash key = Hash.fromHexStringLenient("0x1");
    final ExecutionWitnessResult witness =
        new ExecutionWitnessResult(List.of("0xabcd"), List.of(), List.of(), List.of("0xdeadbeef"));

    cache.put(key, witness);

    assertThat(cache.get(key)).contains(witness);
    assertThat(cache.size()).isEqualTo(1L);
  }

  @Test
  void missReturnsEmpty() {
    final ExecutionWitnessCache cache = new ExecutionWitnessCache(1024);
    assertThat(cache.get(Hash.fromHexStringLenient("0x9"))).isEmpty();
  }

  @Test
  void differentKeysAreIsolated() {
    final ExecutionWitnessCache cache = new ExecutionWitnessCache(64 * 1024);
    final Hash key1 = Hash.fromHexStringLenient("0x1");
    final Hash key2 = Hash.fromHexStringLenient("0x2");
    final ExecutionWitnessResult w1 =
        new ExecutionWitnessResult(List.of("0xabcd"), List.of(), List.of(), List.of());
    final ExecutionWitnessResult w2 =
        new ExecutionWitnessResult(List.of(), List.of(), List.of(), List.of("0x1234"));

    cache.put(key1, w1);
    cache.put(key2, w2);

    assertThat(cache.get(key1)).contains(w1);
    assertThat(cache.get(key2)).contains(w2);
  }

  @Test
  void overwriteReplacesEntry() {
    final ExecutionWitnessCache cache = new ExecutionWitnessCache(64 * 1024);
    final Hash key = Hash.fromHexStringLenient("0x1");
    final ExecutionWitnessResult first =
        new ExecutionWitnessResult(List.of("0xaa"), List.of(), List.of(), List.of());
    final ExecutionWitnessResult second =
        new ExecutionWitnessResult(List.of("0xbb"), List.of(), List.of(), List.of());

    cache.put(key, first);
    cache.put(key, second);

    assertThat(cache.get(key)).contains(second);
  }
}
