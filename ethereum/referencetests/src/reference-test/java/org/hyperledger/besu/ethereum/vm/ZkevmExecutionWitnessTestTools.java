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
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.ethereum.BlockProcessingOutputs;
import org.hyperledger.besu.ethereum.BlockProcessingResult;
import org.hyperledger.besu.ethereum.BlockValidator;
import org.hyperledger.besu.ethereum.ProtocolContext;
import org.hyperledger.besu.ethereum.chain.MutableBlockchain;
import org.hyperledger.besu.ethereum.core.Block;
import org.hyperledger.besu.ethereum.core.BlockHeader;
import org.hyperledger.besu.ethereum.mainnet.HeaderValidationMode;
import org.hyperledger.besu.ethereum.mainnet.ProtocolSchedule;
import org.hyperledger.besu.ethereum.mainnet.ProtocolSpec;
import org.hyperledger.besu.ethereum.referencetests.BlockchainReferenceTestCaseSpec;
import org.hyperledger.besu.ethereum.referencetests.ReferenceTestProtocolSchedules;
import org.hyperledger.besu.ethereum.rlp.RLPException;
import org.hyperledger.besu.ethereum.trie.pathbased.bonsai.storage.BonsaiExecutionWitnessBuilder;
import org.hyperledger.besu.ethereum.trie.pathbased.bonsai.worldview.BonsaiWorldState;
import org.hyperledger.besu.ethereum.trie.pathbased.common.provider.PathBasedWorldStateProvider;
import org.hyperledger.besu.ethereum.trie.pathbased.common.provider.WorldStateQueryParams;
import org.hyperledger.besu.ethereum.worldstate.DataStorageConfiguration;
import org.hyperledger.besu.ethereum.vm.zkevm.FixtureExecutionWitness;
import org.hyperledger.besu.ethereum.vm.zkevm.ZkevmFixtureWitnessLoader;
import org.hyperledger.besu.ethereum.worldstate.WorldStateArchive;
import org.hyperledger.besu.metrics.noop.NoOpMetricsSystem;
import org.hyperledger.besu.plugin.services.trielogs.TrieLog;
import org.hyperledger.besu.plugin.services.worldstate.MutableWorldState;
import org.hyperledger.besu.evm.EVM;
import org.hyperledger.besu.evm.EvmSpecVersion;
import org.hyperledger.besu.evm.account.AccountState;
import org.hyperledger.besu.evm.internal.EvmConfiguration.WorldUpdaterMode;
import org.hyperledger.besu.testutil.JsonTestParameters;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.apache.tuweni.bytes.Bytes32;
import org.assertj.core.api.Assertions;

/**
 * Drives zkevm execution-spec-test fixtures through Besu's block processing and asserts that the
 * {@code debug_executionWitness} output (state, codes, headers) matches the per-block
 * {@code executionWitness} field embedded in each fixture.
 *
 * <p>Blocks are always imported from the fixture RLP — no block re-building is performed.
 */
public class ZkevmExecutionWitnessTestTools {

  private static final List<String> NETWORKS_TO_RUN;
  private static final ReferenceTestProtocolSchedules PROTOCOL_SCHEDULES;

  // Tests whose fixture's executionWitness.headers list is intentionally not directly comparable to
  // a canonical builder output. The validation_headers/* family ships deliberately-malformed
  // witnesses (missing parent, wrong order, corrupt RLP, etc.) to exercise stateless-client
  // rejection logic — Besu builds its own canonical witness so the comparison is meaningless.
  // extra_unused_older_ancestor exercises an optional spec allowance to include older unused
  // ancestors; Besu emits the strict minimum, which is also spec-valid.
  private static final Set<String> HEADERS_COMPARISON_INCOMPATIBLE =
      Set.of(
          "test_validation_headers_empty_block_missing_mandatory_parent",
          "test_validation_headers_non_contiguous_chain",
          "test_validation_headers_malformed_rlp_header",
          "test_validation_headers_missing_parent_header",
          "test_validation_headers_missing_oldest_blockhash_ancestor",
          "test_witness_headers_extra_unused_older_ancestor");

