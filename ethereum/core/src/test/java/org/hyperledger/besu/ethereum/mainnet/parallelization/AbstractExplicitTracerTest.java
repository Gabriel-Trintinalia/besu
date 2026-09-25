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
package org.hyperledger.besu.ethereum.mainnet.parallelization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hyperledger.besu.ethereum.mainnet.parallelization.ParallelBlockProcessorTestSupport.ACCOUNT_2;
import static org.hyperledger.besu.ethereum.mainnet.parallelization.ParallelBlockProcessorTestSupport.ACCOUNT_GENESIS_1_KEYPAIR;
import static org.hyperledger.besu.ethereum.mainnet.parallelization.ParallelBlockProcessorTestSupport.ACCOUNT_GENESIS_2_KEYPAIR;
import static org.hyperledger.besu.ethereum.mainnet.parallelization.ParallelBlockProcessorTestSupport.CONTRACT_ADDRESS;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.ethereum.BlockProcessingResult;
import org.hyperledger.besu.ethereum.core.Block;
import org.hyperledger.besu.ethereum.core.ExecutionContextTestFixture;
import org.hyperledger.besu.ethereum.core.Transaction;
import org.hyperledger.besu.ethereum.mainnet.BlockProcessor;
import org.hyperledger.besu.ethereum.mainnet.block.access.list.BlockAccessList;
import org.hyperledger.besu.ethereum.mainnet.witness.WitnessCodeTracer;
import org.hyperledger.besu.plugin.services.worldstate.MutableWorldState;

import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A caller-supplied tracer has to observe every transaction, which transactions run in the
 * background (untraced) would escape, so the parallel processor runs such a block sequentially.
 */
public abstract class AbstractExplicitTracerTest
    extends AbstractParallelBlockProcessorIntegrationTest {

  private final Address contractAddr = Address.fromHexStringStrict(CONTRACT_ADDRESS);

  @Test
  @DisplayName("An explicit tracer sees every transaction, as with sequential processing")
  void explicitTracerSeesEveryTransaction() {
    final Transaction txSetSlot =
        createContractCallTransaction(
            0, contractAddr, "setSlot1", ACCOUNT_GENESIS_1_KEYPAIR, Optional.of(100));
    final Transaction txGetSlot =
        createContractCallTransaction(
            0, contractAddr, "getSlot1", ACCOUNT_GENESIS_2_KEYPAIR, Optional.empty());
    final Transaction txTransfer =
        createTransferTransaction(
            1, 1_000_000_000_000_000_000L, 300_000L, 0L, 5L, ACCOUNT_2, ACCOUNT_GENESIS_1_KEYPAIR);
    final Transaction[] txs = {txSetSlot, txGetSlot, txTransfer};
    final Hash stateRoot = discoverStateRoot(Wei.of(5), txs);

    final WitnessCodeTracer seqTracer = new WitnessCodeTracer();
    final BlockProcessingResult seqResult =
        process(stateRoot, false, Optional.empty(), seqTracer, txs);
    // With the block's access list, as a BAL-carrying import has it, the BAL variant would
    // otherwise run every transaction in the background.
    final WitnessCodeTracer parTracer = new WitnessCodeTracer();
    final BlockProcessingResult parResult =
        process(stateRoot, true, getBlockAccessList(seqResult), parTracer, txs);

    assertThat(parResult.getNbParallelizedTransactions())
        .as(getVariantName() + " must not run transactions in the background")
        .isEmpty();
    assertThat(seqTracer.codeReads()).contains(contractAddr);
    assertThat(parTracer.codeReads())
        .as(getVariantName() + " witness code reads must match sequential")
        .isEqualTo(seqTracer.codeReads());
  }

  private BlockProcessingResult process(
      final Hash stateRoot,
      final boolean parallel,
      final Optional<BlockAccessList> blockAccessList,
      final WitnessCodeTracer tracer,
      final Transaction... txs) {
    final ExecutionContextTestFixture ctx = createFreshContext();
    final MutableWorldState ws = ctx.getStateArchive().getWorldState();
    final Block block = createBlock(ctx, stateRoot, Wei.of(5), txs);
    final BlockProcessor processor =
        parallel ? createParallelProcessor(ctx) : createSequentialProcessor(ctx);
    final BlockProcessingResult result =
        processor.processBlock(
            ctx.getProtocolContext(),
            ctx.getBlockchain(),
            ws,
            block,
            blockAccessList,
            Optional.of(tracer));
    assertTrue(
        result.isSuccessful(),
        getVariantName() + " processing failed: " + result.errorMessage.orElse("(no message)"));
    return result;
  }
}
