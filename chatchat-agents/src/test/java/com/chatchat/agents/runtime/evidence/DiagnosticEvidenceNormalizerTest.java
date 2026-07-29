package com.chatchat.agents.runtime.evidence;

import com.chatchat.agents.runtime.batch.ToolCallBatchResult;
import com.chatchat.agents.runtime.batch.ToolCallResult;
import com.chatchat.agents.runtime.batch.ToolEvidencePolicy;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DiagnosticEvidenceNormalizerTest {

    @Test
    @SuppressWarnings("unchecked")
    void convertsBatchTransportIntoStableDiagnosticEvidence() {
        ToolCallBatchResult batch = new ToolCallBatchResult(
            "oracle-health",
            "SEQUENTIAL",
            "start",
            "end",
            "PARTIAL_SUCCESS",
            new ToolCallBatchResult.Summary(2, 1, 1, 0, 0, 2),
            List.of(
                new ToolCallResult(
                    "instance_status", "sql_query_execute", "ORACLE_INSTANCE_STATUS",
                    "asset-oracle", "SUCCESS", 10, "e-1",
                    Map.of("rows", List.of(Map.of("STATUS", "OPEN"))), Map.of()
                ),
                new ToolCallResult(
                    "tablespace", "sql_query_execute", "ORACLE_TABLESPACE_HEALTH",
                    "asset-oracle", "RESULT_MISSING", 10, "e-2",
                    Map.of("rows", List.of(Map.of("TABLESPACE_NAME", "USERS"))),
                    Map.of("code", "REQUIRED_EVIDENCE_FIELDS_MISSING")
                )
            )
        );

        Object value = new DiagnosticEvidenceNormalizer().normalize(batch);

        assertThat(value).isInstanceOfSatisfying(Map.class, normalized -> {
            assertThat(normalized)
                .containsEntry("contractVersion", DiagnosticEvidenceNormalizer.CONTRACT_VERSION)
                .containsEntry("executionStatus", "PARTIAL_SUCCESS")
                .containsEntry("assessmentStatus", "PRELIMINARY_AVAILABLE")
                .containsEntry("evidenceCoverage", 0.5);
            List<Map<String, Object>> results =
                (List<Map<String, Object>>) normalized.get("results");
            assertThat(results).hasSize(2);
            assertThat(results.get(0))
                .containsEntry("checkId", "instance_status")
                .containsKey("finding");
            assertThat(results.get(1).get("error")).isEqualTo(
                Map.of("code", "REQUIRED_EVIDENCE_FIELDS_MISSING"));
        });
    }

    @Test
    @SuppressWarnings("unchecked")
    void separatesFullExecutionCoverageFromIncompleteHealthQualityAndTimeContext() {
        ToolCallBatchResult batch = new ToolCallBatchResult(
            "oracle-health",
            "SEQUENTIAL",
            "start",
            "end",
            "SUCCESS",
            new ToolCallBatchResult.Summary(3, 3, 0, 0, 0, 3),
            List.of(
                new ToolCallResult(
                    "instance", "sql_query_execute", "ORACLE_INSTANCE_STATUS",
                    "asset-oracle", "SUCCESS", 10, "e-1",
                    Map.of("rows", List.of(Map.of("INSTANCE_NAME", "oraclewind", "STATUS", "OPEN"))),
                    Map.of(),
                    new ToolEvidencePolicy(
                        "availability_health", true, List.of("INSTANCE_NAME", "STATUS"),
                        "POINT_IN_TIME", List.of(), 300)
                ),
                new ToolCallResult(
                    "events", "sql_query_execute", "ORACLE_SYSTEM_EVENTS",
                    "asset-oracle", "SUCCESS", 10, "e-2",
                    Map.of("rows", List.of(Map.of(
                        "EVENT", "db file async I/O submit",
                        "TIME_WAITED_SECONDS", 102173
                    ))),
                    Map.of(),
                    new ToolEvidencePolicy(
                        "performance_health", true, List.of("EVENT", "TIME_WAITED_SECONDS"),
                        "SINCE_INSTANCE_START", List.of("INSTANCE_UPTIME"), 300)
                ),
                new ToolCallResult(
                    "tablespace", "sql_query_execute", "ORACLE_TABLESPACE_SIZE",
                    "asset-oracle", "SUCCESS", 10, "e-3",
                    Map.of("rows", List.of(Map.of("TABLESPACE_NAME", "USERS", "SIZE_MB", 500))),
                    Map.of(),
                    new ToolEvidencePolicy(
                        "capacity_inventory", false,
                        List.of("TABLESPACE_NAME", "USED_PERCENT", "FREE_MB"),
                        "POINT_IN_TIME", List.of(), 300)
                )
            )
        );

        Map<String, Object> normalized =
            (Map<String, Object>) new DiagnosticEvidenceNormalizer().normalize(batch);
        List<Map<String, Object>> results =
            (List<Map<String, Object>>) normalized.get("results");
        DiagnosticEvidenceQuality waitQuality =
            (DiagnosticEvidenceQuality) results.get(1).get("evidenceQuality");
        DiagnosticEvidenceQuality capacityQuality =
            (DiagnosticEvidenceQuality) results.get(2).get("evidenceQuality");

        assertThat(normalized)
            .containsEntry("executionStatus", "SUCCESS")
            .containsEntry("assessmentStatus", "PRELIMINARY_AVAILABLE")
            .containsEntry("evidenceCoverage", 1.0);
        assertThat(waitQuality.assessmentCapability()).isEqualTo("LIMITED");
        assertThat(waitQuality.timeSemantics()).isEqualTo("SINCE_INSTANCE_START");
        assertThat(waitQuality.missingContext()).containsExactly("INSTANCE_UPTIME");
        assertThat(capacityQuality.assessmentCapability()).isEqualTo("LIMITED");
        assertThat(capacityQuality.healthCapability()).isFalse();
        assertThat(capacityQuality.missingMetrics()).containsExactly("USED_PERCENT", "FREE_MB");
    }
}
