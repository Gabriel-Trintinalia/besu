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
package org.hyperledger.besu.ethereum.core.plugins;

import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.BlobGas;
import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.ethereum.core.BlockHeader;
import org.hyperledger.besu.ethereum.core.BlockHeaderFunctions;
import org.hyperledger.besu.ethereum.core.Difficulty;
import org.hyperledger.besu.ethereum.core.Transaction;
import org.hyperledger.besu.evm.log.LogsBloomFilter;

public class PluginConverter {
  public static BlockHeader toBlockHeader(
      final org.hyperledger.besu.plugin.data.BlockHeader pluginBlockHeader,
      final BlockHeaderFunctions blockHeaderFunctions) {
    return new BlockHeader(
        Hash.fromHexString(pluginBlockHeader.getParentHash().toHexString()),
        Hash.fromHexString(pluginBlockHeader.getOmmersHash().toHexString()),
        Address.fromHexString(pluginBlockHeader.getCoinbase().toHexString()),
        Hash.fromHexString(pluginBlockHeader.getStateRoot().toHexString()),
        Hash.fromHexString(pluginBlockHeader.getTransactionsRoot().toHexString()),
        Hash.fromHexString(pluginBlockHeader.getReceiptsRoot().toHexString()),
        LogsBloomFilter.fromHexString(pluginBlockHeader.getLogsBloom().toHexString()),
        Difficulty.fromHexString(pluginBlockHeader.getDifficulty().toHexString()),
        pluginBlockHeader.getNumber(),
        pluginBlockHeader.getGasLimit(),
        pluginBlockHeader.getGasUsed(),
        pluginBlockHeader.getTimestamp(),
        pluginBlockHeader.getExtraData(),
        pluginBlockHeader.getBaseFee().map(Wei::fromQuantity).orElse(null),
        pluginBlockHeader.getPrevRandao().orElse(null),
        pluginBlockHeader.getNonce(),
        pluginBlockHeader
            .getWithdrawalsRoot()
            .map(h -> Hash.fromHexString(h.toHexString()))
            .orElse(null),
        pluginBlockHeader.getBlobGasUsed().map(Long::longValue).orElse(null),
        pluginBlockHeader.getExcessBlobGas().map(BlobGas.class::cast).orElse(null),
        pluginBlockHeader.getParentBeaconBlockRoot().orElse(null),
        pluginBlockHeader
            .getRequestsRoot()
            .map(h -> Hash.fromHexString(h.toHexString()))
            .orElse(null),
        blockHeaderFunctions);
  }

  public static Transaction toTransaction(
      final org.hyperledger.besu.datatypes.Transaction pluginTransaction) {
    Transaction.Builder builder = Transaction.builder();
    pluginTransaction.getChainId().ifPresent(builder::chainId);
    pluginTransaction
        .getMaxPriorityFeePerGas()
        .ifPresent(
            maxPriorityFeePerGas ->
                builder.maxPriorityFeePerGas(Wei.fromQuantity(maxPriorityFeePerGas)));
    pluginTransaction
        .getMaxFeePerGas()
        .ifPresent(fee -> builder.maxFeePerGas(Wei.fromQuantity(fee)));
    pluginTransaction.getTo().ifPresent(builder::to);
    pluginTransaction.getAccessList().ifPresent(builder::accessList);
    pluginTransaction.getBlobsWithCommitments().ifPresent(builder::blobsWithCommitments);
    pluginTransaction.getCodeDelegationList().ifPresent(builder::codeDelegations);
    builder
        .type(pluginTransaction.getType())
        .nonce(pluginTransaction.getNonce())
        .gasLimit(pluginTransaction.getGasLimit())
        .value(Wei.fromQuantity(pluginTransaction.getValue()))
        .payload(pluginTransaction.getPayload());
    return builder.build();
  }
}
