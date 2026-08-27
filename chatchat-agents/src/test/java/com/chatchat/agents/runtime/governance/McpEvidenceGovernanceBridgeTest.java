package com.chatchat.agents.runtime.governance;

import com.chatchat.agents.runtime.analysis.McpResultAnalysisBridge;
import com.chatchat.agents.runtime.tool.ToolRuntimeRequest;

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
    void projectsOnlyCanonicalBusinessRecordsFromMirroredMcpAnalysisPayload() {
        List<Map<String, Object>> businessRecords = List.of(
            Map.of("KHH", "070200046604", "ZZC", 1_250_000, "DRYK", 8_600));
        Map<String, Object> governedData = Map.of(
            "analysisContext", Map.of("schema", Map.of("fields", List.of(
                Map.of("name", "KHH"), Map.of("name", "ZZC")))),
            "data", Map.of("body", Map.of("records", businessRecords)),
            "execution", Map.of("steps", List.of(Map.of("status", "SUCCESS"))),
            "executionGraph", Map.of("nodes", List.of(Map.of("id", "execute"))));
        Map<String, Object> payload = Map.of(
            "schemaVersion", "mcp_analysis_payload.v1",
            "data", governedData,
            "rawData", Map.of(
                "content", List.of(Map.of("type", "text", "text", governedData)),
                "structuredContent", governedData),
            "runtimeMetadata", Map.of("preflightAudit", List.of(Map.of("status", "PASSED"))));

        Map<String, Object> projection = new McpResultAnalysisBridge()
            .protocolAnalysisProjection("asset-template", payload, 10_000);

        assertThat(projection)
            .containsEntry("adapterId", "mcp_analysis_payload_records.v1")
            .containsEntry("evidenceRole", "MCP_CANONICAL_BUSINESS_DATA")
            .containsEntry("sourcePayloadPreserved", true)
            .containsEntry("authoritativePayloadMutated", false)
            .containsEntry("projectionContainsBusinessDataOnly", true);
        assertThat(String.valueOf(projection.get("sourcePayloadSha256"))).isNotBlank();
        assertThat(projection.get("sourcePayloadChars")).isEqualTo(
            com.chatchat.agents.protocol.ModelProtocolJson.compact(payload).length());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> datasets = (List<Map<String, Object>>) projection.get("datasets");
        assertThat(datasets).singleElement().satisfies(dataset -> {
            assertThat(dataset).containsEntry("datasetReference", "asset-template");
            assertThat(dataset.get("records")).isEqualTo(businessRecords);
            assertThat(String.valueOf(dataset))
                .doesNotContain("executionGraph", "preflightAudit", "structuredContent");
        });
        assertThat(payload.get("rawData")).isEqualTo(Map.of(
            "content", List.of(Map.of("type", "text", "text", governedData)),
            "structuredContent", governedData));
    }

    @Test
    void keepsSuccessfulEmptyCanonicalBodyOutOfGenericMetadataAnalysis() {
        Map<String, Object> payload = Map.of(
            "schemaVersion", "mcp_analysis_payload.v1",
            "data", Map.of(
                "data", Map.of("body", Map.of("records", List.of())),
                "execution", Map.of("steps", List.of(Map.of("status", "SUCCESS")))),
            "rawData", Map.of("structuredContent", Map.of(
                "data", Map.of("body", Map.of("records", List.of())))));

        assertThat(new McpResultAnalysisBridge()
            .protocolAnalysisProjection("empty-template", payload, 10_000)).isEmpty();
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
