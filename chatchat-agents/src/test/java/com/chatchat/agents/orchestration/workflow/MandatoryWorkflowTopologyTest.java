package com.chatchat.agents.orchestration.workflow;

import com.chatchat.agents.orchestration.tool.AgentToolNameResolver;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class MandatoryWorkflowTopologyTest {

    private final AgentToolNameResolver names = new AgentToolNameResolver();
    private final MandatoryWorkflowTopology topology =
        new MandatoryWorkflowTopology(names, new AgentWorkflowToolResolver(names));

    @Test
    void onlyDeclaredDependencyBlocksRecoveryOfItsConsumer() {
        List<Map<String, Object>> dag = List.of(
            Map.of("tool", "source_provider", "dependsOnTools", List.of()),
            Map.of("tool", "dependent_provider", "dependsOnTools", List.of("source_provider")),
            Map.of("tool", "independent_provider", "dependsOnTools", List.of())
        );

        assertThat(topology.unresolvedDependencies(
            dag, Map.of(), "dependent_provider", Set.of()))
            .containsExactly("source_provider");
        assertThat(topology.unresolvedDependencies(
            dag, Map.of(), "independent_provider", Set.of()))
            .isEmpty();
        assertThat(topology.unresolvedDependencies(
            dag, Map.of(), "dependent_provider", Set.of("mcp_source_provider")))
            .isEmpty();
    }
}
