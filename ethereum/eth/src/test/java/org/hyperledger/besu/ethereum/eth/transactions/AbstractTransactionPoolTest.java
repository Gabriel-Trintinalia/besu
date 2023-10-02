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
package org.hyperledger.besu.ethereum.eth.transactions;

import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static java.util.stream.Collectors.toList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.hyperledger.besu.ethereum.mainnet.ValidationResult.valid;
import static org.hyperledger.besu.ethereum.transaction.TransactionInvalidReason.EXCEEDS_BLOCK_GAS_LIMIT;
import static org.hyperledger.besu.ethereum.transaction.TransactionInvalidReason.GAS_PRICE_TOO_LOW;
import static org.hyperledger.besu.ethereum.transaction.TransactionInvalidReason.INVALID_TRANSACTION_FORMAT;
import static org.hyperledger.besu.ethereum.transaction.TransactionInvalidReason.NONCE_TOO_FAR_IN_FUTURE_FOR_SENDER;
import static org.hyperledger.besu.ethereum.transaction.TransactionInvalidReason.NONCE_TOO_LOW;
import static org.hyperledger.besu.ethereum.transaction.TransactionInvalidReason.REPLAY_PROTECTED_SIGNATURE_REQUIRED;
import static org.hyperledger.besu.ethereum.transaction.TransactionInvalidReason.TRANSACTION_REPLACEMENT_UNDERPRICED;
import static org.hyperledger.besu.ethereum.transaction.TransactionInvalidReason.TX_FEECAP_EXCEEDED;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.quality.Strictness.LENIENT;

import org.hyperledger.besu.config.StubGenesisConfigOptions;
import org.hyperledger.besu.crypto.KeyPair;
import org.hyperledger.besu.crypto.SignatureAlgorithmFactory;
import org.hyperledger.besu.datatypes.TransactionType;
import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.ethereum.ProtocolContext;
import org.hyperledger.besu.ethereum.chain.MutableBlockchain;
import org.hyperledger.besu.ethereum.core.Block;
import org.hyperledger.besu.ethereum.core.BlockBody;
import org.hyperledger.besu.ethereum.core.BlockHeader;
import org.hyperledger.besu.ethereum.core.BlockHeaderBuilder;
import org.hyperledger.besu.ethereum.core.BlockHeaderTestFixture;
import org.hyperledger.besu.ethereum.core.Difficulty;
import org.hyperledger.besu.ethereum.core.ExecutionContextTestFixture;
import org.hyperledger.besu.ethereum.core.MiningParameters;
import org.hyperledger.besu.ethereum.core.PrivacyParameters;
import org.hyperledger.besu.ethereum.core.Transaction;
import org.hyperledger.besu.ethereum.core.TransactionReceipt;
import org.hyperledger.besu.ethereum.core.TransactionTestFixture;
import org.hyperledger.besu.ethereum.eth.manager.EthContext;
import org.hyperledger.besu.ethereum.eth.manager.EthPeer;
import org.hyperledger.besu.ethereum.eth.manager.EthProtocolManager;
import org.hyperledger.besu.ethereum.eth.manager.EthProtocolManagerTestUtil;
import org.hyperledger.besu.ethereum.eth.manager.EthScheduler;
import org.hyperledger.besu.ethereum.eth.manager.RespondingEthPeer;
import org.hyperledger.besu.ethereum.eth.messages.EthPV65;
import org.hyperledger.besu.ethereum.eth.transactions.layered.LayeredTransactionPoolBaseFeeTest;
import org.hyperledger.besu.ethereum.eth.transactions.sorter.LegacyTransactionPoolBaseFeeTest;
import org.hyperledger.besu.ethereum.mainnet.MainnetBlockHeaderFunctions;
import org.hyperledger.besu.ethereum.mainnet.ProtocolSchedule;
import org.hyperledger.besu.ethereum.mainnet.ProtocolScheduleBuilder;
import org.hyperledger.besu.ethereum.mainnet.ProtocolSpec;
import org.hyperledger.besu.ethereum.mainnet.ProtocolSpecAdapters;
import org.hyperledger.besu.ethereum.mainnet.TransactionValidationParams;
import org.hyperledger.besu.ethereum.mainnet.TransactionValidatorFactory;
import org.hyperledger.besu.ethereum.mainnet.ValidationResult;
import org.hyperledger.besu.ethereum.mainnet.feemarket.FeeMarket;
import org.hyperledger.besu.ethereum.transaction.TransactionInvalidReason;
import org.hyperledger.besu.evm.account.Account;
import org.hyperledger.besu.evm.internal.EvmConfiguration;
import org.hyperledger.besu.metrics.noop.NoOpMetricsSystem;
import org.hyperledger.besu.plugin.services.MetricsSystem;
import org.hyperledger.besu.plugin.services.txvalidator.PluginTransactionValidator;
import org.hyperledger.besu.plugin.services.txvalidator.PluginTransactionValidatorFactory;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIf;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;

@SuppressWarnings("unchecked")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = LENIENT)
public abstract class AbstractTransactionPoolTest {

  protected static final int MAX_TRANSACTIONS = 5;
  protected static final KeyPair KEY_PAIR1 =
      SignatureAlgorithmFactory.getInstance().generateKeyPair();
  private static final KeyPair KEY_PAIR2 =
      SignatureAlgorithmFactory.getInstance().generateKeyPair();
  protected static final Wei BASE_FEE_FLOOR = Wei.of(7L);

  @Mock(answer = Answers.RETURNS_DEEP_STUBS)
  protected TransactionValidatorFactory transactionValidatorFactory;

  @Mock protected PendingTransactionAddedListener listener;
  @Mock protected MiningParameters miningParameters;
  @Mock protected TransactionsMessageSender transactionsMessageSender;
  @Mock protected NewPooledTransactionHashesMessageSender newPooledTransactionHashesMessageSender;
  @Mock protected ProtocolSpec protocolSpec;

  protected ProtocolSchedule protocolSchedule;

  protected final MetricsSystem metricsSystem = new NoOpMetricsSystem();
  protected MutableBlockchain blockchain;
  protected TransactionBroadcaster transactionBroadcaster;

  protected PendingTransactions transactions;
  protected final Transaction transaction0 = createTransaction(0);
  protected final Transaction transaction1 = createTransaction(1);

  protected final Transaction transactionOtherSender = createTransaction(1, KEY_PAIR2);
  private ExecutionContextTestFixture executionContext;
  protected ProtocolContext protocolContext;
  protected TransactionPool transactionPool;
  protected long blockGasLimit;
  protected EthProtocolManager ethProtocolManager;
  protected EthContext ethContext;
  private PeerTransactionTracker peerTransactionTracker;
  private ArgumentCaptor<Runnable> syncTaskCapture;

