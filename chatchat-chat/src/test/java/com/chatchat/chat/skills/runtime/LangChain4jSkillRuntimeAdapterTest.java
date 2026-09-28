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
