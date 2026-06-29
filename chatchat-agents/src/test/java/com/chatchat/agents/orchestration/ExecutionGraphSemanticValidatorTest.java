package com.chatchat.agents.orchestration;

import com.chatchat.agents.runtime.plan.InterpretationPlanRuntime;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ExecutionGraphSemanticValidatorTest {

    private final ExecutionGraphSemanticValidator validator = new ExecutionGraphSemanticValidator();

    @Test
    void validatesSqlMetadataExecutionPath() {
        Map<String, Object> state = validator.validate(
            "分析 t_ad_dict_entr_supn 表结构",
            executionResult(true),
            sqlMetadataFact(true)
        );

        assertThat(state)
            .containsEntry("schemaVersion", ExecutionGraphSemanticValidator.SCHEMA_VERSION)
            .containsEntry("passed", true)
            .containsEntry("globalIntentMatch", true)
            .containsEntry("graphSucceeded", true)
            .containsEntry("finalAnswerReached", true);
        Map<?, ?> sqlPath = (Map<?, ?>) state.get("sqlMetadataPath");
        assertThat(sqlPath.get("valid")).isEqualTo(true);
        assertThat(sqlPath.get("sourceStepId")).isEqualTo(3);
    }

    @Test
    void rejectsWhenPriorExecutionPathFailed() {
        Map<String, Object> state = validator.validate(
            "分析 t_ad_dict_entr_supn 表结构",
            executionResult(false),
            sqlMetadataFact(true)
        );

        assertThat(state)
            .containsEntry("passed", false);
        Map<?, ?> sqlPath = (Map<?, ?>) state.get("sqlMetadataPath");
        assertThat(sqlPath.get("valid")).isEqualTo(false);
        assertThat(sqlPath.get("priorStepsSucceeded")).isEqualTo(false);
    }

    @Test
    void rejectsWhenRequestedTableDoesNotMatchResolvedTable() {
        Map<String, Object> state = validator.validate(
            "分析 t_other 表结构",
            executionResult(true),
            sqlMetadataFact(false)
        );

        assertThat(state)
            .containsEntry("passed", false)
            .containsEntry("globalIntentMatch", false);
    }

    private InterpretationPlanRuntime.ExecutionResult executionResult(boolean priorStepsSucceed) {
        return new InterpretationPlanRuntime.ExecutionResult(
            priorStepsSucceed ? "completed" : "failed",
            priorStepsSucceed,
            false,
            priorStepsSucceed ? null : "asset discovery failed",
            "done",
            List.of(
                step(1, "mcp_tool", "mcp_chatchat_mcp_server_sql_datasource_asset_query", priorStepsSucceed),
                step(2, "mcp_tool", "mcp_chatchat_mcp_server_sql_datasource_template_query", true),
                step(3, "mcp_tool", "mcp_chatchat_mcp_server_sql_query_execute", true),
                step(4, "final_answer", "", true)
            ),
            Map.of(),
            100
        );
    }

    private InterpretationPlanRuntime.StepExecution step(Integer id, String actionType, String toolName, boolean success) {
        return new InterpretationPlanRuntime.StepExecution(
            id,
            actionType,
            toolName,
            success,
            Map.of(),
            success ? null : "failed",
            null,
            "final_answer".equals(actionType) ? "done" : null,
            10
        );
    }

    private Map<String, Object> sqlMetadataFact(boolean tableMatches) {
        return Map.of(
            "schemaVersion", SqlMetadataAnswerRenderer.FACT_SCHEMA_VERSION,
            "stepId", 3,
            "table", "t_ad_dict_entr_supn",
            "requestedTable", tableMatches ? "t_ad_dict_entr_supn" : "t_other",
            "semanticGatePassed", tableMatches
        );
    }
}
