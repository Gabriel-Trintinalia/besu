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
package org.hyperledger.besu.ethereum.core.encoding.rlp;

import org.hyperledger.besu.ethereum.core.Block;
import org.hyperledger.besu.ethereum.core.BlockBody;
import org.hyperledger.besu.ethereum.core.BlockHeader;
import org.hyperledger.besu.ethereum.core.BlockHeaderFunctions;
import org.hyperledger.besu.ethereum.core.Request;
import org.hyperledger.besu.ethereum.core.Transaction;
import org.hyperledger.besu.ethereum.core.Withdrawal;
import org.hyperledger.besu.ethereum.rlp.RLPInput;

import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import com.google.common.base.Suppliers;

@SuppressWarnings({"UnusedVariable","MissingOverride"})
public class RLPDecoder {

  public interface RequestDecoder {
    Optional<List<Request>> decode(RLPInput input);
  }

  public static class DefaultBlockHeaderDecoder
      implements Decoder<org.hyperledger.besu.plugin.data.BlockHeader> {
    @Override
    public BlockHeader decode(final RLPInput input, final BlockHeaderFunctions hashFunction) {
      return BlockHeader.readFrom(input, hashFunction);
    }
  }

  public static class DefaultTransactionDecoder
      implements Decoder<org.hyperledger.besu.datatypes.Transaction> {
    @Override
    public Transaction decode(final RLPInput input) {
      return Transaction.readFrom(input);
    }
  }

  public static class DefaultWithdrawalDecoder implements Decoder<Withdrawal> {
    public Withdrawal decode(final RLPInput input) {
      return Withdrawal.readFrom(input);
    }
  }

  public static class DefaultRequestDecoder implements RequestDecoder {
    @Override
    public Optional<List<Request>> decode(final RLPInput input) {
      return input.isEndOfCurrentList()
          ? Optional.empty()
          : Optional.of(input.readList(Request::readFrom));
    }
  }

  public static class BlockDecoderImpl implements Decoder<Block> {
    private final Supplier<Decoder<org.hyperledger.besu.plugin.data.BlockHeader>>
        blockHeaderDecoder =
            Suppliers.memoize(
                () ->
                    DecoderRegistry.getInstance()
                        .getDecoder(org.hyperledger.besu.plugin.data.BlockHeader.class));

    private final Supplier<Decoder<org.hyperledger.besu.datatypes.Transaction>> transactionDecoder =
        Suppliers.memoize(
            () ->
                DecoderRegistry.getInstance()
                    .getDecoder(org.hyperledger.besu.datatypes.Transaction.class));

    private final Supplier<Decoder<Withdrawal>> withdrawalsDecoder =
        Suppliers.memoize(() -> DecoderRegistry.getInstance().getDecoder(Withdrawal.class));

    private final RequestDecoder requestDecoder = new DefaultRequestDecoder();

    @Override
    public Block decode(final RLPInput in, final BlockHeaderFunctions hashFunction) {
      in.enterList();
      final BlockHeader header = (BlockHeader) blockHeaderDecoder.get().decode(in, hashFunction);

      final List<Transaction> transactions =
          in.readList(input -> (Transaction) transactionDecoder.get().decode(input));

      final List<BlockHeader> ommers = in.readList(rlp -> BlockHeader.readFrom(rlp, hashFunction));

      final Optional<List<Withdrawal>> withdrawals =
          in.isEndOfCurrentList()
              ? Optional.empty()
              : Optional.of(in.readList(Withdrawal::readFrom));

      final Optional<List<Request>> requests = requestDecoder.decode(in);
      in.leaveList();
      return new Block(header, new BlockBody(transactions, ommers, withdrawals, requests));
    }

    public static BlockDecoderImpl createDefault() {
      return new BlockDecoderImpl();
    }
  }
}
