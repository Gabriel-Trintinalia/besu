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
package org.hyperledger.besu.ethereum.vm;

import static org.assertj.core.api.Assertions.assertThat;

import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.ethereum.BlockProcessingResult;
import org.hyperledger.besu.ethereum.ProtocolContext;
import org.hyperledger.besu.ethereum.core.Block;
import org.hyperledger.besu.ethereum.core.BlockHeader;
import org.hyperledger.besu.ethereum.referencetests.BlockchainReferenceTestCaseSpec;
import org.hyperledger.besu.ethereum.trie.pathbased.bonsai.storage.BonsaiExecutionWitnessBuilder;
import org.hyperledger.besu.ethereum.trie.pathbased.bonsai.worldview.BonsaiWorldState;
import org.hyperledger.besu.ethereum.trie.pathbased.common.provider.PathBasedWorldStateProvider;
import org.hyperledger.besu.ethereum.trie.pathbased.common.provider.WorldStateQueryParams;
import org.hyperledger.besu.ethereum.vm.zkevm.FixtureExecutionWitness;
import org.hyperledger.besu.ethereum.vm.zkevm.ZkevmFixtureWitnessLoader;
import org.hyperledger.besu.testutil.JsonTestParameters;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Drives zkevm execution-spec-test fixtures through Besu's block processing and asserts that the
 * {@code debug_executionWitness} output (state, codes, headers) matches the per-block
 * {@code executionWitness} field embedded in each fixture.
 *
 * <p>Blocks are always imported from the fixture RLP — no block re-building is performed.
 */
public class ZkevmExecutionWitnessTestTools extends BlockchainReferenceTestTools {

  private static final Logger LOG = LoggerFactory.getLogger(ZkevmExecutionWitnessTestTools.class);

  private static final JsonTestParameters<BlockchainReferenceTestCaseSpec, ZkevmTestCase> params =
      JsonTestParameters.create(BlockchainReferenceTestCaseSpec.class, ZkevmTestCase.class)
          .generator(
              (testName, fullPath, spec, collector) -> {
                final String eip = spec.getNetwork();
                collector.add(
                    testName + "[" + eip + "]",
                    fullPath,
                    new ZkevmTestCase(testName, fullPath, spec),
                    NETWORKS_TO_RUN.contains(eip));
              });

  static {
    if (NETWORKS_TO_RUN.isEmpty()) {
      params.ignoreAll();
    }
  }

  /** Bundles the shared blockchain spec with the metadata needed to look up zkevm witnesses. */
  public record ZkevmTestCase(
      String testName, String filePath, BlockchainReferenceTestCaseSpec spec) {}

  private ZkevmTestCase testCase;

  public static Collection<Object[]> generateTestParametersForConfig(final String[] filePath) {
    return params.generate(filePath);
  }

  @SuppressWarnings("java:S5960")
  public static void executeTest(final String name, final ZkevmTestCase testCase) {
    final ZkevmExecutionWitnessTestTools tools = new ZkevmExecutionWitnessTestTools();
    tools.testCase = testCase;
    tools.runTest(name, testCase.spec());
  }

  @Override
  protected boolean shouldBuildBlocks() {
    return false;
  }

  @Override
  protected boolean shouldSkipBlockOnImportFailure() {
    return true;
  }

  @Override
  protected boolean shouldRunAfterBlockImport(final int blockIndex) {
    return !ZkevmFixtureWitnessLoader.isMutated(testCase.filePath(), testCase.testName(), blockIndex);
  }

  @Override
  protected void afterBlockImport(
      final ProtocolContext ctx,
      final Block block,
      final BlockHeader parentHeader,
      final BlockProcessingResult result,
      final int blockIndex) {
    if (!(ctx.getWorldStateArchive() instanceof PathBasedWorldStateProvider pathBasedProvider)) {
      throw new IllegalStateException("zkevm witness tests require a PathBased (Bonsai) archive");
    }

    final Optional<FixtureExecutionWitness> expected =
        ZkevmFixtureWitnessLoader.witnessFor(testCase.filePath(), testCase.testName(), blockIndex);
    if (expected.isEmpty() || parentHeader == null) {
      return;
    }

    final BonsaiWorldState parentWorldState =
        (BonsaiWorldState)
            ctx.getWorldStateArchive()
                .getWorldState(WorldStateQueryParams.withBlockHeaderAndNoUpdateNodeHead(parentHeader))
                .orElse(null);
    if (parentWorldState == null) {
      return;
    }

    final BonsaiExecutionWitnessBuilder.Witness got =
        new BonsaiExecutionWitnessBuilder()
            .buildWitness(block.getHeader(), parentHeader, ctx.getWorldStateArchive(), ctx.getBlockchain(), result.getYield());

    logWitnessDiff("state",   got.state(),   expected.get().state(),   block.getHash());
    logWitnessDiff("codes",   got.codes(),   expected.get().codes(),   block.getHash());
    logWitnessDiff("headers", got.headers(), expected.get().headers(), block.getHash());

    assertThat(got.state()).as("state for block %s", block.getHash()).isEqualTo(expected.get().state());
    assertThat(got.codes()).as("codes for block %s", block.getHash()).isEqualTo(expected.get().codes());
    assertThat(got.headers()).as("headers for block %s", block.getHash()).isEqualTo(expected.get().headers());
  }

  private static void logWitnessDiff(
    final String field,
    final List<String> got,
    final List<String> expected,
    final Hash blockHash) {
    final List<String> missing = new ArrayList<>(expected);
    missing.removeAll(got);
    final List<String> extra = new ArrayList<>(got);
    extra.removeAll(expected);
    if (missing.isEmpty() && extra.isEmpty()) {
      LOG.info("Block {} {} match", blockHash, field);
    } else {
      if (!missing.isEmpty()) {
        LOG.warn("Block {} {} missing ({}):", blockHash, field, missing.size());
        missing.forEach(e -> LOG.warn("  - {}", e));
      }
      if (!extra.isEmpty()) {
        LOG.warn("Block {} {} extra ({}):", blockHash, field, extra.size());
        extra.forEach(e -> LOG.warn("  + {}", e));
      }
    }
  }
}
