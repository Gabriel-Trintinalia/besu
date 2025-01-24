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

import org.junit.jupiter.api.Test;

class OverriddenPrecompilesMessageCallProcessorTest {

  @Test
  void testConstructorWithNoOverrides() {
    PrecompiledContract precompiledContract = mock(PrecompiledContract.class);
    PrecompileContractRegistry originalRegistry = new PrecompileContractRegistry();
    originalRegistry.put(Address.fromHexString("0x01"), precompiledContract);
    MessageCallProcessor originalProcessor = new MessageCallProcessor(null, originalRegistry, null);

    OverriddenPrecompilesMessageCallProcessor processor =
        new OverriddenPrecompilesMessageCallProcessor(originalProcessor, new HashMap<>());

    assertNotNull(processor);
    assertThat(processor.getPrecompileAddresses())
        .isEqualTo(originalRegistry.getPrecompileAddresses());
    processor
        .getPrecompileAddresses()
        .forEach(
            address ->
                assertThat(processor.precompiles.get(address))
                    .isEqualTo(originalProcessor.precompiles.get(address)));
  }

  @Test
  void testConstructorWithOverrides() {
    PrecompiledContract precompiledContract = mock(PrecompiledContract.class);
    PrecompileContractRegistry originalRegistry = new PrecompileContractRegistry();
    originalRegistry.put(Address.fromHexString("0x01"), precompiledContract);
    MessageCallProcessor originalProcessor = new MessageCallProcessor(null, originalRegistry, null);

    Map<Address, Address> precompileOverrides = new HashMap<>();

    // Add an override, so that the original precompile address 0x01 is replaced with 0x02
    precompileOverrides.put(Address.fromHexString("0x01"), Address.fromHexString("0x02"));

    OverriddenPrecompilesMessageCallProcessor processor =
        new OverriddenPrecompilesMessageCallProcessor(originalProcessor, precompileOverrides);

    assertNotNull(processor);

    assertThat(processor.precompiles.get(Address.fromHexString("0x01"))).isNull();
    assertThat(processor.precompiles.get(Address.fromHexString("0x02")))
        .isEqualTo(originalProcessor.precompiles.get(Address.fromHexString("0x01")));
  }

  @Test
  void shouldThrowIfOverridesAddressNotFound() {
    PrecompiledContract precompiledContract = mock(PrecompiledContract.class);
    PrecompileContractRegistry originalRegistry = new PrecompileContractRegistry();
    originalRegistry.put(Address.fromHexString("0x01"), precompiledContract);
    MessageCallProcessor originalProcessor = new MessageCallProcessor(null, originalRegistry, null);

    Map<Address, Address> precompileOverrides = new HashMap<>();

    // Add an override for an address that does not exist in the original registry
    precompileOverrides.put(Address.fromHexString("0x02"), Address.fromHexString("0x03"));

    Exception exception =
        assertThrows(
            IllegalArgumentException.class,
            () -> {
              new OverriddenPrecompilesMessageCallProcessor(originalProcessor, precompileOverrides);
            });
    assertThat(exception.getMessage())
        .contains("Address 0x0000000000000000000000000000000000000002 is not a precompile.");
  }

  @Test
  void testProcessorWithOverriddenAndNonOverriddenPrecompiles() {
    PrecompiledContract precompiledContract1 = mock(PrecompiledContract.class);
    PrecompiledContract precompiledContract2 = mock(PrecompiledContract.class);
    PrecompiledContract precompiledContract3 = mock(PrecompiledContract.class);

    PrecompileContractRegistry originalRegistry = new PrecompileContractRegistry();
    originalRegistry.put(Address.fromHexString("0x01"), precompiledContract1);
    originalRegistry.put(Address.fromHexString("0x02"), precompiledContract2);
    originalRegistry.put(Address.fromHexString("0x03"), precompiledContract3);

    MessageCallProcessor originalProcessor = new MessageCallProcessor(null, originalRegistry, null);

    Map<Address, Address> precompileOverrides = new HashMap<>();
    // Override precompile at address 0x01 with address 0x04
    precompileOverrides.put(Address.fromHexString("0x01"), Address.fromHexString("0x04"));

    OverriddenPrecompilesMessageCallProcessor processor =
        new OverriddenPrecompilesMessageCallProcessor(originalProcessor, precompileOverrides);

    assertNotNull(processor);

    // Check overridden precompile
    assertThat(processor.precompiles.get(Address.fromHexString("0x01"))).isNull();
    assertThat(processor.precompiles.get(Address.fromHexString("0x04")))
        .isEqualTo(originalProcessor.precompiles.get(Address.fromHexString("0x01")));

    // Check non-overridden precompiles
    assertThat(processor.precompiles.get(Address.fromHexString("0x02")))
        .isEqualTo(originalProcessor.precompiles.get(Address.fromHexString("0x02")));
    assertThat(processor.precompiles.get(Address.fromHexString("0x03")))
        .isEqualTo(originalProcessor.precompiles.get(Address.fromHexString("0x03")));
  }
}
