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
package org.hyperledger.besu.ethereum.core.encoding.registry;

import org.hyperledger.besu.ethereum.core.encoding.MainnetTransactionDecoder;
import org.hyperledger.besu.ethereum.core.encoding.PooledMainnetTransactionDecoder;

public class DecoderRegistry {
  private static DecoderRegistry INSTANCE;

  TransactionDecoder transactionDecoder;
  TransactionDecoder pooledTransactionDecoder;

  private DecoderRegistry() {
    registerDefaultDecoders();
  }

  protected static DecoderRegistry getInstance() {
    if (INSTANCE == null) {
      INSTANCE = new DecoderRegistry();
    }
    return INSTANCE;
  }

  public void setTransactionDecoder(final TransactionDecoder decoder){
    this.transactionDecoder = decoder;
  }
  
  public TransactionDecoder getTransactionDecoder(){
    return transactionDecoder;
  }

  public void setPooledTransactionDecoder(final TransactionDecoder pooledTransactionDecoder){
    this.pooledTransactionDecoder = pooledTransactionDecoder;
  }

  public TransactionDecoder getPooledTransactionDecoder(){
    return pooledTransactionDecoder;
  }


  private void registerDefaultDecoders() {
    setTransactionDecoder(new MainnetTransactionDecoder());
    setPooledTransactionDecoder(new PooledMainnetTransactionDecoder());
  }
}
