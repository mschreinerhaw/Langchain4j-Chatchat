package com.chatchat.agents.runtime.analysis;

import com.chatchat.agents.runtime.protocol.RuntimeResultAnalysisAdapter.AnalysisRequest;
import com.chatchat.common.mcp.runtime.McpAnalysisPayload;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class McpAnalysisPayloadResultAnalysisAdapterTest {

    @Test
    void projectsPythonJsonAsRecordsInsteadOfSlicingTransportText() {
        var payload = Map.of("schemaVersion", McpAnalysisPayload.SCHEMA_VERSION,
            "data", Map.of("schemaVersion", "python_analysis_bridge_result.v1",
                "stdout", "{\"result\":{\"total\":248,\"errors\":[{\"line\":1},{\"line\":2}]}}"));
        var result = new McpAnalysisPayloadResultAnalysisAdapter().adapt(
            new AnalysisRequest("logs", payload, 1000));
        assertThat(result.datasets()).singleElement().satisfies(dataset -> {
            assertThat(dataset.analysisContext()).containsEntry("projectionMode", "PYTHON_JSON_STDOUT_RECORDS");
            assertThat(dataset.records()).hasSize(3);
            assertThat(dataset.records()).allSatisfy(row ->
                assertThat(row).containsKeys("sourcePath", "values").doesNotContainKeys("content", "fromChar"));
        });
    }

    @Test
    void preservesSqlNullValuesInCanonicalRows() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("fundCode", "510300");
        row.put("latestScale", null);

        Map<String, Object> analysisContext = new LinkedHashMap<>();
        analysisContext.put("observationDate", null);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("analysisContext", analysisContext);
        data.put("records", List.of(row));
        Map<String, Object> payload = Map.of(
            "schemaVersion", McpAnalysisPayload.SCHEMA_VERSION,
            "data", data);

        var result = new McpAnalysisPayloadResultAnalysisAdapter().adapt(
            new AnalysisRequest("sample_etf_latest_scale", payload, 10_000));

        assertThat(result.datasets()).singleElement().satisfies(dataset -> {
            assertThat(dataset.analysisContext()).doesNotContainKey("observationDate");
            assertThat(dataset.records()).singleElement().satisfies(projectedRow -> {
                assertThat(projectedRow).containsEntry("fundCode", "510300");
                assertThat(projectedRow).containsKey("latestScale");
                assertThat(projectedRow.get("latestScale")).isNull();
            });
        });
    }

    @Test
    void routesDeclaredRecordsAndCarriesSourceVersionCompletenessAndCursorIntoDatasetContext() {
        Map<String, Object> payload = Map.of(
            "schemaVersion", McpAnalysisPayload.SCHEMA_VERSION,
            "resultKind", "RAW_RECORDS",
            "resultSchemaRef", "positions.v2",
            "provenance", Map.of("sourceRef", "ledger/positions", "dataVersion", "close-20260908",
                "inputFingerprint", "sha256:abc", "rowRange", Map.of("from", 1, "to", 2)),
            "pagination", Map.of("nextPageToken", "next-2", "hasMore", true, "pageSize", 2),
            "completeness", Map.of("status", "PARTIAL", "complete", false,
                "missingRequired", List.of("currency")),
            "data", Map.of("records", List.of(Map.of("id", 1), Map.of("id", 2))));

        var result = new McpAnalysisPayloadResultAnalysisAdapter().adapt(
            new AnalysisRequest("positions", payload, 10_000));

        assertThat(result.datasets()).singleElement().satisfies(dataset -> {
            assertThat(dataset.records()).hasSize(2);
            assertThat(dataset.analysisContext())
                .containsEntry("resultKind", "RAW_RECORDS")
                .containsEntry("resultSchemaRef", "positions.v2")
                .containsEntry("resultSemanticsDeclared", true)
                .containsEntry("resultRouting", "DECLARED_RESULT_KIND");
            assertThat(dataset.analysisContext().toString())
                .contains("ledger/positions", "close-20260908", "next-2", "missingRequired", "currency");
        });
    }

    @Test
    void marksUndeclaredLegacyProjectionAsCompatibilityRouting() {
        Map<String, Object> payload = Map.of("schemaVersion", McpAnalysisPayload.SCHEMA_VERSION,
            "data", Map.of("records", List.of(Map.of("id", 1))));

        var result = new McpAnalysisPayloadResultAnalysisAdapter().adapt(
            new AnalysisRequest("legacy", payload, 10_000));

        assertThat(result.datasets()).singleElement().satisfies(dataset -> assertThat(dataset.analysisContext())
            .containsEntry("resultSemanticsDeclared", false)
            .containsEntry("resultRouting", "HEURISTIC_COMPATIBILITY"));
    }
}
