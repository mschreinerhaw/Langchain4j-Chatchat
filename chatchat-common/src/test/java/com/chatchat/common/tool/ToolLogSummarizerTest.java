package com.chatchat.common.tool;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ToolLogSummarizerTest {

    @Test
    void enterpriseMetadataResultLogsCountsWithoutFieldLevelResults() {
        Map<String, Object> result = Map.of(
            "schemaVersion", "enterprise_metadata_field_discovery.v1",
            "success", true,
            "requestId", "metadata-1",
            "targetObject", Map.of("type", "TABLE", "name", "gdp_ads.target_table"),
            "sourceSchema", Map.of(
                "fieldCount", 2,
                "fields", List.of(
                    Map.of("fieldName", "field_a", "description", "sensitive field comment A"),
                    Map.of("fieldName", "field_b", "description", "sensitive field comment B")
                )
            ),
            "fieldMatches", List.of(
                Map.of(
                    "standardFields", List.of(Map.of("name", "standard-a"), Map.of("name", "standard-b")),
                    "termRoots", List.of(Map.of("name", "term-a")),
                    "dictionaries", List.of(Map.of("name", "dictionary-a"))
                ),
                Map.of(
                    "standardFields", List.of(Map.of("name", "standard-c")),
                    "termRoots", List.of(),
                    "dictionaries", List.of(Map.of("name", "dictionary-b"))
                )
            ),
            "evidenceObjects", List.of(Map.of("content", "large evidence"), Map.of("content", "other evidence"))
        );

        Object summarized = ToolLogSummarizer.summarizeResult(
            "mcp_chatchat_mcp_server_enterprise_metadata_search", result);

        assertThat(summarized).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) summarized;
        assertThat(summary)
            .containsEntry("schemaVersion", "enterprise_metadata_field_discovery.v1")
            .containsEntry("requestId", "metadata-1")
            .containsEntry("sourceFieldCount", 2)
            .containsEntry("matchedFieldCount", 2)
            .containsEntry("evidenceObjectCount", 2)
            .containsEntry("detailsLogged", false);
        assertThat((Map<String, Integer>) summary.get("candidateCounts"))
            .containsEntry("standardFields", 3)
            .containsEntry("termRoots", 1)
            .containsEntry("dictionaries", 2);
        assertThat(String.valueOf(summary))
            .doesNotContain("field_a", "field_b", "sensitive field comment",
                "standard-a", "term-a", "dictionary-a", "large evidence");
    }

    @Test
    void ordinaryToolResultKeepsExistingCompactSummaryBehavior() {
        Object summarized = ToolLogSummarizer.summarizeResult(
            "ordinary_tool", Map.of("rows", List.of(Map.of("id", 1, "name", "visible"))));

        assertThat(String.valueOf(summarized)).contains("visible");
    }
}
