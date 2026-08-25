package com.chatchat.common.bridge;

import com.chatchat.common.kernel.KernelDataDomain;
import com.chatchat.common.kernel.KernelDataScope;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Canonical inbound envelope used at every Runtime OS communication bridge. */
public record BridgeRequest<I>(
    String bridgeVersion,
    String requestId,
    String operation,
    KernelDataScope scope,
    Set<KernelDataDomain> requestedReadData,
    Set<KernelDataDomain> requestedWriteData,
    I payload,
    Map<String, Object> metadata,
    long createdAt
) {
    public BridgeRequest {
        if (bridgeVersion == null || bridgeVersion.isBlank()) {
            throw new IllegalArgumentException("bridgeVersion is required");
        }
        requestId = requestId == null || requestId.isBlank() ? UUID.randomUUID().toString() : requestId.trim();
        if (operation == null || operation.isBlank()) throw new IllegalArgumentException("bridge operation is required");
        if (scope == null) throw new IllegalArgumentException("bridge scope is required");
        requestedReadData = requestedReadData == null ? Set.of() : Set.copyOf(requestedReadData);
        requestedWriteData = requestedWriteData == null ? Set.of() : Set.copyOf(requestedWriteData);
        metadata = metadata == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(metadata));
        createdAt = createdAt <= 0 ? System.currentTimeMillis() : createdAt;
    }

    public static <I> BridgeRequest<I> of(BridgeContract contract,
                                          String operation,
                                          KernelDataScope scope,
                                          Set<KernelDataDomain> reads,
                                          Set<KernelDataDomain> writes,
                                          I payload) {
        return new BridgeRequest<>(contract.version(), scope.requestId(), operation, scope, reads, writes,
            payload, Map.of(), System.currentTimeMillis());
    }
}
