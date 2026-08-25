package com.chatchat.agents.runtime;

import com.chatchat.agents.runtime.protocol.RuntimeResultAnalysisAdapter;

import org.junit.jupiter.api.Test;

import java.util.List;
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

    @Test
    void projectsCommandStreamsByPublishedResultProtocolWithoutMutatingPayload() {
        Map<String, Object> payload = Map.of(
            "dataSchema", "ssh_steps.v1",
            "data", Map.of(
                "stdout", "line-1\nline-2",
                "stderr", "",
                "outputLimits", Map.of("stdoutTruncated", false, "stderrTruncated", false)
            )
        );

        Map<String, Object> projection = new McpResultAnalysisBridge()
            .analysisProjection("check-result", payload, 1_000);

        assertThat(projection)
            .containsEntry("schemaVersion", "mcp_analysis_projection.v1")
            .containsEntry("sourceSchemaVersion", "ssh_steps.v1")
            .containsEntry("authoritativePayloadMutated", false);
        assertThat((List<?>) projection.get("datasets")).hasSize(1);
        assertThat(projection.toString()).contains("check-result#stdout", "line-1", "line-2");
        assertThat(payload.toString()).doesNotContain("mcp_analysis_projection.v1");
    }

    @Test
    void fallsBackToLosslessProjectionWithoutInferringProtocolFromBusinessFieldNames() {
        Map<String, Object> payload = Map.of(
            "dataSchema", "business_records.v1",
            "data", Map.of("stdout", "ordinary business field")
        );

        Map<String, Object> projection = new McpResultAnalysisBridge()
            .analysisProjection("customer-result", payload);

        assertThat(projection)
            .containsEntry("adapterId", "generic_bounded_result.v1")
            .containsEntry("sourceSchemaVersion", "business_records.v1");
        assertThat(projection.toString()).contains("ordinary business field");
        assertThat(new McpResultAnalysisBridge()
            .protocolAnalysisProjection("customer-result", payload, 1_000)).isEmpty();
    }

    @Test
    void acceptsCustomProtocolAdapterThroughThePublicContract() {
        RuntimeResultAnalysisAdapter custom = new RuntimeResultAnalysisAdapter() {
            @Override public String id() { return "custom.metrics.v1"; }
            @Override public int priority() { return 500; }
            @Override public boolean supports(AnalysisRequest request) {
                return request.payload() instanceof Map<?, ?> map
                    && "custom.v1".equals(map.get("schemaVersion"));
            }
            @Override public AnalysisResult adapt(AnalysisRequest request) {
                return new AnalysisResult("custom.v1", "CUSTOM_FACTS", List.of(
                    new AnalysisDataset(request.datasetReference() + "#metrics", Map.of(),
                        List.of(Map.of("value", 42)))));
            }
        };

        Map<String, Object> projection = new McpResultAnalysisBridge(List.of(custom))
            .analysisProjection("custom-result", Map.of("schemaVersion", "custom.v1"));

        assertThat(projection).containsEntry("adapterId", "custom.metrics.v1");
        assertThat(projection.toString()).contains("custom-result#metrics", "value=42");
    }
}
