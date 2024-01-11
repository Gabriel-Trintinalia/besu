/*
 * Copyright Hyperledger Besu Contributors.
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
package org.hyperledger.besu.cli.options;

import org.hyperledger.besu.cli.options.stable.EngineRPCOptions;
import org.hyperledger.besu.cli.options.stable.GraphQlOptions;
import org.hyperledger.besu.cli.options.stable.JsonRPCHttpOptions;
import org.hyperledger.besu.cli.options.stable.JsonRPCWebsocketOptionGroup;
import org.hyperledger.besu.cli.options.stable.MetricsOptions;
import org.hyperledger.besu.cli.options.stable.P2PDiscoveryOptions;
import org.hyperledger.besu.cli.options.stable.PermissionsOptions;
import org.hyperledger.besu.cli.options.stable.PrivacyOptions;
import org.hyperledger.besu.cli.options.unstable.RPCOptions;

import picocli.CommandLine;

public class OptionsContext {
  private OptionsContext() {}

  public static OptionsContext create() {
    return new OptionsContext();
  }

  // P2P Discovery Option Group
  @CommandLine.ArgGroup(validate = false, heading = "@|bold P2P Discovery Options|@%n")
  P2PDiscoveryOptions p2PDiscoveryOptionGroup = new P2PDiscoveryOptions();

  @CommandLine.ArgGroup(validate = false, heading = "@|bold GraphQL Options|@%n")
  GraphQlOptions graphQlOptionGroup = new GraphQlOptions();

  // Engine JSON-PRC Options
  @CommandLine.ArgGroup(validate = false, heading = "@|bold Engine JSON-RPC Options|@%n")
  EngineRPCOptions engineRPCOptionGroup = new EngineRPCOptions();

  // Metrics Option Group
  @CommandLine.ArgGroup(validate = false, heading = "@|bold Metrics Options|@%n")
  org.hyperledger.besu.cli.options.stable.MetricsOptions metricsOptionGroup =
      new org.hyperledger.besu.cli.options.stable.MetricsOptions();

  // JSON-RPC HTTP Options
  @CommandLine.ArgGroup(validate = false, heading = "@|bold JSON-RPC HTTP Options|@%n")
  JsonRPCHttpOptions jsonRPCHttpOptionGroup = new JsonRPCHttpOptions();

  // Permission Option Group
  @CommandLine.ArgGroup(validate = false, heading = "@|bold Permissions Options|@%n")
  PermissionsOptions permissionsOptionGroup = new PermissionsOptions();

  // Privacy Options Group
  @CommandLine.ArgGroup(validate = false, heading = "@|bold Privacy Options|@%n")
  PrivacyOptions privacyOptionGroup = new PrivacyOptions();

  // JSON-RPC Websocket Options
  @CommandLine.ArgGroup(validate = false, heading = "@|bold JSON-RPC Websocket Options|@%n")
  JsonRPCWebsocketOptionGroup jsonRPCWebsocketOptionGroup = new JsonRPCWebsocketOptionGroup();

  @CommandLine.ArgGroup(validate = false, heading = "@|bold Tx Pool Common Options|@%n")
  final TransactionPoolOptions transactionPoolOptions = TransactionPoolOptions.create();

  @CommandLine.ArgGroup(validate = false, heading = "@|bold Block Builder Options|@%n")
  final MiningOptions miningOptions = MiningOptions.create();

  private final RPCOptions unstableRPCOptions = RPCOptions.create();

  public P2PDiscoveryOptions getP2PDiscoveryOptionGroup() {
    return p2PDiscoveryOptionGroup;
  }

  public GraphQlOptions getGraphQlOptionGroup() {
    return graphQlOptionGroup;
  }

  public RPCOptions getUnstableRPCOptions() {
    return unstableRPCOptions;
  }

  public EngineRPCOptions getEngineRPCOptionGroup() {
    return engineRPCOptionGroup;
  }

  public MetricsOptions getMetricsOptionGroup() {
    return metricsOptionGroup;
  }

  public JsonRPCHttpOptions getJsonRPCHttpOptionGroup() {
    return jsonRPCHttpOptionGroup;
  }

  public PermissionsOptions getPermissionsOptionGroup() {
    return permissionsOptionGroup;
  }

  public PrivacyOptions getPrivacyOptionGroup() {
    return privacyOptionGroup;
  }

  public JsonRPCWebsocketOptionGroup getJsonRPCWebsocketOptionGroup() {
    return jsonRPCWebsocketOptionGroup;
  }

  public TransactionPoolOptions getTransactionPoolOptions() {
    return transactionPoolOptions;
  }

  public MiningOptions getMiningOptions() {
    return miningOptions;
  }
}
