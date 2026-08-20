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

    @Test
    void normalizesLegacyEnterpriseSearchKeywordsToCanonicalQueryTerms() {
        Map<String, Object> schema = Map.of(
            "type", "object",
            "additionalProperties", false,
            "properties", Map.of(
                "queryTerms", Map.of(
                    "type", "array",
                    "items", Map.of("type", "string"),
                    "aliases", List.of("keywords", "keyword", "queries")
                ),
                "limit", Map.of("type", "integer")
            )
        );

        ToolArgumentCompiler.CompilationResult result = compiler.compile(Map.of(
            "keywords", List.of("var_scr_code_info", "变量评分代码信息", "评分代码"),
            "limit", 50
        ), schema);

        assertThat(result.valid()).isTrue();
        assertThat(result.parameters())
            .containsEntry("queryTerms", List.of("var_scr_code_info", "变量评分代码信息", "评分代码"))
            .containsEntry("limit", 50)
            .doesNotContainKey("keywords");
    }

    @Test
    void promotesSchemaRecognizedValuesFromAnUnpublishedEnvelope() {
        Map<String, Object> schema = Map.of(
            "type", "object",
            "additionalProperties", false,
            "properties", Map.of(
                "query", Map.of("type", "string"),
                "queryTerms", Map.of(
                    "type", "array",
                    "items", Map.of("type", "string"),
                    "aliases", List.of("keywords")
                ),
                "locale", Map.of("type", "string"),
                "limit", Map.of("type", "integer")
            )
        );

        ToolArgumentCompiler.CompilationResult result = compiler.compile(Map.of(
            "filters", Map.of(
                "query", "discover enterprise metadata",
                "keywords", List.of("position", "market value"),
                "locale", "zh-CN",
                "unknown", "discard"
            ),
            "limit", 100
        ), schema);

        assertThat(result.valid()).isTrue();
        assertThat(result.parameters())
            .containsEntry("query", "discover enterprise metadata")
            .containsEntry("queryTerms", List.of("position", "market value"))
            .containsEntry("locale", "zh-CN")
            .containsEntry("limit", 100)
            .doesNotContainKeys("filters", "keywords", "unknown");
        assertThat(result.repairs())
            .extracting(ToolArgumentCompiler.Repair::repairCode)
            .contains("NESTED_SOURCE_PROMOTED");
    }

    @Test
    void invalidOverridesFallBackToDefaultsOrAreOmittedWithoutBlockingExecution() {
        Map<String, Object> schema = Map.of(
            "type", "object",
            "required", List.of("pageSize"),
            "properties", Map.of(
                "pageSize", Map.of("type", "integer", "default", 50),
                "market", Map.of("type", "string", "enum", List.of("SSE", "SZSE")),
                "enabled", Map.of("type", "boolean", "default", true)
            )
        );

        ToolArgumentCompiler.CompilationResult result = compiler.compile(Map.of(
            "pageSize", "not-a-number",
            "market", "UNKNOWN",
            "enabled", "not-a-boolean"
        ), schema);

        assertThat(result.valid()).isTrue();
        assertThat(result.parameters())
            .containsEntry("pageSize", 50)
            .containsEntry("enabled", true)
            .doesNotContainKey("market");
        assertThat(result.repairs()).extracting(ToolArgumentCompiler.Repair::repairCode)
            .contains("INVALID_OVERRIDE_DROPPED_DEFAULT_APPLIED",
                "INVALID_OPTIONAL_OVERRIDE_DROPPED");
    }

    @Test
    void recursivelyNormalizesNestedFilterSchema() {
        Map<String, Object> schema = Map.of(
            "type", "object",
            "additionalProperties", false,
            "properties", Map.of(
                "filters", Map.of(
                    "type", "object",
                    "additionalProperties", false,
                    "required", List.of("startDate"),
                    "properties", Map.of(
                        "startDate", Map.of("type", "string", "format", "date", "aliases", List.of("date")),
                        "limit", Map.of("type", "integer"),
                        "queryTerms", Map.of("type", "array", "items", Map.of("type", "string"))
                    )
                )
            )
        );

        ToolArgumentCompiler.CompilationResult result = compiler.compile(Map.of(
            "filters", Map.of(
                "date", "20260814",
                "limit", "10",
                "queryTerms", List.of(100, "行情数据"),
                "unknown", "discard"
            )
        ), schema);

        assertThat(result.valid()).isTrue();
        assertThat(result.parameters().get("filters"))
            .isEqualTo(Map.of(
                "startDate", "2026-08-14",
                "limit", 10,
                "queryTerms", List.of("100", "行情数据")
            ));
    }

    @Test
    void rejectsConflictingAliasValuesAndDoesNotGuessNameSuffixAliases() {
        Map<String, Object> schema = Map.of(
            "type", "object",
            "required", List.of("assetName"),
            "properties", Map.of(
                "assetName", Map.of("type", "string", "aliases", List.of("asset_name"))
            )
        );

        ToolArgumentCompiler.CompilationResult conflicting = compiler.compile(Map.of(
            "assetName", "canonical-asset",
            "asset_name", "different-asset"
        ), schema);
        ToolArgumentCompiler.CompilationResult guessed = compiler.compile(
            Map.of("asset", "must-not-be-used-as-asset-name"), schema);

        assertThat(conflicting.validationErrors())
            .extracting(ToolArgumentCompiler.ValidationError::errorCode)
            .contains("CONFLICTING_PARAMETER_ALIASES");
        assertThat(guessed.valid()).isFalse();
        assertThat(guessed.parameters()).doesNotContainKey("assetName");
    }
}
