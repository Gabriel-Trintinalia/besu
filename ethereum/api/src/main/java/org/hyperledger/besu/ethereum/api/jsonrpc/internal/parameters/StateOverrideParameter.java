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

import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.datatypes.parameters.UnsignedLongParameter;
import org.hyperledger.besu.plugin.data.StateOverride;

import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Account Override parameter class */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonDeserialize(builder = StateOverrideParameter.Builder.class)
public class StateOverrideParameter extends StateOverride {
  private static final Logger LOG = LoggerFactory.getLogger(StateOverrideParameter.class);

  protected StateOverrideParameter(
      final Optional<Wei> balance,
      final Optional<Long> nonce,
      final Optional<String> code,
      final Optional<Map<String, String>> stateDiff) {
    super(balance, nonce, code, stateDiff);
  }

  public static StateOverrideParameter.Builder builder() {
    return new StateOverrideParameter.Builder();
  }

  /** Builder class for Account overrides */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class Builder {
    private Optional<Wei> balance = Optional.empty();
    private Optional<Long> nonce = Optional.empty();
    private Optional<String> code = Optional.empty();
    private Optional<Map<String, String>> stateDiff = Optional.empty();

    /** Default constructor. */
    public Builder() {}

    /**
     * Sets the balance override
     *
     * @param balance the balance override
     * @return the builder
     */
    public Builder withBalance(final Wei balance) {
      this.balance = Optional.ofNullable(balance);
      return this;
    }

    /**
     * Sets the nonce override
     *
     * @param nonce the nonce override in hex
     * @return the builder
     */
    public Builder withNonce(final UnsignedLongParameter nonce) {
      this.nonce = Optional.of(nonce.getValue());
      return this;
    }

    /**
     * Sets the code override
     *
     * @param code the code override
     * @return the builder
     */
    public Builder withCode(final String code) {
      this.code = Optional.ofNullable(code);
      return this;
    }

    /**
     * Sets the state diff override
     *
     * @param stateDiff the map of state overrides
     * @return the builder
     */
    public Builder withStateDiff(final Map<String, String> stateDiff) {
      this.stateDiff = Optional.ofNullable(stateDiff);
      return this;
    }

    /**
     * build the account override from the builder
     *
     * @return account override
     */
    public StateOverrideParameter build() {
      return new StateOverrideParameter(balance, nonce, code, stateDiff);
    }
  }

  /**
   * utility method to log unknown properties
   *
   * @param key key for the unrecognized value
   * @param value the unrecognized value
   */
  @JsonAnySetter
  public void withUnknownProperties(final String key, final Object value) {
    LOG.debug(
        "unknown property - {} with value - {} and type - {} caught during serialization",
        key,
        value,
        value != null ? value.getClass() : "NULL");
  }
}
