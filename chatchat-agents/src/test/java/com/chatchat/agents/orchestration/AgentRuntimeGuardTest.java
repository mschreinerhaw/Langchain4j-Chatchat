package com.chatchat.agents.orchestration;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AgentRuntimeGuardTest {

    private final AgentRuntimeGuard guard = new AgentRuntimeGuard(
        3,
        "cancelled",
        "maxSteps",
        "maxToolCalls",
        "timeoutMs",
        "deadlineAt"
    );

    @Test
    void usesWorkflowExecutionStrategyMaxSteps() {
        Map<String, Object> attributes = Map.of(
            "mcpWorkflow", Map.of(
                "executionStrategy", Map.of("maxSteps", 8)
            )
        );

        assertThat(guard.maxSteps(attributes)).isEqualTo(8);
        assertThat(guard.hasConfiguredMaxSteps(attributes)).isTrue();
    }

    @Test
    void supportsAliasesPrecedenceDefaultAndBounds() {
        Map<String, Object> nestedWorkflow = Map.of(
            "mcpWorkflow", Map.of(
                "execution_strategy", Map.of("max_steps", 9)
            )
        );

        assertThat(guard.maxSteps(nestedWorkflow)).isEqualTo(9);
        assertThat(guard.maxSteps(Map.of("maxSteps", 2, "mcpWorkflow", nestedWorkflow))).isEqualTo(2);
        assertThat(guard.maxSteps(Map.of("maxSteps", 100))).isEqualTo(50);
        assertThat(guard.maxSteps(Map.of())).isEqualTo(3);
        assertThat(guard.hasConfiguredMaxSteps(Map.of())).isFalse();
    }
}
