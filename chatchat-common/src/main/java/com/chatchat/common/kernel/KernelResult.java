package com.chatchat.common.kernel;

import java.util.LinkedHashMap;
import java.util.Map;

/** Canonical response envelope shared by Runtime and MCP Kernel components. */
public record KernelResult<O>(
    String abiVersion,
    String invocationId,
    KernelProtocol protocol,
    KernelDataScope scope,
    KernelStatus status,
    O data,
    String errorCode,
    String errorMessage,
    Map<String, Object> metadata,
    long completedAt
) {
    public KernelResult {
        abiVersion = abiVersion == null || abiVersion.isBlank()
            ? KernelProtocolCatalog.KERNEL_ABI_VERSION : abiVersion.trim();
        if (protocol == null) throw new IllegalArgumentException("Kernel result protocol is required");
        if (scope == null) throw new IllegalArgumentException("Kernel result scope is required");
        status = status == null ? KernelStatus.FAILURE : status;
        metadata = metadata == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(metadata));
        completedAt = completedAt <= 0 ? System.currentTimeMillis() : completedAt;
    }

    public static <O> KernelResult<O> success(KernelInvocation<?> invocation, O data) {
        return new KernelResult<>(KernelProtocolCatalog.KERNEL_ABI_VERSION, invocation.invocationId(),
            invocation.protocol(), invocation.scope(), KernelStatus.SUCCESS, data, null, null,
            Map.of(), System.currentTimeMillis());
    }

    public static <O> KernelResult<O> failure(KernelInvocation<?> invocation,
                                              KernelStatus status,
                                              String code,
                                              String message) {
        return new KernelResult<>(KernelProtocolCatalog.KERNEL_ABI_VERSION, invocation.invocationId(),
            invocation.protocol(), invocation.scope(), status, null, code, message,
            Map.of(), System.currentTimeMillis());
    }

    public boolean successful() {
        return status == KernelStatus.SUCCESS;
    }
}
