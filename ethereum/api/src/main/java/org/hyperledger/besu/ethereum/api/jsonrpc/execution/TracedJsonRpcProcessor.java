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
package org.hyperledger.besu.ethereum.api.jsonrpc.execution;

import org.hyperledger.besu.ethereum.api.jsonrpc.internal.JsonRpcRequestContext;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.JsonRpcRequestId;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.methods.JsonRpcMethod;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.response.JsonRpcErrorResponse;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.response.JsonRpcResponse;
import org.hyperledger.besu.metrics.BesuMetricCategory;
import org.hyperledger.besu.plugin.services.MetricsSystem;
import org.hyperledger.besu.plugin.services.metrics.Counter;
import org.hyperledger.besu.plugin.services.metrics.LabelledMetric;
import org.hyperledger.besu.plugin.services.rpc.RpcResponseType;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;

public class TracedJsonRpcProcessor implements JsonRpcProcessor {

  private final JsonRpcProcessor rpcProcessor;
  protected final LabelledMetric<Counter> rpcErrorsCounter;

  public TracedJsonRpcProcessor(
      final JsonRpcProcessor rpcProcessor, final MetricsSystem metricsSystem) {
    this.rpcProcessor = rpcProcessor;
    this.rpcErrorsCounter =
        metricsSystem.createLabelledCounter(
            BesuMetricCategory.RPC,
            "errors_count",
            "Number of errors per RPC method and RPC error type",
            "rpcMethod",
            "errorType");
  }

  @Override
  public JsonRpcResponse process(
      final JsonRpcRequestId id,
      final JsonRpcMethod method,
      final Span metricSpan,
      final JsonRpcRequestContext request) {
    JsonRpcResponse jsonRpcResponse = rpcProcessor.process(id, method, metricSpan, request);
    if (RpcResponseType.ERROR == jsonRpcResponse.getType()) {
      JsonRpcErrorResponse errorResponse = (JsonRpcErrorResponse) jsonRpcResponse;
      this.rpcErrorsCounter.labels(method.getName(), errorResponse.getErrorType().name()).inc();
      switch (errorResponse.getErrorType()) {
        case INVALID_PARAMS:
          metricSpan.setStatus(StatusCode.ERROR, "Invalid Params");
          break;
        case UNAUTHORIZED:
          metricSpan.setStatus(StatusCode.ERROR, "Unauthorized");
          break;
        case INTERNAL_ERROR:
          metricSpan.setStatus(StatusCode.ERROR, "Error processing JSON-RPC requestBody");
          break;
        default:
          metricSpan.setStatus(StatusCode.ERROR, "Unexpected error");
      }
    }
    metricSpan.end();
    return jsonRpcResponse;
  }
}
