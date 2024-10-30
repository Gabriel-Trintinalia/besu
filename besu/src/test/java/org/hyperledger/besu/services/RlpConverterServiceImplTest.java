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
package org.hyperledger.besu.services;

import static org.assertj.core.api.Assertions.assertThat;

import org.hyperledger.besu.crypto.SECPSignature;
import org.hyperledger.besu.datatypes.AccessListEntry;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.BlobGas;
import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.datatypes.Transaction;
import org.hyperledger.besu.datatypes.TransactionType;
import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.ethereum.core.BlockHeaderTestFixture;
import org.hyperledger.besu.ethereum.core.ProtocolScheduleFixture;
import org.hyperledger.besu.ethereum.core.encoding.rlp.Decoder;
import org.hyperledger.besu.ethereum.core.encoding.rlp.DecoderRegistry;
import org.hyperledger.besu.ethereum.rlp.RLP;
import org.hyperledger.besu.ethereum.rlp.RLPInput;
import org.hyperledger.besu.plugin.data.BlockHeader;

import java.math.BigInteger;

import org.apache.tuweni.bytes.Bytes;
import org.junit.jupiter.api.Test;

public class RlpConverterServiceImplTest {

  @Test
  public void testBuildRlpFromHeader() {
    // Arrange
    RlpConverterServiceImpl rlpConverterServiceImpl =
        new RlpConverterServiceImpl(ProtocolScheduleFixture.MAINNET);
    // header with cancun fields
    BlockHeader header =
        new BlockHeaderTestFixture()
            .timestamp(1710338135 + 1)
            .baseFeePerGas(Wei.of(1000))
            .requestsRoot(Hash.ZERO)
            .withdrawalsRoot(Hash.ZERO)
            .blobGasUsed(500L)
            .excessBlobGas(BlobGas.of(500L))
            .buildHeader();

    Bytes rlpBytes = rlpConverterServiceImpl.buildRlpFromHeader(header);
    BlockHeader deserialized = rlpConverterServiceImpl.buildHeaderFromRlp(rlpBytes);
    // Assert
    assertThat(header).isEqualTo(deserialized);
    assertThat(header.getBlobGasUsed()).isEqualTo(deserialized.getBlobGasUsed());
    assertThat(header.getExcessBlobGas()).isEqualTo(deserialized.getExcessBlobGas());
  }

