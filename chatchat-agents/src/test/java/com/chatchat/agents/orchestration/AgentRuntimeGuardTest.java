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

    @Test
    void onlyExplicitRequestTimeoutCreatesInitialDeadline() {
        long before = System.currentTimeMillis();
        Map<String, Object> attributes = guard.attributesWithDeadline(Map.of(
            "timeoutMs", 10_000,
            "mcpWorkflow", Map.of(
                "executionStrategy", Map.of("latencyBudgetMs", 2_000)
            )
        ));
        long initialDeadline = ((Number) attributes.get("deadlineAt")).longValue();

        assertThat(initialDeadline).isBetween(before + 9_500, before + 10_500);

        assertThat(guard.attributesWithDeadline(Map.of(
            "mcpWorkflow", Map.of(
                "executionStrategy", Map.of("latencyBudgetMs", 2_000)
            )
        ))).doesNotContainKey("deadlineAt");

        assertThat(guard.remainingTimeMs(attributes)).isBetween(9_000L, 10_000L);
    }
}
