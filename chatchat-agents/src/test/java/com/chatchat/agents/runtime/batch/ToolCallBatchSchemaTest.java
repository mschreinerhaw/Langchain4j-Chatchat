package com.chatchat.agents.runtime.batch;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ToolCallBatchSchemaTest {

    @Test
    void augmentsBatchCapableExecutorWithFormalBoundedSchema() {
        Map<String, Object> schema = ToolCallBatchSchema.augment(
            "mcp_chatchat_mcp_server_sql_query_execute",
            Map.of("type", "object", "required", List.of("templateCode"))
        );

        assertThat(schema).containsKeys("anyOf", "x-chatchat-batch");
        @SuppressWarnings("unchecked")
        Map<String, Object> extension = (Map<String, Object>) schema.get("x-chatchat-batch");
        assertThat(extension)
            .containsEntry("executionMode", "SEQUENTIAL")
            .containsEntry("maxCalls", ToolCallBatchSchema.DEFAULT_MAX_CALLS)
            .containsEntry("maxPayloadBytes", ToolCallBatchSchema.DEFAULT_MAX_PAYLOAD_BYTES)
            .containsEntry("nestedBatchAllowed", false);

        @SuppressWarnings("unchecked")
        Map<String, Object> batch = (Map<String, Object>) ((List<?>) schema.get("anyOf")).get(1);
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) batch.get("properties");
        @SuppressWarnings("unchecked")
        Map<String, Object> calls = (Map<String, Object>) properties.get("calls");
        assertThat(calls)
            .containsEntry("minItems", 1)
            .containsEntry("maxItems", ToolCallBatchSchema.DEFAULT_MAX_CALLS);
    }

    @Test
    void leavesNonExecutorSchemaUnchanged() {
        Map<String, Object> original = Map.of("type", "object", "title", "Document search");

        assertThat(ToolCallBatchSchema.augment("document_search", original))
            .isEqualTo(original)
            .doesNotContainKey("x-chatchat-batch");
    }
}
