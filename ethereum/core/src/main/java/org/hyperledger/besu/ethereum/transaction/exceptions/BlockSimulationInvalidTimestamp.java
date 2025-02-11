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

import java.security.InvalidParameterException;

/** Exception thrown when a block simulation is attempted with an invalid timestamp. */
public class BlockSimulationInvalidTimestamp extends InvalidParameterException {

  /**
   * Construct a new BlockSimulationInvalidTimestamp exception with the specified message.
   *
   * @param message the message
   */
  public BlockSimulationInvalidTimestamp(final String message) {
    super(message);
  }
}
