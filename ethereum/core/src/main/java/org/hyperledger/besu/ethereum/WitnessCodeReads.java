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
package org.hyperledger.besu.ethereum;

import org.hyperledger.besu.datatypes.Address;

import java.util.Set;

/**
 * Immutable snapshot of the EIP-8025 code reads collected during block processing: the addresses
 * whose code was read, during EVM execution or EIP-7702 authorization processing.
 *
 * <p>Only reads the witness needs are included: as in EELS {@code get_code}, a read satisfied by
 * code written earlier in the block is dropped, as is a read of empty code (see {@code
 * AccessLocationTracker}). The ancestor block headers accessed via BLOCKHASH are tracked
 * separately, on {@link BlockProcessingOutputs#getAccessedAncestors()}.
 *
 * @param codeReads addresses whose bytecode the witness has to supply
 */
public record WitnessCodeReads(Set<Address> codeReads) {}