  @Test
  public void testBlockFromRlp() {
    // Arrange
    RlpConverterServiceImpl rlpConverterServiceImpl =
        new RlpConverterServiceImpl(ProtocolScheduleFixture.MAINNET);

    String decompressedBlockRlpDecoded =
        "f90783f901f1a00000000000000000000000000000000000000000000000000000000000000000a01dcc4de8dec75d7aab85b567b6ccd41ad312451b948a7413f0a142fd40d49347940000000000000000000000000000000000000000a00000000000000000000000000000000000000000000000000000000000000000a00000000000000000000000000000000000000000000000000000000000000000a056e81f171bcc55a6ff8345e692c0f86e5b48e01b996cadc001622fb5e363b421b9010000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000808080808466f5b8d580a00000000000000000000000000000000000000000000000000000000000000000880000000000000000f9058bb9012a02f9012680825d74841dcd6500841dcd650e83044d92943921e8cb45b17fc029a0a6de958330ca4e58339080b8e4bc6511880000000000000000000000003aab2285ddcddad8edf438c1bab47e1a9d05a9b4000000000000000000000000e5d7c2a44ffddf6b295a15c148167daaaf5cf34f00000000000000000000000098f0f120de21a90f220b0027a9c70029df9bbde40000000000000000000000000000000000000000000000000000000066f5b9ff0000000000000000000000000000000000000000000000000000000000bb4e2e00000000000000000000000000000000000000000000000029bff9d8cf6738000000000000000000000000000000000000000000000000000000000000000000c0809498f0f120de21a90f220b0027a9c70029df9bbde480b9012902f9012580829ee8841dcd6500841dcd650e83044daa943921e8cb45b17fc029a0a6de958330ca4e58339080b8e4bc6511880000000000000000000000003aab2285ddcddad8edf438c1bab47e1a9d05a9b4000000000000000000000000e5d7c2a44ffddf6b295a15c148167daaaf5cf34f000000000000000000000000004df5de266316d7aa68a639ad73d795a631e2e60000000000000000000000000000000000000000000000000000000066f5b9ff0000000000000000000000000000000000000000000000000000000000ec380e00000000000000000000000000000000000000000000000034a6ad47497df2000000000000000000000000000000000000000000000000000000000000000000c080934df5de266316d7aa68a639ad73d795a631e2e680b86602f863804b8409ece7408409ece747830369d894ad7f33984bed10518012013d4ab0458d37fee6f380a4a0712d6800000000000000000000000000000000000000000000000000001c31bffcf000c08094a47a0b73b7edad29fd0185a8c38791d73ce66f5c80f901640984092dda8082e02d94c626845bf4e6a5802ef774da0b3dfc6707f015f78705af3107a40000b90124fc18063800000000000000000000000000000000000000000000000000000000000000c000000000000000000000000000000000000000000000000000000000000000000000000000000000000000005e809a85aa182a9921edd10a4163745bb3e362840000000000000000000000000000000000000000000000000005af3107a4000000000000000000000000000000000000000000000000000000000000000000310000000000000000000000000000000000000000000000000000000000016082000000000000000000000000000000000000000000000000000000000000002a307845323331343930373546353830423936463838426144313536363435303361333533316241373141000000000000000000000000000000000000000000008094e23149075f580b96f88bad15664503a3531ba71a80f9016082018084091e9840830450f294610d2f07b7edc67565160f587f37636194c34e7480b9012418a13086000000000000000000000000000000000000000000000008798aa85e9f5c836500000000000000000000000000000000000000000000000000080405f9261e0d00000000000000000000000000000000000000000000000000000000000000a0000000000000000000000000315fff7c53d75737d5a9f5165bed76ca1f689c730000000000000000000000000000000000000000000000000000000066f5bd9700000000000000000000000000000000000000000000000000000000000000010000000000000000000000001a51b19ce03dbe0cb44c1528e34a7edd7771e9af000000000000000000000000e5d7c2a44ffddf6b295a15c148167daaaf5cf34f00000000000000000000000000000000000000000000000000000000000000008094315fff7c53d75737d5a9f5165bed76ca1f689c7380c0";
    var bytes = Bytes.fromHexString(decompressedBlockRlpDecoded);

    registerTransactionDecoder();
    rlpConverterServiceImpl.buildBlockFromRlp(bytes);
  }

  private void registerTransactionDecoder() {
    DecoderRegistry decoders = DecoderRegistry.getInstance();
    decoders.registerDecoder(Transaction.class, new NoSignatureTransactionDecoder());
  }

  private static class NoSignatureTransactionDecoder implements Decoder<Transaction> {
    @Override
    public Transaction decode(final RLPInput input) {
      {
        if (!input.nextIsList()) {
          final Bytes typedTransactionBytes = input.readBytes();
          final RLPInput transactionInput = RLP.input(typedTransactionBytes.slice(1));
          byte transactionType = typedTransactionBytes.get(0);
          if (transactionType == 0x01) {
            return decodeAccessList(transactionInput);
          }
          if (transactionType == 0x02) {
            return decode1559(transactionInput);
          }
          throw new IllegalArgumentException("Unsupported transaction type");
        } else { // Frontier transaction
          return decodeFrontier(input);
        }
      }
    }

