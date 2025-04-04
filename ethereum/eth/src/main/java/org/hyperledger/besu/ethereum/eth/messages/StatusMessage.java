/*
 * Copyright ConsenSys AG.
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
package org.hyperledger.besu.ethereum.eth.messages;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.ethereum.core.Difficulty;
import org.hyperledger.besu.ethereum.eth.EthProtocolVersion;
import org.hyperledger.besu.ethereum.forkid.ForkId;
import org.hyperledger.besu.ethereum.p2p.rlpx.wire.AbstractMessageData;
import org.hyperledger.besu.ethereum.p2p.rlpx.wire.MessageData;
import org.hyperledger.besu.ethereum.rlp.BytesValueRLPOutput;
import org.hyperledger.besu.ethereum.rlp.RLP;
import org.hyperledger.besu.ethereum.rlp.RLPInput;
import org.hyperledger.besu.ethereum.rlp.RLPOutput;

import java.math.BigInteger;
import java.util.Optional;
import java.util.function.Consumer;

import com.google.common.annotations.VisibleForTesting;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;

public final class StatusMessage extends AbstractMessageData {

  private EthStatus status;

  private StatusMessage(final Bytes data) {
    super(data);
  }

  @VisibleForTesting
  public static StatusMessage create(final Bytes data) {
    return new StatusMessage(data);
  }

  private static StatusMessage create(final EthStatus status) {
    final BytesValueRLPOutput out = new BytesValueRLPOutput();
    status.writeTo(out);

    return new StatusMessage(out.encoded());
  }

  public static StatusMessage readFrom(final MessageData message) {
    if (message instanceof StatusMessage) {
      return (StatusMessage) message;
    }
    final int code = message.getCode();
    if (code != EthProtocolMessages.STATUS) {
      throw new IllegalArgumentException(
          String.format("Message has code %d and thus is not a StatusMessage.", code));
    }
    return new StatusMessage(message.getData());
  }

  @Override
  public int getCode() {
    return EthProtocolMessages.STATUS;
  }

  /**
   * Return The eth protocol version the associated node is running.
   *
   * @return The eth protocol version the associated node is running.
   */
  public int protocolVersion() {
    return status().protocolVersion;
  }

  /**
   * Return The id of the network the associated node is participating in.
   *
   * @return The id of the network the associated node is participating in.
   */
  public BigInteger networkId() {
    return status().networkId;
  }

  /**
   * Return The total difficulty of the head of the associated node's local blockchain.
   *
   * @return The total difficulty of the head of the associated node's local blockchain.
   */
  public Difficulty totalDifficulty() {
    return status().totalDifficulty;
  }

  /**
   * Return The hash of the head of the associated node's local blockchain.
   *
   * @return The hash of the head of the associated node's local blockchain.
   */
  public Hash bestHash() {
    return status().bestHash;
  }

  /**
   * Return The hash of the genesis block of the network the associated node is participating in.
   *
   * @return The hash of the genesis block of the network the associated node is participating in.
   */
  public Bytes32 genesisHash() {
    return status().genesisHash;
  }

  /**
   * Return The fork id of the network the associated node is participating in.
   *
   * @return The fork id of the network the associated node is participating in.
   */
  public ForkId forkId() {
    return status().forkId;
  }

  /**
   * Return The block range of the associated node's local blockchain. (Eth/69)
   *
   * @return The block range of the associated node's local blockchain.
   */
  public Optional<BlockRange> blockRange() {
    return Optional.ofNullable(status().blockRange);
  }

  private EthStatus status() {
    if (status == null) {
      final RLPInput input = RLP.input(data);
      status = EthStatus.readFrom(input);
    }
    return status;
  }

  public static StatusMessage.Builder builder() {
    return new StatusMessage.Builder();
  }

  public static class Builder {
    private Integer protocolVersion;
    private BigInteger networkId;
    private Difficulty totalDifficulty;
    private Hash bestHash;
    private Hash genesisHash;
    private ForkId forkId;
    private BlockRange blockRange;

    public Builder protocolVersion(final int protocolVersion) {
      this.protocolVersion = protocolVersion;
      return this;
    }

    public Builder networkId(final BigInteger networkId) {
      this.networkId = networkId;
      return this;
    }

    public Builder totalDifficulty(final Difficulty totalDifficulty) {
      this.totalDifficulty = totalDifficulty;
      return this;
    }

    public Builder bestHash(final Hash bestHash) {
      this.bestHash = bestHash;
      return this;
    }

    public Builder genesisHash(final Hash genesisHash) {
      this.genesisHash = genesisHash;
      return this;
    }

    public Builder forkId(final ForkId forkId) {
      this.forkId = forkId;
      return this;
    }

    public Builder blockRange(final BlockRange blockRange) {
      this.blockRange = blockRange;
      return this;
    }

    public Builder apply(final Consumer<Builder> consumer) {
      consumer.accept(this);
      return this;
    }

    public StatusMessage build() {
      checkNotNull(protocolVersion, "protocolVersion must be set");
      checkNotNull(networkId, "networkId must be set");
      checkNotNull(bestHash, "bestHash must be set");
      checkNotNull(genesisHash, "genesisHash must be set");
      checkNotNull(forkId, "forkId must be set");
      checkState(
          blockRange == null || protocolVersion >= EthProtocolVersion.V69,
          "blockRange is only supported for protocol version >= 69");
      checkState(
          blockRange != null || protocolVersion <= EthProtocolVersion.V68,
          "blockRange must be present for protocol version >= 69");
      checkState(
          totalDifficulty == null || protocolVersion <= EthProtocolVersion.V68,
          "totalDifficulty must be not present for protocol version >= 69");
      checkState(
          totalDifficulty != null || protocolVersion >= EthProtocolVersion.V69,
          "totalDifficulty must be present for protocol version <= 68");

      final EthStatus status =
          new EthStatus(
              protocolVersion,
              networkId,
              totalDifficulty,
              bestHash,
              genesisHash,
              forkId,
              blockRange);
      return create(status);
    }
  }

  private static class EthStatus {
    private final int protocolVersion;
    private final BigInteger networkId;
    private final Difficulty totalDifficulty;
    private final Hash bestHash;
    private final Hash genesisHash;
    private final ForkId forkId;
    private final BlockRange blockRange;

    EthStatus(
        final int protocolVersion,
        final BigInteger networkId,
        final Difficulty totalDifficulty,
        final Hash bestHash,
        final Hash genesisHash,
        final ForkId forkHash,
        final BlockRange blockRange) {
      this.protocolVersion = protocolVersion;
      this.networkId = networkId;
      this.totalDifficulty = totalDifficulty;
      this.bestHash = bestHash;
      this.genesisHash = genesisHash;
      this.forkId = forkHash;
      this.blockRange = blockRange;
    }

    public void writeTo(final RLPOutput out) {
      out.startList();

      out.writeIntScalar(protocolVersion);
      out.writeBigIntegerScalar(networkId);
      if (totalDifficulty != null) {
        out.writeUInt256Scalar(totalDifficulty);
      }
      out.writeBytes(bestHash);
      out.writeBytes(genesisHash);

      forkId.writeTo(out);
      if (blockRange != null) {
        out.writeLongScalar(blockRange.getEarliestBlock());
      }
      out.endList();
    }

    public static EthStatus readFrom(final RLPInput in) {
      in.enterList();

      final int protocolVersion = in.readIntScalar();
      final BigInteger networkId = in.readBigIntegerScalar();
      final Difficulty totalDifficulty = Difficulty.of(in.readUInt256Scalar());
      final Hash bestHash = Hash.wrap(in.readBytes32());
      final Hash genesisHash = Hash.wrap(in.readBytes32());
      final ForkId forkId = ForkId.readFrom(in);
      BlockRange blockRange = null;
      if (!in.isEndOfCurrentList()) {
        blockRange = new BlockRange(in.readLongScalar());
      }
      in.leaveList();

      return new EthStatus(
          protocolVersion, networkId, totalDifficulty, bestHash, genesisHash, forkId, blockRange);
    }

    @Override
    public String toString() {
      return "{"
          + "protocolVersion="
          + protocolVersion
          + ", networkId="
          + networkId
          + ", totalDifficulty="
          + totalDifficulty
          + ", bestHash="
          + bestHash
          + ", genesisHash="
          + genesisHash
          + ", forkId="
          + forkId
          + ", blockRange="
          + blockRange
          + '}';
    }
  }

  @Override
  public String toStringDecoded() {
    return status().toString();
  }

  public static class BlockRange {
    private final long earliestBlock;

    public BlockRange(final long earliestBlock) {
      this.earliestBlock = earliestBlock;
    }

    long getEarliestBlock() {
      return earliestBlock;
    }

    @Override
    public String toString() {
      return "{" + "earliestBlock=" + earliestBlock + '}';
    }
  }
}
