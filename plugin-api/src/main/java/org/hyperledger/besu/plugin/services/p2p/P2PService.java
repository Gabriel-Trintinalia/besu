/*
 * Copyright contributors to Hyperledger Besu.
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
package org.hyperledger.besu.plugin.services.p2p;

import org.hyperledger.besu.datatypes.MessageData;
import org.hyperledger.besu.datatypes.PeerInfo;
import org.hyperledger.besu.plugin.services.BesuService;

import java.util.stream.Stream;

/** Service to enable and disable P2P service. */
public interface P2PService extends BesuService {

  /** Enables P2P discovery. */
  void enableDiscovery();

  /** Disables P2P discovery. */
  void disableDiscovery();

  /**
   * Adds a message listener for a specific message type and version.
   *
   * @param protocol the protocol of the message type
   * @param version the version of the message type
   * @param listener the listener to handle incoming messages of the specified type and version
   */
  void addMessageListener(final String protocol, final int version, final MessageListener listener);

  /**
   * Adds a listener for when a peer connects.
   *
   * @param listener the listener to handle peer connections
   */
  void addPeerConnectListener(final PeerConnectListener listener);

  /**
   * Adds a listener for when a peer disconnects.
   *
   * @param listener the listener to handle peer disconnections
   */
  void addPeerDisconnectListener(final PeerDisconnectListener listener);

  /**
   * Returns a stream of connected peers.
   *
   * @return a stream of connected peers
   */
  Stream<PeerInfo> getConnectedPeers();

  /** Listener interface for handling incoming messages. */
  interface MessageListener {
    /**
     * Called when a message of the specified type and version is received.
     *
     * @param messageData the data of the received message
     */
    void onMessage(final PeerInfo peerInfo, final MessageData messageData);
  }

  /** Listener interface for handling peer connections. */
  interface PeerConnectListener {
    /** Called when a message of the specified type and version is received. */
    void onConnect(final PeerInfo peerInfo, int connectionId);
  }

  interface PeerDisconnectListener {
    /** Called when a message of the specified type and version is received. */
    void onDisconnect(
        final PeerInfo peerInfo, String reason, boolean initiatedByPeer, int connectionId);
  }
}
