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
package org.hyperledger.besu.ethereum.mainnet.feemarket;

import org.hyperledger.besu.config.BlobFork;
import org.hyperledger.besu.datatypes.BlobGas;
import org.hyperledger.besu.datatypes.Wei;

import java.util.Optional;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PragueFeeMarket extends CancunFeeMarket {
  private static final Logger LOG = LoggerFactory.getLogger(PragueFeeMarket.class);

  PragueFeeMarket(
      final long londonForkBlockNumber,
      final Optional<Wei> baseFeePerGasOverride,
      final Function<Long, Long> baseFeeUpdateFractionSupplier) {
    super(londonForkBlockNumber, baseFeePerGasOverride, baseFeeUpdateFractionSupplier);
  }

  PragueFeeMarket(final long londonForkBlockNumber, final Optional<Wei> baseFeePerGasOverride) {
    this(
        londonForkBlockNumber,
        baseFeePerGasOverride,
        (__) -> BlobFork.PRAGUE_DEFAULT.getBaseFeeUpdateFraction());
  }

  @Override
  public Wei blobGasPricePerGas(final BlobGas excessBlobGas, final long ignored) {
    final var blobGasPrice =
        Wei.of(
            fakeExponential(
                BLOB_GAS_PRICE, excessBlobGas.toBigInteger(), getBaseFeeUpdateFraction(ignored)));
    LOG.atTrace()
        .setMessage("parentExcessBlobGas: {} blobGasPrice: {}")
        .addArgument(excessBlobGas::toShortHexString)
        .addArgument(blobGasPrice::toHexString)
        .log();

    return blobGasPrice;
  }
}
