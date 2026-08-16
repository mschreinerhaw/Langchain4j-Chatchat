package com.chatchat.common.tool;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ToolLogSummarizerTest {

    @Test
    void enterpriseMetadataDiscoveryLogsReturnedTypeCountsWithoutRecords() {
        Map<String, Object> result = Map.of(
            "schemaVersion", "enterprise_metadata_search_result.v3",
            "success", true,
            "operationMode", "ENTERPRISE_METADATA_DISCOVERY",
            "backend", "opensearch",
            "count", 3,
            "countsByType", Map.of(
                "metadata_field", 1,
                "metadata_term", 1,
                "metadata_dictionary", 1
            ),
            "results", List.of(
                Map.of("name", "客户号", "description", "sensitive standard definition")
            ),
            "evidenceObjects", List.of(Map.of("content", "large evidence"))
        );

        Object summarized = ToolLogSummarizer.summarizeResult(
            "mcp_chatchat_mcp_server_enterprise_metadata_search", result);

        assertThat(summarized).isInstanceOfSatisfying(Map.class, summary -> {
            assertThat(summary)
                .containsEntry("operationMode", "ENTERPRISE_METADATA_DISCOVERY")
                .containsEntry("backend", "opensearch")
                .containsEntry("count", 3)
                .containsEntry("evidenceObjectCount", 1)
                .doesNotContainKeys("results");
        });
        assertThat(String.valueOf(summarized))
            .doesNotContain("客户号", "sensitive standard definition", "large evidence");
    }

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

    @Test
    void externalizedResultLogsReferenceStateInsteadOfInventingDomainCounts() {
        Object summarized = ToolLogSummarizer.summarizeResult(
            "mcp_chatchat_mcp_server_enterprise_metadata_search",
            Map.of(
                "outputExternal", true,
                "outputTruncated", true,
                "originalBytes", 7_388_563,
                "maxInlineBytes", 262_144,
                "reason", "TOOL_OUTPUT_LIMIT_EXCEEDED",
                "documentId", "tool-output:internal",
                "preview", Map.of("fieldMatches", List.of("first"))
            ));

        assertThat(summarized).isInstanceOfSatisfying(Map.class, summary ->
            assertThat(summary)
                .containsEntry("schemaVersion", "externalized_tool_result_summary.v1")
                .containsEntry("outputExternal", true)
                .containsEntry("outputTruncated", true)
                .containsEntry("originalBytes", 7_388_563)
                .containsEntry("resultPresent", true)
                .doesNotContainKeys("sourceFieldCount", "matchedFieldCount", "preview", "documentId"));
    }

    @Test
    void oversizedStructuredSummaryKeepsAJsonSerializableContractInsteadOfMapToString() {
        Object summarized = ToolLogSummarizer.summarize(Map.of(
            "results", java.util.stream.IntStream.range(0, 20)
                .mapToObj(index -> Map.of("title", "x".repeat(500), "index", index))
                .toList()
        ), 200);

        assertThat(summarized).isInstanceOfSatisfying(Map.class, envelope ->
            assertThat(envelope)
                .containsEntry("schemaVersion", "tool_result_summary.v1")
                .containsEntry("summaryTruncated", true)
                .containsEntry("resultPresent", true));
    }

    @Test
    void completeEvidenceRedactionPreservesAllValuesAndMasksCredentials() {
        String fullValue = "head-" + "x".repeat(20_000) + "-tail";
        Object redacted = ToolLogSummarizer.redactComplete(Map.of(
            "rows", java.util.stream.IntStream.range(0, 100)
                .mapToObj(index -> Map.of("index", index, "payload", fullValue + index))
                .toList(),
            "password", "must-not-leak"
        ));

        assertThat(redacted).isInstanceOfSatisfying(Map.class, result -> {
            assertThat(result.get("password")).isEqualTo("***");
            assertThat(result.get("rows")).isInstanceOfSatisfying(List.class, rows -> {
                assertThat(rows).hasSize(100);
                assertThat(rows.get(99)).isInstanceOfSatisfying(Map.class, row ->
                    assertThat(row.get("payload")).isEqualTo(fullValue + 99));
            });
        });
    }
}
