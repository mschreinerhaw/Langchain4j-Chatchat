package com.chatchat.agents.orchestration.planning.generation;

import com.chatchat.agents.runtime.context.AgentRoleAnalysisContext;
import com.chatchat.agents.tool.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class AgentPlannerRoleContextTest {

    @Test
    void dagBuildAndTemplateDiscoveryPlanningUseTheRunScopedRoleObjective() {
        Map<String, Object> attributes = new LinkedHashMap<>(Map.of(
            AgentRoleAnalysisContext.RUNTIME_ATTRIBUTE,
            AgentRoleAnalysisContext.create("Risk analyst", "Identify operational risk",
                List.of("Risk review"), List.of("risk"))));
        AgentRoleAnalysisContext.pinToRuntime(attributes, "run-risk", "agent-risk");
        AgentPlannerPromptBuilder builder = new AgentPlannerPromptBuilder(
            mock(ToolRegistry.class), new ObjectMapper(), Clock.systemUTC());

        String prompt = builder.build(
            "analyze", null, List.of(), List.of(), List.of(), List.of(), List.of(),
            false, false, null, null, attributes);

        assertThat(prompt).contains(
            "DAG_BUILD_AND_TEMPLATE_DISCOVERY_PLANNING",
            "Identify operational risk", "Risk review", "run-risk");
    }
}
