package com.chatchat.tools.workflow;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SqlWorkflowEngineTest {

    private final SqlWorkflowEngine engine = new SqlWorkflowEngine();

    @Test
    void executesDependencyLevelsAndResolvesUpstreamResult() {
        SqlWorkflowNode root = node("BASE", List.of(), List.of());
        SqlWorkflowNode asset = node("ASSET", List.of("BASE"), List.of(new SqlWorkflowParameterMapping(
            "customerNo", "UPSTREAM_RESULT", null, "BASE", "$.rows[0].customer_no", null, true)));
        SqlWorkflowNode risk = node("RISK", List.of("BASE"), List.of());

        SqlWorkflowExecution result = engine.execute(List.of(asset, risk, root), Map.of(), Map.of(), 2,
            (node, parameters) -> "BASE".equals(node.code())
                ? new SqlWorkflowNodeResult(true, Map.of("rows", List.of(Map.of("customer_no", "100001")), "rowCount", 1), null, 2)
                : new SqlWorkflowNodeResult(true, Map.of("rows", List.of(), "rowCount", 0), null, 2));

        assertThat(result.executionLevels()).containsExactly(List.of("BASE"), List.of("ASSET", "RISK"));
        assertThat(result.steps()).hasSize(3);
        assertThat(result.steps().stream().filter(step -> "ASSET".equals(step.node().code())).findFirst().orElseThrow()
            .resolvedParameters()).containsEntry("customerNo", "100001");
        assertThat(result.status()).isEqualTo("SUCCESS");
    }

    @Test
    void rejectsCircularDependencyBeforeExecution() {
        assertThatThrownBy(() -> engine.executionLevels(List.of(
            node("A", List.of("B"), List.of()), node("B", List.of("A"), List.of()))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("circular dependency");
    }

    @Test
    void reportsRequiredParameterResolutionAsNodeFailure() {
        SqlWorkflowNode node = node("A", List.of(), List.of(new SqlWorkflowParameterMapping(
            "customerNo", "USER_INPUT", "customerNo", null, null, null, true)));

        SqlWorkflowExecution result = engine.execute(List.of(node), Map.of(), Map.of(), 1,
            (ignored, parameters) -> new SqlWorkflowNodeResult(true, Map.of(), null, 0));

        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(result.steps().get(0).result().errorMessage()).contains("customerNo");
    }

    private SqlWorkflowNode node(String code,
                                 List<String> dependencies,
                                 List<SqlWorkflowParameterMapping> mappings) {
        return new SqlWorkflowNode(code, code, code, "select 1", 1, dependencies, mappings, Map.of(),
            "STOP", "CONTINUE", 30, 50);
    }
}
