package org.hyperledger.besu.ethereum.core;

import static com.google.common.base.Preconditions.checkState;
import static org.hyperledger.besu.datatypes.VersionedHash.SHA256_VERSION_ID;
import static org.hyperledger.besu.ethereum.core.TransactionPreImage.computeSenderRecoveryHash;

import org.hyperledger.besu.crypto.KeyPair;
import org.hyperledger.besu.crypto.SECPSignature;
import org.hyperledger.besu.crypto.SignatureAlgorithmFactory;
import org.hyperledger.besu.datatypes.*;

import java.math.BigInteger;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import org.apache.tuweni.bytes.Bytes;

public class TransactionBuilder {
  private static final Optional<List<AccessListEntry>> EMPTY_ACCESS_LIST = Optional.of(List.of());

  protected TransactionType transactionType;

  protected long nonce = -1L;

  protected Wei gasPrice;

  protected Wei maxPriorityFeePerGas;

  protected Wei maxFeePerGas;
  protected Wei maxFeePerBlobGas;

  protected long gasLimit = -1L;

  protected Optional<Address> to = Optional.empty();

  protected Wei value;

  protected SECPSignature signature;

  protected Bytes payload;

  protected Optional<List<AccessListEntry>> accessList = Optional.empty();

  protected Address sender;

  protected Optional<BigInteger> chainId = Optional.empty();
  protected Optional<BigInteger> v = Optional.empty();
  protected List<VersionedHash> versionedHashes = null;
  private BlobsWithCommitments blobsWithCommitments;

  public TransactionBuilder type(final TransactionType transactionType) {
    this.transactionType = transactionType;
    return this;
  }

  public TransactionBuilder chainId(final BigInteger chainId) {
    this.chainId = Optional.of(chainId);
    return this;
  }

  public TransactionBuilder v(final BigInteger v) {
    this.v = Optional.of(v);
    return this;
  }

  public TransactionBuilder gasPrice(final Wei gasPrice) {
    this.gasPrice = gasPrice;
    return this;
  }

  public TransactionBuilder maxPriorityFeePerGas(final Wei maxPriorityFeePerGas) {
    this.maxPriorityFeePerGas = maxPriorityFeePerGas;
    return this;
  }

  public TransactionBuilder maxFeePerGas(final Wei maxFeePerGas) {
    this.maxFeePerGas = maxFeePerGas;
    return this;
  }

  public TransactionBuilder maxFeePerBlobGas(final Wei maxFeePerBlobGas) {
    this.maxFeePerBlobGas = maxFeePerBlobGas;
    return this;
  }

  public TransactionBuilder gasLimit(final long gasLimit) {
    this.gasLimit = gasLimit;
    return this;
  }

  public TransactionBuilder nonce(final long nonce) {
    this.nonce = nonce;
    return this;
  }

  public TransactionBuilder value(final Wei value) {
    this.value = value;
    return this;
  }

  public TransactionBuilder to(final Address to) {
    this.to = Optional.ofNullable(to);
    return this;
  }

  public TransactionBuilder payload(final Bytes payload) {
    this.payload = payload;
    return this;
  }

  public TransactionBuilder accessList(final List<AccessListEntry> accessList) {
    this.accessList =
        accessList == null
            ? Optional.empty()
            : accessList.isEmpty() ? EMPTY_ACCESS_LIST : Optional.of(accessList);
    return this;
  }

  public TransactionBuilder sender(final Address sender) {
    this.sender = sender;
    return this;
  }

  public TransactionBuilder signature(final SECPSignature signature) {
    this.signature = signature;
    return this;
  }

  public TransactionBuilder versionedHashes(final List<VersionedHash> versionedHashes) {
    this.versionedHashes = versionedHashes;
    return this;
  }

  public TransactionBuilder guessType() {
    if (versionedHashes != null && !versionedHashes.isEmpty()) {
      transactionType = TransactionType.BLOB;
    } else if (maxPriorityFeePerGas != null || maxFeePerGas != null) {
      transactionType = TransactionType.EIP1559;
    } else if (accessList.isPresent()) {
      transactionType = TransactionType.ACCESS_LIST;
    } else {
      transactionType = TransactionType.FRONTIER;
    }
    return this;
  }

  public TransactionType getTransactionType() {
    return transactionType;
  }

  public Transaction build() {
    if (transactionType == null) guessType();
    return new Transaction(
        transactionType,
        nonce,
        Optional.ofNullable(gasPrice),
        Optional.ofNullable(maxPriorityFeePerGas),
        Optional.ofNullable(maxFeePerGas),
        Optional.ofNullable(maxFeePerBlobGas),
        gasLimit,
        to,
        value,
        signature,
        payload,
        accessList,
        sender,
        chainId,
        Optional.ofNullable(versionedHashes),
        Optional.ofNullable(blobsWithCommitments));
  }

  public Transaction signAndBuild(final KeyPair keys) {
    checkState(
        signature == null, "The transaction signature has already been provided to this builder");
    signature(computeSignature(keys));
    sender(Address.extract(Hash.hash(keys.getPublicKey().getEncodedBytes())));
    return build();
  }

  SECPSignature computeSignature(final KeyPair keys) {
    return SignatureAlgorithmFactory.getInstance()
        .sign(
            computeSenderRecoveryHash(
                transactionType,
                nonce,
                gasPrice,
                maxPriorityFeePerGas,
                maxFeePerGas,
                maxFeePerBlobGas,
                gasLimit,
                to,
                value,
                payload,
                accessList,
                versionedHashes,
                chainId),
            keys);
  }

  public TransactionBuilder kzgBlobs(
      final List<KZGCommitment> kzgCommitments,
      final List<Blob> blobs,
      final List<KZGProof> kzgProofs) {
    if (this.versionedHashes == null || this.versionedHashes.isEmpty()) {
      this.versionedHashes =
          kzgCommitments.stream()
              .map(c -> new VersionedHash(SHA256_VERSION_ID, Sha256Hash.sha256(c.getData())))
              .collect(Collectors.toList());
    }
    this.blobsWithCommitments =
        new BlobsWithCommitments(kzgCommitments, blobs, kzgProofs, versionedHashes);
    return this;
  }
}
