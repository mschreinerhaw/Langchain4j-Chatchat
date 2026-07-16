package com.chatchat.agents.runtime.toolcall;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ToolArgumentCompilerTest {

    private final ToolArgumentCompiler compiler = new ToolArgumentCompiler();

    @Test
    void deterministicallyNormalizesAliasesTypesDatesEnumsAndDefaults() {
        Map<String, Object> schema = Map.of(
            "type", "object",
            "additionalProperties", false,
            "required", List.of("customerId", "startDate", "limit"),
            "properties", Map.of(
                "customerId", Map.of("type", "string", "aliases", List.of("custId")),
                "startDate", Map.of("type", "string", "format", "date"),
                "limit", Map.of("type", "integer"),
                "enabled", Map.of("type", "boolean", "default", true),
                "market", Map.of("type", "string", "enum", List.of("SSE", "SZSE"))
            )
        );

        ToolArgumentCompiler.CompilationResult result = compiler.compile(Map.of(
            "custId", 100086,
            "startDate", "20260715",
            "limit", "30",
            "market", "sse",
            "unknown", "discard"
        ), schema);

        assertThat(result.valid()).isTrue();
        assertThat(result.parameters()).containsAllEntriesOf(Map.of(
            "customerId", "100086",
            "startDate", "2026-07-15",
            "limit", 30,
            "enabled", true,
            "market", "SSE"
        ));
        assertThat(result.parameters()).doesNotContainKey("unknown");
        assertThat(result.repairs()).isNotEmpty();
    }

    @Test
    void returnsStructuredValidationErrorsInsteadOfGuessingMissingOrSemanticValues() {
        Map<String, Object> schema = Map.of(
            "type", "object",
            "required", List.of("customerId", "startDate"),
            "properties", Map.of(
                "customerId", Map.of("type", "string"),
                "startDate", Map.of("type", "string", "format", "date")
            )
        );

        ToolArgumentCompiler.CompilationResult result = compiler.compile(
            Map.of("startDate", "最近一个月"), schema);

        assertThat(result.valid()).isFalse();
        assertThat(result.status()).isEqualTo("INVALID_TOOL_ARGUMENTS");
        assertThat(result.validationErrors().stream().map(ToolArgumentCompiler.ValidationError::errorCode))
            .contains("INVALID_PARAMETER_TYPE", "REQUIRED_PARAMETER_MISSING");
        assertThat(result.structuredError("database_query", "query_customer_asset"))
            .contains("validationErrors", "customerId", "startDate");
    }
}
