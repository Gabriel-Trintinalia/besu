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
package org.hyperledger.besu.ethereum.vm.zkevm;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Loads per-block {@code executionWitness} entries from zkevm reference-test fixture files,
 * independently of {@code BlockchainReferenceTestCaseSpec} so the shared spec stays unaware of
 * zkevm-specific fields. Parsed files are cached because the test runner asks for one (testName,
 * blockIndex) at a time.
 */
public final class ZkevmFixtureWitnessLoader {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final Map<String, Map<String, List<Optional<FixtureExecutionWitness>>>> CACHE =
      new ConcurrentHashMap<>();

  private ZkevmFixtureWitnessLoader() {}

  /** Returns the witness at {@code blockIndex} for the named test in the fixture file, if any. */
  public static Optional<FixtureExecutionWitness> witnessFor(
      final String filePath, final String testName, final int blockIndex) {
    final List<Optional<FixtureExecutionWitness>> witnesses =
        CACHE.computeIfAbsent(filePath, ZkevmFixtureWitnessLoader::parse).get(testName);
    if (witnesses == null || blockIndex >= witnesses.size()) {
      return Optional.empty();
    }
    return witnesses.get(blockIndex);
  }

  /** Returns {@code true} when the block at {@code blockIndex} carries a mutated witness. */
  public static boolean isMutated(
      final String filePath, final String testName, final int blockIndex) {
    return witnessFor(filePath, testName, blockIndex)
        .map(FixtureExecutionWitness::mutated)
        .orElse(false);
  }

  private static Map<String, List<Optional<FixtureExecutionWitness>>> parse(final String filePath) {
    final JsonNode root;
    try {
      root = MAPPER.readTree(new File(filePath));
    } catch (final IOException e) {
      throw new RuntimeException("Failed to read fixture file " + filePath, e);
    }

    final Map<String, List<Optional<FixtureExecutionWitness>>> byTest = new HashMap<>();
    root.fields()
        .forEachRemaining(
            entry -> {
              final JsonNode blocks = entry.getValue().get("blocks");
              if (blocks == null || !blocks.isArray()) {
                return;
              }
              final List<Optional<FixtureExecutionWitness>> witnesses = new ArrayList<>();
              for (final JsonNode block : blocks) {
                final boolean mutated = block.path("executionWitnessMutated").asBoolean(false);
                witnesses.add(readWitness(block.get("executionWitness"), mutated));
              }
              byTest.put(entry.getKey(), Collections.unmodifiableList(witnesses));
            });
    return byTest;
  }

  private static Optional<FixtureExecutionWitness> readWitness(
      final JsonNode node, final boolean mutated) {
    if (node == null || node.isNull()) {
      return Optional.empty();
    }
    return Optional.of(
        new FixtureExecutionWitness(
            readStringList(node.get("state")),
            readStringList(node.get("codes")),
            readStringList(node.get("headers")),
            mutated));
  }

  private static List<String> readStringList(final JsonNode node) {
    if (node == null || !node.isArray()) {
      return List.of();
    }
    final List<String> out = new ArrayList<>(node.size());
    node.forEach(child -> out.add(child.asText()));
    return Collections.unmodifiableList(out);
  }
}
