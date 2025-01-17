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
package org.hyperledger.besu.ethereum.api.jsonrpc.internal.parameters;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

public class SimulateV1Parameter {
  @JsonProperty("blockStateCalls")
  private final List<JsonBlockStateCall> blockStateCalls = new ArrayList<>();

  @JsonProperty("validation")
  private final boolean validation;

  @JsonProperty("traceTransfers")
  private final boolean traceTransfers;

  @JsonProperty("returnFullTransactions")
  private final boolean returnFullTransactions;

  @JsonCreator
  public SimulateV1Parameter(
      @JsonProperty("blockStateCalls") final List<JsonBlockStateCall> blockStateCalls,
      @JsonProperty("validation") final boolean validation,
      @JsonProperty("traceTransfers") final boolean traceTransfers,
      @JsonProperty("returnFullTransactions") final boolean returnFullTransactions) {
    this.blockStateCalls.addAll(blockStateCalls);
    this.validation = validation;
    this.traceTransfers = traceTransfers;
    this.returnFullTransactions = returnFullTransactions;
  }

  public List<JsonBlockStateCall> getBlockStateCalls() {
    return blockStateCalls;
  }

  public boolean isValidation() {
    return validation;
  }

  public boolean isTraceTransfers() {
    return traceTransfers;
  }

  public boolean isReturnFullTransactions() {
    return returnFullTransactions;
  }
}
