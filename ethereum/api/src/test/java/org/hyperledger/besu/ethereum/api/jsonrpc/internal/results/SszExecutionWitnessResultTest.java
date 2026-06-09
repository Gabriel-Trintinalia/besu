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
package org.hyperledger.besu.ethereum.api.jsonrpc.internal.results;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/**
 * Verifies that {@link SszExecutionWitnessResult} produces SSZ-encoded bytes that match the Python
 * reference implementation in {@code execution_testing/rpc/tests/test_new_payload_with_witness.py}.
 *
 * <p>Expected bytes are derived from {@code _build_inner_witness()} in that file, which encodes a
 * {@code _SszExecutionWitness} container using the remerkleable library.
 */
class SszExecutionWitnessResultTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /**
   * Mirrors Python {@code test_decode_valid_with_witness}:
   *
   * <pre>
   * _build_inner_witness(
   *     state   = [b"\xaa\xaa", b"\xbb\xbb\xbb"],
   *     codes   = [b"\x60\x01"],
   *     headers = [b"\xf9\x02"],
   * )
   * </pre>
   *
   * <p>SSZ layout (37 bytes):
   *
   * <pre>
   * Container fixed part (3 × uint32LE offsets = 12 bytes):
   *   0c 00 00 00  offset_state   = 12
   *   19 00 00 00  offset_codes   = 25 (12 + 13)
   *   1f 00 00 00  offset_headers = 31 (25 + 6)
   *
   * state list (13 bytes):
   *   08 00 00 00  offset[0] = 8
   *   0a 00 00 00  offset[1] = 10 (8 + 2)
   *   aa aa        item[0]
   *   bb bb bb     item[1]
   *
   * codes list (6 bytes):
   *   04 00 00 00  offset[0] = 4
   *   60 01        item[0]
   *
   * headers list (6 bytes):
   *   04 00 00 00  offset[0] = 4
   *   f9 02        item[0]
   * </pre>
   */
  @Test
  void encodeMatchesPythonReferenceWithMultipleItems() {
    final SszExecutionWitnessResult result =
        new SszExecutionWitnessResult(
            List.of("0xaaaa", "0xbbbbbb"), List.of("0x6001"), List.of("0xf902"));

    assertThat(result.getValue())
        .isEqualTo("0x0c000000190000001f000000080000000a000000aaaabbbbbb04000000600104000000f902");
  }

  /**
   * Mirrors Python {@code test_decode_syncing_empty_witness}: all three lists are empty.
   *
   * <p>SSZ layout (12 bytes — only the container offset table, no variable data):
   *
   * <pre>
   *   0c 00 00 00  offset_state   = 12
   *   0c 00 00 00  offset_codes   = 12
   *   0c 00 00 00  offset_headers = 12
   * </pre>
   */
  @Test
  void encodeMatchesPythonReferenceEmptyWitness() {
    final SszExecutionWitnessResult result =
        new SszExecutionWitnessResult(List.of(), List.of(), List.of());

    assertThat(result.getValue()).isEqualTo("0x0c0000000c0000000c000000");
  }

  /** Jackson must serialise the result as a quoted hex string, not a JSON object. */
  @Test
  void jacksonSerializesAsHexStringNotObject() throws Exception {
    final SszExecutionWitnessResult result =
        new SszExecutionWitnessResult(List.of(), List.of(), List.of());

    final String json = MAPPER.writeValueAsString(result);

    assertThat(json).startsWith("\"0x");
    assertThat(json).doesNotContain("{");
  }
}
