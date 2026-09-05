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
}
