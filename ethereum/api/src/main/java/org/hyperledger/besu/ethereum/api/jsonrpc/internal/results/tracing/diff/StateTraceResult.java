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
package org.hyperledger.besu.ethereum.api.jsonrpc.internal.results.tracing.diff;

import org.hyperledger.besu.datatypes.Hash;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;

@JsonSerialize(using = StateTraceResult.Serializer.class)
public class StateTraceResult {
  final StateDiffTrace stateDiffTrace;
  final boolean diffMode;

  public StateTraceResult(final StateDiffTrace stateDiffTrace, final boolean diffMode) {
    this.stateDiffTrace = stateDiffTrace;
    this.diffMode = diffMode;
  }

  /**
   * Custom serializer for StateDiffTracePrestate. Produces JSON in the following format: { "pre": {
   * ... all "from" values of DiffNodes ... }, "post": { ... all "to" values of DiffNodes that have
   * changed ... } }
   */
  public static class Serializer extends StdSerializer<StateTraceResult> {

    public Serializer() {
      super(StateTraceResult.class);
    }

    /**
     * Serialize the StateDiffTracePrestate to JSON. Generates two sections: "pre" and "post".
     *
     * @param result the result object
     * @param gen JsonGenerator used to write JSON
     * @param provider serializer provider
     */
    @Override
    public void serialize(
        final StateTraceResult result, final JsonGenerator gen, final SerializerProvider provider)
        throws IOException {
      StateDiffTrace trace = result.stateDiffTrace;

      gen.writeStartObject();

      if (result.diffMode) {
        // diffMode = true → same as before: "pre" + "post"
        gen.writeObjectFieldStart("post");
        for (var entry : trace.entrySet()) {
          writePostNode(gen, entry.getKey(), entry.getValue());
        }
        gen.writeEndObject();

        gen.writeObjectFieldStart("pre");
        for (var entry : trace.entrySet()) {
          writePreNode(gen, entry.getKey(), entry.getValue());
        }
        gen.writeEndObject();

      } else {
        // diffMode = false → just the content of "pre", no wrapper
        for (var entry : trace.entrySet()) {
          writeNode(gen, entry.getKey(), entry.getValue());
        }
      }

      gen.writeEndObject();
    }

    private void writeNode(
        final JsonGenerator gen, final String addr, final AccountDiff accountDiff)
        throws IOException {

      if (!shouldWritePreNode(accountDiff)) {
        // No pre-state data to write, skip this node
        return;
      }

      gen.writeObjectFieldStart(addr);

      if (accountDiff.getBalance().getFrom().isPresent()) {
        gen.writeStringField("balance", accountDiff.getBalance().getFrom().get());
      }

      if (accountDiff.getCode().getFrom().isPresent()) {
        String code = accountDiff.getCode().getFrom().get();
        if (!"0x".equals(code)) {
          gen.writeStringField("code", code);
        }
      }

      if (accountDiff.getCodeHash().getFrom().isPresent()) {
        String codeHash = accountDiff.getCodeHash().getFrom().get();
        if (!Hash.EMPTY.toHexString().equals(codeHash)) {
          gen.writeStringField("codeHash", codeHash);
        }
      }

      if (accountDiff.getNonce().getFrom().isPresent()) {
        String nonceStr = accountDiff.getNonce().getFrom().get();
        try {
          long nonce = Long.decode(nonceStr); // handles "0x..." or decimal
          if (nonce != 0) {
            gen.writeNumberField("nonce", nonce); // write as numeric value
          }
        } catch (NumberFormatException e) {
          // fallback: write as-is as string if parse fails
          gen.writeStringField("nonce", nonceStr);
        }
      }

      var storageEntries = accountDiff.getStorage().entrySet().stream().toList();

      if (!storageEntries.isEmpty()) {
        gen.writeObjectFieldStart("storage");
        for (var se : storageEntries) {
          DiffNode node = se.getValue();
          gen.writeStringField(se.getKey(), node.getFrom().get());
        }
        gen.writeEndObject();
      }
      gen.writeEndObject();
    }

