package com.chatchat.mcpserver.routing;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TargetKindContractTest {

    private final TargetKindRegistry registry = new TargetKindRegistry();

    @Test
    void acceptsValidDatabaseRoutingRequest() {
        TargetKindRegistry.Resolution resolution = registry.resolveForTool(
            "template_query",
            null,
            request("database", 0.82, Map.of("intent", "database status")),
            Map.of("intent", "database status")
        );

        assertThat(resolution.definition().targetKind()).isEqualTo("database");
        assertThat(resolution.definition().assetType()).isEqualTo("sql_datasource");
        assertThat(resolution.decision()).isEqualTo(TargetKindRegistry.DECISION_ACCEPTED);
        assertThat(resolution.trace()).containsEntry("plannerVersion", "v1.0");
    }

    @Test
    void acceptsRoutingCandidateSetWithExplicitFinalDecision() {
        TargetKindRegistry.Resolution resolution = registry.resolveForTool(
            "template_query",
            null,
            candidateRequest("database", Map.of("intent", "database status"),
                candidate("database", 0.81),
                candidate("http", 0.74)),
            Map.of("intent", "database status")
        );

        assertThat(resolution.finalDecision()).isEqualTo("database");
        assertThat(resolution.confidence()).isEqualTo(0.81);
        assertThat(resolution.definition().assetType()).isEqualTo("sql_datasource");
        assertThat(resolution.candidates()).extracting(TargetKindRegistry.RoutingCandidate::targetKind)
            .containsExactly("database", "http");
        assertThat(resolution.candidates().get(0).feasibilityReasons())
            .contains("filters_schema_match", "tool_allowed", "asset_existence_deferred_to_retrieval");
    }

    @Test
    void acceptsDatabaseTemplateRetrievalSignalFilters() {
        TargetKindRegistry.Resolution resolution = registry.resolveForTool(
            "template_query",
            null,
            candidateRequest("database", Map.of(
                    "assetName", "248测试数据库",
                    "intent", "分析当前连接 current connections",
                    "bilingualIntent", List.of("当前连接", "current connections", "连接数", "connection count"),
                    "intentZh", "分析当前连接",
                    "intentEn", "analyze current connections",
                    "intentAliases", List.of("当前连接", "current connections", "processlist", "pg_stat_activity"),
                    "keywords", List.of("SHOW PROCESSLIST", "pg_stat_activity", "Threads_connected", "max_connections")
                ),
                candidate("database", 0.9)),
            Map.of("intent", "database connection diagnostics")
        );

        assertThat(resolution.definition().targetKind()).isEqualTo("database");
        assertThat(resolution.decision()).isEqualTo(TargetKindRegistry.DECISION_ACCEPTED);
    }

    @Test
    void acceptsIntentEnsembleFiltersForHostAssetDiscovery() {
        TargetKindRegistry.Resolution resolution = registry.resolveForTool(
            "asset_query",
            null,
            candidateRequest("host", Map.of(
                    "intent", "分析MySQL服务器管理进程信息",
                    "intentCandidates", List.of(
                        Map.of("intent", "MySQL服务器管理进程", "score", 0.92),
                        Map.of("intent", "mysqld process status", "score", 0.87)
                    ),
                    "queries", List.of("mysqld process", "systemctl status mysql"),
                    "expandedQueries", List.of("ps aux mysqld")
                ),
                candidate("host", 0.9)),
            Map.of("intent", "分析MySQL服务器管理进程信息")
        );

        assertThat(resolution.definition().targetKind()).isEqualTo("host");
        assertThat(resolution.decision()).isEqualTo(TargetKindRegistry.DECISION_ACCEPTED);
    }

    @Test
    void rejectsFinalDecisionOutsideCandidateSet() {
        assertThatThrownBy(() -> registry.resolveForTool(
            "template_query",
            null,
            candidateRequest("database", Map.of("intent", "database status"),
                candidate("host", 0.9),
                candidate("http", 0.8)),
            Map.of("intent", "database status")
        ))
            .isInstanceOf(TargetKindRegistry.TargetKindException.class)
            .extracting(error -> ((TargetKindRegistry.TargetKindException) error).code())
            .isEqualTo("FINAL_DECISION_NOT_IN_CANDIDATES");
    }

    @Test
    void rejectsCandidateSetWithoutFinalDecision() {
        assertThatThrownBy(() -> registry.resolveForTool(
            "template_query",
            null,
            Map.of(
                "candidates", List.of(candidate("database", 0.81), candidate("http", 0.74)),
                "filters", Map.of("intent", "database status"),
                "trace", trace()
            ),
            Map.of("intent", "database status")
        ))
            .isInstanceOf(TargetKindRegistry.TargetKindException.class)
            .extracting(error -> ((TargetKindRegistry.TargetKindException) error).code())
            .isEqualTo("TARGET_KIND_REQUIRED");
    }

    @Test
    void rejectsInfeasibleFinalDecisionForToolPermission() {
        assertThatThrownBy(() -> registry.resolveForTool(
            "asset_query",
            null,
            candidateRequest("document", Map.of(),
                candidate("document", 0.91),
                candidate("database", 0.8)),
            Map.of()
        ))
            .isInstanceOf(TargetKindRegistry.TargetKindException.class)
            .extracting(error -> ((TargetKindRegistry.TargetKindException) error).code())
            .isEqualTo("TARGET_KIND_TOOL_NOT_ALLOWED");
    }

    @Test
    void scoresFeasibleCandidatesAboveHigherConfidenceInfeasibleCandidates() {
        TargetKindRegistry.Resolution resolution = registry.resolveForTool(
            "asset_query",
            null,
            candidateRequest("database", Map.of("database", "orders"),
                candidate("host", 0.99),
                candidate("database", 0.80)),
            Map.of("database", "orders")
        );

        assertThat(resolution.candidates()).extracting(TargetKindRegistry.RoutingCandidate::targetKind)
            .containsExactly("database", "host");
        assertThat(resolution.candidates().get(0).feasible()).isTrue();
        assertThat(resolution.candidates().get(1).feasible()).isFalse();
    }

    @Test
    void rejectsMissingTargetKind() {
        assertThatThrownBy(() -> registry.resolveForTool(
            "template_query",
            null,
            Map.of("confidence", 0.9, "filters", Map.of(), "trace", trace()),
            Map.of()
        ))
            .isInstanceOf(TargetKindRegistry.TargetKindException.class)
            .extracting(error -> ((TargetKindRegistry.TargetKindException) error).code())
            .isEqualTo("TARGET_KIND_REQUIRED");
    }

    @Test
    void rejectsInvalidTargetKind() {
        assertThatThrownBy(() -> registry.resolveForTool(
            "asset_query",
            null,
            request("db_status", 0.9, Map.of()),
            Map.of()
        ))
            .isInstanceOf(TargetKindRegistry.TargetKindException.class)
            .extracting(error -> ((TargetKindRegistry.TargetKindException) error).code())
            .isEqualTo("TARGET_KIND_INVALID");
    }

    @Test
    void returnsReviewDecisionForLowConfidence() {
        TargetKindRegistry.Resolution resolution = registry.resolveForTool(
            "asset_query",
            null,
            request("database", 0.5, Map.of("assetName", "orders-db")),
            Map.of("assetName", "orders-db")
        );

        assertThat(resolution.reviewRequired()).isTrue();
        assertThat(resolution.decision()).isEqualTo(TargetKindRegistry.DECISION_REVIEW_REQUIRED);
    }

    @Test
    void rejectsFilterOutsideSchema() {
        assertThatThrownBy(() -> registry.resolveForTool(
            "asset_query",
            null,
            request("database", 0.9, Map.of("host", "10.0.0.1")),
            Map.of("host", "10.0.0.1")
        ))
            .isInstanceOf(TargetKindRegistry.TargetKindException.class)
            .extracting(error -> ((TargetKindRegistry.TargetKindException) error).code())
            .isEqualTo("FILTER_FIELD_NOT_ALLOWED");
    }

    @Test
    void rejectsMissingTrace() {
        assertThatThrownBy(() -> registry.resolveForTool(
            "asset_query",
            null,
            Map.of("targetKind", "host", "confidence", 0.9, "filters", Map.of()),
            Map.of()
        ))
            .isInstanceOf(TargetKindRegistry.TargetKindException.class)
            .extracting(error -> ((TargetKindRegistry.TargetKindException) error).code())
            .isEqualTo("TRACE_REQUIRED");
    }

    private Map<String, Object> request(String targetKind, double confidence, Map<String, Object> filters) {
        return Map.of(
            "targetKind", targetKind,
            "confidence", confidence,
            "filters", filters,
            "trace", trace()
        );
    }

    private Map<String, Object> candidateRequest(String finalDecision,
                                                 Map<String, Object> filters,
                                                 Map<String, Object>... candidates) {
        return Map.of(
            "finalDecision", finalDecision,
            "candidates", List.of(candidates),
            "filters", filters,
            "trace", trace()
        );
    }

    private Map<String, Object> candidate(String targetKind, double confidence) {
        return Map.of(
            "targetKind", targetKind,
            "confidence", confidence
        );
    }

    private Map<String, Object> trace() {
        return Map.of("plannerVersion", "v1.0", "model", "unit-test");
    }
}
