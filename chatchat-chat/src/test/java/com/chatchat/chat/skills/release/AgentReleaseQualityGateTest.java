package com.chatchat.chat.skills.release;

import com.chatchat.chat.skills.SkillDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AgentReleaseQualityGateTest {

    private final AgentReleaseQualityGate gate = new AgentReleaseQualityGate();

    @Test
    void acceptsMaterializedExecutionContract() {
        SkillDefinition skill = skill("agent-a", "You are a governed agent", Map.of());
        assertThat(gate.evaluate(skill).passed()).isTrue();
    }

    @Test
    void rejectsMissingInstructionsAndWorkflowMaterialization() {
        SkillDefinition skill = skill("agent-a", null, null);
        AgentReleaseQualityReport report = gate.evaluate(skill);
        assertThat(report.passed()).isFalse();
        assertThat(report.checks()).anyMatch(check -> !check.passed() && check.id().equals("execution_contract"));
    }

    private SkillDefinition skill(String id, String prompt, Map<String, Object> workflow) {
        return new SkillDefinition(id, "Agent A", null, List.of(), List.of(), "agent_chat", null,
            prompt, null, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null,
            workflow, null, null, List.of(), "draft", false);
    }
}