  static {
    final String networks =
        System.getProperty(
            "test.ethereum.blockchain.eips",
            "FrontierToHomesteadAt5,HomesteadToEIP150At5,HomesteadToDaoAt5,EIP158ToByzantiumAt5,CancunToPragueAtTime15k,"
                + "Frontier,Homestead,EIP150,EIP158,Byzantium,Constantinople,ConstantinopleFix,Istanbul,Berlin,"
                + "London,Merge,Paris,Shanghai,Cancun,Prague,Osaka,Amsterdam,Bogota,Polis,Bangkok");
    NETWORKS_TO_RUN = Arrays.asList(networks.split(","));
    PROTOCOL_SCHEDULES = ReferenceTestProtocolSchedules.create();
  }

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

  /** Bundles the shared blockchain spec with the metadata needed to look up zkevm witnesses. */
  public record ZkevmTestCase(
      String testName, String filePath, BlockchainReferenceTestCaseSpec spec) {}

  static {
    if (NETWORKS_TO_RUN.isEmpty()) {
      params.ignoreAll();
    }
  }

  private ZkevmExecutionWitnessTestTools() {}

  public static Collection<Object[]> generateTestParametersForConfig(final String[] filePath) {
    return params.generate(filePath);
  }

  @SuppressWarnings("java:S5960")
  public static void executeTest(final String name, final ZkevmTestCase testCase) {
    final BlockchainReferenceTestCaseSpec spec = testCase.spec();
    final MutableBlockchain blockchain = spec.buildBlockchain();
    final BlockHeader genesisBlockHeader = spec.getGenesisBlockHeader();
    final ProtocolContext protocolContext =
        spec.buildProtocolContext(DataStorageConfiguration.DEFAULT_BONSAI_CONFIG, blockchain);
    final WorldStateArchive worldStateArchive = protocolContext.getWorldStateArchive();
    worldStateArchive.getWorldState(
        WorldStateQueryParams.withBlockHeaderAndNoUpdateNodeHead(genesisBlockHeader));

    if (!(worldStateArchive instanceof PathBasedWorldStateProvider pathBasedProvider)) {
      throw new IllegalStateException("zkevm witness tests require a PathBased (Bonsai) archive");
    }

    final ProtocolSchedule schedule = PROTOCOL_SCHEDULES.getByName(spec.getNetwork());

    final BlockchainReferenceTestCaseSpec.CandidateBlock[] candidates = spec.getCandidateBlocks();
    for (int blockIndex = 0; blockIndex < candidates.length; blockIndex++) {
      final BlockchainReferenceTestCaseSpec.CandidateBlock candidateBlock = candidates[blockIndex];
      if (!candidateBlock.isExecutable()) {
        return;
      }

      final Optional<FixtureExecutionWitness> expectedWitness =
          ZkevmFixtureWitnessLoader.witnessFor(
              testCase.filePath(), testCase.testName(), blockIndex);

      try {
        final Block block = candidateBlock.getBlock();
        final ProtocolSpec protocolSpec = schedule.getByBlockHeader(block.getHeader());

        verifyJournaledEVMAccountCompatability(
            worldStateArchive
                .getWorldState(
                    WorldStateQueryParams.withBlockHeaderAndNoUpdateNodeHead(genesisBlockHeader))
                .orElseThrow(),
            protocolSpec);

        // Capture the parent world state BEFORE importing the block so the archive's head
        // remains at the parent level while we hold the reference.
        final BlockHeader parentHeader =
            blockchain.getBlockHeader(block.getHeader().getParentHash()).orElse(null);
        final BonsaiWorldState parentWorldState =
            (parentHeader != null && candidateBlock.isValid() && expectedWitness.isPresent())
                ? (BonsaiWorldState)
                    worldStateArchive
                        .getWorldState(
                            WorldStateQueryParams.withBlockHeaderAndNoUpdateNodeHead(parentHeader))
                        .orElse(null)
                : null;

        final HeaderValidationMode validationMode =
            "NoProof".equalsIgnoreCase(spec.getSealEngine())
                ? HeaderValidationMode.LIGHT
                : HeaderValidationMode.FULL;

        final BlockValidator blockValidator = protocolSpec.getBlockValidator();
        final BlockProcessingResult processingResult =
            blockValidator.validateAndProcessBlock(
                protocolContext,
                block,
                validationMode,
                validationMode,
                candidateBlock.getBlockAccessList(),
                false);

        final boolean imported = processingResult.isSuccessful();
        if (imported) {
          processingResult
              .getYield()
              .ifPresent(
                  outputs -> {
                    protocolContext
                        .getBlockchain()
                        .appendBlock(block, outputs.getReceipts(), outputs.getBlockAccessList());
                    final long oldest =
                        outputs.getAccessedAncestors().keySet().stream()
                            .mapToLong(Long::longValue)
                            .min()
                            .orElseGet(() -> block.getHeader().getNumber() - 1L);
                    protocolContext
                        .getBlockchain()
                        .storeOldestAccessedAncestor(block.getHash(), oldest);
                    protocolContext
                        .getWorldStateArchive()
                        .getWorldState(
                            WorldStateQueryParams.newBuilder()
                                .withBlockHeader(block.getHeader())
                                .withShouldWorldStateUpdateHead(true)
                                .build());
                  });
        }

        // Blocks tagged with expectException are semantically invalid; Besu must reject them
        // and we skip the witness comparison since the fixture's stateless artifacts were
        // generated against the un-mutated form.
        if (candidateBlock.getExpectedException().isPresent()) {
          assertThat(imported)
              .as("Block import status for block %s (expected exception)", block.getHash())
              .isFalse();
          continue;
        }

        assertThat(imported)
            .as("Block import status for block %s", block.getHash())
            .isEqualTo(candidateBlock.isValid());

        if (imported && parentWorldState != null) {
          final Map<Long, Hash> accessedAncestors =
              processingResult
                  .getYield()
                  .map(BlockProcessingOutputs::getAccessedAncestors)
                  .orElse(Map.of());

          expectedWitness.ifPresent(
              expected -> {
                final TrieLog trieLog =
                    pathBasedProvider
                        .getTrieLogManager()
                        .getTrieLogLayer(block.getHash())
                        .orElseThrow();
                final BonsaiExecutionWitnessBuilder.Witness got =
                    new BonsaiExecutionWitnessBuilder(new NoOpMetricsSystem())
                        .build(
                            block.getHeader(),
                            trieLog,
                            parentWorldState,
                            blockchain,
                            accessedAncestors);

                assertThat(got.state())
                    .as("state for block %s", block.getHash())
                    .isEqualTo(expected.state());
                assertThat(got.codes())
                    .as("codes for block %s", block.getHash())
                    .isEqualTo(expected.codes());
                if (!HEADERS_COMPARISON_INCOMPATIBLE.contains(extractTestFunctionName(testCase))) {
                  assertThat(got.headers())
                      .as("headers for block %s", block.getHash())
                      .isEqualTo(expected.headers());
                }
              });
        }

      } catch (final RLPException e) {
        assertThat(candidateBlock.isValid()).isFalse();
      }
    }

    Assertions.assertThat(blockchain.getChainHeadHash()).isEqualTo(spec.getLastBlockHash());
  }

  // Extracts the bare pytest function name from a fully-qualified fixture test id like
  // "tests/.../test_witness_headers.py::test_witness_headers_extra_unused_older_ancestor[...]".
  private static String extractTestFunctionName(final ZkevmTestCase testCase) {
    final String testName = testCase.testName();
    final int sep = testName.indexOf("::");
    if (sep < 0) {
      return testName;
    }
    final int after = sep + 2;
    final int bracket = testName.indexOf('[', after);
    return bracket < 0 ? testName.substring(after) : testName.substring(after, bracket);
  }

  private static void verifyJournaledEVMAccountCompatability(
      final MutableWorldState worldState, final ProtocolSpec protocolSpec) {
    EVM evm = protocolSpec.getEvm();
    if (evm.getEvmConfiguration().worldUpdaterMode() == WorldUpdaterMode.JOURNALED) {
      assumeFalse(
          worldState.streamAccounts(Bytes32.ZERO, Integer.MAX_VALUE).anyMatch(AccountState::isEmpty),
          "Journaled account configured and empty account detected");
      assumeFalse(
          EvmSpecVersion.SPURIOUS_DRAGON.compareTo(evm.getEvmVersion()) > 0,
          "Journaled account configured and fork prior to the merge specified");
    }
  }
}
