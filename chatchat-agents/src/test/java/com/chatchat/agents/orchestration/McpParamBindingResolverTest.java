package com.chatchat.agents.orchestration;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class McpParamBindingResolverTest {

    private final McpParamBindingResolver resolver = new McpParamBindingResolver();

    @Test
    void enrichesTemplateQueryWithBilingualMetadataSignalsWhenModelOnlyProvidedChineseIntent() {
        Map<String, Object> result = resolver.resolve(
            "mcp_chatchat_mcp_server_template_query",
            null,
            Map.of(
                "finalDecision", "database",
                "confidence", 0.95,
                "filters", Map.of(
                    "assetName", "local_mysql",
                    "intent", "\u67e5\u8be2 user_info_file \u8868\u5143\u6570\u636e\u4fe1\u606f"
                )
            ),
            "\u67e5\u8be2 user_info_file \u8868\u5143\u6570\u636e\u4fe1\u606f"
        );

        Map<?, ?> filters = (Map<?, ?>) result.get("filters");
        assertThat(strings(filters.get("bilingualIntent")))
            .contains("\u8868\u5143\u6570\u636e", "table metadata", "table schema", "user_info_file");
        assertThat(strings(filters.get("intentAliases")))
            .contains("\u8868\u7ed3\u6784", "table metadata", "SHOW CREATE TABLE", "DESCRIBE TABLE");
        assertThat(strings(filters.get("keywords")))
            .contains("INFORMATION_SCHEMA", "SHOW COLUMNS", "COLUMNS", "user_info_file", "\u5b57\u6bb5");
        assertThat(filters.get("intentZh")).isEqualTo("\u8868\u5143\u6570\u636e");
        assertThat(filters.get("intentEn")).isEqualTo("table metadata");
    }

    @Test
    void enrichesTemplateQueryWithEnglishInnoDbCommandSignalsWhenModelOnlyProvidedChineseIntent() {
        Map<String, Object> result = resolver.resolve(
            "mcp_chatchat_mcp_server_template_query",
            null,
            Map.of(
                "finalDecision", "database",
                "confidence", 0.95,
                "filters", Map.of(
                    "assetName", "local_mysql",
                    "intent", "\u5206\u6790InnoDB\u72b6\u6001\uff0c\u5305\u62ec\u9501\u7b49\u5f85\u3001\u6b7b\u9501\u548c\u7f13\u51b2\u6c60"
                )
            ),
            "\u5206\u6790InnoDB\u72b6\u6001\uff0c\u5305\u62ec\u9501\u7b49\u5f85\u3001\u6b7b\u9501\u548c\u7f13\u51b2\u6c60"
        );

        Map<?, ?> filters = (Map<?, ?>) result.get("filters");
        assertThat(strings(filters.get("bilingualIntent")))
            .contains("\u67e5\u8be2InnoDB\u72b6\u6001", "SHOW ENGINE INNODB STATUS", "InnoDB engine status", "lock wait", "deadlock");
        assertThat(strings(filters.get("intentAliases")))
            .contains("\u5206\u6790InnoDB\u72b6\u6001", "SHOW ENGINE INNODB STATUS", "InnoDB status", "deadlock");
        assertThat(strings(filters.get("keywords")))
            .contains("InnoDB", "SHOW ENGINE INNODB STATUS", "transaction", "lock wait", "deadlock", "buffer pool");
        assertThat(filters.get("intentEn")).isEqualTo("SHOW ENGINE INNODB STATUS");
    }

    @Test
    void dedicatedBusinessQueryTemplateToolOverridesMismatchedPlannerTargetKind() {
        Map<String, Object> result = resolver.resolve(
            "mcp_chatchat_mcp_server_business_query_template_search",
            null,
            Map.of(
                "candidates", List.of(Map.of("targetKind", "database", "confidence", 0.9)),
                "finalDecision", "database",
                "filters", Map.of(
                    "intent", "\u5206\u6790\u884c\u60c5\u6570\u636e\u53d1\u751f\u8f83\u5927\u6ce2\u52a8\u65f6\u5f02\u5e38\u63d0\u9192\u6570\u636e"
                ),
                "trace", Map.of("plannerVersion", "v1.1")
            ),
            "\u5206\u6790\u884c\u60c5\u6570\u636e\u53d1\u751f\u8f83\u5927\u6ce2\u52a8\u65f6\u5f02\u5e38\u63d0\u9192\u6570\u636e"
        );

        assertThat(result)
            .containsEntry("targetKind", "business_database_query")
            .containsEntry("finalDecision", "business_database_query")
            .containsEntry("assetType", "database_query");
        assertThat((List<?>) result.get("candidates"))
            .singleElement()
            .satisfies(candidate -> {
                Map<?, ?> candidateMap = (Map<?, ?>) candidate;
                assertThat(candidateMap.get("targetKind")).isEqualTo("business_database_query");
                assertThat(candidateMap.get("confidence")).isEqualTo(0.9);
            });
    }

    private List<String> strings(Object value) {
        if (value instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        return List.of();
    }
}