  protected abstract PendingTransactions createPendingTransactions(
      final TransactionPoolConfiguration poolConfig,
      final BiFunction<PendingTransaction, PendingTransaction, Boolean>
          transactionReplacementTester);

  protected TransactionTestFixture createBaseTransactionGasPriceMarket(
      final int transactionNumber) {
    return new TransactionTestFixture()
        .nonce(transactionNumber)
        .gasLimit(blockGasLimit)
        .type(TransactionType.FRONTIER);
  }

  protected TransactionTestFixture createBaseTransactionBaseFeeMarket(final int nonce) {
    return new TransactionTestFixture()
        .nonce(nonce)
        .gasLimit(blockGasLimit)
        .gasPrice(null)
        .maxFeePerGas(Optional.of(Wei.of(5000L)))
        .maxPriorityFeePerGas(Optional.of(Wei.of(1000L)))
        .type(TransactionType.EIP1559);
  }

  protected abstract ExecutionContextTestFixture createExecutionContextTestFixture();

  protected static ExecutionContextTestFixture createExecutionContextTestFixtureBaseFeeMarket() {
    final ProtocolSchedule protocolSchedule =
        new ProtocolScheduleBuilder(
                new StubGenesisConfigOptions().londonBlock(0L).baseFeePerGas(10L),
                BigInteger.valueOf(1),
                ProtocolSpecAdapters.create(0, Function.identity()),
                new PrivacyParameters(),
                false,
                EvmConfiguration.DEFAULT)
            .createProtocolSchedule();
    final ExecutionContextTestFixture executionContextTestFixture =
        ExecutionContextTestFixture.builder().protocolSchedule(protocolSchedule).build();

    final Block block =
        new Block(
            new BlockHeaderTestFixture()
                .gasLimit(
                    executionContextTestFixture
                        .getBlockchain()
                        .getChainHeadBlock()
                        .getHeader()
                        .getGasLimit())
                .difficulty(Difficulty.ONE)
                .baseFeePerGas(Wei.of(10L))
                .parentHash(executionContextTestFixture.getBlockchain().getChainHeadHash())
                .number(executionContextTestFixture.getBlockchain().getChainHeadBlockNumber() + 1)
                .buildHeader(),
            new BlockBody(List.of(), List.of()));
    executionContextTestFixture.getBlockchain().appendBlock(block, List.of());

    return executionContextTestFixture;
  }

  protected abstract FeeMarket getFeeMarket();

  @BeforeEach
  public void setUp() {
    executionContext = createExecutionContextTestFixture();
    protocolContext = executionContext.getProtocolContext();
    blockchain = executionContext.getBlockchain();
    when(protocolSpec.getTransactionValidatorFactory()).thenReturn(transactionValidatorFactory);
    when(protocolSpec.getFeeMarket()).thenReturn(getFeeMarket());
    protocolSchedule = spy(executionContext.getProtocolSchedule());
    doReturn(protocolSpec).when(protocolSchedule).getByBlockHeader(any());
    blockGasLimit = blockchain.getChainHeadBlock().getHeader().getGasLimit();
    ethProtocolManager = EthProtocolManagerTestUtil.create();
    ethContext = spy(ethProtocolManager.ethContext());

    final EthScheduler ethScheduler = mock(EthScheduler.class);
    syncTaskCapture = ArgumentCaptor.forClass(Runnable.class);
    doNothing().when(ethScheduler).scheduleSyncWorkerTask(syncTaskCapture.capture());
    doAnswer(invocation -> ((Supplier<Void>) invocation.getArguments()[0]).get())
        .when(ethScheduler)
        .scheduleServiceTask(any(Supplier.class));
    doReturn(ethScheduler).when(ethContext).getScheduler();

    peerTransactionTracker = new PeerTransactionTracker();
    transactionBroadcaster =
        spy(
            new TransactionBroadcaster(
                ethContext,
                peerTransactionTracker,
                transactionsMessageSender,
                newPooledTransactionHashesMessageSender));

    transactionPool = createTransactionPool();
    blockchain.observeBlockAdded(transactionPool);
    when(miningParameters.getMinTransactionGasPrice()).thenReturn(Wei.of(2));
  }

  protected TransactionPool createTransactionPool() {
    return createTransactionPool(b -> {});
  }

  protected TransactionPool createTransactionPool(
      final Consumer<ImmutableTransactionPoolConfiguration.Builder> configConsumer) {
    return createTransactionPool(configConsumer, null);
  }

