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
package org.hyperledger.besu.ethereum.mainnet.requests;

import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.ethereum.core.MutableWorldState;
import org.hyperledger.besu.ethereum.core.ProcessableBlockHeader;
import org.hyperledger.besu.ethereum.core.Request;
import org.hyperledger.besu.ethereum.core.TransactionReceipt;
import org.hyperledger.besu.ethereum.core.WithdrawalRequest;
import org.hyperledger.besu.ethereum.mainnet.ProtocolSpec;
import org.hyperledger.besu.ethereum.mainnet.SystemCallProcessor;
import org.hyperledger.besu.ethereum.mainnet.WithdrawalRequestContractHelper;
import org.hyperledger.besu.evm.tracing.OperationTracer;

import java.util.List;
import java.util.Optional;

public class WithdrawalRequestProcessor implements RequestProcessor {

  public static final Address WITHDRAWAL_REQUEST_PREDEPLOY_ADDRESS =
      Address.fromHexString("0x00A3ca265EBcb825B45F985A16CEFB49958cE017");

  @Override
  public Optional<List<? extends Request>> process(
      final ProcessableBlockHeader blockHeader,
      final MutableWorldState mutableWorldState,
      final ProtocolSpec protocolSpec,
      final List<TransactionReceipt> transactionReceipts,
      final OperationTracer operationTracer) {

    SystemCallProcessor systemCallProcessor = new SystemCallProcessor(protocolSpec.getTransactionProcessor());
    systemCallProcessor.process(
        WITHDRAWAL_REQUEST_PREDEPLOY_ADDRESS,
        mutableWorldState.updater(),
        blockHeader,
        operationTracer,
        protocolSpec.getBlockHashProcessor().getBlockHashLookup(),
        null);
    List<WithdrawalRequest> withdrawalRequests =
        WithdrawalRequestContractHelper.popWithdrawalRequestsFromQueue(mutableWorldState).stream()
            .toList();

    return Optional.of(withdrawalRequests);
  }
}
