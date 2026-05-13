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

import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.results.ExecutionWitnessResult;
import org.hyperledger.besu.util.cache.MemoryBoundCache;

import java.util.List;
import java.util.Optional;

import org.apache.tuweni.bytes.Bytes32;

/**
 * Byte-bounded cache of EIP-8025 execution witnesses keyed by block hash. Populated by the {@code
 * engine_newPayloadWithWitnessV*} engine API path; consulted by {@code debug_executionWitness} so
 * that hot blocks are served from memory without re-execution.
 */
public class ExecutionWitnessCache {

  /** Default cache budget when no explicit value is configured. */
  public static final long DEFAULT_MAX_BYTES = 256L * 1024 * 1024;

  private final MemoryBoundCache<Hash, ExecutionWitnessResult> cache;

  /**
   * @param maxBytes upper bound on the cache footprint in bytes; entries are evicted by
   *     least-recently-used when the budget is exceeded.
   */
  public ExecutionWitnessCache(final long maxBytes) {
    this.cache = new MemoryBoundCache<>(maxBytes, ExecutionWitnessCache::weigh);
  }

  public Optional<ExecutionWitnessResult> get(final Hash blockHash) {
    return Optional.ofNullable(cache.getIfPresent(blockHash));
  }

  public void put(final Hash blockHash, final ExecutionWitnessResult witness) {
    cache.put(blockHash, witness);
  }

  /** Returns the number of entries currently held. */
  public long size() {
    return cache.estimatedSize();
  }

  /**
   * Approximates a witness's memory footprint by summing the character lengths of every hex string
   * across the witness fields plus the cache key hash. The actual JVM heap cost is dominated by
   * these strings.
   */
  private static int weigh(final Hash key, final ExecutionWitnessResult witness) {
    int bytes = Bytes32.SIZE;
    bytes += weighStrings(witness.getState());
    bytes += weighStrings(witness.getCodes());
    bytes += weighStrings(witness.getKeys());
    bytes += weighStrings(witness.getHeaders());
    return bytes;
  }

  private static int weighStrings(final List<String> strings) {
    if (strings == null) return 0;
    int total = 0;
    for (final String s : strings) {
      total += s.length();
    }
    return total;
  }
}
