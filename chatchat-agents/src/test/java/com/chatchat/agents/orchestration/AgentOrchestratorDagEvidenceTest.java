package com.chatchat.agents.orchestration;

import com.chatchat.agents.runtime.ToolRuntimeService;
import com.chatchat.agents.runtime.plan.InterpretationPlan;
import com.chatchat.agents.runtime.plan.InterpretationPlanRuntime;
import com.chatchat.agents.runtime.batch.ToolCallBatchResult;
import com.chatchat.agents.runtime.batch.ToolCallResult;
import com.chatchat.agents.tool.ToolRegistry;
import com.chatchat.common.config.ModelsConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class AgentOrchestratorDagEvidenceTest {

    @Test
    void keepsCompleteDagEvidenceWhenContextIsBelowCompressionThreshold() {
        AgentOrchestrator orchestrator = new AgentOrchestrator(
            null,
            mock(ToolRegistry.class),
            mock(ToolRuntimeService.class),
            new ObjectMapper(),
            new ModelsConfig()
        );
        Map<String, Object> output = Map.of(
            "rows", List.of(Map.of("customerId", "C001", "customerName", "张三")),
            "complete", true
        );
        InterpretationPlanRuntime.StepExecution execution =
            new InterpretationPlanRuntime.StepExecution(
                1, "mcp_tool", "customer_search", true, output,
                null, null, null, 10L, Map.of("review", "accepted")
            );
        InterpretationPlanRuntime.DagDecisionRequest request = dagRequest(execution);

        String prompt = orchestrator.buildInterpretationPlanDagDecisionPrompt("查询客户", "", request);

        assertThat(prompt)
            .contains("enabled=false")
            .contains("\"customerId\":\"C001\"")
            .contains("\"customerName\":\"张三\"")
            .doesNotContain("dag_decision_tool_result_projection_v1");
    }

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

    @Test
    void boundsEnterpriseMetadataInDagControllerPromptWhileRetainingRuntimeResult() {
        AgentOrchestrator orchestrator = new AgentOrchestrator(
            null,
            mock(ToolRegistry.class),
            mock(ToolRuntimeService.class),
            new ObjectMapper(),
            new ModelsConfig()
        );
        List<Map<String, Object>> candidates = java.util.stream.IntStream.range(0, 2_000)
            .mapToObj(index -> Map.<String, Object>of(
                "name", "candidate-" + index,
                "metadata", Map.of("description", "large-evidence-marker-" + index + "-" + "x".repeat(300))
            ))
            .toList();
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("schemaVersion", "enterprise_metadata_field_discovery.v1");
        output.put("success", true);
        output.put("targetObject", Map.of("type", "TABLE", "name", "gdp_ads.customer"));
        output.put("sourceSchema", Map.of(
            "fieldCount", 1,
            "fields", List.of(Map.of("fieldName", "cust_num"))
        ));
        output.put("fieldMatches", List.of(Map.of(
            "fieldRef", "cust_num",
            "input", Map.of("fieldName", "cust_num"),
            "standardFields", candidates,
            "termRoots", candidates,
            "dictionaries", candidates
        )));
        output.put("evidenceObjects", candidates);

        InterpretationPlanRuntime.StepExecution execution =
            new InterpretationPlanRuntime.StepExecution(
                1,
                "mcp_tool",
                "mcp_chatchat_mcp_server_enterprise_metadata_search",
                true,
                output,
                null,
                null,
                null,
                10L,
                Map.of("resolvedInput", output)
            );
        InterpretationPlanRuntime.DagDecisionRequest request = dagRequest(execution);

        String prompt = orchestrator.buildInterpretationPlanDagDecisionPrompt(
            "设计客户信息表",
            "",
            request
        );

        assertThat(output.toString()).contains("large-evidence-marker-1999");
        assertThat(prompt)
            .contains("enabled=true")
            .contains("context_compression_envelope.v1")
            .contains("CONTEXT_TOKEN_BUDGET")
            .contains("rawEvidenceUnchanged")
            .contains("runtime_step_record_and_tool_trace")
            .doesNotContain("large-evidence-marker-1999");
        assertThat(prompt.length()).isLessThan(100_000);

        Map<String, Object> envelope = orchestrator.dagDecisionModelOutputSnapshot(execution);
        Map<String, Object> compression = (Map<String, Object>) envelope.get("compression");
        assertThat(compression)
            .containsEntry("trigger", "CONTEXT_TOKEN_BUDGET")
            .containsEntry("rawEvidenceUnchanged", true);
        assertThat(((Number) compression.get("beforeTokens")).longValue())
            .isGreaterThan(((Number) compression.get("afterTokens")).longValue());
        assertThat(execution.output()).isSameAs(output);
    }

    private InterpretationPlanRuntime.DagDecisionRequest dagRequest(
        InterpretationPlanRuntime.StepExecution execution
    ) {
        InterpretationPlan plan = new InterpretationPlan(
            "1.0",
            null,
            null,
            new InterpretationPlan.Plan(List.of(
                new InterpretationPlan.Step(1, "mcp_tool", execution.toolName(), Map.of(), List.of(), null, null),
                new InterpretationPlan.Step(2, "final_answer", null, Map.of(), List.of(1), null, null)
            )),
            null,
            null
        );
        return new InterpretationPlanRuntime.DagDecisionRequest(
            plan,
            new LinkedHashSet<>(List.of(2)),
            Map.of(1, execution),
            List.of(execution),
            new LinkedHashSet<>(List.of(1)),
            2,
            "interpretation_execution_v1",
            "trace-1",
            null
        );
    }
}
