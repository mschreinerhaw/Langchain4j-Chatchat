package com.chatchat.agents.orchestration;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class McpParamBindingResolverTest {

    private final McpParamBindingResolver resolver = new McpParamBindingResolver();

    @Test
    void doesNotInferEnvironmentFromDatabaseAssetProperName() {
        Map<String, Object> result = resolver.resolve(
            "mcp_chatchat_mcp_server_sql_datasource_asset_query",
            null,
            Map.of(
                "finalDecision", "database",
                "confidence", 0.95,
                "filters", Map.of()
            ),
            "\u5206\u6790248\u6d4b\u8bd5\u6570\u636e\u5e93"
        );

        Map<?, ?> filters = (Map<?, ?>) result.get("filters");
        assertThat(filters.containsKey("env")).isFalse();
        assertThat(filters.get("queryTerms"))
            .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST)
            .contains("\u5206\u6790248\u6d4b\u8bd5\u6570\u636e\u5e93");
    }

    @Test
    void infersCanonicalEnvironmentOnlyFromExplicitEnvironmentExpression() {
        Map<String, Object> result = resolver.resolve(
            "mcp_chatchat_mcp_server_sql_datasource_asset_query",
            null,
            Map.of(
                "finalDecision", "database",
                "confidence", 0.95,
                "filters", Map.of()
            ),
            "\u5728TEST\u73af\u5883\u5206\u6790248\u6570\u636e\u5e93"
        );

        Map<?, ?> filters = (Map<?, ?>) result.get("filters");
        assertThat(filters.get("env")).isEqualTo("TEST");
    }

    @Test
    void removesProtocolFieldsFromTemplateDiscoveryFilters() {
        Map<String, Object> result = resolver.resolve(
            "mcp_chatchat_mcp_server_ssh_template_query",
            null,
            Map.of(
                "candidates", List.of(Map.of("targetKind", "host", "confidence", 0.9)),
                "finalDecision", "host",
                "confidence", 0.9,
                "filters", Map.of(
                    "assetName", "TDH scheduler",
                    "env", "DEV",
                    "intent", "list java processes",
                    "trace", Map.of("plannerVersion", "v1.1"),
                    "finalDecision", "host",
                    "filtersSchemaVersion", "target_filters.v1"
                ),
                "trace", Map.of("plannerVersion", "v1.1")
            ),
            "list java processes"
        );

        Map<?, ?> filters = (Map<?, ?>) result.get("filters");
        assertThat(filters.get("assetName")).isEqualTo("TDH scheduler");
        assertThat(filters.get("env")).isEqualTo("DEV");
        assertThat(filters.get("intent")).isEqualTo("list java processes");
        assertThat(filters.containsKey("trace")).isFalse();
        assertThat(filters.containsKey("finalDecision")).isFalse();
        assertThat(filters.containsKey("filtersSchemaVersion")).isFalse();
        assertThat(result.get("trace")).isInstanceOf(Map.class);
    }

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

    @Test
    @SuppressWarnings("unchecked")
    void enrichesAssetDiscoveryRetrievalWithTopTwoIntentCandidatesAndOriginalQuery() {
        Map<String, Object> result = resolver.resolve(
            "mcp_chatchat_mcp_server_ssh_asset_query",
            null,
            Map.of(
                "candidates", List.of(Map.of("targetKind", "host", "confidence", 0.9)),
                "finalDecision", "host",
                "confidence", 0.9,
                "filters", Map.of(
                    "intent", "分析MySQL服务器管理进程信息",
                    "intentCandidates", List.of(
                        Map.of("intent", "Linux service status", "score", 0.61),
                        Map.of("intent", "MySQL服务器管理进程", "score", 0.92),
                        Map.of("intent", "mysqld process status", "score", 0.87)
                    )
                ),
                "trace", Map.of("plannerVersion", "v1.1")
            ),
            "分析MySQL服务器管理进程信息"
        );

        Map<?, ?> filters = (Map<?, ?>) result.get("filters");
        assertThat(strings(filters.get("queryTerms")))
            .containsExactly("MySQL服务器管理进程", "mysqld process status", "分析MySQL服务器管理进程信息");
        assertThat(strings(filters.get("retrievalSignals")))
            .containsExactly("MySQL服务器管理进程", "mysqld process status", "分析MySQL服务器管理进程信息");
        Map<String, Object> intentScoring = (Map<String, Object>) filters.get("intentScoring");
        assertThat(intentScoring)
            .containsEntry("strategy", "threshold_intent_ensemble_plus_original_query")
            .containsEntry("threshold", 0.75);
        assertThat(filters.containsKey("assetName")).isFalse();
    }

    @Test
    @SuppressWarnings("unchecked")
    void keepsAllIntentCandidatesAboveThresholdAndIncludesExpandedQueries() {
        Map<String, Object> result = resolver.resolve(
            "mcp_chatchat_mcp_server_ssh_asset_query",
            null,
            Map.of(
                "candidates", List.of(Map.of("targetKind", "host", "confidence", 0.91)),
                "finalDecision", "host",
                "confidence", 0.91,
                "filters", Map.of(
                    "intent", "kafka消费慢是不是rocksdb写入导致的",
                    "intentCandidates", List.of(
                        Map.of("intent", "Kafka", "score", 0.96, "queries", List.of("consumer lag", "offset commit")),
                        Map.of("intent", "RocksDB", "score", 0.88, "expandedQueries", List.of("write stall", "compaction")),
                        Map.of("intent", "Flink", "score", 0.80, "keywords", List.of("checkpoint", "state backend")),
                        Map.of("intent", "Linux", "score", 0.11, "queries", List.of("iowait"))
                    )
                ),
                "trace", Map.of("plannerVersion", "v1.1")
            ),
            "kafka消费慢是不是rocksdb写入导致的"
        );

        Map<?, ?> filters = (Map<?, ?>) result.get("filters");
        assertThat(strings(filters.get("queryTerms")))
            .contains("Kafka", "consumer lag", "offset commit")
            .contains("RocksDB", "write stall", "compaction")
            .contains("Flink", "checkpoint", "state backend")
            .contains("kafka消费慢是不是rocksdb写入导致的")
            .doesNotContain("Linux", "iowait");
        Map<String, Object> intentScoring = (Map<String, Object>) filters.get("intentScoring");
        assertThat(intentScoring)
            .containsEntry("fallback", "top2_when_no_candidate_reaches_threshold");
    }

    private List<String> strings(Object value) {
        if (value instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        return List.of();
    }
}
