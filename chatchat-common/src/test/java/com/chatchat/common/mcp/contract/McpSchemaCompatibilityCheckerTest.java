package com.chatchat.common.mcp.contract;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class McpSchemaCompatibilityCheckerTest {

    private final McpSchemaCompatibilityChecker checker = new McpSchemaCompatibilityChecker();

    @Test
    void additiveOptionalParameterAndNewToolRemainCompatible() {
        var baseline = contract("db_query", objectSchema(
            Map.of("sql", Map.of("type", "string")), List.of("sql")));
        var candidate = contract("db_query", objectSchema(
            Map.of(
                "sql", Map.of("type", "string"),
                "timeoutMs", Map.of("type", "integer")),
            List.of("sql")));

        var report = checker.compare(List.of(baseline), List.of(candidate,
            contract("new_search", objectSchema(Map.of(), List.of()))));

        assertThat(report.compatible()).isTrue();
        assertThat(report.breakingChanges()).isEmpty();
    }

    @Test
    void renamedParameterNewRequiredInputAndNarrowedEnumBlockRelease() {
        var baseline = new McpSchemaCompatibilityChecker.ToolContract(
            "db_query",
            objectSchema(Map.of(
                "sql", Map.of("type", "string"),
                "mode", Map.of("type", "string", "enum", List.of("read", "explain"))), List.of("sql")),
            objectSchema(Map.of("rows", Map.of("type", "array")), List.of("rows")));
        var candidate = new McpSchemaCompatibilityChecker.ToolContract(
            "db_query",
            objectSchema(Map.of(
                "query", Map.of("type", "string"),
                "mode", Map.of("type", "string", "enum", List.of("read"))), List.of("query")),
            objectSchema(Map.of(), List.of()));

        var report = checker.compare(List.of(baseline), List.of(candidate));

        assertThat(report.compatible()).isFalse();
        assertThat(report.breakingChanges())
            .extracting(McpSchemaCompatibilityChecker.BreakingChange::code)
            .contains("REQUIRED_INPUT_ADDED", "INPUT_PROPERTY_REMOVED", "ENUM_NARROWED",
                "OUTPUT_PROPERTY_REMOVED", "REQUIRED_OUTPUT_REMOVED");
    }

    @Test
    void removedToolAndTypeChangeBlockRelease() {
        var baseline = List.of(
            contract("web_search", objectSchema(Map.of("query", Map.of("type", "string")), List.of("query"))),
            contract("db_query", objectSchema(Map.of("limit", Map.of("type", "integer")), List.of())));
        var candidate = List.of(
            contract("db_query", objectSchema(Map.of("limit", Map.of("type", "string")), List.of())));

        var report = checker.compare(baseline, candidate);

        assertThat(report.breakingChanges())
            .extracting(McpSchemaCompatibilityChecker.BreakingChange::code)
            .contains("TOOL_REMOVED", "TYPE_CHANGED");
    }

    private McpSchemaCompatibilityChecker.ToolContract contract(String name, Map<String, Object> input) {
        return new McpSchemaCompatibilityChecker.ToolContract(name, input, Map.of());
    }

    private Map<String, Object> objectSchema(Map<String, Object> properties, List<String> required) {
        return Map.of(
            "type", "object",
            "properties", properties,
            "required", required,
            "additionalProperties", false);
    }
}
