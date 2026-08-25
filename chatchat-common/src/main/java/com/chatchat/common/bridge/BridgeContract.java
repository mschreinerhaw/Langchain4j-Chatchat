package com.chatchat.common.bridge;

import com.chatchat.common.kernel.KernelDataBoundary;
import com.chatchat.common.kernel.KernelProtocol;

import java.util.Set;

/** Stable contract published by every API, MCP or in-process bridge. */
public record BridgeContract(
    String bridgeId,
    String version,
    KernelProtocol protocol,
    Set<String> operations,
    KernelDataBoundary dataBoundary
) {
    public BridgeContract {
        if (bridgeId == null || bridgeId.isBlank()) throw new IllegalArgumentException("bridgeId is required");
        if (version == null || version.isBlank()) throw new IllegalArgumentException("bridge version is required");
        if (protocol == null) throw new IllegalArgumentException("bridge protocol is required");
        operations = operations == null ? Set.of() : Set.copyOf(operations);
        if (operations.isEmpty()) throw new IllegalArgumentException("bridge operations are required");
        if (dataBoundary == null) throw new IllegalArgumentException("bridge data boundary is required");
    }

    public boolean supports(String operation) {
        return operation != null && operations.contains(operation);
    }
}
