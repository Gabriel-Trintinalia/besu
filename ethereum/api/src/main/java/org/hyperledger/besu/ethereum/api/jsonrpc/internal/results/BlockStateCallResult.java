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
package org.hyperledger.besu.ethereum.api.jsonrpc.internal.results;

import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.response.JsonRpcError;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.response.RpcErrorType;
import org.hyperledger.besu.ethereum.api.query.TransactionWithMetadata;
import org.hyperledger.besu.ethereum.core.Block;
import org.hyperledger.besu.ethereum.core.LogWithMetadata;
import org.hyperledger.besu.ethereum.processing.TransactionProcessingResult;
import org.hyperledger.besu.ethereum.transaction.BlockSimulationResult;
import org.hyperledger.besu.ethereum.transaction.TransactionSimulatorResult;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import com.fasterxml.jackson.annotation.JsonGetter;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * Represents the result of a block state call, encapsulating information about the block,
 * transactions, and call processing results.
 *
 * <p>This class extends {@link BlockResult} to provide additional details specific to the state
 * call result, including processed call results and optionally, full transaction details.
 */
@JsonPropertyOrder(alphabetic = true)
public class BlockStateCallResult extends BlockResult {
  private final List<CallProcessingResult> callProcessingResults;

  /**
   * Constructs a new {@code BlockStateCallResult} with the specified block, transactions, and call
   * processing results.
   *
   * @param block the block associated with this result
   * @param transactions a list of transactions included in the block, detailed according to the
   *     request
   * @param callProcessingResults a list of results from processing calls within the block
   */
  private BlockStateCallResult(
      final Block block,
      final List<TransactionResult> transactions,
      final List<CallProcessingResult> callProcessingResults) {
    super(
        block.getHeader(),
        transactions,
        List.of(),
        callProcessingResults,
        null,
        block.calculateSize(),
        false,
        block.getBody().getWithdrawals());
    this.callProcessingResults = callProcessingResults;
  }

  /**
   * Gets the list of call processing results.
   *
   * @return a list of {@link CallProcessingResult} objects representing the results of processed
   *     calls
   */
  @JsonGetter(value = "calls")
  public List<CallProcessingResult> getTransactionProcessingResults() {
    return callProcessingResults;
  }

  /**
   * Returns the total difficulty of the block, which is not applicable for this result type.
   *
   * @return {@code null} as total difficulty is not applicable
   */
  @JsonGetter(value = "totalDifficulty")
  @Override
  public String getTotalDifficulty() {
    return null;
  }

  /**
   * Creates a {@code BlockStateCallResult} based on the simulation result and the detail level of
   * transactions requested.
   *
   * <p>This method decides whether to include full transaction details or just transaction hashes
   * based on the {@code isFullTransactionReturn} flag.
   *
   * @param simulationResult the result of the block simulation containing transactions and their
   *     execution results
   * @param isFullTransactionReturn a boolean flag indicating whether full transaction details
   *     should be included
   * @return a new instance of {@code BlockStateCallResult} populated with the block, transactions,
   *     and call processing results
   */
  public static BlockStateCallResult create(
      final BlockSimulationResult simulationResult, final boolean isFullTransactionReturn) {
    Block block = simulationResult.getBlock();
    List<TransactionResult> transactionResults =
        isFullTransactionReturn
            ? createFullTransactionResults(block)
            : createHashTransactionResults(block);

    List<LogWithMetadata> logs = new ArrayList<>();
    for (var transactionSimulation : simulationResult.getTransactionSimulations()) {
      logs.addAll(LogWithMetadata.generate(0,
        transactionSimulation.result().getLogs(),
        block.getHeader().getNumber(),
        block.getHash(),
        transactionSimulation.transaction().getHash(),
        block.getBody().getTransactions().indexOf(transactionSimulation.transaction()),
        false));
    }

    var callProcessingResults =
        simulationResult.getTransactionSimulations().stream()
            .map(simulatorResult -> createTransactionProcessingResult(simulatorResult, logs))
            .collect(Collectors.toList());

    return new BlockStateCallResult(block, transactionResults, callProcessingResults);
  }

  /**
   * Generates a list of {@link TransactionResult} objects with full transaction details for each
   * transaction in the block.
   *
   * @param block the block containing the transactions
   * @return a list of {@link TransactionResult} objects with full transaction details
   */
  private static List<TransactionResult> createFullTransactionResults(final Block block) {
    return block.getBody().getTransactions().stream()
        .map(
            transaction ->
                new TransactionWithMetadata(
                    transaction,
                    block.getHeader().getNumber(),
                    block.getHeader().getBaseFee(),
                    block.getHash(),
                    block.getBody().getTransactions().indexOf(transaction)))
        .map(TransactionCompleteResult::new)
        .collect(Collectors.toList());
  }

  /**
   * Generates a list of {@link TransactionResult} objects containing only the transaction hashes
   * for each transaction in the block.
   *
   * @param block the block containing the transactions
   * @return a list of {@link TransactionResult} objects with only transaction hashes
   */
  private static List<TransactionResult> createHashTransactionResults(final Block block) {
    return block.getBody().getTransactions().stream()
        .map(transaction -> transaction.getHash().toString())
        .map(TransactionHashResult::new)
        .collect(Collectors.toList());
  }

  /**
   * Constructs a {@link CallProcessingResult} from a transaction simulation result and associated
   * logs.
   *
   * <p>This method evaluates the transaction simulation result to determine success, output, gas
   * used, and any errors or logs associated with the transaction.
   *
   * @param simulatorResult the result of simulating a single transaction
   * @param logs a list of logs associated with the transaction
   * @return a {@link CallProcessingResult} representing the outcome of the transaction simulation
   */
  private static CallProcessingResult createTransactionProcessingResult(
      final TransactionSimulatorResult simulatorResult, final List<LogWithMetadata> logs) {
    Hash transactionHash = simulatorResult.transaction().getHash();
    List<LogWithMetadata> transactionLogs =
        logs.stream()
            .filter(log -> log.getTransactionHash().equals(transactionHash))
            .collect(Collectors.toList());

    TransactionProcessingResult result = simulatorResult.result();
    JsonRpcError error = determineError(result);

    return new CallProcessingResult(
        result.isSuccessful() ? 1 : 0,
        result.getOutput(),
        result.getEstimateGasUsedByTransaction(),
        error,
        new LogsResult(transactionLogs));
  }

  /**
   * Determines the appropriate {@link JsonRpcError} based on the transaction processing result.
   *
   * <p>This method checks for revert reasons and exceptional halt reasons to construct an
   * appropriate error message.
   *
   * @param result the result of processing a transaction
   * @return a {@link JsonRpcError} representing the error encountered during transaction
   *     processing, or {@code null} if no error occurred
   */
  private static JsonRpcError determineError(final TransactionProcessingResult result) {
    if (result.getRevertReason().isPresent()) {
      return new JsonRpcError(
          RpcErrorType.REVERT_ERROR, result.getRevertReason().get().toHexString());
    } else if (result.getExceptionalHaltReason().isPresent()) {
      return new JsonRpcError(
          -32015, result.getExceptionalHaltReason().get().getDescription(), null);
    }
    return null;
  }
}
