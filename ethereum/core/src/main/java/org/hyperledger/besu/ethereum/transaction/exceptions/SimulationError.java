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
package org.hyperledger.besu.ethereum.transaction.exceptions;

public enum SimulationError {
  TOO_MANY_BLOCK_CALLS(-38026, "Too many block calls"),
  BLOCK_NUMBERS_NOT_ASCENDING(-38020, "Block numbers are not ascending"),
  TIMESTAMPS_NOT_ASCENDING(-38021, "Timestamps are not ascending"),
  INVALID_PRECOMPILE_ADDRESS(-32000, "Invalid precompile address"),
  INVALID_NONCES(-32602, "Invalid nonces");

  private final int code;
  private final String message;

  SimulationError(final int code, final String message) {
    this.code = code;
    this.message = message;
  }

  public int getCode() {
    return code;
  }

  public String getMessage() {
    return message;
  }
}
