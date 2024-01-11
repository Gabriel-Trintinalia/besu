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
package org.hyperledger.besu.cli.options.stable;

import org.hyperledger.besu.datatypes.Address;

import picocli.CommandLine;

public class PermissionsOptions {
  @CommandLine.Option(
      names = {"--permissions-nodes-config-file-enabled"},
      description = "Enable node level permissions (default: ${DEFAULT-VALUE})")
  public final Boolean permissionsNodesEnabled = false;

  @SuppressWarnings({"FieldCanBeFinal", "FieldMayBeFinal"}) // PicoCLI requires non-final Strings.
  @CommandLine.Option(
      names = {"--permissions-nodes-config-file"},
      description =
          "Node permissioning config TOML file (default: a file named \"permissions_config.toml\" in the Besu data folder)")
  public String nodePermissionsConfigFile = null;

  @CommandLine.Option(
      names = {"--permissions-accounts-config-file-enabled"},
      description = "Enable account level permissions (default: ${DEFAULT-VALUE})")
  public final Boolean permissionsAccountsEnabled = false;

  @SuppressWarnings({"FieldCanBeFinal", "FieldMayBeFinal"}) // PicoCLI requires non-final Strings.
  @CommandLine.Option(
      names = {"--permissions-accounts-config-file"},
      description =
          "Account permissioning config TOML file (default: a file named \"permissions_config.toml\" in the Besu data folder)")
  public String accountPermissionsConfigFile = null;

  @CommandLine.Option(
      names = {"--permissions-nodes-contract-address"},
      description = "Address of the node permissioning smart contract",
      arity = "1")
  public final Address permissionsNodesContractAddress = null;

  @CommandLine.Option(
      names = {"--permissions-nodes-contract-version"},
      description = "Version of the EEA Node Permissioning interface (default: ${DEFAULT-VALUE})")
  public final Integer permissionsNodesContractVersion = 1;

  @CommandLine.Option(
      names = {"--permissions-nodes-contract-enabled"},
      description = "Enable node level permissions via smart contract (default: ${DEFAULT-VALUE})")
  public final Boolean permissionsNodesContractEnabled = false;

  @CommandLine.Option(
      names = {"--permissions-accounts-contract-address"},
      description = "Address of the account permissioning smart contract",
      arity = "1")
  public final Address permissionsAccountsContractAddress = null;

  @CommandLine.Option(
      names = {"--permissions-accounts-contract-enabled"},
      description =
          "Enable account level permissions via smart contract (default: ${DEFAULT-VALUE})")
  public final Boolean permissionsAccountsContractEnabled = false;
}
