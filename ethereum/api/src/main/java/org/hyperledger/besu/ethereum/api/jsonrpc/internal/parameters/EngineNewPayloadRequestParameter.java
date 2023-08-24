package org.hyperledger.besu.ethereum.api.jsonrpc.internal.parameters;

import java.util.List;
import java.util.Optional;

import org.apache.tuweni.bytes.Bytes32;

public class EngineNewPayloadRequestParameter {

  private final Object requestId;
  private final EnginePayloadParameter payload;
  private final Optional<List<String>> maybeVersionedHashParam;
  private final Optional<Bytes32> maybeParentBeaconBlockRoot;

  public EngineNewPayloadRequestParameter(
      final Object requestId, final EnginePayloadParameter payload) {
    this(requestId, payload, Optional.empty(), Optional.empty());
  }

  public EngineNewPayloadRequestParameter(
      final Object requestId,
      final EnginePayloadParameter payload,
      final Optional<List<String>> maybeVersionedHashParam,
      final Optional<Bytes32> maybeParentBeaconBlockRoot) {
    this.requestId = requestId;
    this.payload = payload;
    this.maybeVersionedHashParam = maybeVersionedHashParam;
    this.maybeParentBeaconBlockRoot = maybeParentBeaconBlockRoot;
  }

  public Object getRequestId() {
    return requestId;
  }

  public EnginePayloadParameter getPayload() {
    return payload;
  }

  public Optional<List<String>> getVersionedHashParam() {
    return maybeVersionedHashParam;
  }

  public Optional<Bytes32> getParentBeaconBlockRoot() {
    return maybeParentBeaconBlockRoot;
  }
}
