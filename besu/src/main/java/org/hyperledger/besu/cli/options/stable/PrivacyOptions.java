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

import org.hyperledger.besu.cli.DefaultCommandValues;
import org.hyperledger.besu.ethereum.core.PrivacyParameters;

import java.io.File;
import java.net.URI;
import java.nio.file.Path;

import picocli.CommandLine;

public class PrivacyOptions {
  @CommandLine.Option(
      names = {"--privacy-tls-enabled"},
      paramLabel = DefaultCommandValues.MANDATORY_FILE_FORMAT_HELP,
      description = "Enable TLS for connecting to privacy enclave (default: ${DEFAULT-VALUE})")
  public final Boolean isPrivacyTlsEnabled = false;

  @CommandLine.Option(
      names = "--privacy-tls-keystore-file",
      paramLabel = DefaultCommandValues.MANDATORY_FILE_FORMAT_HELP,
      description =
          "Path to a PKCS#12 formatted keystore; used to enable TLS on inbound connections.")
  public final Path privacyKeyStoreFile = null;

  @CommandLine.Option(
      names = "--privacy-tls-keystore-password-file",
      paramLabel = DefaultCommandValues.MANDATORY_FILE_FORMAT_HELP,
      description = "Path to a file containing the password used to decrypt the keystore.")
  public final Path privacyKeyStorePasswordFile = null;

  @CommandLine.Option(
      names = "--privacy-tls-known-enclave-file",
      paramLabel = DefaultCommandValues.MANDATORY_FILE_FORMAT_HELP,
      description = "Path to a file containing the fingerprints of the authorized privacy enclave.")
  public final Path privacyTlsKnownEnclaveFile = null;

  @CommandLine.Option(
      names = {"--privacy-enabled"},
      description = "Enable private transactions (default: ${DEFAULT-VALUE})")
  public final Boolean isPrivacyEnabled = false;

  @CommandLine.Option(
      names = {"--privacy-multi-tenancy-enabled"},
      description = "Enable multi-tenant private transactions (default: ${DEFAULT-VALUE})")
  public final Boolean isPrivacyMultiTenancyEnabled = false;

  @CommandLine.Option(
      names = {"--privacy-url"},
      description = "The URL on which the enclave is running")
  public final URI privacyUrl = PrivacyParameters.DEFAULT_ENCLAVE_URL;

  @CommandLine.Option(
      names = {"--privacy-public-key-file"},
      description = "The enclave's public key file")
  public final File privacyPublicKeyFile = null;

  @CommandLine.Option(
      names = {"--privacy-marker-transaction-signing-key-file"},
      description =
          "The name of a file containing the private key used to sign privacy marker transactions. If unset, each will be signed with a random key.")
  public final Path privateMarkerTransactionSigningKeyPath = null;

  @CommandLine.Option(
      names = {"--privacy-enable-database-migration"},
      description = "Enable private database metadata migration (default: ${DEFAULT-VALUE})")
  public final Boolean migratePrivateDatabase = false;

  @CommandLine.Option(
      names = {"--privacy-flexible-groups-enabled"},
      description = "Enable flexible privacy groups (default: ${DEFAULT-VALUE})")
  public final Boolean isFlexiblePrivacyGroupsEnabled = false;

  @CommandLine.Option(
      hidden = true,
      names = {"--privacy-onchain-groups-enabled"},
      description =
          "!!DEPRECATED!! Use `--privacy-flexible-groups-enabled` instead. Enable flexible (onchain) privacy groups (default: ${DEFAULT-VALUE})")
  public final Boolean isOnchainPrivacyGroupsEnabled = false;
}
