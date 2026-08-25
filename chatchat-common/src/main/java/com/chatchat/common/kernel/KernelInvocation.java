package com.chatchat.common.kernel;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Canonical request envelope shared by Runtime and MCP Kernel components. */
public record KernelInvocation<I>(
    String abiVersion,
    String invocationId,
    String operation,
    KernelProtocol protocol,
    KernelDataScope scope,
    Set<KernelDataDomain> requestedReadData,
    Set<KernelDataDomain> requestedWriteData,
    I payload,
    Map<String, Object> metadata,
    long createdAt
) {
    public KernelInvocation {
        abiVersion = abiVersion == null || abiVersion.isBlank()
            ? KernelProtocolCatalog.KERNEL_ABI_VERSION : abiVersion.trim();
        invocationId = invocationId == null || invocationId.isBlank()
            ? UUID.randomUUID().toString() : invocationId.trim();
        operation = operation == null || operation.isBlank() ? "execute" : operation.trim();
        if (protocol == null) throw new IllegalArgumentException("Kernel protocol is required");
        if (scope == null) throw new IllegalArgumentException("Kernel data scope is required");
        requestedReadData = requestedReadData == null ? Set.of() : Set.copyOf(requestedReadData);
        requestedWriteData = requestedWriteData == null ? Set.of() : Set.copyOf(requestedWriteData);
        metadata = metadata == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(metadata));
        createdAt = createdAt <= 0 ? System.currentTimeMillis() : createdAt;
    }

    public static <I> KernelInvocation<I> of(String operation,
                                              KernelProtocol protocol,
                                              KernelDataScope scope,
                                              Set<KernelDataDomain> requestedReadData,
                                              I payload) {
        return new KernelInvocation<>(KernelProtocolCatalog.KERNEL_ABI_VERSION, null, operation,
            protocol, scope, requestedReadData, Set.of(), payload, Map.of(), System.currentTimeMillis());
    }

    public static <I> KernelInvocation<I> of(String operation,
                                              KernelProtocol protocol,
                                              KernelDataScope scope,
                                              Set<KernelDataDomain> requestedReadData,
                                              Set<KernelDataDomain> requestedWriteData,
                                              I payload) {
        return new KernelInvocation<>(KernelProtocolCatalog.KERNEL_ABI_VERSION, null, operation,
            protocol, scope, requestedReadData, requestedWriteData, payload, Map.of(), System.currentTimeMillis());
    }
}
