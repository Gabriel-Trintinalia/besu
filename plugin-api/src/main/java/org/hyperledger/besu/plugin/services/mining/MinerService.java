package org.hyperledger.besu.plugin.services.mining;

import org.hyperledger.besu.plugin.services.BesuService;

public interface MinerService extends BesuService {
  void stop();
}
