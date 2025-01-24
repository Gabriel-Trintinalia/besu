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
package org.hyperledger.besu.evm.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.evm.precompile.PrecompileContractRegistry;
import org.hyperledger.besu.evm.precompile.PrecompiledContract;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OverriddenPrecompilesMessageCallProcessorTest {

  private PrecompiledContract originalPrecompiledContract;
  private PrecompileContractRegistry originalRegistry;
  private MessageCallProcessor originalProcessor;

  private static final Address ORIGINAL_ADDRESS_1 = Address.fromHexString("0x01");
  private static final Address ORIGINAL_ADDRESS_2 = Address.fromHexString("0x02");

  private static final Address OVERRIDE_ADDRESS = Address.fromHexString("0x03");
  private static final Address NON_EXISTENT_ADDRESS = Address.fromHexString("0x04");

  @BeforeEach
  void setUp() {
    originalPrecompiledContract = mock(PrecompiledContract.class);
    originalRegistry = new PrecompileContractRegistry();
    originalRegistry.put(ORIGINAL_ADDRESS_1, originalPrecompiledContract);
    originalRegistry.put(ORIGINAL_ADDRESS_2, originalPrecompiledContract);
    originalProcessor = new MessageCallProcessor(null, originalRegistry, null);
  }

  @Test
  void shouldPreserveOriginalPrecompilesWhenNoOverridesProvided() {
    OverriddenPrecompilesMessageCallProcessor processor =
        new OverriddenPrecompilesMessageCallProcessor(originalProcessor, new HashMap<>());

    assertNotNull(processor);
    assertThat(processor.getPrecompileAddresses())
        .containsExactlyInAnyOrder(ORIGINAL_ADDRESS_1, ORIGINAL_ADDRESS_2);
    assertThat(processor.precompiles.get(ORIGINAL_ADDRESS_1))
        .isEqualTo(originalPrecompiledContract);
  }

  @Test
  void shouldCorrectlyApplyOverrides() {
    Map<Address, Address> precompileOverrides = new HashMap<>();
    precompileOverrides.put(ORIGINAL_ADDRESS_1, OVERRIDE_ADDRESS);

    OverriddenPrecompilesMessageCallProcessor processor =
        new OverriddenPrecompilesMessageCallProcessor(originalProcessor, precompileOverrides);

    assertNotNull(processor);
    assertThat(processor.precompiles.getPrecompileAddresses().contains(ORIGINAL_ADDRESS_1))
        .isFalse();
    assertThat(processor.precompiles.get(OVERRIDE_ADDRESS)).isEqualTo(originalPrecompiledContract);
  }

  @Test
  void shouldHandleBothOverriddenAndNonOverriddenPrecompiles() {
    PrecompiledContract precompiledContract2 = mock(PrecompiledContract.class);
    originalRegistry.put(
        OVERRIDE_ADDRESS,
        precompiledContract2);

    Map<Address, Address> precompileOverrides = new HashMap<>();
    precompileOverrides.put(
        ORIGINAL_ADDRESS_1, NON_EXISTENT_ADDRESS);

    OverriddenPrecompilesMessageCallProcessor processor =
        new OverriddenPrecompilesMessageCallProcessor(originalProcessor, precompileOverrides);

    assertNotNull(processor);
    assertThat(processor.precompiles.getPrecompileAddresses().contains(ORIGINAL_ADDRESS_1))
        .isFalse();
    assertThat(processor.precompiles.get(NON_EXISTENT_ADDRESS))
        .isEqualTo(originalPrecompiledContract);
    assertThat(processor.precompiles.get(OVERRIDE_ADDRESS)).isEqualTo(precompiledContract2);
  }

  @Test
  void shouldThrowIfOverrideAddressNotFoundInOriginalRegistry() {
    Map<Address, Address> precompileOverrides = new HashMap<>();
    precompileOverrides.put(NON_EXISTENT_ADDRESS, OVERRIDE_ADDRESS);

    Exception exception =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                new OverriddenPrecompilesMessageCallProcessor(
                    originalProcessor, precompileOverrides));

    assertThat(exception.getMessage())
        .contains("Address " + NON_EXISTENT_ADDRESS + " is not a precompile.");
  }

  @Test
  void shouldThrowWhenDuplicateNewAddressIsProvided() {
    Map<Address, Address> precompileOverrides = new HashMap<>();
    precompileOverrides.put(ORIGINAL_ADDRESS_1, OVERRIDE_ADDRESS);
    precompileOverrides.put(ORIGINAL_ADDRESS_2, OVERRIDE_ADDRESS); // Duplicate new address

    Exception exception =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                new OverriddenPrecompilesMessageCallProcessor(
                    originalProcessor, precompileOverrides));

    assertThat(exception.getMessage())
        .contains("Duplicate precompile address: " + OVERRIDE_ADDRESS);
  }

  @Test
  void shouldThrowWhenOriginalAddressIsReusedAsNewAddress() {
    Map<Address, Address> precompileOverrides = new HashMap<>();
    precompileOverrides.put(ORIGINAL_ADDRESS_1, ORIGINAL_ADDRESS_2);

    Exception exception =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                new OverriddenPrecompilesMessageCallProcessor(
                    originalProcessor, precompileOverrides));

    assertThat(exception.getMessage())
        .contains("Duplicate precompile address: " + ORIGINAL_ADDRESS_2);
  }
}
