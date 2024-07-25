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
package org.hyperledger.besu.ethereum.eth.manager.snap;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;

import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.ethereum.core.BlockchainSetupUtil;
import org.hyperledger.besu.ethereum.eth.manager.EthMessages;
import org.hyperledger.besu.ethereum.eth.messages.snap.AccountRangeMessage;
import org.hyperledger.besu.ethereum.eth.messages.snap.GetAccountRangeMessage;
import org.hyperledger.besu.ethereum.eth.sync.snapsync.ImmutableSnapSyncConfiguration;
import org.hyperledger.besu.ethereum.eth.sync.snapsync.SnapSyncConfiguration;
import org.hyperledger.besu.ethereum.mainnet.HeaderValidationMode;
import org.hyperledger.besu.ethereum.worldstate.WorldStateStorageCoordinator;
import org.hyperledger.besu.plugin.services.storage.DataStorageFormat;

import java.math.BigInteger;

import org.apache.tuweni.bytes.Bytes32;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

public class SnapServerTest2 {

  private static final String ROOT_HASH_HEX =
      "ea4c1f4d9fa8664c22574c5b2f948a78c4b1a753cebc1861e7fb5b1aa21c5a94";
  private static final String START_HASH_HEX =
      "0x0000000000000000000000000000000000000000000000000000000000000000";
  private static final String LIMIT_HASH_HEX =
      "0xffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff";
  private SnapServer snapServer;

  @BeforeEach
  public void setupTest() {
    // Setup local blockchain for testing
    BlockchainSetupUtil localBlockchainSetup =
        BlockchainSetupUtil.forSnapTesting(DataStorageFormat.BONSAI);
    localBlockchainSetup.importAllBlocks(
        HeaderValidationMode.LIGHT_DETACHED_ONLY, HeaderValidationMode.LIGHT);

    var protocolContext = localBlockchainSetup.getProtocolContext();

    WorldStateStorageCoordinator worldStateStorageCoordinator =
        new WorldStateStorageCoordinator(
            protocolContext.getWorldStateArchive().getWorldStateStorage());

    SnapSyncConfiguration snapSyncConfiguration =
        ImmutableSnapSyncConfiguration.builder().isSnapServerEnabled(true).build();
    snapServer =
        new SnapServer(
                snapSyncConfiguration,
                new EthMessages(),
                worldStateStorageCoordinator,
                protocolContext)
            .start();
  }

  @Test
  public void test0_RequestEntireStateRangeWith4000BytesLimit() {
    testAccountRangeRequest(ROOT_HASH_HEX, START_HASH_HEX, LIMIT_HASH_HEX, 4000, 86);
  }

  @Test
  public void test1_RequestEntireStateRangeWith3000BytesLimit() {
    testAccountRangeRequest(ROOT_HASH_HEX, START_HASH_HEX, LIMIT_HASH_HEX, 3000, 65);
  }

  @Test
  public void test2_RequestEntireStateRangeWith2000BytesLimit() {
    testAccountRangeRequest(ROOT_HASH_HEX, START_HASH_HEX, LIMIT_HASH_HEX, 2000, 44);
  }

  @Test
  public void test3_RequestEntireStateRangeWith1ByteLimit() {
    testAccountRangeRequest(ROOT_HASH_HEX, START_HASH_HEX, LIMIT_HASH_HEX, 1, 1);
  }

  @Test
  public void test4_RequestEntireStateRangeWithZeroBytesLimit() {
    testAccountRangeRequest(ROOT_HASH_HEX, START_HASH_HEX, LIMIT_HASH_HEX, 0, 1);
  }

  @Test
  @Disabled
  public void test5_RequestRangeBeforeFirstAccountKey() {
    String startHash = "0x005e94bf632e80cde11add7d3447cd4ca93a5f2205d9874261484ae1807189e2";
    String limitHash = "0x005e94bf632e80cde11add7d3447cd4ca93a5f2205d9874261484ae180718bd7";
    testAccountRangeRequest(ROOT_HASH_HEX, startHash, limitHash, 4000, 2);
  }

  @Test
  public void test6_RequestRangeBothBoundsBeforeFirstAccountKey() {
    String startHash = "0x005e94bf632e80cde11add7d3447cd4ca93a5f2205d9874261484ae1807189e2";
    String limitHash = "0x005e94bf632e80cde11add7d3447cd4ca93a5f2205d9874261484ae180718a14";
    testAccountRangeRequest(ROOT_HASH_HEX, startHash, limitHash, 4000, 1);
  }

