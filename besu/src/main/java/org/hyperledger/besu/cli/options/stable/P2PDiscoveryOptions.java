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
import org.hyperledger.besu.cli.converter.PercentageConverter;
import org.hyperledger.besu.ethereum.p2p.peers.EnodeURLImpl;
import org.hyperledger.besu.util.NetworkUtility;
import org.hyperledger.besu.util.number.Fraction;
import org.hyperledger.besu.util.number.Percentage;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import org.apache.tuweni.bytes.Bytes;
import picocli.CommandLine;

public class P2PDiscoveryOptions {

  // Public IP stored to prevent having to research it each time we need it.
  private InetAddress autoDiscoveredDefaultIP = null;

  // Completely disables P2P within Besu.
  @CommandLine.Option(
      names = {"--p2p-enabled"},
      description = "Enable P2P functionality (default: ${DEFAULT-VALUE})",
      arity = "1")
  public final Boolean p2pEnabled = true;

  // Boolean option to indicate if peers should NOT be discovered, default to
  // false indicates that
  // the peers should be discovered by default.
  //
  // This negative option is required because of the nature of the option that is
  // true when
  // added on the command line. You can't do --option=false, so false is set as
  // default
  // and you have not to set the option at all if you want it false.
  // This seems to be the only way it works with Picocli.
  // Also many other software use the same negative option scheme for false
  // defaults
  // meaning that it's probably the right way to handle disabling options.
  @CommandLine.Option(
      names = {"--discovery-enabled"},
      description = "Enable P2P discovery (default: ${DEFAULT-VALUE})",
      arity = "1")
  public final Boolean peerDiscoveryEnabled = true;

  // A list of bootstrap nodes can be passed
  // and a hardcoded list will be used otherwise by the Runner.
  // NOTE: we have no control over default value here.
  @CommandLine.Option(
      names = {"--bootnodes"},
      paramLabel = "<enode://id@host:port>",
      description =
          "Comma separated enode URLs for P2P discovery bootstrap. "
              + "Default is a predefined list.",
      split = ",",
      arity = "0..*")
  public final List<String> bootNodes = null;

  @SuppressWarnings({"FieldCanBeFinal", "FieldMayBeFinal"}) // PicoCLI requires non-final Strings.
  @CommandLine.Option(
      names = {"--p2p-host"},
      paramLabel = DefaultCommandValues.MANDATORY_HOST_FORMAT_HELP,
      description = "IP address this node advertises to its peers (default: ${DEFAULT-VALUE})",
      arity = "1")
  public String p2pHost = autoDiscoverDefaultIP().getHostAddress();

  @SuppressWarnings({"FieldCanBeFinal", "FieldMayBeFinal"}) // PicoCLI requires non-final Strings.
  @CommandLine.Option(
      names = {"--p2p-interface"},
      paramLabel = DefaultCommandValues.MANDATORY_HOST_FORMAT_HELP,
      description =
          "The network interface address on which this node listens for P2P communication (default: ${DEFAULT-VALUE})",
      arity = "1")
  public String p2pInterface = NetworkUtility.INADDR_ANY;

  @CommandLine.Option(
      names = {"--p2p-port"},
      paramLabel = DefaultCommandValues.MANDATORY_PORT_FORMAT_HELP,
      description = "Port on which to listen for P2P communication (default: ${DEFAULT-VALUE})",
      arity = "1")
  public final Integer p2pPort = EnodeURLImpl.DEFAULT_LISTENING_PORT;

  @CommandLine.Option(
      names = {"--max-peers", "--p2p-peer-upper-bound"},
      paramLabel = DefaultCommandValues.MANDATORY_INTEGER_FORMAT_HELP,
      description = "Maximum P2P connections that can be established (default: ${DEFAULT-VALUE})")
  public final Integer maxPeers = DefaultCommandValues.DEFAULT_MAX_PEERS;

  @CommandLine.Option(
      names = {"--remote-connections-limit-enabled"},
      description =
          "Whether to limit the number of P2P connections initiated remotely. (default: ${DEFAULT-VALUE})")
  public final Boolean isLimitRemoteWireConnectionsEnabled = true;

  @CommandLine.Option(
      names = {"--remote-connections-max-percentage"},
      paramLabel = DefaultCommandValues.MANDATORY_DOUBLE_FORMAT_HELP,
      description =
          "The maximum percentage of P2P connections that can be initiated remotely. Must be between 0 and 100 inclusive. (default: ${DEFAULT-VALUE})",
      arity = "1",
      converter = PercentageConverter.class)
  public final Percentage maxRemoteConnectionsPercentage =
      Fraction.fromFloat(DefaultCommandValues.DEFAULT_FRACTION_REMOTE_WIRE_CONNECTIONS_ALLOWED)
          .toPercentage();

  @SuppressWarnings({"FieldCanBeFinal", "FieldMayBeFinal"}) // PicoCLI requires non-final Strings.
  @CommandLine.Option(
      names = {"--discovery-dns-url"},
      description = "Specifies the URL to use for DNS discovery")
  public String discoveryDnsUrl = null;

  @CommandLine.Option(
      names = {"--random-peer-priority-enabled"},
      description =
          "Allow for incoming connections to be prioritized randomly. This will prevent (typically small, stable) networks from forming impenetrable peer cliques. (default: ${DEFAULT-VALUE})")
  public final Boolean randomPeerPriority = Boolean.FALSE;

  @CommandLine.Option(
      names = {"--banned-node-ids", "--banned-node-id"},
      paramLabel = DefaultCommandValues.MANDATORY_NODE_ID_FORMAT_HELP,
      description = "A list of node IDs to ban from the P2P network.",
      split = ",",
      arity = "1..*")
  void setBannedNodeIds(final List<String> values) {
    try {
      bannedNodeIds =
          values.stream()
              .filter(value -> !value.isEmpty())
              .map(EnodeURLImpl::parseNodeId)
              .collect(Collectors.toList());
    } catch (final IllegalArgumentException e) {
      throw new CommandLine.ParameterException(
          new CommandLine(this), "Invalid ids supplied to '--banned-node-ids'. " + e.getMessage());
    }
  }

  public Collection<Bytes> bannedNodeIds = new ArrayList<>();

  // Used to discover the default IP of the client.
  // Loopback IP is used by default as this is how smokeTests require it to be
  // and it's probably a good security behaviour to default only on the localhost.
  public InetAddress autoDiscoverDefaultIP() {
    autoDiscoveredDefaultIP =
        Optional.ofNullable(autoDiscoveredDefaultIP).orElseGet(InetAddress::getLoopbackAddress);

    return autoDiscoveredDefaultIP;
  }
}
