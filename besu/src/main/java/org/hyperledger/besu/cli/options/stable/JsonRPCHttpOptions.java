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

import static org.hyperledger.besu.ethereum.api.jsonrpc.JsonRpcConfiguration.DEFAULT_JSON_RPC_PORT;
import static org.hyperledger.besu.ethereum.api.jsonrpc.JsonRpcConfiguration.DEFAULT_PRETTY_JSON_ENABLED;
import static org.hyperledger.besu.ethereum.api.jsonrpc.RpcApis.DEFAULT_RPC_APIS;

import org.hyperledger.besu.cli.DefaultCommandValues;
import org.hyperledger.besu.cli.custom.CorsAllowedOriginsProperty;
import org.hyperledger.besu.ethereum.api.jsonrpc.authentication.JwtAlgorithm;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import picocli.CommandLine;

public class JsonRPCHttpOptions {
  @CommandLine.Option(
      names = {"--rpc-http-enabled"},
      description = "Set to start the JSON-RPC HTTP service (default: ${DEFAULT-VALUE})")
  public final Boolean isRpcHttpEnabled = false;

  @SuppressWarnings({"FieldCanBeFinal", "FieldMayBeFinal"}) // PicoCLI requires non-final Strings.
  @CommandLine.Option(
      names = {"--rpc-http-host"},
      paramLabel = DefaultCommandValues.MANDATORY_HOST_FORMAT_HELP,
      description = "Host for JSON-RPC HTTP to listen on (default: ${DEFAULT-VALUE})",
      arity = "1")
  public String rpcHttpHost;

  @CommandLine.Option(
      names = {"--rpc-http-port"},
      paramLabel = DefaultCommandValues.MANDATORY_PORT_FORMAT_HELP,
      description = "Port for JSON-RPC HTTP to listen on (default: ${DEFAULT-VALUE})",
      arity = "1")
  public final Integer rpcHttpPort = DEFAULT_JSON_RPC_PORT;

  @CommandLine.Option(
      names = {"--rpc-http-max-active-connections"},
      description =
          "Maximum number of HTTP connections allowed for JSON-RPC (default: ${DEFAULT-VALUE}). Once this limit is reached, incoming connections will be rejected.",
      arity = "1")
  public final Integer rpcHttpMaxConnections = DefaultCommandValues.DEFAULT_HTTP_MAX_CONNECTIONS;

  // A list of origins URLs that are accepted by the JsonRpcHttpServer (CORS)
  @CommandLine.Option(
      names = {"--rpc-http-cors-origins"},
      description = "Comma separated origin domain URLs for CORS validation (default: none)")
  public final CorsAllowedOriginsProperty rpcHttpCorsAllowedOrigins =
      new CorsAllowedOriginsProperty();

  @CommandLine.Option(
      names = {"--rpc-http-api", "--rpc-http-apis"},
      paramLabel = "<api name>",
      split = " {0,1}, {0,1}",
      arity = "1..*",
      description =
          "Comma separated list of APIs to enable on JSON-RPC HTTP service (default: ${DEFAULT-VALUE})")
  public final List<String> rpcHttpApis = DEFAULT_RPC_APIS;

  @CommandLine.Option(
      names = {"--rpc-http-api-method-no-auth", "--rpc-http-api-methods-no-auth"},
      paramLabel = "<api name>",
      split = " {0,1}, {0,1}",
      arity = "1..*",
      description =
          "Comma separated list of API methods to exclude from RPC authentication services, RPC HTTP authentication must be enabled")
  public final List<String> rpcHttpApiMethodsNoAuth = new ArrayList<String>();

  @CommandLine.Option(
      names = {"--rpc-http-authentication-enabled"},
      description =
          "Require authentication for the JSON-RPC HTTP service (default: ${DEFAULT-VALUE})")
  public final Boolean isRpcHttpAuthenticationEnabled = false;

  @SuppressWarnings({"FieldCanBeFinal", "FieldMayBeFinal"}) // PicoCLI requires non-final Strings.
  @CommandLine.Option(
      names = {"--rpc-http-authentication-credentials-file"},
      paramLabel = DefaultCommandValues.MANDATORY_FILE_FORMAT_HELP,
      description =
          "Storage file for JSON-RPC HTTP authentication credentials (default: ${DEFAULT-VALUE})",
      arity = "1")
  public String rpcHttpAuthenticationCredentialsFile = null;

