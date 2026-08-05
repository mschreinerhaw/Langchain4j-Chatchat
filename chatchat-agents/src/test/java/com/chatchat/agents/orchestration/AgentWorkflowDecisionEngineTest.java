package com.chatchat.agents.orchestration;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AgentWorkflowDecisionEngineTest {

    private final AgentWorkflowDecisionEngine engine = new AgentWorkflowDecisionEngine();

    @Test
    void dependencyGraphOverridesMisorderedAndDuplicateStepNumbers() {
        String asset = "mcp_chatchat_mcp_server_api_asset_query";
        String query = "mcp_chatchat_mcp_server_api_template_query";
        String execute = "mcp_chatchat_mcp_server_api_template_execute";
        Map<String, Object> workflow = Map.of(
            "enabled", true,
            "steps", List.of(
                Map.of("step", 1, "tool", asset, "required", true),
                Map.of("step", 2, "tool", execute, "required", true,
                    "dependsOn", List.of("template_retrieval")),
                Map.of("step", "template_retrieval", "order", 2, "tool", query, "required", true,
                    "dependsOn", List.of(asset))
            )
        );

        WorkflowMandatoryResolution result = engine.resolveWorkflowMandatoryTools(
            List.of(asset, query, execute), Map.of("mcpWorkflow", workflow), "customer profile");

        assertThat(result.tools()).containsExactly(asset, query, execute);
    }

    @Test
    void dependencyCanReferenceNumericStepIdentifier() {
        String query = "mcp_chatchat_mcp_server_api_template_query";
        String execute = "mcp_chatchat_mcp_server_api_template_execute";
        Map<String, Object> workflow = Map.of(
            "steps", List.of(
                Map.of("step", 2, "tool", execute, "required", true, "dependsOn", List.of("1")),
                Map.of("step", 1, "tool", query, "required", true)
            )
        );

        WorkflowMandatoryResolution result = engine.resolveWorkflowMandatoryTools(
            List.of(query, execute), Map.of("mcpWorkflow", workflow), "execute template");

        assertThat(result.tools()).containsExactly(query, execute);
    }
}
