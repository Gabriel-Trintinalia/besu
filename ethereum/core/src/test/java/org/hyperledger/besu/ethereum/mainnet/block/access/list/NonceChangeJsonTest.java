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
package org.hyperledger.besu.ethereum.mainnet.block.access.list;

import static org.assertj.core.api.Assertions.assertThat;

import org.hyperledger.besu.ethereum.referencetests.AccountChangesJson;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * A nonce is a uint64, so fixtures may legitimately carry values above {@code Long.MAX_VALUE} —
 * EIP-2681 max-nonce and high-nonce CREATE tests do exactly that. Besu stores nonces in a signed
 * long using the two's-complement representation, so those must decode rather than overflow.
 */
class NonceChangeJsonTest {

  private static long decode(final String postNonce) {
    return new AccountChangesJson.NonceChangeJson("0x0", postNonce).toNonceChange().newNonce();
  }

  @Test
  void decodesMaxUint64Nonce() {
    // 2^64-1: the EIP-2681 limit. Long.decode() throws on this.
    assertThat(decode("0xffffffffffffffff")).isEqualTo(-1L);
  }

  @ParameterizedTest
  @CsvSource({
    "0x0, 0",
    "0x1, 1",
    "0x7fffffffffffffff, 9223372036854775807", // Long.MAX_VALUE
    "0x8000000000000000, -9223372036854775808", // first value that overflows a signed long
    "0xfffffffffffffffe, -2", // 2^64-2, the "high nonce minus one" fixtures
  })
  void decodesAcrossTheSignedBoundary(final String postNonce, final long expected) {
    assertThat(decode(postNonce)).isEqualTo(expected);
  }

  @Test
  void treatsAbsentPostNonceAsZero() {
    assertThat(decode(null)).isZero();
  }
}
