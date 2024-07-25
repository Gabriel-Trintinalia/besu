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
import java.util.NavigableMap;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class SnapServerGetAccountRangeTest {

  private static final String ROOT_HASH_HEX =
      "ea4c1f4d9fa8664c22574c5b2f948a78c4b1a753cebc1861e7fb5b1aa21c5a94";
  private static final String ZERO_HASH_HEX =
      "0x0000000000000000000000000000000000000000000000000000000000000000";
  private static final String LIMIT_HASH_HEX =
      "0xffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff";
  public static final String FIRST_ACCOUNT =
      "0x005e94bf632e80cde11add7d3447cd4ca93a5f2205d9874261484ae180718bd6";
  public static final String SECOND_ACCOUNT =
      "0x005e94bf632e80cde11add7d3447cd4ca93a5f2205d9874261484ae180718bd7";
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

  /**
   * In this test, we request the entire state range, but limit the response to 4000 bytes.
   * Expected: 86 accounts.
   */
  @Test
  public void test0_RequestEntireStateRangeWith4000BytesLimit() {
    testAccountRangeRequest(
        ROOT_HASH_HEX,
        ZERO_HASH_HEX,
        LIMIT_HASH_HEX,
        4000,
        86,
        FIRST_ACCOUNT,
        "0x445cb5c1278fdce2f9cbdb681bdd76c52f8e50e41dbd9e220242a69ba99ac099");
  }

  /**
   * In this test, we request the entire state range, but limit the response to 3000 bytes.
   * Expected: 65 accounts.
   */
  @Test
  public void test1_RequestEntireStateRangeWith3000BytesLimit() {
    testAccountRangeRequest(
        ROOT_HASH_HEX,
        ZERO_HASH_HEX,
        LIMIT_HASH_HEX,
        3000,
        65,
        FIRST_ACCOUNT,
        "0x2e6fe1362b3e388184fd7bf08e99e74170b26361624ffd1c5f646da7067b58b6");
  }

  /**
   * In this test, we request the entire state range, but limit the response to 2000 bytes.
   * Expected: 44 accounts.
   */
  @Test
  public void test2_RequestEntireStateRangeWith2000BytesLimit() {
    testAccountRangeRequest(
        ROOT_HASH_HEX,
        ZERO_HASH_HEX,
        LIMIT_HASH_HEX,
        2000,
        44,
        FIRST_ACCOUNT,
        "0x1c3f74249a4892081ba0634a819aec9ed25f34c7653f5719b9098487e65ab595");
  }

  /**
   * In this test, we request the entire state range, but limit the response to 1 byte. The server
   * should return the first account of the state. Expected: 1 account.
   */
  @Test
  public void test3_RequestEntireStateRangeWith1ByteLimit() {
    testAccountRangeRequest(
        ROOT_HASH_HEX, ZERO_HASH_HEX, LIMIT_HASH_HEX, 1, 1, FIRST_ACCOUNT, FIRST_ACCOUNT);
  }

  /**
   * Here we request with a responseBytes limit of zero. The server should return one account.
   * Expected: 1 account.
   */
  @Test
  public void test4_RequestEntireStateRangeWithZeroBytesLimit() {
    testAccountRangeRequest(
        ROOT_HASH_HEX, ZERO_HASH_HEX, LIMIT_HASH_HEX, 0, 1, FIRST_ACCOUNT, FIRST_ACCOUNT);
  }

  /**
   * In this test, we request a range where startingHash is before the first available account key,
   * and limitHash is after. The server should return the first and second account of the state
   * (because the second account is the 'next available'). Expected: 2 accounts.
   */
  @Test
  public void test5_RequestRangeBeforeFirstAccountKey() {
    String startHash = "0x005e94bf632e80cde11add7d3447cd4ca93a5f2205d9874261484ae1807189e2";
    testAccountRangeRequest(
        ROOT_HASH_HEX, startHash, SECOND_ACCOUNT, 4000, 2, FIRST_ACCOUNT, SECOND_ACCOUNT);
  }

  /**
   * Here we request range where both bounds are before the first available account key. This should
   * return the first account (even though it's out of bounds). Expected: 1 account.
   */
  @Test
  public void test6_RequestRangeBothBoundsBeforeFirstAccountKey() {
    String startHash = "0x005e94bf632e80cde11add7d3447cd4ca93a5f2205d9874261484ae1807189e2";
    String limitHash = "0x005e94bf632e80cde11add7d3447cd4ca93a5f2205d9874261484ae180718a14";
    testAccountRangeRequest(
        ROOT_HASH_HEX, startHash, limitHash, 4000, 1, FIRST_ACCOUNT, FIRST_ACCOUNT);
  }

  /**
   * In this test, both startingHash and limitHash are zero. The server should return the first
   * available account. Expected: 1 account.
   */
  @Test
  public void test7_RequestBothBoundsZero() {
    testAccountRangeRequest(
        ROOT_HASH_HEX, ZERO_HASH_HEX, ZERO_HASH_HEX, 4000, 1, FIRST_ACCOUNT, FIRST_ACCOUNT);
  }

  /**
   * In this test, startingHash is exactly the first available account key. The server should return
   * the first available account of the state as the first item. Expected: 86 accounts.
   */
  @Test
  public void test8_RequestStartingHashFirstAvailableAccountKey() {
    String startHash = FIRST_ACCOUNT;
    testAccountRangeRequest(
        ROOT_HASH_HEX,
        startHash,
        LIMIT_HASH_HEX,
        4000,
        86,
        FIRST_ACCOUNT,
        "0x445cb5c1278fdce2f9cbdb681bdd76c52f8e50e41dbd9e220242a69ba99ac099");
  }

  /**
   * In this test, startingHash is after the first available key. The server should return the
   * second account of the state as the first item. Expected: 86 accounts.
   */
  @Test
  public void test9_RequestStartingHashAfterFirstAvailableKey() {
    testAccountRangeRequest(
        ROOT_HASH_HEX,
        SECOND_ACCOUNT,
        LIMIT_HASH_HEX,
        4000,
        86,
        SECOND_ACCOUNT,
        "0x4615e5f5df5b25349a00ad313c6cd0436b6c08ee5826e33a018661997f85ebaa");
  }

  /** This test requests a non-existent state root. Expected: 0 accounts. */
  @Test
  public void test10_RequestNonExistentStateRoot() {
    String rootHash = "1337000000000000000000000000000000000000000000000000000000000000";
    testAccountRangeRequest(rootHash, ZERO_HASH_HEX, LIMIT_HASH_HEX, 4000, 0, null, null);
  }

  /**
   * This test requests data at the state root of the genesis block. We expect the server to return
   * no data because genesis is older than 127 blocks. Expected: 0 accounts.
   */
  @Test
  public void test11_RequestStateRootOfGenesisBlock() {
    String rootHash = "17fa928a94db88a7959927626d9bb0c82d28710e59aa91c0eaa12b33e303fd52";
    testAccountRangeRequest(rootHash, ZERO_HASH_HEX, LIMIT_HASH_HEX, 4000, 0, null, null);
  }

  /**
   * This test requests data at a state root that is 127 blocks old. We expect the server to have
   * this state available. Expected: 84 accounts.
   */
  @Test
  public void test12_RequestStateRoot127BlocksOld() {
    String rootHash = "e5a5661f0d0f149de13c6a68eadbb59e31cb30cf6e18629346fe80789b1f3fbc";
    testAccountRangeRequest(
        rootHash,
        ZERO_HASH_HEX,
        LIMIT_HASH_HEX,
        4000,
        84,
        FIRST_ACCOUNT,
        "0x580aa878e2f92d113a12c0a3ce3c21972b03dbe80786858d49a72097e2c491a3");
  }

  /**
   * This test requests data at a state root that is actually the storage root of an existing
   * account. The server is supposed to ignore this request. Expected: 0 accounts.
   */
  @Test
  public void test13_RequestStateRootIsStorageRoot() {
    String rootHash = "df97f94bc47471870606f626fb7a0b42eed2d45fcc84dc1200ce62f7831da990";
    testAccountRangeRequest(rootHash, ZERO_HASH_HEX, LIMIT_HASH_HEX, 4000, 0, null, null);
  }

  /**
   * In this test, the startingHash is after limitHash (wrong order). The server should ignore this
   * invalid request. Expected: 0 accounts.
   */
  @Test
  public void test14_RequestStartingHashAfterLimitHash() {
    testAccountRangeRequest(ROOT_HASH_HEX, LIMIT_HASH_HEX, ZERO_HASH_HEX, 4000, 0, null, null);
  }

  /**
   * In this test, the startingHash is the first available key, and limitHash is a key before
   * startingHash (wrong order). The server should return the first available key. Expected: 1
   * account.
   */
  @Test
  public void test15_RequestStartingHashFirstAvailableKeyAndLimitHashBefore() {
    String startHash = FIRST_ACCOUNT;
    String limitHash = "0x005e94bf632e80cde11add7d3447cd4ca93a5f2205d9874261484ae180718bd5";
    testAccountRangeRequest(
        ROOT_HASH_HEX, startHash, limitHash, 4000, 1, FIRST_ACCOUNT, FIRST_ACCOUNT);
  }

  /**
   * In this test, the startingHash is the first available key and limitHash is zero. (wrong order).
   * The server should return the first available key. Expected: 1 account.
   */
  @Test
  public void test16_RequestStartingHashFirstAvailableKeyAndLimitHashZero() {
    String startHash = FIRST_ACCOUNT;
    testAccountRangeRequest(
        ROOT_HASH_HEX, startHash, ZERO_HASH_HEX, 4000, 1, FIRST_ACCOUNT, FIRST_ACCOUNT);
  }

  private void testAccountRangeRequest(
      final String rootHashHex,
      final String startHashHex,
      final String limitHashHex,
      final int responseBytes,
      final int expectedAccounts,
      final String expectedFirstAccount,
      final String expectedLastAccount) {
    Hash rootHash = Hash.fromHexString(rootHashHex);
    Bytes32 startHash = Bytes32.fromHexString(startHashHex);
    Bytes32 limitHash = Bytes32.fromHexString(limitHashHex);
    BigInteger sizeRequest = BigInteger.valueOf(responseBytes);

    AccountRangeMessage message = requestAccountRange(rootHash, startHash, limitHash, sizeRequest);
    NavigableMap<Bytes32, Bytes> accounts = message.accountData(false).accounts();
    assertThat(message.accountData(false).accounts().size()).isEqualTo(expectedAccounts);
    if (expectedAccounts > 0) {
      assertThat(accounts.firstKey()).isEqualTo(Bytes32.fromHexString(expectedFirstAccount));
      assertThat(accounts.lastKey()).isEqualTo(Bytes32.fromHexString(expectedLastAccount));
    }
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
