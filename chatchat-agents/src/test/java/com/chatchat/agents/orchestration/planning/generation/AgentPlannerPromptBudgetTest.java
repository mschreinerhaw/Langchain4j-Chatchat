package com.chatchat.agents.orchestration.planning.generation;

import com.chatchat.agents.tool.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class AgentPlannerPromptBudgetTest {

    @Test
    void authoritativeWorkflowUsesCompactModelFacingContract() {
        AgentPlannerPromptBuilder builder = new AgentPlannerPromptBuilder(
            mock(ToolRegistry.class), new ObjectMapper(), Clock.systemUTC());
        List<String> tools = List.of("data_query", "sql_execute", "web_search");
        Map<String, Object> attributes = Map.of(
            "authoritativeWorkflowDag", List.of(
                Map.of("id", "discover", "tool", "data_query"),
                Map.of("id", "execute", "tool", "sql_execute", "dependsOnTools", List.of("data_query")),
                Map.of("id", "search", "tool", "web_search")
            ),
            "agentRuntimeEnvironment", "DEV"
        );

        String prompt = builder.build(
            "分析最新数据", null, tools, List.of("x".repeat(40_000)),
            List.of(), List.of(), tools, true, false, null, null, attributes);

        assertThat(prompt)
            .contains("Compact InterpretationPlan shape", "complete value retained by Runtime")
            .contains("step discover: tool=data_query", "step execute: tool=sql_execute")
            .doesNotContain("InterpretationPlan JSON Schema:")
            .hasSizeLessThan(24_000);
    }
}
