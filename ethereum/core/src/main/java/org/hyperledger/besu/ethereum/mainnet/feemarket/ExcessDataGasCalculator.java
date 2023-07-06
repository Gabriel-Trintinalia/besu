package org.hyperledger.besu.ethereum.mainnet.feemarket;

import org.hyperledger.besu.datatypes.DataGas;
import org.hyperledger.besu.ethereum.core.BlockHeader;
import org.hyperledger.besu.ethereum.mainnet.ProtocolSpec;

public class ExcessDataGasCalculator {
  public static DataGas calculateExcessDataGasForParent(
      final ProtocolSpec protocolSpec, final BlockHeader parentHeader) {
    // Blob Data Excess
    long headerExcess =
        protocolSpec
            .getGasCalculator()
            .computeExcessDataGas(
                parentHeader.getExcessDataGas().map(DataGas::toLong).orElse(0L),
                parentHeader.getDataGasUsed().orElse(0L));
    return DataGas.of(headerExcess);
  }
}
