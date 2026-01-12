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
package org.hyperledger.besu.ethereum.p2p.discovery.discv5;

import java.net.InetSocketAddress;

/**
 * An {@link org.ethereum.beacon.discovery.AddressAccessPolicy} implementation that allows all
 * socket addresses without restriction.
 *
 * <p>This policy performs no validation or filtering and is intended for environments where
 * address-level access control is either unnecessary or enforced elsewhere.
 */
public class PermissiveAddressAccessPolicy
    implements org.ethereum.beacon.discovery.AddressAccessPolicy {
  /**
   * Always allows the given socket address.
   *
   * @param inetSocketAddress the remote socket address being evaluated
   * @return {@code true} in all cases
   */
  @Override
  public boolean allow(InetSocketAddress inetSocketAddress) {
    return true;
  }
}
