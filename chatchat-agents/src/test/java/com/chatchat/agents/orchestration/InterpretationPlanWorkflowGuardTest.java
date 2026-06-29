package com.chatchat.agents.orchestration;

import com.chatchat.agents.runtime.plan.InterpretationPlan;
import com.chatchat.agents.runtime.plan.InterpretationPlanRuntime;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class InterpretationPlanWorkflowGuardTest {

    private final InterpretationPlanWorkflowGuard guard = new InterpretationPlanWorkflowGuard();

    @Test
    void blocksFinalAnswerWhenConfiguredMcpWorkflowIsIncomplete() {
        InterpretationPlanRuntime.ExecutionResult result = result(List.of(
            execution(1, "mcp_tool", "mcp_chatchat_mcp_server_sql_datasource_asset_query", true),
            execution(2, "final_answer", "", true)
        ));

        InterpretationPlanWorkflowGuard.GuardResult evaluated = guard.evaluate(
            plan(),
            result,
            List.of(
                "mcp_chatchat_mcp_server_sql_datasource_asset_query",
                "mcp_chatchat_mcp_server_sql_datasource_template_query",
                "mcp_chatchat_mcp_server_sql_query_execute"
            ),
            List.of()
        );

        assertThat(evaluated.allowed()).isFalse();
        assertThat(evaluated.code()).isEqualTo("mcp_workflow_incomplete");
        assertThat(evaluated.missingRequiredTools())
            .containsExactly(
                "mcp_chatchat_mcp_server_sql_datasource_template_query",
                "mcp_chatchat_mcp_server_sql_query_execute"
            );
    }

    @Test
    void allowsFinalAnswerWhenConfiguredMcpWorkflowCompletedAndFinalWasLast() {
        InterpretationPlanRuntime.ExecutionResult result = result(List.of(
            execution(1, "mcp_tool", "mcp_chatchat_mcp_server_sql_datasource_asset_query", true),
            execution(2, "mcp_tool", "mcp_chatchat_mcp_server_sql_datasource_template_query", true),
            execution(3, "mcp_tool", "mcp_chatchat_mcp_server_sql_query_execute", true),
            execution(4, "final_answer", "", true)
        ));

        InterpretationPlanWorkflowGuard.GuardResult evaluated = guard.evaluate(
            plan(),
            result,
            List.of(
                "sql_datasource_asset_query",
                "sql_datasource_template_query",
                "sql_query_execute"
            ),
            List.of()
        );

        assertThat(evaluated.allowed()).isTrue();
        assertThat(evaluated.code()).isEqualTo("mcp_workflow_complete");
        assertThat(evaluated.missingRequiredTools()).isEmpty();
    }

    @Test
    void blocksWhenFinalAnswerIsNotLastExecutedStep() {
        InterpretationPlanRuntime.ExecutionResult result = result(List.of(
            execution(1, "mcp_tool", "document_search", true),
            execution(2, "final_answer", "", true),
            execution(3, "mcp_tool", "web_search", true)
        ));

        InterpretationPlanWorkflowGuard.GuardResult evaluated = guard.evaluate(
            plan(),
            result,
            List.of("document_search", "web_search"),
            List.of()
        );

        assertThat(evaluated.allowed()).isFalse();
        assertThat(evaluated.code()).isEqualTo("final_answer_not_last_step");
    }

    @Test
    void doesNotInventMandatoryToolsWhenWorkflowIsNotConfigured() {
        InterpretationPlanRuntime.ExecutionResult result = result(List.of(
            execution(1, "mcp_tool", "mcp_chatchat_mcp_server_sql_datasource_asset_query", true),
            execution(2, "final_answer", "", true)
        ));

        InterpretationPlanWorkflowGuard.GuardResult evaluated = guard.evaluate(plan(), result, List.of(), List.of());

        assertThat(evaluated.allowed()).isTrue();
        assertThat(evaluated.code()).isEqualTo("no_mandatory_workflow_configured");
    }

    @Test
    void countsWorkflowToolsCompletedBeforeCurrentDag() {
        InterpretationPlanRuntime.ExecutionResult result = result(List.of(
            execution(2, "mcp_tool", "mcp_web_search", true),
            execution(3, "final_answer", "", true)
        ));

        InterpretationPlanWorkflowGuard.GuardResult evaluated = guard.evaluate(
            plan(),
            result,
            List.of("document_search", "mcp_web_search"),
            List.of("document_search")
        );

        assertThat(evaluated.allowed()).isTrue();
        assertThat(evaluated.code()).isEqualTo("mcp_workflow_complete");
    }

    private InterpretationPlan plan() {
        return new InterpretationPlan(
            "1.0",
            new InterpretationPlan.Intent("sql_analysis", "Analyze table metadata", "low"),
            new InterpretationPlan.Context(List.of(), List.of(), List.of(), List.of()),
            new InterpretationPlan.Plan(List.of()),
            new InterpretationPlan.ExecutionPolicy(5, false, List.of(), List.of(), 30000),
            null
        );
    }

    private InterpretationPlanRuntime.ExecutionResult result(List<InterpretationPlanRuntime.StepExecution> steps) {
        return new InterpretationPlanRuntime.ExecutionResult(
            "completed",
            true,
            false,
            null,
            "done",
            steps,
            Map.of(),
            10L
        );
    }

    private InterpretationPlanRuntime.StepExecution execution(Integer stepId,
                                                              String actionType,
                                                              String toolName,
                                                              boolean success) {
        return new InterpretationPlanRuntime.StepExecution(
            stepId,
            actionType,
            toolName,
            success,
            Map.of("ok", true),
            null,
            null,
            "final_answer".equals(actionType) ? "done" : null,
            1L
        );
    }
}
