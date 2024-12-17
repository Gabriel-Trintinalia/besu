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

import org.hyperledger.besu.datatypes.Address;

import java.util.HashMap;
import java.util.Map;

/** A map of state overrides. */
public class StateOverrides extends HashMap<Address, StateOverride> {

  /** Default constructor. */
  public StateOverrides() {
    super();
  }

  /**
   * Constructor.
   *
   * @param map the map to copy, where the values are of type {@code T} which extends {@code
   *     StateOverride}
   * @param <T> the type of the values in the map, which extends {@code StateOverride}
   */
  public <T extends StateOverride> StateOverrides(final Map<Address, T> map) {
    super();
    this.putAll(map);
  }
}