    private Transaction decodeAccessList(final RLPInput transactionInput) {
      final org.hyperledger.besu.ethereum.core.Transaction.Builder builder =
          org.hyperledger.besu.ethereum.core.Transaction.builder();

      transactionInput.enterList();
      builder
          .type(TransactionType.ACCESS_LIST)
          .chainId(BigInteger.valueOf(transactionInput.readLongScalar()))
          .nonce(transactionInput.readLongScalar())
          .gasPrice(Wei.of(transactionInput.readUInt256Scalar()))
          .gasLimit(transactionInput.readLongScalar())
          .to(
              transactionInput.readBytes(
                  addressBytes -> addressBytes.isEmpty() ? null : Address.wrap(addressBytes)))
          .value(Wei.of(transactionInput.readUInt256Scalar()))
          .payload(transactionInput.readBytes())
          .accessList(
              transactionInput.readList(
                  accessListEntryRLPInput -> {
                    accessListEntryRLPInput.enterList();
                    final AccessListEntry accessListEntry =
                        new AccessListEntry(
                            Address.wrap(accessListEntryRLPInput.readBytes()),
                            accessListEntryRLPInput.readList(RLPInput::readBytes32));
                    accessListEntryRLPInput.leaveList();
                    return accessListEntry;
                  }));
      transactionInput.readUnsignedByteScalar();
      builder.sender(Address.extract(transactionInput.readUInt256Scalar()));
      transactionInput.readUInt256Scalar();
      transactionInput.leaveList();
      return builder
          .signature(new SECPSignature(BigInteger.ZERO, BigInteger.ZERO, (byte) 0))
          .build();
    }

    private Transaction decode1559(final RLPInput transactionInput) {
      final org.hyperledger.besu.ethereum.core.Transaction.Builder builder =
          org.hyperledger.besu.ethereum.core.Transaction.builder();
      transactionInput.enterList();
      final BigInteger chainId = transactionInput.readBigIntegerScalar();
      builder
          .type(TransactionType.EIP1559)
          .chainId(chainId)
          .nonce(transactionInput.readLongScalar())
          .maxPriorityFeePerGas(Wei.of(transactionInput.readUInt256Scalar()))
          .maxFeePerGas(Wei.of(transactionInput.readUInt256Scalar()))
          .gasLimit(transactionInput.readLongScalar())
          .to(transactionInput.readBytes(v -> v.isEmpty() ? null : Address.wrap(v)))
          .value(Wei.of(transactionInput.readUInt256Scalar()))
          .payload(transactionInput.readBytes())
          .accessList(
              transactionInput.readList(
                  accessListEntryRLPInput -> {
                    accessListEntryRLPInput.enterList();
                    final AccessListEntry accessListEntry =
                        new AccessListEntry(
                            Address.wrap(accessListEntryRLPInput.readBytes()),
                            accessListEntryRLPInput.readList(RLPInput::readBytes32));
                    accessListEntryRLPInput.leaveList();
                    return accessListEntry;
                  }));
      transactionInput.readUnsignedByteScalar();
      builder.sender(Address.extract(transactionInput.readUInt256Scalar()));
      transactionInput.readUInt256Scalar();
      transactionInput.leaveList();
      return builder
          .signature(new SECPSignature(BigInteger.ZERO, BigInteger.ZERO, (byte) 0))
          .build();
    }

    private Transaction decodeFrontier(final RLPInput input) {
      final org.hyperledger.besu.ethereum.core.Transaction.Builder builder =
          org.hyperledger.besu.ethereum.core.Transaction.builder();
      input.enterList();
      builder
          .type(TransactionType.FRONTIER)
          .nonce(input.readLongScalar())
          .gasPrice(Wei.of(input.readUInt256Scalar()))
          .gasLimit(input.readLongScalar())
          .to(input.readBytes(v -> v.isEmpty() ? null : Address.wrap(v)))
          .value(Wei.of(input.readUInt256Scalar()))
          .payload(input.readBytes());

      input.readBigIntegerScalar();
      builder.sender(Address.extract(input.readUInt256Scalar()));
      input.readUInt256Scalar();
      final SECPSignature signature = new SECPSignature(BigInteger.ZERO, BigInteger.ZERO, (byte) 0);
      input.leaveList();
      return builder.signature(signature).build();
    }
  }
}
