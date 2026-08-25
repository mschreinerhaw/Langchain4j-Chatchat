package com.chatchat.common.kernel;

import java.util.LinkedHashMap;
import java.util.Map;

/** Tenant and trace partition carried across every Kernel invocation. */
public record KernelDataScope(
    String tenantId,
    String userId,
    String requestId,
    String conversationId,
    String runId,
    String environment,
    Map<String, Object> attributes
) {
    public KernelDataScope {
        tenantId = clean(tenantId);
        userId = clean(userId);
        requestId = clean(requestId);
        conversationId = clean(conversationId);
        runId = clean(runId);
        environment = clean(environment);
        attributes = attributes == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(attributes));
    }

    public static KernelDataScope system(String requestId) {
        return new KernelDataScope("system", null, requestId, null, null, null, Map.of());
    }

    public String partitionKey() {
        return tenantId + ":" + (runId == null ? requestId : runId);
    }

    private static String clean(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
