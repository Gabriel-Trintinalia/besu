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
package org.hyperledger.besu.ethereum.vm.zkevm;

import java.util.List;

/** Per-block execution witness as specified by EIP-8025, present in zkevm fixtures. */
public record FixtureExecutionWitness(
    List<String> state, List<String> codes, List<String> headers) {

  public FixtureExecutionWitness(
      final List<String> state, final List<String> codes, final List<String> headers) {
    this.state = state != null ? state : List.of();
    this.codes = codes != null ? codes : List.of();
    this.headers = headers != null ? headers : List.of();
  }
}
