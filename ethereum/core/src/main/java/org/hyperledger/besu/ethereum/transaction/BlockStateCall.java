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
package org.hyperledger.besu.ethereum.transaction;

import org.hyperledger.besu.datatypes.BlockOverrides;

import java.util.List;

public class BlockStateCall {

  private final BlockOverrides blockOverrides;

  private final List<? extends CallParameter> calls;

  public BlockStateCall(
      final List<? extends CallParameter> calls, final BlockOverrides blockOverrides) {
    this.calls = calls;
    this.blockOverrides = blockOverrides;
  }

  public BlockOverrides getBlockOverrides() {
    return blockOverrides;
  }

  public List<? extends CallParameter> getCalls() {
    return calls;
  }
}
