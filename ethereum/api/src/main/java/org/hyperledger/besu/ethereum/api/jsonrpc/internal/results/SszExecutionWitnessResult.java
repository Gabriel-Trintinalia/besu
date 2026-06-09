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

import java.util.List;

import com.fasterxml.jackson.annotation.JsonValue;
import org.apache.tuweni.bytes.Bytes;

/**
 * SSZ-encoded execution witness for {@code engine_newPayloadWithWitnessV5} (EIP-8025).
 *
 * <p>Serializes as a hex-prefixed SSZ byte string matching the Python reference:
 *
 * <pre>
 * class _SszExecutionWitness(Container):
 *     state:   List[ByteList[MAX_WITNESS_ITEM_BYTES], MAX_WITNESS_STATE_NODES]
 *     codes:   List[ByteList[MAX_WITNESS_ITEM_BYTES], MAX_WITNESS_CODES]
 *     headers: List[ByteList[MAX_WITNESS_ITEM_BYTES], MAX_WITNESS_HEADERS]
 * </pre>
 *
 * @see ExecutionWitnessResult for the JSON object variant used by {@code debug_executionWitness}.
 */
public class SszExecutionWitnessResult {

  private final String hexValue;

  public SszExecutionWitnessResult(
      final List<String> state, final List<String> codes, final List<String> headers) {
    this.hexValue = sszEncodeWitness(state, codes, headers).toHexString();
  }

  @JsonValue
  public String getValue() {
    return hexValue;
  }

  // TODO: replace with a proper SSZ library once one is available in Besu (e.g. tuweni-ssz).
  // The encoding below follows the SSZ spec
  // (https://github.com/ethereum/consensus-specs/blob/dev/ssz/simple-serialize.md)
  // for a Container whose three fields are all variable-length (List[ByteList]).
  //
  // Container layout (fixed part = 3 × 4-byte offsets = 12 bytes):
  //   [offset_state | offset_codes | offset_headers | encoded_state | encoded_codes |
  // encoded_headers]
  //
  // List[ByteList] layout (fixed part = N × 4-byte offsets):
  //   [offset_0 | offset_1 | ... | item_0 | item_1 | ...]

  private static Bytes sszEncodeWitness(
      final List<String> state, final List<String> codes, final List<String> headers) {
    final Bytes encodedState = sszEncodeBytesList(state);
    final Bytes encodedCodes = sszEncodeBytesList(codes);
    final Bytes encodedHeaders = sszEncodeBytesList(headers);

    final int fixedSize = 12; // 3 × 4-byte offsets
    return Bytes.concatenate(
        uint32LE(fixedSize),
        uint32LE(fixedSize + encodedState.size()),
        uint32LE(fixedSize + encodedState.size() + encodedCodes.size()),
        encodedState,
        encodedCodes,
        encodedHeaders);
  }

  private static Bytes sszEncodeBytesList(final List<String> hexItems) {
    if (hexItems.isEmpty()) {
      return Bytes.EMPTY;
    }
    final List<Bytes> items = hexItems.stream().map(Bytes::fromHexString).toList();
    // Fixed part: one 4-byte offset per item pointing into the variable part.
    int offset = 4 * items.size();
    Bytes offsets = Bytes.EMPTY;
    for (final Bytes item : items) {
      offsets = Bytes.concatenate(offsets, uint32LE(offset));
      offset += item.size();
    }
    final Bytes[] parts = new Bytes[1 + items.size()];
    parts[0] = offsets;
    for (int i = 0; i < items.size(); i++) {
      parts[i + 1] = items.get(i);
    }
    return Bytes.concatenate(parts);
  }

  private static Bytes uint32LE(final int value) {
    return Bytes.of(
        (byte) (value & 0xFF),
        (byte) ((value >> 8) & 0xFF),
        (byte) ((value >> 16) & 0xFF),
        (byte) ((value >> 24) & 0xFF));
  }
}
