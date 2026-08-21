package com.chatchat.agents.runtime.batch;

import com.chatchat.common.tool.ToolMetadata;
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

    @Test
    void keepsPythonDiscoveryOutsideBatchAndAdmitsNativePythonExecutor() {
        assertThat(ToolCallBatchSchema.supports("python_analysis_query")).isFalse();
        assertThat(ToolCallBatchSchema.supports("python_template_execute")).isTrue();
        assertThat(ToolCallBatchSchema.augment("python_template_execute", Map.of("type", "object")))
            .containsKey("x-chatchat-batch");
    }

    @Test
    void admitsArbitraryExecutorThroughDeclaredCapabilityInsteadOfToolName() {
        ToolMetadata metadata = ToolMetadata.builder()
            .id("tenant_operation_gateway")
            .metadata(Map.of("capabilities", List.of("template_execution", "batch_execution")))
            .build();

        assertThat(ToolCallBatchSchema.supports("tenant_operation_gateway", metadata)).isTrue();
        assertThat(ToolCallBatchSchema.supports("tenant_operation_gateway")).isFalse();
        assertThat(ToolCallBatchSchema.augmentDeclared(Map.of("type", "object")))
            .containsKey("x-chatchat-batch");
    }

    @Test
    void acceptsCapabilityFromSchemaExtensionAndStructuredCapabilityMap() {
        ToolMetadata schemaDeclared = ToolMetadata.builder()
            .id("schema_executor")
            .metadata(Map.of("inputSchema", Map.of("x-chatchat-batch", Map.of())))
            .build();
        ToolMetadata mapDeclared = ToolMetadata.builder()
            .id("map_executor")
            .metadata(Map.of("capabilities", Map.of(
                "batchExecution", true,
                "templateExecution", true)))
            .build();

        assertThat(ToolCallBatchSchema.supports("schema_executor", schemaDeclared)).isTrue();
        assertThat(ToolCallBatchSchema.supports("map_executor", mapDeclared)).isTrue();
    }

    @Test
    void rejectsBatchTransportDeclarationWithoutTemplateGovernance() {
        ToolMetadata metadata = ToolMetadata.builder()
            .id("unmanaged_batch_gateway")
            .metadata(Map.of("capabilities", List.of("batch_execution")))
            .build();

        assertThat(ToolCallBatchSchema.supports("unmanaged_batch_gateway", metadata)).isFalse();
    }

    @Test
    void capabilityAdmissionDoesNotDependOnGeneratedToolFamilyNamesUnderLoad() {
        for (int index = 0; index < 10_000; index++) {
            String toolName = "tenant_" + index + "_operation_gateway";
            boolean declared = index % 2 == 0;
            ToolMetadata metadata = ToolMetadata.builder()
                .id(toolName)
                .metadata(declared
                    ? Map.of("capabilities", List.of("template_execution", "batch_execution"))
                    : Map.of("capabilities", List.of("single_execution")))
                .build();

            assertThat(ToolCallBatchSchema.supports(toolName, metadata)).isEqualTo(declared);
        }
    }
}
