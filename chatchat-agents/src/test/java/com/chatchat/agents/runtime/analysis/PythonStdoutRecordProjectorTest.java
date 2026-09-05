package com.chatchat.agents.runtime.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class PythonStdoutRecordProjectorTest {
    @Test void keepsStatisticsAndEveryDetailInOneAssignmentWithoutSlicingJson() throws Exception {
        List<Map<String, Object>> details = new ArrayList<>();
        for (int line = 1; line <= 237; line++) details.add(Map.of("line", line, "message", "upstream failure " + line));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total_lines", 248);
        result.put("total_error_count", 237);
        result.put("baseline", null);
        result.put("statistics", Map.of("levels", Map.of("error", 235, "failed", 2)));
        result.put("top_errors", List.of(Map.of("type", "UnknownError", "count", 237, "details", details)));
        String json = new ObjectMapper().writeValueAsString(Map.of("result", result));
        var datasets = new PythonStdoutRecordProjector().project("tool", Map.of(
            "schemaVersion", "python_analysis_bridge_result.v1", "stdout", json));
        assertThat(datasets).hasSize(1);
        var records = datasets.get(0).records();
        assertThat(records).hasSize(240);
        var values = records.stream().map(row -> (Map<?, ?>) row.get("values")).toList();
        assertThat(values.stream().filter(row -> row.containsKey("line"))).hasSize(237);
        assertThat(values.stream().filter(row -> row.containsKey("baseline")).findFirst().orElseThrow().get("baseline")).isNull();
        assertThat(values.stream().filter(row -> row.containsKey("total_lines")).findFirst().orElseThrow().get("total_lines")).isEqualTo(248);
        assertThat(records.get(records.size() - 1).get("sourcePath").toString()).contains("details");
        assertThat(records).allSatisfy(row -> assertThat(row).doesNotContainKeys("content", "fromChar", "toChar"));
    }

    @Test void malformedOrNonPythonStdoutUsesExistingLosslessFallback() {
        var projector = new PythonStdoutRecordProjector();
        assertThat(projector.project("tool", Map.of("schemaVersion", "python_analysis_bridge_result.v1", "stdout", "{broken"))).isEmpty();
        assertThat(projector.project("tool", Map.of("schemaVersion", "other.v1", "stdout", "{\"count\":1}"))).isEmpty();
    }
}
