package com.chatchat.agents.runtime.analysis;

import com.chatchat.agents.orchestration.analysis.dataset.DatasetHandle;
import com.chatchat.agents.orchestration.analysis.dataset.PagedDatasetHandle;
import com.chatchat.agents.runtime.protocol.RuntimeResultAnalysisAdapter;
import com.chatchat.common.mcp.runtime.McpAnalysisPayload;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class McpResultAnalysisBridgeTest {

    private final McpResultAnalysisBridge bridge = new McpResultAnalysisBridge();

    @Test
    void projectsEveryNonNullRuntimeDataShapeIntoAnalyzableRecords() {
        List<Object> arbitraryResults = List.of(
            Map.of("temperature", 42, "healthy", true),
            List.of("alpha", "beta"),
            "service healthy",
            73,
            true
        );

        for (Object result : arbitraryResults) {
            Map<String, Object> projection = bridge.analysisProjection("arbitrary-result", result);

            assertThat(projection).containsEntry("projectionContainsBusinessDataOnly", true);
            assertThat(projection.get("datasets"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST)
                .isNotEmpty();
            assertThat(projection.toString()).contains("records");
        }
    }

    @Test
    void typedProjectionPreservesCursorBackedHandle() {
        DatasetHandle handle = new PagedDatasetHandle(10_000, true,
            (offset, limit) -> new DatasetHandle.Page(offset,
                offset >= 10_000 ? List.of() : List.of(Map.of("offset", offset)),
                offset + 1 < 10_000), null, null, Map.of("source", "cursor"));
        RuntimeResultAnalysisAdapter adapter = new RuntimeResultAnalysisAdapter() {
            @Override public String id() { return "cursor-test"; }
            @Override public int priority() { return 10_000; }
            @Override public boolean supports(AnalysisRequest request) { return true; }
            @Override public AnalysisResult adapt(AnalysisRequest request) {
                return new AnalysisResult("cursor.v1", "BUSINESS_DATA",
                    List.of(new AnalysisDataset(request.datasetReference(), Map.of(), handle)));
            }
        };
        var typedBridge = new McpResultAnalysisBridge(List.of(adapter));

        var result = typedBridge.analysisResult("large", Map.of("ignored", true), 10_000, true);

        assertThat(result.datasets()).singleElement().satisfies(dataset -> {
            assertThat(dataset.handle()).isSameAs(handle);
            assertThat(dataset.handle().recordCount()).isEqualTo(10_000);
        });
    }

    @Test
    void preservesDeclaredEmptyResultWithoutInventingADataRecord() {
        Map<String, Object> projection = bridge.analysisProjection("empty", Map.of(
            "schemaVersion", McpAnalysisPayload.SCHEMA_VERSION,
            "resultKind", "EMPTY",
            "status", "EMPTY_RESULT",
            "completeness", Map.of("complete", true),
            "data", List.of(), "rawData", List.of()));

        assertThat(projection)
            .containsEntry("declaredNoRecords", true)
            .containsEntry("resultKind", "EMPTY")
            .containsEntry("resultRouting", "DECLARED_RESULT_KIND");
        assertThat(projection.get("datasets"))
            .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST).isEmpty();
    }
}
