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

import org.hyperledger.besu.datatypes.Transaction;
import org.hyperledger.besu.ethereum.core.Block;
import org.hyperledger.besu.ethereum.core.encoding.MainnetTransactionDecoder;
import org.hyperledger.besu.plugin.data.BlockHeader;
import org.hyperledger.besu.plugin.data.Withdrawal;

import java.util.HashMap;
import java.util.Map;

public class DecoderRegistry {
  private static DecoderRegistry INSTANCE;
  private final Map<Class<?>, RLPDecoder<?>> decoders = new HashMap<>();

  private DecoderRegistry() {
    registerDefaultDecoders();
  }

  protected static DecoderRegistry getInstance() {
    if (INSTANCE == null) {
      INSTANCE = new DecoderRegistry();
    }
    return INSTANCE;
  }

  public <T> void registerDecoder(final Class<T> clazz, final RLPDecoder<T> decoder) {
    decoders.put(clazz, decoder);
  }

  @SuppressWarnings("unchecked")
  public <T> RLPDecoder<T> getDecoder(final Class<T> clazz) {
    return (RLPDecoder<T>) decoders.get(clazz);
  }

  private void registerDefaultDecoders() {
    registerDecoder(Block.class, BlockDecoder.builder().build());
    registerDecoder(BlockHeader.class, new DefaultBlockHeaderDecoder());
    registerDecoder(Transaction.class, new MainnetTransactionDecoder());
    registerDecoder(Withdrawal.class, new DefaultWithdrawalDecoder());
  }
}