    private void writePreNode(
        final JsonGenerator gen, final String addr, final AccountDiff accountDiff)
        throws IOException {

      if (!accountDiff.hasDifference()) {
        return;
      }

      if (!shouldWritePreNode(accountDiff)) {
        // No pre-state data to write, skip this node
        return;
      }

      gen.writeObjectFieldStart(addr);

      if (accountDiff.getBalance().getFrom().isPresent()) {
        gen.writeStringField("balance", accountDiff.getBalance().getFrom().get());
      }

      if (accountDiff.getCode().getFrom().isPresent()) {
        String code = accountDiff.getCode().getFrom().get();
        if (!"0x".equals(code)) {
          gen.writeStringField("code", code);
        }
      }

      if (accountDiff.getCodeHash().getFrom().isPresent()) {
        String codeHash = accountDiff.getCodeHash().getFrom().get();
        if (!Hash.EMPTY.toHexString().equals(codeHash)) {
          gen.writeStringField("codeHash", codeHash);
        }
      }

      if (accountDiff.getNonce().getFrom().isPresent()) {
        String nonceStr = accountDiff.getNonce().getFrom().get();
        try {
          long nonce = Long.decode(nonceStr); // handles "0x..." or decimal
          if (nonce != 0) {
            gen.writeNumberField("nonce", nonce); // write as numeric value
          }
        } catch (NumberFormatException e) {
          // fallback: write as-is as string if parse fails
          gen.writeStringField("nonce", nonceStr);
        }
      }

      var storageEntries =
          accountDiff.getStorage().entrySet().stream()
              .filter(se -> shouldWriteStorage(se.getValue(), DiffNode::getFrom))
              .toList();

      if (!storageEntries.isEmpty()) {
        gen.writeObjectFieldStart("storage");
        for (var se : storageEntries) {
          DiffNode node = se.getValue();
          gen.writeStringField(se.getKey(), node.getFrom().get());
        }
        gen.writeEndObject();
      }
      gen.writeEndObject();
    }

    private void writePostNode(
        final JsonGenerator gen, final String addr, final AccountDiff accountDiff)
        throws IOException {

      if (!accountDiff.hasDifference()) {
        return;
      }

      if (!shouldWritePostNode(accountDiff)) {
        // No post-state data to write, skip this node
        return;
      }

      gen.writeObjectFieldStart(addr);
      if (accountDiff.getBalance().hasDifference()) {

        String value = accountDiff.getBalance().getTo().get();
        if (!(accountDiff.getBalance().getFrom().isEmpty() && "0x0".equals(value))) {
          gen.writeStringField("balance", accountDiff.getBalance().getTo().get());
        }
      }

      if ((accountDiff.getCode().hasDifference())) {
        String code = accountDiff.getCode().getTo().get();
        if (!"0x".equals(code)) {
          gen.writeStringField("code", accountDiff.getCode().getTo().get());
        }
      }

      if ((accountDiff.getCodeHash().hasDifference())) {
        String codeHash = accountDiff.getCodeHash().getTo().get();
        if (!Hash.EMPTY.toHexString().equals(codeHash)) {
          gen.writeStringField("codeHash", accountDiff.getCodeHash().getTo().get());
        }
      }

      if (accountDiff.getNonce().hasDifference()) {
        String nonceStr = accountDiff.getNonce().getTo().get();
        try {
          long nonce = Long.decode(nonceStr); // handles "0x..." or decimal
          if (nonce != 0) {
            gen.writeNumberField("nonce", nonce); // write as numeric value
          }
        } catch (NumberFormatException e) {
          // fallback: write as-is as string if parse fails
          gen.writeStringField("nonce", nonceStr);
        }
      }

      var storageEntries =
          accountDiff.getStorage().entrySet().stream()
              .filter(se -> shouldWriteStorage(se.getValue(), DiffNode::getTo))
              .toList();

      if (!storageEntries.isEmpty()) {
        gen.writeObjectFieldStart("storage");
        for (var se : storageEntries) {
          DiffNode node = se.getValue();
          gen.writeStringField(se.getKey(), node.getTo().get());
        }
        gen.writeEndObject();
      }
      gen.writeEndObject();
    }

    private boolean shouldWritePreNode(final AccountDiff accountDiff) {
      // No pre-state data to write, skip this node
      return accountDiff.getBalance().getFrom().isPresent()
          || accountDiff.getCode().getFrom().isPresent()
          || accountDiff.getNonce().getFrom().isPresent()
          || hasStorageToWrite(accountDiff.getStorage());
    }

    private boolean shouldWritePostNode(final AccountDiff accountDiff) {
      // No post-state data to write, skip this node
      return (accountDiff.getBalance().getTo().isPresent()
              && !accountDiff.getBalance().getTo().get().equals("0x0"))
          || accountDiff.getCode().getTo().isPresent()
          || accountDiff.getNonce().getTo().isPresent()
          || hasStorageToWritePost(accountDiff.getStorage());
    }

    private boolean hasStorageToWrite(final Map<String, DiffNode> storage) {
      if (storage == null || storage.isEmpty()) return false;
      for (var node : storage.values()) {
        if (shouldWriteStorage(node, DiffNode::getFrom)) {
          return true;
        }
      }
      return false;
    }

    private boolean hasStorageToWritePost(final Map<String, DiffNode> storage) {
      if (storage == null || storage.isEmpty()) return false;
      for (var node : storage.values()) {
        if (shouldWriteStorage(node, DiffNode::getTo)) {
          return true;
        }
      }
      return false;
    }

    private boolean shouldWriteStorage(
        final DiffNode node, final Function<DiffNode, Optional<String>> extractor) {
      if (node == null) return false;
      return extractor
          .apply(node)
          .filter(
              v -> !v.equals("0x0000000000000000000000000000000000000000000000000000000000000000"))
          .isPresent();
    }
  }
}
