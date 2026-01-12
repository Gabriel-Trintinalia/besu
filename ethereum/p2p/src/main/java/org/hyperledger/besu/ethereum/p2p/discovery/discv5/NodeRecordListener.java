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
package org.hyperledger.besu.ethereum.p2p.discovery.discv5;

import org.hyperledger.besu.ethereum.p2p.discovery.NodeRecordManager;

import org.ethereum.beacon.discovery.schema.NodeRecord;

/**
 * A {@link org.ethereum.beacon.discovery.storage.NodeRecordListener} that forwards node record
 * update events to the {@link NodeRecordManager}.
 *
 * <p>This listener acts as a bridge between the discv5 storage layer and Besu’s {@link
 * NodeRecordManager}, ensuring that updates to {@link NodeRecord}s are propagated to the local node
 * record management logic.
 */
public class NodeRecordListener
    implements org.ethereum.beacon.discovery.storage.NodeRecordListener {
  private final NodeRecordManager nodeRecordManager;

  /**
   * Creates a new listener that delegates node record updates to the given {@link
   * NodeRecordManager}.
   *
   * @param nodeRecordManager the manager responsible for handling node record updates
   */
  public NodeRecordListener(NodeRecordManager nodeRecordManager) {
    this.nodeRecordManager = nodeRecordManager;
  }

  /**
   * Notifies the {@link NodeRecordManager} that a node record has been updated.
   *
   * @param previousRecord the previous version of the node record
   * @param updatedRecord the updated version of the node record
   */
  @Override
  public void recordUpdated(NodeRecord previousRecord, NodeRecord updatedRecord) {
    nodeRecordManager.updateNodeRecord();
  }
}
