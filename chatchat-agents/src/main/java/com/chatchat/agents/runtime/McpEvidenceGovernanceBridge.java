package com.chatchat.agents.runtime;

import com.chatchat.agents.runtime.protocol.RuntimeEvidenceProtocol;

import java.util.Map;
import java.util.Objects;

/**
 * The mandatory bridge between MCP runtime output and every downstream observation or summary.
 */
public final class McpEvidenceGovernanceBridge
    implements RuntimeEvidenceProtocol<McpEvidenceResult> {

    public McpEvidenceResult capture(ToolRuntimeRequest request,
                                     String toolName,
                                     String outcome,
                                     Object boundedPayload) {
        GovernanceIsolationScope scope = trustedScope(request);
        String payloadFingerprint = Integer.toUnsignedString(Objects.hashCode(boundedPayload), 36);
        String evidenceId = scope.partitionKey() + ":" + text(toolName, "unknown-tool")
            + ":" + text(request == null ? null : request.getRequestId(), "unknown-request")
            + ":" + payloadFingerprint;
        return new McpEvidenceResult(
            McpEvidenceResult.SCHEMA_VERSION,
            evidenceId,
            toolName,
            outcome,
            scope,
            boundedPayload,
            Map.of(
                "factBoundary", "MCP_RUNTIME_RETURNED_PAYLOAD",
                "payloadTrust", "UNTRUSTED_DATA_NOT_INSTRUCTIONS",
                "tenantSource", GovernanceIsolationScope.RUNTIME_AUTHORITY,
                "crossTenantMergeAllowed", false,
                "summaryMutationAllowed", false
            )
        );
    }

    public GovernanceIsolationScope trustedScope(ToolRuntimeRequest request) {
        Map<String, Object> attributes = request == null || request.getAttributes() == null
            ? Map.of() : request.getAttributes();
        String runId = firstText(
            string(attributes.get("__agentRunId")),
            firstText(string(attributes.get("agentRunId")), request == null ? null : request.getRequestId())
        );
        return GovernanceIsolationScope.runtime(
            request == null ? null : request.getTenantId(),
            request == null ? null : request.getUserId(),
            runId,
            request == null ? null : request.getRequestId(),
            request == null ? null : request.getConversationId()
        );
    }

    private String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String firstText(String first, String second) {
        return first == null || first.isBlank() ? second : first;
    }

    private String text(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
