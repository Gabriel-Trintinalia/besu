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

import static org.apache.tuweni.bytes.Bytes32.leftPad;

import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.log.Log;
import org.hyperledger.besu.evm.log.LogTopic;
import org.hyperledger.besu.evm.precompile.PrecompileContractRegistry;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.google.common.collect.ImmutableList;

/**
 * A message call processor designed specifically for simulation purposes that allows for overriding
 * precompile addresses.
 */
public class SimulationMessageCallProcessor extends MessageCallProcessor {

  public static final Address TRANSFER_ADDRESS = Address.fromHexString("0xeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee");
  public static final Bytes TRANSFER_TOPIC =
    Bytes.fromHexString("ddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef");

  final boolean isTraceTransfers;
  /**
   * Instantiates a new Modifiable precompiles message call processor for simulation.
   *
   * @param originalProcessor the original processor
   * @param precompileOverrides the address overrides
   */
  public SimulationMessageCallProcessor(
    final MessageCallProcessor originalProcessor,
    final Map<Address, Address> precompileOverrides,
    final boolean isTraceTransfers) {
    super(
        originalProcessor.evm,
        precompileOverrides.isEmpty()
            ? originalProcessor.precompiles
            : createRegistryWithPrecompileOverrides(
                originalProcessor.precompiles, precompileOverrides));
    this.isTraceTransfers = isTraceTransfers;
  }

  /**
   * Creates a new PrecompileContractRegistry with the specified address overrides.
   *
   * @param originalRegistry the original precompile contract registry
   * @param precompileOverrides the address overrides
   * @return a new PrecompileContractRegistry with the overrides applied
   * @throws IllegalArgumentException if an override address does not exist in the original registry
   */
  private static PrecompileContractRegistry createRegistryWithPrecompileOverrides(
      final PrecompileContractRegistry originalRegistry,
      final Map<Address, Address> precompileOverrides) {

    PrecompileContractRegistry newRegistry = new PrecompileContractRegistry();
    Set<Address> originalAddresses = originalRegistry.getPrecompileAddresses();

    precompileOverrides.forEach(
        (oldAddress, newAddress) -> {
          if (!originalAddresses.contains(oldAddress)) {
            throw new IllegalArgumentException("Address " + oldAddress + " is not a precompile.");
          }
          if (newRegistry.getPrecompileAddresses().contains(newAddress)) {
            throw new IllegalArgumentException("Duplicate precompile address: " + newAddress);
          }
          newRegistry.put(newAddress, originalRegistry.get(oldAddress));
        });

    originalAddresses.stream()
        .filter(originalAddress -> !precompileOverrides.containsKey(originalAddress))
        .forEach(
            originalAddress -> {
              if (newRegistry.getPrecompileAddresses().contains(originalAddress)) {
                throw new IllegalArgumentException(
                    "Duplicate precompile address: " + originalAddress);
              }
              newRegistry.put(originalAddress, originalRegistry.get(originalAddress));
            });

    return newRegistry;
  }

  @Override
  protected void transferValue(final MessageFrame frame) {
    super.transferValue(frame);
    if(shouldEmitTransferLog(frame)) {
      emitEthTransferLog(frame);
    }
  }

  private boolean shouldEmitTransferLog(final MessageFrame frame) {
    return isTraceTransfers && frame.getValue().compareTo(Wei.ZERO) > 0 && !frame.getRecipientAddress().equals(TRANSFER_ADDRESS);
  }

  private void emitEthTransferLog(final MessageFrame frame) {
    final ImmutableList.Builder<LogTopic> builder = ImmutableList.builderWithExpectedSize(3);
    builder.add(LogTopic.create(TRANSFER_TOPIC));
    builder.add(LogTopic.create(leftPad (frame.getSenderAddress())));
    builder.add(LogTopic.create(leftPad(frame.getRecipientAddress())));
    frame.addLog(new Log(TRANSFER_ADDRESS, frame.getValue(), builder.build()));
  }
}