  @CommandLine.Option(
      names = {"--rpc-http-authentication-jwt-public-key-file"},
      paramLabel = DefaultCommandValues.MANDATORY_FILE_FORMAT_HELP,
      description = "JWT public key file for JSON-RPC HTTP authentication",
      arity = "1")
  public final File rpcHttpAuthenticationPublicKeyFile = null;

  @CommandLine.Option(
      names = {"--rpc-http-authentication-jwt-algorithm"},
      description =
          "Encryption algorithm used for HTTP JWT public key. Possible values are ${COMPLETION-CANDIDATES}"
              + " (default: ${DEFAULT-VALUE})",
      arity = "1")
  public final JwtAlgorithm rpcHttpAuthenticationAlgorithm =
      DefaultCommandValues.DEFAULT_JWT_ALGORITHM;

  @CommandLine.Option(
      names = {"--rpc-http-tls-enabled"},
      description = "Enable TLS for the JSON-RPC HTTP service (default: ${DEFAULT-VALUE})")
  public final Boolean isRpcHttpTlsEnabled = false;

  @CommandLine.Option(
      names = {"--rpc-http-tls-keystore-file"},
      paramLabel = DefaultCommandValues.MANDATORY_FILE_FORMAT_HELP,
      description =
          "Keystore (PKCS#12) containing key/certificate for the JSON-RPC HTTP service. Required if TLS is enabled.")
  public final Path rpcHttpTlsKeyStoreFile = null;

  @CommandLine.Option(
      names = {"--rpc-http-tls-keystore-password-file"},
      paramLabel = DefaultCommandValues.MANDATORY_FILE_FORMAT_HELP,
      description =
          "File containing password to unlock keystore for the JSON-RPC HTTP service. Required if TLS is enabled.")
  public final Path rpcHttpTlsKeyStorePasswordFile = null;

  @CommandLine.Option(
      names = {"--rpc-http-tls-client-auth-enabled"},
      description =
          "Enable TLS client authentication for the JSON-RPC HTTP service (default: ${DEFAULT-VALUE})")
  public final Boolean isRpcHttpTlsClientAuthEnabled = false;

  @CommandLine.Option(
      names = {"--rpc-http-tls-known-clients-file"},
      paramLabel = DefaultCommandValues.MANDATORY_FILE_FORMAT_HELP,
      description =
          "Path to file containing clients certificate common name and fingerprint for client authentication")
  public final Path rpcHttpTlsKnownClientsFile = null;

  @CommandLine.Option(
      names = {"--rpc-http-tls-ca-clients-enabled"},
      description =
          "Enable to accept clients certificate signed by a valid CA for client authentication (default: ${DEFAULT-VALUE})")
  public final Boolean isRpcHttpTlsCAClientsEnabled = false;

  @CommandLine.Option(
      names = {"--rpc-http-tls-protocol", "--rpc-http-tls-protocols"},
      description = "Comma separated list of TLS protocols to support (default: ${DEFAULT-VALUE})",
      split = ",",
      arity = "1..*")
  public final List<String> rpcHttpTlsProtocols =
      new ArrayList<>(DefaultCommandValues.DEFAULT_TLS_PROTOCOLS);

  @CommandLine.Option(
      names = {"--rpc-http-tls-cipher-suite", "--rpc-http-tls-cipher-suites"},
      description = "Comma separated list of TLS cipher suites to support",
      split = ",",
      arity = "1..*")
  public final List<String> rpcHttpTlsCipherSuites = new ArrayList<>();

  @CommandLine.Option(
      names = {"--rpc-http-max-batch-size"},
      paramLabel = DefaultCommandValues.MANDATORY_INTEGER_FORMAT_HELP,
      description =
          "Specifies the maximum number of requests in a single RPC batch request via RPC. -1 specifies no limit  (default: ${DEFAULT-VALUE})")
  public final Integer rpcHttpMaxBatchSize = DefaultCommandValues.DEFAULT_HTTP_MAX_BATCH_SIZE;

  @CommandLine.Option(
      names = {"--rpc-http-max-request-content-length"},
      paramLabel = DefaultCommandValues.MANDATORY_LONG_FORMAT_HELP,
      description = "Specifies the maximum request content length. (default: ${DEFAULT-VALUE})")
  public final Long rpcHttpMaxRequestContentLength =
      DefaultCommandValues.DEFAULT_MAX_REQUEST_CONTENT_LENGTH;

  @CommandLine.Option(
      names = {"--json-pretty-print-enabled"},
      description = "Enable JSON pretty print format (default: ${DEFAULT-VALUE})")
  public final Boolean prettyJsonEnabled = DEFAULT_PRETTY_JSON_ENABLED;
}