  @Test
  public void test7_RequestBothBoundsZero() {
    testAccountRangeRequest(ROOT_HASH_HEX, START_HASH_HEX, START_HASH_HEX, 4000, 1);
  }

  @Test
  public void test8_RequestStartingHashFirstAvailableAccountKey() {
    String startHash = "0x005e94bf632e80cde11add7d3447cd4ca93a5f2205d9874261484ae180718bd6";
    testAccountRangeRequest(ROOT_HASH_HEX, startHash, LIMIT_HASH_HEX, 4000, 86);
  }

  @Test
  public void test9_RequestStartingHashAfterFirstAvailableKey() {
    String startHash = "0x005e94bf632e80cde11add7d3447cd4ca93a5f2205d9874261484ae180718bd7";
    testAccountRangeRequest(ROOT_HASH_HEX, startHash, LIMIT_HASH_HEX, 4000, 86);
  }

  @Test
  public void test10_RequestNonExistentStateRoot() {
    String rootHash = "1337000000000000000000000000000000000000000000000000000000000000";
    testAccountRangeRequest(rootHash, START_HASH_HEX, LIMIT_HASH_HEX, 4000, 0);
  }

  @Test
  @Disabled
  public void test11_RequestStateRootOfGenesisBlock() {
    String rootHash = "17fa928a94db88a7959927626d9bb0c82d28710e59aa91c0eaa12b33e303fd52";
    testAccountRangeRequest(rootHash, START_HASH_HEX, LIMIT_HASH_HEX, 4000, 0);
  }

  @Test
  public void test12_RequestStateRoot127BlocksOld() {
    String rootHash = "e5a5661f0d0f149de13c6a68eadbb59e31cb30cf6e18629346fe80789b1f3fbc";
    testAccountRangeRequest(rootHash, START_HASH_HEX, LIMIT_HASH_HEX, 4000, 84);
  }

  @Test
  public void test13_RequestStateRootIsStorageRoot() {
    String rootHash = "df97f94bc47471870606f626fb7a0b42eed2d45fcc84dc1200ce62f7831da990";
    testAccountRangeRequest(rootHash, START_HASH_HEX, LIMIT_HASH_HEX, 4000, 0);
  }

  @Test
  @Disabled
  public void test14_RequestStartingHashAfterLimitHash() {
    testAccountRangeRequest(ROOT_HASH_HEX, LIMIT_HASH_HEX, START_HASH_HEX, 4000, 0);
  }

  @Test
  public void test15_RequestStartingHashFirstAvailableKeyAndLimitHashBefore() {
    String startHash = "0x005e94bf632e80cde11add7d3447cd4ca93a5f2205d9874261484ae180718bd6";
    String limitHash = "0x005e94bf632e80cde11add7d3447cd4ca93a5f2205d9874261484ae180718bd5";
    testAccountRangeRequest(ROOT_HASH_HEX, startHash, limitHash, 4000, 1);
  }

  @Test
  public void test16_RequestStartingHashFirstAvailableKeyAndLimitHashZero() {
    String startHash = "0x005e94bf632e80cde11add7d3447cd4ca93a5f2205d9874261484ae180718bd6";
    testAccountRangeRequest(ROOT_HASH_HEX, startHash, START_HASH_HEX, 4000, 1);
  }

  private void testAccountRangeRequest(
      final String rootHashHex,
      final String startHashHex,
      final String limitHashHex,
      final int responseBytes,
      final int expectedAccounts) {
    Hash rootHash = Hash.fromHexString(rootHashHex);
    Bytes32 startHash = Bytes32.fromHexString(startHashHex);
    Bytes32 limitHash = Bytes32.fromHexString(limitHashHex);
    BigInteger sizeRequest = BigInteger.valueOf(responseBytes);

    AccountRangeMessage message = requestAccountRange(rootHash, startHash, limitHash, sizeRequest);
    var accounts = message.accountData(false).accounts();
    assertThat(accounts.size()).isEqualTo(expectedAccounts);
  }

  private AccountRangeMessage requestAccountRange(
      final Hash rootHash,
      final Bytes32 startHash,
      final Bytes32 limitHash,
      final BigInteger sizeRequest) {
    GetAccountRangeMessage requestMessage =
        GetAccountRangeMessage.create(rootHash, startHash, limitHash, sizeRequest);
    return AccountRangeMessage.readFrom(
        snapServer.constructGetAccountRangeResponse(
            requestMessage.wrapMessageData(BigInteger.ONE)));
  }
}
