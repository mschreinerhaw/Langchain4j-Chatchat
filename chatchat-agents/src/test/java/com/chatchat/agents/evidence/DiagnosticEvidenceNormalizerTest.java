package com.chatchat.agents.evidence;

import com.chatchat.agents.runtime.batch.ToolCallBatchResult;
import com.chatchat.agents.runtime.batch.ToolCallResult;
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
}
