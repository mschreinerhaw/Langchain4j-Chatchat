package com.chatchat.agents.orchestration.planning.generation;

import com.chatchat.agents.tool.ToolRegistry;
import com.chatchat.common.skills.DomainSkillRuntimePort;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class AgentPlannerDomainSkillContextTest {

    @Test
    void selectedDomainSkillsDirectlyGuideNormalPlanGeneration() {
        String prompt = builder().build(
            "分析组合风险", "Agent system prompt", List.of(), List.of(), List.of(), List.of(), List.of(),
            false, false, null, null, domainSkillAttributes());

        assertPlanningContext(prompt);
    }

    @Test
    void selectedDomainSkillsSurviveCompactAuthoritativeWorkflowPlanning() {
        Map<String, Object> attributes = new LinkedHashMap<>(domainSkillAttributes());
        attributes.put("authoritativeWorkflowDag", List.of(
            Map.of("id", "risk-data", "tool", "portfolio_risk_query")));

        String prompt = builder().build(
            "分析组合风险", "x".repeat(20_000), List.of(), List.of(), List.of(), List.of(), List.of(),
            false, false, null, null, attributes);

        assertPlanningContext(prompt);
        assertThat(prompt).contains("step risk-data: tool=portfolio_risk_query");
    }

    private AgentPlannerPromptBuilder builder() {
        return new AgentPlannerPromptBuilder(
            mock(ToolRegistry.class), new ObjectMapper(), Clock.systemUTC());
    }

    private Map<String, Object> domainSkillAttributes() {
        return Map.of(DomainSkillRuntimePort.PLANNING_CONTEXT_ATTRIBUTE, Map.of(
            "schemaVersion", "domain_skill_planning.v1",
            "count", 1,
            "skills", List.of(Map.of(
                "id", "skill-risk",
                "name", "证券风险分析",
                "category", "风险管理",
                "instructions", "先核验证券代码，再按交易日拆分数据步骤。"
            ))
        ));
    }

    private void assertPlanningContext(String prompt) {
        assertThat(prompt)
            .contains("Selected domain skills for plan generation")
            .contains("证券风险分析", "风险管理", "先核验证券代码，再按交易日拆分数据步骤。")
            .contains("intent interpretation, task decomposition, tool inputs, validation, and completion criteria")
            .contains("do not authorize unbound tools");
    }
}
