package com.chatchat.chat.skills.runtime;

import com.chatchat.agents.runtime.AgentRuntime;
import com.chatchat.runtime.skill.api.resolution.AuthorizedSkillScope;
import com.chatchat.runtime.skill.api.skill.ResolvedSkill;
import com.chatchat.runtime.skill.api.agent.RuntimeAgentExecutionRequest;
import com.chatchat.runtime.skill.api.skill.SkillDescriptor;
import com.chatchat.runtime.skill.api.identity.SkillRoleContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class LangChain4jSkillRuntimeAdapterTest {
    @Test
    void declaredDataIsPassedToAnalysisWithoutToolsOrDocumentRetrieval() {
        AgentRuntime runtime = mock(AgentRuntime.class);
        org.mockito.Mockito.when(runtime.run(org.mockito.ArgumentMatchers.any())).thenReturn(
            com.chatchat.agents.runtime.AgentRunResult.builder().runId("run").answer("analysis").build());
        var requirement = new com.chatchat.runtime.skill.api.skill.SkillDataRequirement("returns", "customer.returns.v1",
            List.of("describe"), false, Map.of());
        var skill = new ResolvedSkill(new SkillDescriptor("skill", "1", "Skill", "", "", "DATABASE",
            "source", "", "", 1D, Map.of()), "instructions", List.of(),
            new com.chatchat.runtime.skill.api.skill.SkillRequirements(List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(requirement)), Map.of());
        var scope = new AuthorizedSkillScope(true, List.of("doc"), List.of("kb"), List.of("sql"),
            List.of(), List.of(), List.of());
        var role = new SkillRoleContext("tenant", "user", List.of("role"), List.of(), Map.of());
        var dataset = new com.chatchat.runtime.skill.api.execution.SkillDataResult(requirement,
            com.chatchat.runtime.skill.api.execution.SkillDataResult.Status.AVAILABLE,
            List.of(Map.of("return", 0.05)), Map.of("evidenceId", "e1"), List.of());
        var request = new RuntimeAgentExecutionRequest("LANGCHAIN4J", "analyze", role, skill, scope, null,
            Map.of(com.chatchat.runtime.skill.application.SkillDataAcquisition.RESULTS, List.of(dataset)));
        new LangChain4jSkillRuntimeAdapter(runtime).execute(request);
        var capture = org.mockito.ArgumentCaptor.forClass(com.chatchat.agents.runtime.AgentRunRequest.class);
        org.mockito.Mockito.verify(runtime).run(capture.capture());
        assertThat(capture.getValue().getQuery()).contains("customer.returns.v1", "e1", "0.05");
        assertThat(capture.getValue().getAvailableTools()).isEmpty();
        assertThat(capture.getValue().getRequiredToolNames()).isEmpty();
        assertThat(capture.getValue().getBoundDocumentIds()).isEmpty();
        assertThat(capture.getValue().getMaxToolCalls()).isZero();
    }

    @Test
    void malformedRuntimeConstraintsFailClosed() {
        AgentRuntime runtime = mock(AgentRuntime.class);
        LangChain4jSkillRuntimeAdapter adapter = new LangChain4jSkillRuntimeAdapter(runtime);
        SkillDescriptor descriptor = new SkillDescriptor("skill", "1", "Skill", "", "", "DATABASE",
            "source", "", "", 1D, Map.of());
        ResolvedSkill skill = new ResolvedSkill(descriptor, "instructions", List.of(), null, Map.of());
        AuthorizedSkillScope scope = new AuthorizedSkillScope(true, List.of(), List.of(), List.of(),
            List.of(), List.of(), List.of());
        SkillRoleContext role = new SkillRoleContext("tenant", "user", List.of("role"), List.of(), Map.of());
        RuntimeAgentExecutionRequest request = new RuntimeAgentExecutionRequest(
            "LANGCHAIN4J", "query", role, skill, scope, null, Map.of("maxSteps", "invalid"));

        var result = adapter.execute(request);

        assertThat(result.status()).isEqualTo("INVALID_RUNTIME_CONSTRAINT");
        verifyNoInteractions(runtime);
    }
}
