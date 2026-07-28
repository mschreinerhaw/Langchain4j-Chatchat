package com.chatchat.agents.orchestration;

import com.chatchat.agents.runtime.ToolRuntimeService;
import com.chatchat.agents.runtime.batch.ToolCallBatchResult;
import com.chatchat.agents.runtime.batch.ToolCallResult;
import com.chatchat.agents.tool.ToolRegistry;
import com.chatchat.common.config.ModelsConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class AgentOrchestratorDagEvidenceTest {

    @Test
    @SuppressWarnings("unchecked")
    void preservesEveryReturnedRowForEveryBatchChildInDagDecisionInput() {
        AgentOrchestrator orchestrator = new AgentOrchestrator(
            null,
            mock(ToolRegistry.class),
            mock(ToolRuntimeService.class),
            new ObjectMapper(),
            new ModelsConfig()
        );
        ToolCallBatchResult batch = new ToolCallBatchResult(
            "diagnostic-step-3",
            "SEQUENTIAL",
            "start",
            "end",
            "SUCCESS",
            new ToolCallBatchResult.Summary(2, 2, 0, 0, 0, 2),
            List.of(
                new ToolCallResult(
                    "instance_status", "sql_query_execute", "ORACLE_INSTANCE_STATUS", "asset-1",
                    "SUCCESS", 10, "evidence-1",
                    Map.of(
                        "columns", List.of("INSTANCE_NAME", "STATUS"),
                        "rowCount", 1,
                        "rows", List.of(Map.of("INSTANCE_NAME", "oraclewind", "STATUS", "OPEN"))
                    ),
                    Map.of()
                ),
                new ToolCallResult(
                    "sessions", "sql_query_execute", "ORACLE_SESSION_OVERVIEW", "asset-1",
                    "SUCCESS", 12, "evidence-2",
                    Map.of(
                        "data", Map.of(
                            "columns", List.of("TOTAL_SESSIONS"),
                            "rowCount", 4,
                            "rows", List.of(
                                Map.of("TOTAL_SESSIONS", 18),
                                Map.of("TOTAL_SESSIONS", 19),
                                Map.of("TOTAL_SESSIONS", 20),
                                Map.of("TOTAL_SESSIONS", 21)
                            )
                        )
                    ),
                    Map.of()
                )
            )
        );

        Map<String, Object> snapshot = orchestrator.dagDecisionOutputSnapshot(batch);
        List<Map<String, Object>> results = (List<Map<String, Object>>) snapshot.get("results");

        assertThat(results).hasSize(2);
        Map<String, Object> instanceOutput = (Map<String, Object>) results.get(0).get("output");
        assertThat(instanceOutput)
            .containsEntry("rowCount", 1);
        List<Map<String, Object>> instanceRows =
            (List<Map<String, Object>>) instanceOutput.get("rows");
        assertThat(instanceRows).hasSize(1);
        assertThat(instanceRows.get(0)).containsEntry("STATUS", "OPEN");

        Map<String, Object> sessionOutput = (Map<String, Object>) results.get(1).get("output");
        Map<String, Object> sessionData = (Map<String, Object>) sessionOutput.get("data");
        assertThat(sessionData).containsEntry("rowCount", 4);
        List<Map<String, Object>> sessionRows =
            (List<Map<String, Object>>) sessionData.get("rows");
        assertThat(sessionRows).hasSize(4);
        assertThat(sessionRows.get(3)).containsEntry("TOTAL_SESSIONS", 21);
        assertThat(snapshot.toString())
            .doesNotContain("sampleRows", "previewTruncated");
    }
}
