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
package org.hyperledger.besu.plugin.data;

import org.hyperledger.besu.datatypes.Wei;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Account Override parameter class */
public class StateOverride {

  private final Optional<Wei> balance;
  private final Optional<Long> nonce;
  private final Optional<String> code;
  private final Optional<Map<String, String>> stateDiff;

  /**
   * Constructor
   *
   * @param balance the balance override
   * @param nonce the nonce override
   * @param code the code override
   * @param stateDiff the state override map
   */
  protected StateOverride(
      final Optional<Wei> balance,
      final Optional<Long> nonce,
      final Optional<String> code,
      final Optional<Map<String, String>> stateDiff) {
    this.balance = balance;
    this.nonce = nonce;
    this.code = code;
    this.stateDiff = stateDiff;
  }

  /**
   * Gets the balance override
   *
   * @return the balance if present
   */
  public Optional<Wei> getBalance() {
    return balance;
  }

  /**
   * Gets the nonce override
   *
   * @return the nonce if present
   */
  public Optional<Long> getNonce() {
    return nonce;
  }

  /**
   * Gets the code override
   *
   * @return the code if present
   */
  public Optional<String> getCode() {
    return code;
  }

  /**
   * Gets the state override map
   *
   * @return the state override map if present
   */
  public Optional<Map<String, String>> getStateDiff() {
    return stateDiff;
  }

  @Override
  public boolean equals(final Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    final StateOverride stateOverride = (StateOverride) o;
    return balance.equals(stateOverride.balance)
        && nonce.equals(stateOverride.nonce)
        && code.equals(stateOverride.code)
        && stateDiff.equals(stateOverride.stateDiff);
  }

  @Override
  public int hashCode() {
    return Objects.hash(balance, nonce, code, stateDiff);
  }

  @Override
  public String toString() {
    return "StateOverrides{"
        + "balance="
        + balance
        + ", nonce="
        + nonce
        + ", code="
        + code
        + ", stateDiff="
        + stateDiff
        + '}';
  }
}
