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
package org.hyperledger.besu.ethereum.core.encoding.registry;

import static com.google.common.base.Preconditions.checkNotNull;

import org.hyperledger.besu.ethereum.core.Block;
import org.hyperledger.besu.ethereum.core.BlockBody;
import org.hyperledger.besu.ethereum.core.BlockHeader;
import org.hyperledger.besu.ethereum.core.BlockHeaderFunctions;
import org.hyperledger.besu.ethereum.core.Transaction;
import org.hyperledger.besu.ethereum.core.Withdrawal;
import org.hyperledger.besu.ethereum.rlp.RLPInput;

import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import com.google.common.base.Suppliers;

public class BlockDecoder implements RLPDecoder<Block> {
  private final Supplier<BlockHeaderDecoder> blockHeaderDecoder;
  private final Supplier<TransactionDecoder> transactionDecoder;
  private final Supplier<WithdrawalDecoder> withdrawalsDecoder;

  private BlockDecoder(final Builder builder) {
    this.blockHeaderDecoder = builder.blockHeaderDecoder;
    this.transactionDecoder = builder.transactionDecoder;
    this.withdrawalsDecoder = builder.withdrawalsDecoder;
  }

  @Override
  public Block readFrom(final RLPInput in, final BlockHeaderFunctions hashFunction) {
    in.enterList();
    BlockHeader header = decodeBlockHeader(in, hashFunction);
    List<Transaction> transactions = decodeTransactions(in);
    List<BlockHeader> ommers = decodeOmmers(in, hashFunction);
    Optional<List<Withdrawal>> withdrawals = decodeWithdrawals(in);
    in.leaveList();
    return new Block(header, new BlockBody(transactions, ommers, withdrawals));
  }

  private BlockHeader decodeBlockHeader(
      final RLPInput in, final BlockHeaderFunctions hashFunction) {
    return blockHeaderDecoder.get().readFrom(in, hashFunction);
  }

  private List<Transaction> decodeTransactions(final RLPInput in) {
    return in.readList(input -> transactionDecoder.get().readFrom(input));
  }

  private List<BlockHeader> decodeOmmers(
      final RLPInput in, final BlockHeaderFunctions hashFunction) {
    return in.readList(rlp -> blockHeaderDecoder.get().readFrom(in, hashFunction));
  }

  private Optional<List<Withdrawal>> decodeWithdrawals(final RLPInput in) {
    return in.isEndOfCurrentList()
        ? Optional.empty()
        : Optional.of(in.readList(rlpInput -> withdrawalsDecoder.get().readFrom(rlpInput)));
  }

  public static Builder builder() {
    return new Builder();
  }

  public static class Builder {
    private Supplier<BlockHeaderDecoder> blockHeaderDecoder =
        Suppliers.memoize(DefaultBlockHeaderDecoder::new);
    private Supplier<TransactionDecoder> transactionDecoder =
        Suppliers.memoize(DefaultTransactionDecoder::new);
    private Supplier<WithdrawalDecoder> withdrawalsDecoder =
        Suppliers.memoize(DefaultWithdrawalDecoder::new);

    public Builder withBlockHeaderDecoder(final Supplier<BlockHeaderDecoder> blockHeaderDecoder) {
      this.blockHeaderDecoder = blockHeaderDecoder;
      return this;
    }

    public Builder withTransactionDecoder(final Supplier<TransactionDecoder> transactionDecoder) {
      this.transactionDecoder = transactionDecoder;
      return this;
    }

    public Builder withWithdrawalsDecoder(final Supplier<WithdrawalDecoder> withdrawalsDecoder) {
      this.withdrawalsDecoder = withdrawalsDecoder;
      return this;
    }

    public BlockDecoder build() {
      checkNotNull(blockHeaderDecoder);
      checkNotNull(transactionDecoder);
      checkNotNull(withdrawalsDecoder);
      return new BlockDecoder(this);
    }
  }
}
