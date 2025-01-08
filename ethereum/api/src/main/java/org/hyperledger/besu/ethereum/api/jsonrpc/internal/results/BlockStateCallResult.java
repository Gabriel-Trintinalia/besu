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
package org.hyperledger.besu.ethereum.api.jsonrpc.internal.results;

import org.hyperledger.besu.ethereum.core.BlockHeader;
import org.hyperledger.besu.ethereum.core.Withdrawal;

import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.annotation.JsonGetter;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.databind.JsonNode;

@JsonPropertyOrder(alphabetic = true)
public class BlockStateCallResult extends BlockResult {
  public BlockStateCallResult(
      final BlockHeader header,
      final List<TransactionResult> transactions,
      final List<JsonNode> ommers,
      final List<CallProcessingResult> callProcessingResults,
      final int size,
      final Optional<List<Withdrawal>> withdrawals) {
    super(header, transactions, ommers, callProcessingResults, null, size, false, withdrawals);
  }

  @JsonGetter(value = "totalDifficulty")
  @Override
  public String getTotalDifficulty() {
    return null;
  }
}
