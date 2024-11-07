package org.hyperledger.besu.ethereum.core.encoding.registry;

import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.ethereum.core.Transaction;
import org.hyperledger.besu.ethereum.rlp.RLPInput;

public class TransactionDecoderProvider {
  public static Transaction readFrom(final RLPInput input){
    return getDecoder().readFrom(input);
  }

  public static Transaction readFrom(final Bytes bytes){
    return getDecoder().readFrom(bytes);
  }

  private static RLPDecoder<Transaction> getDecoder(){
  return  DecoderRegistry.getInstance().getDecoder(Transaction.class);
  }
}
