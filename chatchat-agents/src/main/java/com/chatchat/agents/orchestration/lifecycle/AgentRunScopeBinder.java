package com.chatchat.agents.orchestration.lifecycle;

import com.chatchat.agents.runtime.AgentRunRequest;
import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.kernel.KernelViolationException;

import java.util.LinkedHashMap;
import java.util.Map;

/** Enforces and projects the immutable Kernel scope onto a runtime request. */
public final class AgentRunScopeBinder {

    public AgentRunRequest bind(AgentRunRequest request, KernelDataScope scope) {
        if (request == null) {
            throw new IllegalArgumentException("Agent run request is required");
        }
        if (scope == null) {
            throw new IllegalArgumentException("Kernel data scope is required");
        }
        requireMatch("tenantId", request.getTenantId(), scope.tenantId());
        requireMatch("requestId", request.getRequestId(), scope.requestId());
        requireMatch("conversationId", request.getConversationId(), scope.conversationId());
        requireMatch("runId", request.getRunId(), scope.runId());
        requireMatch("userId", request.getUserId(), scope.userId());
        if (request.getTenantId() == null) request.setTenantId(scope.tenantId());
        if (request.getRequestId() == null) request.setRequestId(scope.requestId());
        if (request.getConversationId() == null) request.setConversationId(scope.conversationId());
        if (request.getRunId() == null) request.setRunId(scope.runId());
        if (request.getUserId() == null) request.setUserId(scope.userId());

        Map<String, Object> attributes = new LinkedHashMap<>(
            request.getAttributes() == null ? Map.of() : request.getAttributes());
        Map<String, Object> projection = new LinkedHashMap<>();
        put(projection, "tenantId", scope.tenantId());
        put(projection, "userId", scope.userId());
        put(projection, "requestId", scope.requestId());
        put(projection, "conversationId", scope.conversationId());
        put(projection, "runId", scope.runId());
        put(projection, "environment", scope.environment());
        projection.put("attributes", scope.attributes());
        attributes.put("kernelDataScope", Map.copyOf(projection));
        request.setAttributes(attributes);
        return request;
    }

    private void requireMatch(String field, String requestValue, String scopeValue) {
        if (requestValue != null && !requestValue.isBlank() && scopeValue != null
            && !scopeValue.equals(requestValue)) {
            throw new KernelViolationException("KERNEL_SCOPE_MISMATCH",
                "Agent request " + field + " does not match Kernel scope");
        }
    }

    private void put(Map<String, Object> target, String key, String value) {
        if (value != null && !value.isBlank()) {
            target.put(key, value);
        }
    }
}
