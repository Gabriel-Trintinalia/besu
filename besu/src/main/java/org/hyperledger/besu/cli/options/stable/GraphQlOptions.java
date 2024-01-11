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

import static java.util.Arrays.asList;
import static org.hyperledger.besu.ethereum.api.graphql.GraphQLConfiguration.DEFAULT_GRAPHQL_HTTP_PORT;

import org.hyperledger.besu.cli.DefaultCommandValues;
import org.hyperledger.besu.cli.custom.CorsAllowedOriginsProperty;
import org.hyperledger.besu.cli.custom.JsonRPCAllowlistHostsProperty;
import org.hyperledger.besu.cli.options.OptionsContext;
import org.hyperledger.besu.cli.util.CommandLineUtils;
import org.hyperledger.besu.ethereum.api.graphql.GraphQLConfiguration;

import com.google.common.base.Strings;
import org.slf4j.Logger;
import picocli.CommandLine;

public class GraphQlOptions {
  @CommandLine.Option(
      names = {"--graphql-http-enabled"},
      description = "Set to start the GraphQL HTTP service (default: ${DEFAULT-VALUE})")
  public final Boolean isGraphQLHttpEnabled = false;

  @SuppressWarnings({"FieldCanBeFinal", "FieldMayBeFinal"}) // PicoCLI requires non-final Strings.
  @CommandLine.Option(
      names = {"--graphql-http-host"},
      paramLabel = DefaultCommandValues.MANDATORY_HOST_FORMAT_HELP,
      description = "Host for GraphQL HTTP to listen on (default: ${DEFAULT-VALUE})",
      arity = "1")
  public String graphQLHttpHost;

  @CommandLine.Option(
      names = {"--graphql-http-port"},
      paramLabel = DefaultCommandValues.MANDATORY_PORT_FORMAT_HELP,
      description = "Port for GraphQL HTTP to listen on (default: ${DEFAULT-VALUE})",
      arity = "1")
  public final Integer graphQLHttpPort = DEFAULT_GRAPHQL_HTTP_PORT;

  @CommandLine.Option(
      names = {"--graphql-http-cors-origins"},
      description = "Comma separated origin domain URLs for CORS validation (default: none)")
  public final CorsAllowedOriginsProperty graphQLHttpCorsAllowedOrigins =
      new CorsAllowedOriginsProperty();

  public GraphQLConfiguration graphQLConfiguration(
      final Logger logger,
      final CommandLine commandLine,
      final OptionsContext optionsContext,
      final JsonRPCAllowlistHostsProperty hostsAllowlist) {
    CommandLineUtils.checkOptionDependencies(
        logger,
        commandLine,
        "--graphql-http-enabled",
        !optionsContext.getGraphQlOptionGroup().isGraphQLHttpEnabled,
        asList("--graphql-http-cors-origins", "--graphql-http-host", "--graphql-http-port"));
    final GraphQLConfiguration graphQLConfiguration = GraphQLConfiguration.createDefault();
    graphQLConfiguration.setEnabled(isGraphQLHttpEnabled);
    graphQLConfiguration.setHost(
        Strings.isNullOrEmpty(graphQLHttpHost)
            ? optionsContext.getP2PDiscoveryOptionGroup().autoDiscoverDefaultIP().getHostAddress()
            : graphQLHttpHost);
    graphQLConfiguration.setPort(graphQLHttpPort);
    graphQLConfiguration.setHostsAllowlist(hostsAllowlist);
    graphQLConfiguration.setCorsAllowedDomains(graphQLHttpCorsAllowedOrigins);
    graphQLConfiguration.setHttpTimeoutSec(
        optionsContext.getUnstableRPCOptions().getHttpTimeoutSec());
    return graphQLConfiguration;
  }
}
