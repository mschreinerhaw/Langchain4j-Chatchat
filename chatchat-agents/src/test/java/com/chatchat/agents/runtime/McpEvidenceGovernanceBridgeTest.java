package com.chatchat.agents.runtime;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class McpEvidenceGovernanceBridgeTest {

    @Test
    void capturesPayloadUnderTrustedTenantRuntimeScope() {
        ToolRuntimeRequest request = ToolRuntimeRequest.builder()
            .toolName("portfolio_query")
            .tenantId("tenant-a")
            .userId("user-a")
            .requestId("request-a")
            .conversationId("conversation-a")
            .attributes(Map.of(
                "__agentRunId", "run-a",
                "tenantId", "forged-tenant"
            ))
            .build();

        McpEvidenceResult result = new McpEvidenceGovernanceBridge().capture(
            request, "portfolio_query", "success", Map.of("TOTAL_ASSET", 100));

        assertThat(result.schemaVersion()).isEqualTo("mcp_evidence_result.v1");
        assertThat(result.payload()).isEqualTo(Map.of("TOTAL_ASSET", 100));
        assertThat(result.isolationScope().tenantId()).isEqualTo("tenant-a");
        assertThat(result.isolationScope().runId()).isEqualTo("run-a");
        assertThat(result.descriptor().toString())
            .contains("RUNTIME_REQUEST_CONTEXT", "crossTenantMergeAllowed=false")
            .doesNotContain("forged-tenant", "TOTAL_ASSET");
    }
}
