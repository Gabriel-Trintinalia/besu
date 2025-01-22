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

import static org.hyperledger.besu.ethereum.transaction.SimulationError.BLOCK_NUMBERS_NOT_ASCENDING;
import static org.hyperledger.besu.ethereum.transaction.SimulationError.INVALID_NONCES;
import static org.hyperledger.besu.ethereum.transaction.SimulationError.INVALID_PRECOMPILE_ADDRESS;
import static org.hyperledger.besu.ethereum.transaction.SimulationError.TIMESTAMPS_NOT_ASCENDING;
import static org.hyperledger.besu.ethereum.transaction.SimulationError.TOO_MANY_BLOCK_CALLS;
import static org.hyperledger.besu.ethereum.transaction.TransactionSimulator.DEFAULT_SIMULATION_FROM;

import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.StateOverride;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public class BlockSimulationParameter {
  private static final int MAX_BLOCK_CALL_SIZE = 256;

  static final BlockSimulationParameter EMPTY =
      new BlockSimulationParameter(List.of(), false, false, false);

  final List<? extends BlockStateCall> blockStateCalls;
  private final boolean validation;

  private final boolean traceTransfers;

  private final boolean returnFullTransactions;

  public BlockSimulationParameter(final List<? extends BlockStateCall> blockStateCalls) {
    this.blockStateCalls = blockStateCalls;
    this.validation = false;
    this.traceTransfers = false;
    this.returnFullTransactions = false;
  }

  public BlockSimulationParameter(final BlockStateCall blockStateCall) {
    this(List.of(blockStateCall));
  }

  public BlockSimulationParameter(final BlockStateCall blockStateCall, final boolean validation) {
    this(List.of(blockStateCall), validation, false, false);
  }

  public BlockSimulationParameter(
      final List<? extends BlockStateCall> blockStateCalls,
      final boolean validation,
      final boolean traceTransfers,
      final boolean returnFullTransactions) {

    this.blockStateCalls = blockStateCalls;
    this.validation = validation;
    this.traceTransfers = traceTransfers;
    this.returnFullTransactions = returnFullTransactions;
  }

  public List<? extends BlockStateCall> getBlockStateCalls() {
    return blockStateCalls;
  }

  public boolean isValidation() {
    return validation;
  }

  public boolean isTraceTransfers() {
    return traceTransfers;
  }

  public boolean isReturnFullTransactions() {
    return returnFullTransactions;
  }

  public Optional<SimulationError> validate(final Set<Address> validPrecompileAddresses) {
    if (blockStateCalls.size() > MAX_BLOCK_CALL_SIZE) {
      return Optional.of(TOO_MANY_BLOCK_CALLS);
    }

    Optional<SimulationError> blockNumberError = validateBlockNumbers();
    if (blockNumberError.isPresent()) {
      return blockNumberError;
    }

    Optional<SimulationError> timestampError = validateTimestamps();
    if (timestampError.isPresent()) {
      return timestampError;
    }

    Optional<SimulationError> nonceError = validateNonces();
    if (nonceError.isPresent()) {
      return nonceError;
    }

    return validateStateOverrides(validPrecompileAddresses);
  }

  private Optional<SimulationError> validateBlockNumbers() {
    long previousBlockNumber = 0;
    for (BlockStateCall call : blockStateCalls) {
      Optional<Long> blockNumberOverride = call.getBlockOverrides().getBlockNumber();
      if (blockNumberOverride.isPresent()) {
        long currentBlockNumber = blockNumberOverride.get();
        if (currentBlockNumber <= previousBlockNumber) {
          return Optional.of(BLOCK_NUMBERS_NOT_ASCENDING);
        }
        previousBlockNumber = currentBlockNumber;
      }
    }
    return Optional.empty();
  }

  private Optional<SimulationError> validateTimestamps() {
    long previousTimestamp = 0;
    for (BlockStateCall call : blockStateCalls) {
      Optional<Long> blockTimestampOverride = call.getBlockOverrides().getTimestamp();
      if (blockTimestampOverride.isPresent()) {
        long blockTimestamp = blockTimestampOverride.get();
        if (blockTimestamp <= previousTimestamp) {
          return Optional.of(TIMESTAMPS_NOT_ASCENDING);
        }
        previousTimestamp = blockTimestamp;
      }
    }
    return Optional.empty();
  }

  private Optional<SimulationError> validateNonces() {
    Map<Address, Long> previousNonces = new HashMap<>();
    for (BlockStateCall call : blockStateCalls) {
      for (CallParameter callParameter : call.getCalls()) {
        Address fromAddress =
            Optional.ofNullable(callParameter.getFrom()).orElse(DEFAULT_SIMULATION_FROM);
        Optional<Long> nonce = callParameter.getNonce();

        if (nonce.isPresent()) {
          long currentNonce = nonce.get();
          if (previousNonces.containsKey(fromAddress)) {
            long previousNonce = previousNonces.get(fromAddress);
            if (currentNonce <= previousNonce) {
              return Optional.of(INVALID_NONCES);
            }
          }
          previousNonces.put(fromAddress, currentNonce);
        }
      }
    }
    return Optional.empty();
  }

  private Optional<SimulationError> validateStateOverrides(
      final Set<Address> validPrecompileAddresses) {
    for (BlockStateCall call : blockStateCalls) {
      if (call.getStateOverrideMap().isPresent()) {
        var stateOverrideMap = call.getStateOverrideMap().get();
        for (Address stateOverride : stateOverrideMap.keySet()) {
          final StateOverride override = stateOverrideMap.get(stateOverride);
          if (override.getMovePrecompileToAddress().isPresent()
              && !validPrecompileAddresses.contains(stateOverride)) {
            return Optional.of(INVALID_PRECOMPILE_ADDRESS);
          }
        }
      }
    }
    return Optional.empty();
  }
}
