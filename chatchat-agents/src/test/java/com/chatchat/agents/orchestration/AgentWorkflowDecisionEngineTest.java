package com.chatchat.agents.orchestration;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
        assertThat(result.authoritativeDag()).extracting(WorkflowDagNode::toolName)
            .containsExactly(asset, query, execute);
        assertThat(result.authoritativeDag().get(1).dependsOnTools()).containsExactly(asset);
        assertThat(result.authoritativeDag().get(2).dependsOnTools()).containsExactly(query);
    }

    @Test
    void templateProtocolRepairsMissingDependenciesBeforePlannerValidation() {
        String asset = "mcp_chatchat_mcp_server_api_asset_query";
        String query = "mcp_chatchat_mcp_server_api_template_query";
        String execute = "mcp_chatchat_mcp_server_api_template_execute";
        Map<String, Object> workflow = Map.of(
            "steps", List.of(
                Map.of("step", 1, "tool", asset, "required", true),
                Map.of("step", 2, "tool", execute, "required", true),
                Map.of("step", 3, "tool", query, "required", true)
            )
        );

        WorkflowMandatoryResolution result = engine.resolveWorkflowMandatoryTools(
            List.of(asset, query, execute), Map.of("mcpWorkflow", workflow), "customer profile");

        assertThat(result.tools()).containsExactly(asset, query, execute);
        assertThat(result.authoritativeDag().get(1).dependsOnTools()).containsExactly(asset);
        assertThat(result.authoritativeDag().get(2).dependsOnTools()).containsExactly(query);
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

    @Test
    void rejectsAmbiguousNumericStepIdentifier() {
        Map<String, Object> workflow = Map.of(
            "steps", List.of(
                Map.of("step", 1, "tool", "load_customer", "required", true),
                Map.of("step", 1, "tool", "load_account", "required", true),
                Map.of("step", 2, "tool", "build_report", "required", true,
                    "dependsOn", List.of("1"))
            )
        );

        assertThatThrownBy(() -> engine.resolveWorkflowMandatoryTools(
            List.of("load_customer", "load_account", "build_report"),
            Map.of("mcpWorkflow", workflow), "build report"))
            .isInstanceOf(AgentWorkflowConfigurationException.class)
            .hasMessageContaining("WORKFLOW_DEPENDENCY_AMBIGUOUS")
            .hasMessageContaining("use a unique id or name");
    }

    @Test
    void rejectsUnresolvedDependencyInsteadOfIgnoringIt() {
        Map<String, Object> workflow = Map.of(
            "steps", List.of(
                Map.of("id", "report", "tool", "build_report", "required", true,
                    "dependsOn", List.of("missing_input"))
            )
        );

        assertThatThrownBy(() -> engine.resolveWorkflowMandatoryTools(
            List.of("build_report"), Map.of("mcpWorkflow", workflow), "build report"))
            .isInstanceOf(AgentWorkflowConfigurationException.class)
            .hasMessageContaining("WORKFLOW_DEPENDENCY_UNRESOLVED")
            .hasMessageContaining("missing_input");
    }

    @Test
    void rejectsDependencyCycleInsteadOfFallingBackToNumericOrder() {
        Map<String, Object> workflow = Map.of(
            "steps", List.of(
                Map.of("id", "load", "tool", "load_data", "required", true,
                    "dependsOn", List.of("report")),
                Map.of("id", "report", "tool", "build_report", "required", true,
                    "dependsOn", List.of("load"))
            )
        );

        assertThatThrownBy(() -> engine.resolveWorkflowMandatoryTools(
            List.of("load_data", "build_report"), Map.of("mcpWorkflow", workflow), "build report"))
            .isInstanceOf(AgentWorkflowConfigurationException.class)
            .hasMessageContaining("WORKFLOW_DEPENDENCY_CYCLE");
    }

    @Test
    void doesNotTreatSourcePositionAsAnImplicitStepIdentifier() {
        Map<String, Object> workflow = Map.of(
            "steps", List.of(
                Map.of("tool", "load_data", "required", true),
                Map.of("tool", "build_report", "required", true,
                    "dependsOn", List.of("1"))
            )
        );

        assertThatThrownBy(() -> engine.resolveWorkflowMandatoryTools(
            List.of("load_data", "build_report"), Map.of("mcpWorkflow", workflow), "build report"))
            .isInstanceOf(AgentWorkflowConfigurationException.class)
            .hasMessageContaining("WORKFLOW_DEPENDENCY_UNRESOLVED");
    }
}