  private TransactionPool createTransactionPool(
      final Consumer<ImmutableTransactionPoolConfiguration.Builder> configConsumer,
      final PluginTransactionValidatorFactory pluginTransactionValidatorFactory) {
    final ImmutableTransactionPoolConfiguration.Builder configBuilder =
        ImmutableTransactionPoolConfiguration.builder();
    configConsumer.accept(configBuilder);
    final TransactionPoolConfiguration poolConfig = configBuilder.build();

    final TransactionPoolReplacementHandler transactionReplacementHandler =
        new TransactionPoolReplacementHandler(poolConfig.getPriceBump());

    final BiFunction<PendingTransaction, PendingTransaction, Boolean> transactionReplacementTester =
        (t1, t2) ->
            transactionReplacementHandler.shouldReplace(
                t1, t2, protocolContext.getBlockchain().getChainHeadHeader());

    transactions = spy(createPendingTransactions(poolConfig, transactionReplacementTester));

    final TransactionPool txPool =
        new TransactionPool(
            () -> transactions,
            protocolSchedule,
            protocolContext,
            transactionBroadcaster,
            ethContext,
            miningParameters,
            new TransactionPoolMetrics(metricsSystem),
            poolConfig,
            pluginTransactionValidatorFactory);
    txPool.setEnabled();
    return txPool;
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  public void localTransactionHappyPath(final boolean disableLocalTxs) {
    this.transactionPool = createTransactionPool(b -> b.disableLocalTransactions(disableLocalTxs));
    final Transaction transaction = createTransaction(0);

    givenTransactionIsValid(transaction);

    addAndAssertTransactionViaApiValid(transaction, disableLocalTxs);
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  public void shouldReturnLocalTransactionsWhenAppropriate(final boolean disableLocalTxs) {
    this.transactionPool = createTransactionPool(b -> b.disableLocalTransactions(disableLocalTxs));
    final Transaction localTransaction2 = createTransaction(2);

    givenTransactionIsValid(localTransaction2);
    givenTransactionIsValid(transaction0);
    givenTransactionIsValid(transaction1);

    addAndAssertTransactionViaApiValid(localTransaction2, disableLocalTxs);
    addAndAssertRemoteTransactionValid(transaction0);
    addAndAssertRemoteTransactionValid(transaction1);

    assertThat(transactions.size()).isEqualTo(3);
    List<Transaction> localTransactions = transactions.getLocalTransactions();
    assertThat(localTransactions.size()).isEqualTo(disableLocalTxs ? 0 : 1);
  }

  @Test
  public void shouldRemoveTransactionsFromPendingListWhenIncludedInBlockOnchain() {
    givenTransactionIsValid(transaction0);

    addAndAssertRemoteTransactionValid(transaction0);

    appendBlock(transaction0);

    assertTransactionNotPending(transaction0);
  }

  @Test
  public void shouldRemoveMultipleTransactionsAddedInOneBlock() {
    givenTransactionIsValid(transaction0);
    givenTransactionIsValid(transaction1);

    addAndAssertRemoteTransactionValid(transaction0);
    addAndAssertRemoteTransactionValid(transaction1);

    appendBlock(transaction0, transaction1);

    assertTransactionNotPending(transaction0);
    assertTransactionNotPending(transaction1);
    assertThat(transactions.size()).isZero();
  }

  @Test
  public void shouldIgnoreUnknownTransactionsThatAreAddedInABlock() {
    givenTransactionIsValid(transaction0);

    addAndAssertRemoteTransactionValid(transaction0);

    appendBlock(transaction0, transaction1);

    assertTransactionNotPending(transaction0);
    assertTransactionNotPending(transaction1);
    assertThat(transactions.size()).isZero();
  }

  @Test
  public void shouldNotRemovePendingTransactionsWhenABlockAddedToAFork() {
    givenTransactionIsValid(transaction0);

    addAndAssertRemoteTransactionValid(transaction0);

    final BlockHeader commonParent = getHeaderForCurrentChainHead();
    final Block canonicalHead = appendBlock(Difficulty.of(1000), commonParent);
    appendBlock(Difficulty.ONE, commonParent, transaction0);

    verifyChainHeadIs(canonicalHead);

    assertTransactionPending(transaction0);
  }

  @Test
  public void shouldRemovePendingTransactionsFromAllBlocksOnAForkWhenItBecomesTheCanonicalChain() {
    givenTransactionIsValid(transaction0);
    givenTransactionIsValid(transaction1);

    addAndAssertRemoteTransactionValid(transaction0);
    addAndAssertRemoteTransactionValid(transaction1);

    final BlockHeader commonParent = getHeaderForCurrentChainHead();
    final Block originalChainHead = appendBlock(Difficulty.of(1000), commonParent);

    final Block forkBlock1 = appendBlock(Difficulty.ONE, commonParent, transaction0);
    verifyChainHeadIs(originalChainHead);

    final Block forkBlock2 = appendBlock(Difficulty.of(2000), forkBlock1.getHeader(), transaction1);
    verifyChainHeadIs(forkBlock2);

    assertTransactionNotPending(transaction0);
    assertTransactionNotPending(transaction1);
  }

  @Test
  public void shouldReAddTransactionsFromThePreviousCanonicalHeadWhenAReorgOccurs() {
    givenTransactionIsValid(transaction0);
    givenTransactionIsValid(transactionOtherSender);

    transactionPool.addTransactionViaApi(transaction0);
    transactionPool.addRemoteTransactions(List.of(transactionOtherSender));

    final BlockHeader commonParent = getHeaderForCurrentChainHead();
    final Block originalFork1 = appendBlock(Difficulty.of(1000), commonParent, transaction0);
    final Block originalFork2 =
        appendBlock(Difficulty.ONE, originalFork1.getHeader(), transactionOtherSender);
    assertTransactionNotPending(transaction0);
    assertTransactionNotPending(transactionOtherSender);
    assertThat(transactions.getLocalTransactions()).isEmpty();

    final Block reorgFork1 = appendBlock(Difficulty.ONE, commonParent);
    verifyChainHeadIs(originalFork2);

    transactions.subscribePendingTransactions(listener);
    final Block reorgFork2 = appendBlock(Difficulty.of(2000), reorgFork1.getHeader());
    verifyChainHeadIs(reorgFork2);

    assertTransactionPending(transaction0);
    assertTransactionPending(transactionOtherSender);
    assertThat(transactions.getLocalTransactions()).contains(transaction0);
    assertThat(transactions.getLocalTransactions()).doesNotContain(transactionOtherSender);
    verify(listener).onTransactionAdded(transaction0);
    verify(listener).onTransactionAdded(transactionOtherSender);
    verifyNoMoreInteractions(listener);
  }

  @Test
  public void shouldNotReAddTransactionsThatAreInBothForksWhenReorgHappens() {
    givenTransactionIsValid(transaction0);
    givenTransactionIsValid(transaction1);

    addAndAssertRemoteTransactionValid(transaction0);
    addAndAssertRemoteTransactionValid(transaction1);

    final BlockHeader commonParent = getHeaderForCurrentChainHead();
    final Block originalFork1 = appendBlock(Difficulty.of(1000), commonParent, transaction0);
    final Block originalFork2 =
        appendBlock(Difficulty.ONE, originalFork1.getHeader(), transaction1);
    assertTransactionNotPending(transaction0);
    assertTransactionNotPending(transaction1);

    final Block reorgFork1 = appendBlock(Difficulty.ONE, commonParent, transaction0);
    verifyChainHeadIs(originalFork2);

    final Block reorgFork2 = appendBlock(Difficulty.of(2000), reorgFork1.getHeader());
    verifyChainHeadIs(reorgFork2);

    assertTransactionNotPending(transaction0);
    assertTransactionPending(transaction1);
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  public void addLocalTransaction_strictReplayProtectionOn_txWithChainId_chainIdIsConfigured(
      final boolean disableLocalTxs) {
    protocolSupportsTxReplayProtection(1337, true);
    transactionPool =
        createTransactionPool(
            b ->
                b.strictTransactionReplayProtectionEnabled(true)
                    .disableLocalTransactions(disableLocalTxs));
    final Transaction tx = createTransaction(1);
    givenTransactionIsValid(tx);

    addAndAssertTransactionViaApiValid(tx, disableLocalTxs);
  }

  @Test
  public void addRemoteTransactions_strictReplayProtectionOn_txWithChainId_chainIdIsConfigured() {
    protocolSupportsTxReplayProtection(1337, true);
    transactionPool = createTransactionPool(b -> b.strictTransactionReplayProtectionEnabled(true));
    final Transaction tx = createTransaction(1);
    givenTransactionIsValid(tx);

    addAndAssertRemoteTransactionValid(tx);
  }

  @Test
  public void shouldNotAddRemoteTransactionsWhenGasPriceBelowMinimum() {
    final Transaction transaction = createTransaction(1, Wei.ONE);
    transactionPool.addRemoteTransactions(singletonList(transaction));

    assertTransactionNotPending(transaction);
    verifyNoMoreInteractions(transactionValidatorFactory);
  }

  @Test
  public void shouldNotAddRemoteTransactionsThatAreInvalidAccordingToStateDependentChecks() {
    givenTransactionIsValid(transaction0);
    givenTransactionIsValid(transaction1);
    when(transactionValidatorFactory
            .get()
            .validateForSender(eq(transaction1), eq(null), any(TransactionValidationParams.class)))
        .thenReturn(ValidationResult.invalid(NONCE_TOO_LOW));
    transactionPool.addRemoteTransactions(asList(transaction0, transaction1));

    assertTransactionPending(transaction0);
    assertTransactionNotPending(transaction1);
    verify(transactionBroadcaster).onTransactionsAdded(singletonList(transaction0));
    verify(transactionValidatorFactory.get())
        .validate(eq(transaction0), any(Optional.class), any());
    verify(transactionValidatorFactory.get())
        .validateForSender(eq(transaction0), eq(null), any(TransactionValidationParams.class));
    verify(transactionValidatorFactory.get())
        .validate(eq(transaction1), any(Optional.class), any());
    verify(transactionValidatorFactory.get()).validateForSender(eq(transaction1), any(), any());
    verifyNoMoreInteractions(transactionValidatorFactory.get());
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  public void shouldAllowSequenceOfTransactionsWithIncreasingNonceFromSameSender(
      final boolean disableLocalTxs) {
    transactionPool = createTransactionPool(b -> b.disableLocalTransactions(disableLocalTxs));
    final Transaction transaction1 = createTransaction(1);
    final Transaction transaction2 = createTransaction(2);
    final Transaction transaction3 = createTransaction(3);

    givenTransactionIsValid(transaction1);
    givenTransactionIsValid(transaction2);
    givenTransactionIsValid(transaction3);

    addAndAssertTransactionViaApiValid(transaction1, disableLocalTxs);
    addAndAssertTransactionViaApiValid(transaction2, disableLocalTxs);
    addAndAssertTransactionViaApiValid(transaction3, disableLocalTxs);
  }

  @Test
  public void
      shouldAllowSequenceOfTransactionsWithIncreasingNonceFromSameSenderWhenSentInBatchOutOfOrder() {
    final Transaction transaction2 = createTransaction(2);

    givenTransactionIsValid(transaction0);
    givenTransactionIsValid(transaction1);
    givenTransactionIsValid(transaction2);

    addAndAssertRemoteTransactionValid(transaction2);
    addAndAssertRemoteTransactionValid(transaction0);
    addAndAssertRemoteTransactionValid(transaction1);
  }

  @Test
  public void shouldDiscardRemoteTransactionThatAlreadyExistsBeforeValidation() {
    doReturn(true).when(transactions).containsTransaction(transaction0);
    transactionPool.addRemoteTransactions(singletonList(transaction0));

    verify(transactions).containsTransaction(transaction0);
    verifyNoInteractions(transactionValidatorFactory);
  }

  @Test
  public void shouldNotNotifyBatchListenerWhenRemoteTransactionDoesNotReplaceExisting() {
    final Transaction transaction0a = createTransaction(0, Wei.of(100));
    final Transaction transaction0b = createTransaction(0, Wei.of(50));

    givenTransactionIsValid(transaction0a);
    givenTransactionIsValid(transaction0b);

    addAndAssertRemoteTransactionValid(transaction0a);
    addAndAssertRemoteTransactionInvalid(transaction0b);
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  public void shouldNotNotifyBatchListenerWhenLocalTransactionDoesNotReplaceExisting(
      final boolean disableLocalTxs) {
    transactionPool = createTransactionPool(b -> b.disableLocalTransactions(disableLocalTxs));
    final Transaction transaction0a = createTransaction(0, Wei.of(10));
    final Transaction transaction0b = createTransaction(0, Wei.of(9));

    givenTransactionIsValid(transaction0a);
    givenTransactionIsValid(transaction0b);

    addAndAssertTransactionViaApiValid(transaction0a, disableLocalTxs);
    addAndAssertTransactionViaApiInvalid(transaction0b, TRANSACTION_REPLACEMENT_UNDERPRICED);
  }

  @Test
  public void shouldRejectLocalTransactionsWhereGasLimitExceedBlockGasLimit() {
    final Transaction transaction0 =
        createBaseTransaction(0).gasLimit(blockGasLimit + 1).createTransaction(KEY_PAIR1);

    givenTransactionIsValid(transaction0);

    addAndAssertTransactionViaApiInvalid(transaction0, EXCEEDS_BLOCK_GAS_LIMIT);
  }

  @Test
  public void shouldRejectRemoteTransactionsWhereGasLimitExceedBlockGasLimit() {
    final Transaction transaction0 =
        createBaseTransaction(0).gasLimit(blockGasLimit + 1).createTransaction(KEY_PAIR1);

    givenTransactionIsValid(transaction0);

    addAndAssertRemoteTransactionInvalid(transaction0);
  }

  @Test
  public void shouldAcceptLocalTransactionsEvenIfAnInvalidTransactionWithLowerNonceExists() {
    transactionPool = createTransactionPool(b -> b.disableLocalTransactions(false));
    final Transaction invalidTx =
        createBaseTransaction(0).gasLimit(blockGasLimit + 1).createTransaction(KEY_PAIR1);

    final Transaction nextTx = createBaseTransaction(1).gasLimit(1).createTransaction(KEY_PAIR1);

    givenTransactionIsValid(invalidTx);
    givenTransactionIsValid(nextTx);

    addAndAssertTransactionViaApiInvalid(invalidTx, EXCEEDS_BLOCK_GAS_LIMIT);
    addAndAssertTransactionViaApiValid(nextTx, false);
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  public void shouldRejectLocalTransactionsWhenNonceTooFarInFuture(final boolean disableLocalTxs) {
    transactionPool = createTransactionPool(b -> b.disableLocalTransactions(disableLocalTxs));
    final Transaction transactionFarFuture = createTransaction(Integer.MAX_VALUE);

    givenTransactionIsValid(transactionFarFuture);

    addAndAssertTransactionViaApiInvalid(transactionFarFuture, NONCE_TOO_FAR_IN_FUTURE_FOR_SENDER);
  }

  @Test
  public void shouldNotNotifyBatchListenerIfNoTransactionsAreAdded() {
    transactionPool.addRemoteTransactions(emptyList());
    verifyNoInteractions(transactionBroadcaster);
  }

  @Test
  public void shouldSendPooledTransactionHashesIfPeerSupportsEth65() {
    EthPeer peer = mock(EthPeer.class);
    when(peer.hasSupportForMessage(EthPV65.NEW_POOLED_TRANSACTION_HASHES)).thenReturn(true);

    givenTransactionIsValid(transaction0);
    transactionPool.addTransactionViaApi(transaction0);
    transactionPool.handleConnect(peer);
    syncTaskCapture.getValue().run();
    verify(newPooledTransactionHashesMessageSender).sendTransactionHashesToPeer(peer);
  }

  @Test
  public void shouldSendFullTransactionsIfPeerDoesNotSupportEth65() {
    EthPeer peer = mock(EthPeer.class);
    when(peer.hasSupportForMessage(EthPV65.NEW_POOLED_TRANSACTION_HASHES)).thenReturn(false);

    givenTransactionIsValid(transaction0);
    transactionPool.addTransactionViaApi(transaction0);
    transactionPool.handleConnect(peer);
    syncTaskCapture.getValue().run();
    verify(transactionsMessageSender).sendTransactionsToPeer(peer);
  }

  @Test
  public void shouldSendFullTransactionPoolToNewlyConnectedPeer() {
    givenTransactionIsValid(transaction0);
    givenTransactionIsValid(transaction1);

    transactionPool.addTransactionViaApi(transaction0);
    transactionPool.addRemoteTransactions(Collections.singletonList(transaction1));

    RespondingEthPeer peer = EthProtocolManagerTestUtil.createPeer(ethProtocolManager);

    Set<Transaction> transactionsToSendToPeer =
        peerTransactionTracker.claimTransactionsToSendToPeer(peer.getEthPeer());

    assertThat(transactionsToSendToPeer).contains(transaction0, transaction1);
  }

  @Test
  public void shouldCallValidatorWithExpectedValidationParameters() {
    final ArgumentCaptor<TransactionValidationParams> txValidationParamCaptor =
        ArgumentCaptor.forClass(TransactionValidationParams.class);

    when(transactionValidatorFactory.get().validate(eq(transaction0), any(Optional.class), any()))
        .thenReturn(valid());
    when(transactionValidatorFactory
            .get()
            .validateForSender(any(), any(), txValidationParamCaptor.capture()))
        .thenReturn(valid());

    final TransactionValidationParams expectedValidationParams =
        TransactionValidationParams.transactionPool();

    transactionPool.addTransactionViaApi(transaction0);

    assertThat(txValidationParamCaptor.getValue())
        .usingRecursiveComparison()
        .isEqualTo(expectedValidationParams);
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  public void shouldIgnoreFeeCapIfSetZero(final boolean disableLocalTxs) {
    final Wei twoEthers = Wei.fromEth(2);
    transactionPool =
        createTransactionPool(b -> b.txFeeCap(Wei.ZERO).disableLocalTransactions(disableLocalTxs));
    final Transaction transaction = createTransaction(0, twoEthers.add(Wei.of(1)));

    givenTransactionIsValid(transaction);

    addAndAssertTransactionViaApiValid(transaction, disableLocalTxs);
  }

  @Test
  public void shouldRejectLocalTransactionIfFeeCapExceeded() {
    final Wei twoEthers = Wei.fromEth(2);
    transactionPool =
        createTransactionPool(b -> b.txFeeCap(twoEthers).disableLocalTransactions(false));

    final Transaction transactionLocal = createTransaction(0, twoEthers.add(1));

    givenTransactionIsValid(transactionLocal);

    addAndAssertTransactionViaApiInvalid(transactionLocal, TX_FEECAP_EXCEEDED);
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  public void shouldRejectZeroGasPriceLocalTransactionWhenNotMining(final boolean disableLocalTxs) {
    transactionPool = createTransactionPool(b -> b.disableLocalTransactions(disableLocalTxs));
    when(miningParameters.isMiningEnabled()).thenReturn(false);

    final Transaction transaction = createTransaction(0, Wei.ZERO);

    givenTransactionIsValid(transaction);

    addAndAssertTransactionViaApiInvalid(transaction, GAS_PRICE_TOO_LOW);
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  public void transactionNotRejectedByPluginShouldBeAdded(final boolean disableLocalTxs) {
    final PluginTransactionValidatorFactory pluginTransactionValidatorFactory =
        getPluginTransactionValidatorFactoryReturning(true);
    this.transactionPool =
        createTransactionPool(
            b -> b.disableLocalTransactions(disableLocalTxs), pluginTransactionValidatorFactory);

    givenTransactionIsValid(transaction0);

    addAndAssertTransactionViaApiValid(transaction0, disableLocalTxs);
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  public void transactionRejectedByPluginShouldNotBeAdded(final boolean disableLocalTxs) {
    final PluginTransactionValidatorFactory pluginTransactionValidatorFactory =
        getPluginTransactionValidatorFactoryReturning(false);
    this.transactionPool =
        createTransactionPool(
            b -> b.disableLocalTransactions(disableLocalTxs), pluginTransactionValidatorFactory);

    givenTransactionIsValid(transaction0);

    addAndAssertTransactionViaApiInvalid(
        transaction0, TransactionInvalidReason.PLUGIN_TX_VALIDATOR_INVALIDATED);
  }

  @Test
  public void remoteTransactionRejectedByPluginShouldNotBeAdded() {
    final PluginTransactionValidatorFactory pluginTransactionValidatorFactory =
        getPluginTransactionValidatorFactoryReturning(false);
    this.transactionPool = createTransactionPool(b -> {}, pluginTransactionValidatorFactory);

    givenTransactionIsValid(transaction0);

    addAndAssertRemoteTransactionInvalid(transaction0);
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  @DisabledIf("isBaseFeeMarket")
  public void
      addLocalTransaction_strictReplayProtectionOn_txWithoutChainId_chainIdIsConfigured_protectionNotSupportedAtCurrentBlock(
          final boolean disableLocalTxs) {
    protocolSupportsTxReplayProtection(1337, false);
    transactionPool =
        createTransactionPool(
            b ->
                b.strictTransactionReplayProtectionEnabled(true)
                    .disableLocalTransactions(disableLocalTxs));
    final Transaction tx = createTransactionWithoutChainId(1);
    givenTransactionIsValid(tx);

    addAndAssertTransactionViaApiValid(tx, disableLocalTxs);
  }

  @Test
  @DisabledIf("isBaseFeeMarket")
  public void
      addRemoteTransactions_strictReplayProtectionOff_txWithoutChainId_chainIdIsConfigured() {
    protocolSupportsTxReplayProtection(1337, true);
    transactionPool = createTransactionPool(b -> b.strictTransactionReplayProtectionEnabled(false));
    final Transaction tx = createTransactionWithoutChainId(1);
    givenTransactionIsValid(tx);

    addAndAssertRemoteTransactionValid(tx);
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  @DisabledIf("isBaseFeeMarket")
  public void addLocalTransaction_strictReplayProtectionOff_txWithoutChainId_chainIdIsConfigured(
      final boolean disableLocalTxs) {
    protocolSupportsTxReplayProtection(1337, true);
    transactionPool =
        createTransactionPool(
            b ->
                b.strictTransactionReplayProtectionEnabled(false)
                    .disableLocalTransactions(disableLocalTxs));
    final Transaction tx = createTransactionWithoutChainId(1);
    givenTransactionIsValid(tx);

    addAndAssertTransactionViaApiValid(tx, disableLocalTxs);
  }

  @Test
  @DisabledIf("isBaseFeeMarket")
  public void addLocalTransaction_strictReplayProtectionOn_txWithoutChainId_chainIdIsConfigured() {
    protocolSupportsTxReplayProtection(1337, true);
    transactionPool = createTransactionPool(b -> b.strictTransactionReplayProtectionEnabled(true));
    final Transaction tx = createTransactionWithoutChainId(1);
    givenTransactionIsValid(tx);

    addAndAssertTransactionViaApiInvalid(tx, REPLAY_PROTECTED_SIGNATURE_REQUIRED);
  }

  @Test
  @DisabledIf("isBaseFeeMarket")
  public void
      addRemoteTransactions_strictReplayProtectionOn_txWithoutChainId_chainIdIsConfigured() {
    protocolSupportsTxReplayProtection(1337, true);
    transactionPool = createTransactionPool(b -> b.strictTransactionReplayProtectionEnabled(true));
    final Transaction tx = createTransactionWithoutChainId(1);
    givenTransactionIsValid(tx);

    addAndAssertRemoteTransactionValid(tx);
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  @DisabledIf("isBaseFeeMarket")
  public void addLocalTransaction_strictReplayProtectionOn_txWithoutChainId_chainIdIsNotConfigured(
      final boolean disableLocalTxs) {
    protocolDoesNotSupportTxReplayProtection();
    transactionPool =
        createTransactionPool(
            b ->
                b.strictTransactionReplayProtectionEnabled(true)
                    .disableLocalTransactions(disableLocalTxs));
    final Transaction tx = createTransactionWithoutChainId(1);
    givenTransactionIsValid(tx);

    addAndAssertTransactionViaApiValid(tx, disableLocalTxs);
  }

  @Test
  @DisabledIf("isBaseFeeMarket")
  public void
      addRemoteTransactions_strictReplayProtectionOn_txWithoutChainId_chainIdIsNotConfigured() {
    protocolDoesNotSupportTxReplayProtection();
    transactionPool = createTransactionPool(b -> b.strictTransactionReplayProtectionEnabled(true));
    final Transaction tx = createTransactionWithoutChainId(1);
    givenTransactionIsValid(tx);

    addAndAssertRemoteTransactionValid(tx);
  }

  @Test
  @DisabledIf("isBaseFeeMarket")
  public void shouldIgnoreEIP1559TransactionWhenNotAllowed() {
    final Transaction transaction =
        createBaseTransaction(1)
            .type(TransactionType.EIP1559)
            .maxFeePerGas(Optional.of(Wei.of(100L)))
            .maxPriorityFeePerGas(Optional.of(Wei.of(50L)))
            .gasLimit(10)
            .gasPrice(null)
            .createTransaction(KEY_PAIR1);

    givenTransactionIsValid(transaction);

    addAndAssertTransactionViaApiInvalid(transaction, INVALID_TRANSACTION_FORMAT);
  }

  @Test
  @DisabledIf("isBaseFeeMarket")
  public void shouldAcceptZeroGasPriceFrontierLocalTransactionsWhenMining() {
    transactionPool = createTransactionPool(b -> b.disableLocalTransactions(false));
    when(miningParameters.isMiningEnabled()).thenReturn(true);

    final Transaction transaction = createTransaction(0, Wei.ZERO);

    givenTransactionIsValid(transaction);

    addAndAssertTransactionViaApiValid(transaction, false);
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  @DisabledIf("isBaseFeeMarket")
  public void shouldAcceptZeroGasPriceTransactionWhenMinGasPriceIsZero(
      final boolean disableLocalTxs) {
    transactionPool = createTransactionPool(b -> b.disableLocalTransactions(disableLocalTxs));
    when(miningParameters.getMinTransactionGasPrice()).thenReturn(Wei.ZERO);

    final Transaction transaction = createTransaction(0, Wei.ZERO);

    givenTransactionIsValid(transaction);

    addAndAssertTransactionViaApiValid(transaction, disableLocalTxs);
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  public void shouldAcceptZeroGasPriceFrontierTxsWhenMinGasPriceIsZeroAndLondonWithZeroBaseFee(
      final boolean disableLocalTxs) {
    transactionPool = createTransactionPool(b -> b.disableLocalTransactions(disableLocalTxs));
    when(miningParameters.getMinTransactionGasPrice()).thenReturn(Wei.ZERO);
    when(protocolSpec.getFeeMarket()).thenReturn(FeeMarket.london(0, Optional.of(Wei.ZERO)));
    whenBlockBaseFeeIs(Wei.ZERO);

    final Transaction frontierTransaction = createFrontierTransaction(0, Wei.ZERO);

    givenTransactionIsValid(frontierTransaction);
    addAndAssertTransactionViaApiValid(frontierTransaction, disableLocalTxs);
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  public void shouldAcceptZeroGasPrice1559TxsWhenMinGasPriceIsZeroAndLondonWithZeroBaseFee(
      final boolean disableLocalTxs) {
    transactionPool = createTransactionPool(b -> b.disableLocalTransactions(disableLocalTxs));
    when(miningParameters.getMinTransactionGasPrice()).thenReturn(Wei.ZERO);
    when(protocolSpec.getFeeMarket()).thenReturn(FeeMarket.london(0, Optional.of(Wei.ZERO)));
    whenBlockBaseFeeIs(Wei.ZERO);

    final Transaction transaction = createTransaction(0, Wei.ZERO);

    givenTransactionIsValid(transaction);
    addAndAssertTransactionViaApiValid(transaction, disableLocalTxs);
  }

  @Test
  public void shouldAcceptBaseFeeFloorGasPriceFrontierLocalTransactionsWhenMining() {
    transactionPool = createTransactionPool(b -> b.disableLocalTransactions(false));
    final Transaction frontierTransaction = createFrontierTransaction(0, BASE_FEE_FLOOR);

    givenTransactionIsValid(frontierTransaction);

    addAndAssertTransactionViaApiValid(frontierTransaction, false);
  }

  @Test
  public void shouldRejectRemote1559TxsWhenMaxFeePerGasBelowMinGasPrice() {
    final Wei genesisBaseFee = Wei.of(100L);
    final Wei minGasPrice = Wei.of(200L);
    final Wei lastBlockBaseFee = minGasPrice.add(50L);
    final Wei txMaxFeePerGas = minGasPrice.subtract(1L);

    assertThat(
            add1559TxAndGetPendingTxsCount(
                genesisBaseFee, minGasPrice, lastBlockBaseFee, txMaxFeePerGas, false))
        .isEqualTo(0);
  }

  @Test
  public void shouldAcceptRemote1559TxsWhenMaxFeePerGasIsAtLeastEqualToMinGasPrice() {
    final Wei genesisBaseFee = Wei.of(100L);
    final Wei minGasPrice = Wei.of(200L);
    final Wei lastBlockBaseFee = minGasPrice.add(50L);
    final Wei txMaxFeePerGas = minGasPrice;

    assertThat(
            add1559TxAndGetPendingTxsCount(
                genesisBaseFee, minGasPrice, lastBlockBaseFee, txMaxFeePerGas, false))
        .isEqualTo(1);
  }

  @Test
  public void shouldRejectLocal1559TxsWhenMaxFeePerGasBelowMinGasPrice() {
    final Wei genesisBaseFee = Wei.of(100L);
    final Wei minGasPrice = Wei.of(200L);
    final Wei lastBlockBaseFee = minGasPrice.add(50L);
    final Wei txMaxFeePerGas = minGasPrice.subtract(1L);

    assertThat(
            add1559TxAndGetPendingTxsCount(
                genesisBaseFee, minGasPrice, lastBlockBaseFee, txMaxFeePerGas, true))
        .isEqualTo(0);
  }

  @Test
  public void shouldAcceptLocal1559TxsWhenMaxFeePerGasIsAtLeastEqualToMinMinGasPrice() {
    final Wei genesisBaseFee = Wei.of(100L);
    final Wei minGasPrice = Wei.of(200L);
    final Wei lastBlockBaseFee = minGasPrice.add(50L);
    final Wei txMaxFeePerGas = minGasPrice;

    assertThat(
            add1559TxAndGetPendingTxsCount(
                genesisBaseFee, minGasPrice, lastBlockBaseFee, txMaxFeePerGas, true))
        .isEqualTo(1);
  }

  @Test
  public void addRemoteTransactionsShouldAllowDuplicates() {
    final Transaction transaction1 = createTransaction(1, Wei.of(7L));
    final Transaction transaction2 = createTransaction(2, Wei.of(7L));
    final Transaction transaction3 = createTransaction(2, Wei.of(7L));
    final Transaction transaction4 = createTransaction(3, Wei.of(7L));

    givenTransactionIsValid(transaction1);
    givenTransactionIsValid(transaction2);
    givenTransactionIsValid(transaction3);
    givenTransactionIsValid(transaction4);

    assertThatCode(
            () ->
                transactionPool.addRemoteTransactions(
                    List.of(transaction1, transaction2, transaction3, transaction4)))
        .doesNotThrowAnyException();
  }

  private static PluginTransactionValidatorFactory getPluginTransactionValidatorFactoryReturning(
      final boolean b) {
    final PluginTransactionValidator pluginTransactionValidator = transaction -> b;
    return () -> pluginTransactionValidator;
  }

  @SuppressWarnings("unused")
  private static boolean isBaseFeeMarket(final ExtensionContext extensionContext) {
    final Class<?> cz = extensionContext.getTestClass().get();

    return cz.equals(LegacyTransactionPoolBaseFeeTest.class)
        || cz.equals(LayeredTransactionPoolBaseFeeTest.class);
  }

  protected void assertTransactionNotPending(final Transaction transaction) {
    assertThat(transactions.getTransactionByHash(transaction.getHash())).isEmpty();
  }

  protected void addAndAssertRemoteTransactionInvalid(final Transaction tx) {
    transactionPool.addRemoteTransactions(List.of(tx));

    verify(transactionBroadcaster, never()).onTransactionsAdded(singletonList(tx));
    assertTransactionNotPending(tx);
  }

  protected void assertTransactionPending(final Transaction t) {
    assertThat(transactions.getTransactionByHash(t.getHash())).contains(t);
  }

  protected void addAndAssertRemoteTransactionValid(final Transaction... txs) {
    transactionPool.addRemoteTransactions(List.of(txs));

    verify(transactionBroadcaster)
        .onTransactionsAdded(
            argThat(btxs -> btxs.size() == txs.length && btxs.containsAll(List.of(txs))));
    Arrays.stream(txs).forEach(this::assertTransactionPending);
    assertThat(transactions.getLocalTransactions()).doesNotContain(txs);
  }

  protected void addAndAssertTransactionViaApiValid(final Transaction tx) {
    addAndAssertTransactionViaApiValid(tx, false);
  }

  protected void addAndAssertTransactionViaApiValid(
      final Transaction tx, final boolean disableLocals) {
    final ValidationResult<TransactionInvalidReason> result =
        transactionPool.addTransactionViaApi(tx);

    assertThat(result.isValid()).isTrue();
    assertTransactionPending(tx);
    verify(transactionBroadcaster).onTransactionsAdded(singletonList(tx));
    if (disableLocals) {
      assertThat(transactions.getLocalTransactions()).doesNotContain(tx);
    } else {
      assertThat(transactions.getLocalTransactions()).contains(tx);
    }
  }

  protected void addAndAssertTransactionViaApiInvalid(
      final Transaction tx, final TransactionInvalidReason invalidReason) {
    final ValidationResult<TransactionInvalidReason> result =
        transactionPool.addTransactionViaApi(tx);

    assertThat(result.isValid()).isFalse();
    assertThat(result.getInvalidReason()).isEqualTo(invalidReason);
    assertTransactionNotPending(tx);
    verify(transactionBroadcaster, never()).onTransactionsAdded(singletonList(tx));
  }

  @SuppressWarnings("unchecked")
  protected void givenTransactionIsValid(final Transaction transaction) {
    when(transactionValidatorFactory.get().validate(eq(transaction), any(Optional.class), any()))
        .thenReturn(valid());
    when(transactionValidatorFactory
            .get()
            .validateForSender(
                eq(transaction), nullable(Account.class), any(TransactionValidationParams.class)))
        .thenReturn(valid());
  }

  protected abstract Block appendBlock(
      final Difficulty difficulty,
      final BlockHeader parentBlock,
      final Transaction... transactionsToAdd);

  protected Transaction createTransactionGasPriceMarket(
      final int transactionNumber, final Wei maxPrice) {
    return createBaseTransaction(transactionNumber).gasPrice(maxPrice).createTransaction(KEY_PAIR1);
  }

  protected Transaction createTransactionBaseFeeMarket(final int nonce, final Wei maxPrice) {
    return createBaseTransaction(nonce)
        .maxFeePerGas(Optional.of(maxPrice))
        .maxPriorityFeePerGas(Optional.of(maxPrice.divide(5L)))
        .createTransaction(KEY_PAIR1);
  }

  protected abstract TransactionTestFixture createBaseTransaction(final int nonce);

  protected Transaction createTransaction(
      final int transactionNumber, final Optional<BigInteger> maybeChainId) {
    return createBaseTransaction(transactionNumber)
        .chainId(maybeChainId)
        .createTransaction(KEY_PAIR1);
  }

  protected abstract Transaction createTransaction(final int nonce, final Wei maxPrice);

  protected Transaction createTransaction(final int nonce) {
    return createTransaction(nonce, Optional.of(BigInteger.ONE));
  }

  protected Transaction createTransaction(final int nonce, final KeyPair keyPair) {
    return createBaseTransaction(nonce).createTransaction(keyPair);
  }

  protected void verifyChainHeadIs(final Block forkBlock2) {
    assertThat(blockchain.getChainHeadHash()).isEqualTo(forkBlock2.getHash());
  }

  protected BlockHeader getHeaderForCurrentChainHead() {
    return blockchain.getBlockHeader(blockchain.getChainHeadHash()).get();
  }

  protected void appendBlock(final Transaction... transactionsToAdd) {
    appendBlock(Difficulty.ONE, getHeaderForCurrentChainHead(), transactionsToAdd);
  }

  protected void protocolSupportsTxReplayProtection(
      final long chainId, final boolean isSupportedAtCurrentBlock) {
    when(protocolSpec.isReplayProtectionSupported()).thenReturn(isSupportedAtCurrentBlock);
    when(protocolSchedule.getChainId()).thenReturn(Optional.of(BigInteger.valueOf(chainId)));
  }

  protected void protocolDoesNotSupportTxReplayProtection() {
    when(protocolSchedule.getChainId()).thenReturn(Optional.empty());
  }

  protected Transaction createTransactionWithoutChainId(final int transactionNumber) {
    return createTransaction(transactionNumber, Optional.empty());
  }

  protected void whenBlockBaseFeeIs(final Wei baseFee) {
    final BlockHeader header =
        BlockHeaderBuilder.fromHeader(blockchain.getChainHeadHeader())
            .baseFee(baseFee)
            .blockHeaderFunctions(new MainnetBlockHeaderFunctions())
            .parentHash(blockchain.getChainHeadHash())
            .buildBlockHeader();
    blockchain.appendBlock(new Block(header, BlockBody.empty()), emptyList());
  }

  protected Transaction createFrontierTransaction(final int transactionNumber, final Wei gasPrice) {
    return new TransactionTestFixture()
        .nonce(transactionNumber)
        .gasPrice(gasPrice)
        .gasLimit(blockGasLimit)
        .type(TransactionType.FRONTIER)
        .createTransaction(KEY_PAIR1);
  }

  protected int add1559TxAndGetPendingTxsCount(
      final Wei genesisBaseFee,
      final Wei minGasPrice,
      final Wei lastBlockBaseFee,
      final Wei txMaxFeePerGas,
      final boolean isLocal) {
    when(miningParameters.getMinTransactionGasPrice()).thenReturn(minGasPrice);
    when(protocolSpec.getFeeMarket()).thenReturn(FeeMarket.london(0, Optional.of(genesisBaseFee)));
    whenBlockBaseFeeIs(lastBlockBaseFee);

    final Transaction transaction = createTransaction(0, txMaxFeePerGas);

    givenTransactionIsValid(transaction);

    if (isLocal) {
      transactionPool.addTransactionViaApi(transaction);
    } else {
      transactionPool.addRemoteTransactions(List.of(transaction));
    }

    return transactions.size();
  }

  protected Block appendBlockGasPriceMarket(
      final Difficulty difficulty,
      final BlockHeader parentBlock,
      final Transaction[] transactionsToAdd) {
    final List<Transaction> transactionList = asList(transactionsToAdd);
    final Block block =
        new Block(
            new BlockHeaderTestFixture()
                .difficulty(difficulty)
                .gasLimit(parentBlock.getGasLimit())
                .parentHash(parentBlock.getHash())
                .number(parentBlock.getNumber() + 1)
                .buildHeader(),
            new BlockBody(transactionList, emptyList()));
    final List<TransactionReceipt> transactionReceipts =
        transactionList.stream()
            .map(transaction -> new TransactionReceipt(1, 1, emptyList(), Optional.empty()))
            .collect(toList());
    blockchain.appendBlock(block, transactionReceipts);
    return block;
  }

  protected Block appendBlockBaseFeeMarket(
      final Difficulty difficulty,
      final BlockHeader parentBlock,
      final Transaction[] transactionsToAdd) {
    final List<Transaction> transactionList = asList(transactionsToAdd);
    final Block block =
        new Block(
            new BlockHeaderTestFixture()
                .baseFeePerGas(Wei.of(10L))
                .gasLimit(parentBlock.getGasLimit())
                .difficulty(difficulty)
                .parentHash(parentBlock.getHash())
                .number(parentBlock.getNumber() + 1)
                .buildHeader(),
            new BlockBody(transactionList, emptyList()));
    final List<TransactionReceipt> transactionReceipts =
        transactionList.stream()
            .map(transaction -> new TransactionReceipt(1, 1, emptyList(), Optional.empty()))
            .collect(toList());
    blockchain.appendBlock(block, transactionReceipts);
    return block;
  }
}
