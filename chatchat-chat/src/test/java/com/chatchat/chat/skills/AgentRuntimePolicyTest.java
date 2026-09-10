package com.chatchat.chat.skills;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AgentRuntimePolicyTest {

    @Test
    void projectsAndBoundsRuntimePolicyFromWorkflowConfiguration() {
        AgentRuntimePolicy policy = AgentRuntimePolicy.from(Map.of("runtimePolicy", Map.of(
            "knowledgeTokenBudget", 9000,
            "knowledgeSkillTimeoutMs", 50
        )), 1500);

        assertThat(policy.knowledgeTokenBudget()).isEqualTo(4000);
        assertThat(policy.knowledgeSkillTimeoutMs()).isEqualTo(100L);
    }
}
