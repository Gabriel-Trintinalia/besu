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
package org.hyperledger.besu.ethereum.p2p.peers;

import static com.google.common.base.Preconditions.checkArgument;

import org.hyperledger.besu.plugin.data.EnodeURL;

import java.net.URI;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.common.primitives.Ints;
import org.apache.tuweni.bytes.Bytes;

public class NodeURLFactory {
  private static final Pattern DISCPORT_QUERY_STRING_REGEX =
      Pattern.compile("^discport=([0-9]{1,5})$");
  private static final Pattern NODE_ID_PATTERN = Pattern.compile("^[0-9a-fA-F]{128}$");

  public static EnodeURL fromString(final String value) {
    return fromString(value, EnodeDnsConfiguration.dnsDisabled());
  }

  public static EnodeURL fromString(
      final String value, final EnodeDnsConfiguration enodeDnsConfiguration) {
    try {
      checkStringArgumentNotEmpty(value, "Invalid empty value.");
      return fromURI(URI.create(value), enodeDnsConfiguration);
    } catch (final IllegalArgumentException e) {
      String message = "";
      if (enodeDnsConfiguration.dnsEnabled() && !enodeDnsConfiguration.updateEnabled()) {
        message =
            String.format(
                "Invalid IP address '%s' (or DNS query resolved an invalid IP). --Xdns-enabled is true but --Xdns-update-enabled flag is false.",
                value);
      } else {
        message =
            String.format(
                "Invalid enode URL syntax '%s'. Enode URL should have the following format 'enode://<node_id>@<ip>:<listening_port>[?discport=<discovery_port>]'.",
                value);
        if (e.getMessage() != null) {
          message += " " + e.getMessage();
        }
      }

      throw new IllegalArgumentException(message, e);
    }
  }

  public static EnodeURL fromURI(final URI uri) {
    return fromURI(uri, EnodeDnsConfiguration.dnsDisabled());
  }

  public static EnodeURL fromURI(final URI uri, final EnodeDnsConfiguration enodeDnsConfiguration) {
    checkArgument(uri != null, "URI cannot be null");
    checkStringArgumentNotEmpty(uri.getScheme(), "Missing 'enode' scheme.");
    checkStringArgumentNotEmpty(uri.getHost(), "Missing or invalid host or ip address.");
    checkStringArgumentNotEmpty(uri.getUserInfo(), "Missing node ID.");

    checkArgument(
        uri.getScheme().equalsIgnoreCase("enode"), "Invalid URI scheme (must equal \"enode\").");
    checkArgument(
        NODE_ID_PATTERN.matcher(uri.getUserInfo()).matches(),
        "Invalid node ID: node ID must have exactly 128 hexadecimal characters and should not include any '0x' hex prefix.");

    final Bytes id = Bytes.fromHexString(uri.getUserInfo());
    String host = uri.getHost();
    int tcpPort = uri.getPort();

    // Parse discport if it exists
    Optional<Integer> discoveryPort = Optional.empty();
    String query = uri.getQuery();
    if (query != null) {
      final Matcher discPortMatcher = DISCPORT_QUERY_STRING_REGEX.matcher(query);
      if (discPortMatcher.matches()) {
        discoveryPort = Optional.ofNullable(Ints.tryParse(discPortMatcher.group(1)));
      }
      checkArgument(discoveryPort.isPresent(), "Invalid discovery port: '" + query + "'.");
    } else {
      discoveryPort = Optional.of(tcpPort);
    }

    return EnodeURLImpl.builder()
        .ipAddress(host, enodeDnsConfiguration)
        .nodeId(id)
        .listeningPort(tcpPort)
        .discoveryPort(discoveryPort)
        .build();
  }

  private static void checkStringArgumentNotEmpty(final String argument, final String message) {
    checkArgument(argument != null && !argument.trim().isEmpty(), message);
  }
}
