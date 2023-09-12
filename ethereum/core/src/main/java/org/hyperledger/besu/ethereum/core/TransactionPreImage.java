package org.hyperledger.besu.ethereum.core;

import static com.google.common.base.Preconditions.checkArgument;
import static org.hyperledger.besu.crypto.Hash.keccak256;

import org.hyperledger.besu.datatypes.*;
import org.hyperledger.besu.ethereum.core.encoding.BlobTransactionEncoder;
import org.hyperledger.besu.ethereum.core.encoding.TransactionEncoder;
import org.hyperledger.besu.ethereum.rlp.RLP;
import org.hyperledger.besu.ethereum.rlp.RLPOutput;

import java.math.BigInteger;
import java.util.List;
import java.util.Optional;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.units.bigints.UInt256;

public class TransactionPreImage {

  protected static Bytes32 computeSenderRecoveryHash(
      final TransactionType transactionType,
      final long nonce,
      final Wei gasPrice,
      final Wei maxPriorityFeePerGas,
      final Wei maxFeePerGas,
      final Wei maxFeePerBlobGas,
      final long gasLimit,
      final Optional<Address> to,
      final Wei value,
      final Bytes payload,
      final Optional<List<AccessListEntry>> accessList,
      final List<VersionedHash> versionedHashes,
      final Optional<BigInteger> chainId) {
    if (transactionType.requiresChainId()) {
      checkArgument(chainId.isPresent(), "Transaction type %s requires chainId", transactionType);
    }
    final Bytes preimage;
    switch (transactionType) {
      case FRONTIER:
        preimage = frontierPreimage(nonce, gasPrice, gasLimit, to, value, payload, chainId);
        break;
      case EIP1559:
        preimage =
            eip1559Preimage(
                nonce,
                maxPriorityFeePerGas,
                maxFeePerGas,
                gasLimit,
                to,
                value,
                payload,
                chainId,
                accessList);
        break;
      case BLOB:
        preimage =
            blobPreimage(
                nonce,
                maxPriorityFeePerGas,
                maxFeePerGas,
                maxFeePerBlobGas,
                gasLimit,
                to,
                value,
                payload,
                chainId,
                accessList,
                versionedHashes);
        break;
      case ACCESS_LIST:
        preimage =
            accessListPreimage(
                nonce,
                gasPrice,
                gasLimit,
                to,
                value,
                payload,
                accessList.orElseThrow(
                    () ->
                        new IllegalStateException(
                            "Developer error: the transaction should be guaranteed to have an access list here")),
                chainId);
        break;
      default:
        throw new IllegalStateException(
            "Developer error. Didn't specify signing hash preimage computation");
    }
    return keccak256(preimage);
  }

  private static Bytes frontierPreimage(
      final long nonce,
      final Wei gasPrice,
      final long gasLimit,
      final Optional<Address> to,
      final Wei value,
      final Bytes payload,
      final Optional<BigInteger> chainId) {
    return RLP.encode(
        rlpOutput -> {
          rlpOutput.startList();
          rlpOutput.writeLongScalar(nonce);
          rlpOutput.writeUInt256Scalar(gasPrice);
          rlpOutput.writeLongScalar(gasLimit);
          rlpOutput.writeBytes(to.map(Bytes::copy).orElse(Bytes.EMPTY));
          rlpOutput.writeUInt256Scalar(value);
          rlpOutput.writeBytes(payload);
          if (chainId.isPresent()) {
            rlpOutput.writeBigIntegerScalar(chainId.get());
            rlpOutput.writeUInt256Scalar(UInt256.ZERO);
            rlpOutput.writeUInt256Scalar(UInt256.ZERO);
          }
          rlpOutput.endList();
        });
  }

  private static Bytes eip1559Preimage(
      final long nonce,
      final Wei maxPriorityFeePerGas,
      final Wei maxFeePerGas,
      final long gasLimit,
      final Optional<Address> to,
      final Wei value,
      final Bytes payload,
      final Optional<BigInteger> chainId,
      final Optional<List<AccessListEntry>> accessList) {
    final Bytes encoded =
        RLP.encode(
            rlpOutput -> {
              rlpOutput.startList();
              eip1559PreimageFields(
                  nonce,
                  maxPriorityFeePerGas,
                  maxFeePerGas,
                  gasLimit,
                  to,
                  value,
                  payload,
                  chainId,
                  accessList,
                  rlpOutput);
              rlpOutput.endList();
            });
    return Bytes.concatenate(Bytes.of(TransactionType.EIP1559.getSerializedType()), encoded);
  }

  private static void eip1559PreimageFields(
      final long nonce,
      final Wei maxPriorityFeePerGas,
      final Wei maxFeePerGas,
      final long gasLimit,
      final Optional<Address> to,
      final Wei value,
      final Bytes payload,
      final Optional<BigInteger> chainId,
      final Optional<List<AccessListEntry>> accessList,
      final RLPOutput rlpOutput) {
    rlpOutput.writeBigIntegerScalar(chainId.orElseThrow());
    rlpOutput.writeLongScalar(nonce);
    rlpOutput.writeUInt256Scalar(maxPriorityFeePerGas);
    rlpOutput.writeUInt256Scalar(maxFeePerGas);
    rlpOutput.writeLongScalar(gasLimit);
    rlpOutput.writeBytes(to.map(Bytes::copy).orElse(Bytes.EMPTY));
    rlpOutput.writeUInt256Scalar(value);
    rlpOutput.writeBytes(payload);
    TransactionEncoder.writeAccessList(rlpOutput, accessList);
  }

  private static Bytes blobPreimage(
      final long nonce,
      final Wei maxPriorityFeePerGas,
      final Wei maxFeePerGas,
      final Wei maxFeePerBlobGas,
      final long gasLimit,
      final Optional<Address> to,
      final Wei value,
      final Bytes payload,
      final Optional<BigInteger> chainId,
      final Optional<List<AccessListEntry>> accessList,
      final List<VersionedHash> versionedHashes) {

    final Bytes encoded =
        RLP.encode(
            rlpOutput -> {
              rlpOutput.startList();
              eip1559PreimageFields(
                  nonce,
                  maxPriorityFeePerGas,
                  maxFeePerGas,
                  gasLimit,
                  to,
                  value,
                  payload,
                  chainId,
                  accessList,
                  rlpOutput);
              rlpOutput.writeUInt256Scalar(maxFeePerBlobGas);
              BlobTransactionEncoder.writeBlobVersionedHashes(rlpOutput, versionedHashes);
              rlpOutput.endList();
            });
    return Bytes.concatenate(Bytes.of(TransactionType.BLOB.getSerializedType()), encoded);
  }

  private static Bytes accessListPreimage(
      final long nonce,
      final Wei gasPrice,
      final long gasLimit,
      final Optional<Address> to,
      final Wei value,
      final Bytes payload,
      final List<AccessListEntry> accessList,
      final Optional<BigInteger> chainId) {
    final Bytes encode =
        RLP.encode(
            rlpOutput -> {
              rlpOutput.startList();
              TransactionEncoder.encodeAccessListInner(
                  chainId, nonce, gasPrice, gasLimit, to, value, payload, accessList, rlpOutput);
              rlpOutput.endList();
            });
    return Bytes.concatenate(Bytes.of(TransactionType.ACCESS_LIST.getSerializedType()), encode);
  }
}
