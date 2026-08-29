package com.chatchat.agents.orchestration;

import com.chatchat.agents.orchestration.workflow.AgentWorkflowConfigurationException;
import com.chatchat.agents.orchestration.AgentWorkflowDecisionEngine;

import com.chatchat.agents.tool.ToolRegistry;
import com.chatchat.common.interaction.InteractionToolTrace;
import com.chatchat.common.mcp.capability.McpCapabilityHierarchy;
import com.chatchat.common.mcp.capability.McpCapabilityNode;
import com.chatchat.common.tool.ToolMetadata;
import com.chatchat.common.tool.ToolWorkflowContract;
import com.chatchat.common.tool.ToolWorkflowRole;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentWorkflowDecisionEngineTest {

    private final AgentWorkflowDecisionEngine engine = new AgentWorkflowDecisionEngine();

    @Test
    void parentTraceDoesNotCompleteGovernedPublishedChild() {
        String parent = "mcp_chatchat_mcp_server_api_service_query";
        String child = "mcp_chatchat_mcp_server_customer_service_template_query";
        ToolRegistry registry = mock(ToolRegistry.class);
        when(registry.getAllToolNames()).thenReturn(Set.of(parent, child));
        when(registry.getToolMetadata(parent)).thenReturn(capabilityMetadata(parent, "api_service_query", null));
        when(registry.getToolMetadata(child)).thenReturn(capabilityMetadata(
            child, "customer_service_template_query", "api_service_query"));
        AgentWorkflowDecisionEngine treeAware = new AgentWorkflowDecisionEngine(registry);
        InteractionToolTrace parentTrace = InteractionToolTrace.builder()
            .toolName(parent).success(true).build();

        FinalExecutionDecision decision = treeAware.resolveFinalExecution(
            false, List.of(parent, child), List.of(parentTrace), Map.of());

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.missingMandatoryTools()).containsExactly(child);
    }

    @Test
    void abstractParentIsReplacedByConcreteBusinessImplementationInWorkflowDag() {
        String parent = "mcp_chatchat_mcp_server_api_service_query";
        String child = "mcp_chatchat_mcp_server_customer_service_template_query";
        String execute = "mcp_chatchat_mcp_server_api_template_execute";
        ToolRegistry registry = mock(ToolRegistry.class);
        when(registry.getAllToolNames()).thenReturn(Set.of(parent, child, execute));
        when(registry.getToolMetadata(parent)).thenReturn(capabilityMetadata(parent, "api_service_query", null));
        when(registry.getToolMetadata(child)).thenReturn(capabilityMetadata(
            child, "customer_service_template_query", "api_service_query"));
        when(registry.getWorkflowRole(parent)).thenReturn(ToolWorkflowRole.TEMPLATE_DISCOVERY);
        when(registry.getWorkflowRole(child)).thenReturn(ToolWorkflowRole.TEMPLATE_DISCOVERY);
        when(registry.getWorkflowRole(execute)).thenReturn(ToolWorkflowRole.TEMPLATE_EXECUTION);
        AgentWorkflowDecisionEngine treeAware = new AgentWorkflowDecisionEngine(registry);
        Map<String, Object> workflow = Map.of("steps", List.of(
            Map.of("step", "abstract_query", "tool", parent, "required", true),
            Map.of("step", "customer_query", "tool", child, "required", true),
            Map.of("step", "execute", "tool", execute, "required", true,
                "dependsOn", List.of("abstract_query"))
        ));

        WorkflowMandatoryResolution resolution = treeAware.resolveWorkflowMandatoryTools(
            List.of(parent, child, execute), Map.of("mcpWorkflow", workflow), "customer analysis");

        assertThat(resolution.tools()).containsExactly(child, execute);
        assertThat(resolution.authoritativeDag()).extracting(WorkflowDagNode::toolName)
            .containsExactly(child, execute);
        assertThat(resolution.authoritativeDag().get(1).dependsOnTools()).containsExactly(child);
    }

    @Test
    void abstractParentIsSuppressedForEverySelectedBusinessImplementation() {
        String parent = "mcp_chatchat_mcp_server_api_service_query";
        String customer = "mcp_chatchat_mcp_server_customer_service_template_query";
        String account = "mcp_chatchat_mcp_server_account_service_template_query";
        String execute = "mcp_chatchat_mcp_server_api_template_execute";
        ToolRegistry registry = mock(ToolRegistry.class);
        when(registry.getAllToolNames()).thenReturn(Set.of(parent, customer, account, execute));
        when(registry.getToolMetadata(parent)).thenReturn(capabilityMetadata(parent, "api_service_query", null));
        when(registry.getToolMetadata(customer)).thenReturn(capabilityMetadata(
            customer, "customer_service_template_query", "api_service_query"));
        when(registry.getToolMetadata(account)).thenReturn(capabilityMetadata(
            account, "account_service_template_query", "api_service_query"));
        when(registry.getWorkflowRole(parent)).thenReturn(ToolWorkflowRole.TEMPLATE_DISCOVERY);
        when(registry.getWorkflowRole(customer)).thenReturn(ToolWorkflowRole.TEMPLATE_DISCOVERY);
        when(registry.getWorkflowRole(account)).thenReturn(ToolWorkflowRole.TEMPLATE_DISCOVERY);
        when(registry.getWorkflowRole(execute)).thenReturn(ToolWorkflowRole.TEMPLATE_EXECUTION);
        AgentWorkflowDecisionEngine treeAware = new AgentWorkflowDecisionEngine(registry);
        Map<String, Object> workflow = Map.of("steps", List.of(
            Map.of("step", "abstract_query", "tool", parent, "required", true),
            Map.of("step", "customer_query", "tool", customer, "required", true),
            Map.of("step", "account_query", "tool", account, "required", true),
            Map.of("step", "execute", "tool", execute, "required", true,
                "dependsOn", List.of("abstract_query"))
        ));

        WorkflowMandatoryResolution resolution = treeAware.resolveWorkflowMandatoryTools(
            List.of(parent, customer, account, execute), Map.of("mcpWorkflow", workflow),
            "customer and account analysis");

        assertThat(resolution.tools()).containsExactly(customer, account, execute);
        assertThat(resolution.authoritativeDag()).extracting(WorkflowDagNode::toolName)
            .containsExactly(customer, account, execute)
            .doesNotContain(parent);
        assertThat(resolution.authoritativeDag().get(2).dependsOnTools())
            .containsExactly(customer, account);
    }

    private ToolMetadata capabilityMetadata(String localName, String remoteName, String parentRemoteName) {
        Map<String, Object> node = new java.util.LinkedHashMap<>();
        node.put("serviceId", "chatchat-mcp-server");
        node.put("toolName", localName);
        node.put("relationType", parentRemoteName == null
            ? McpCapabilityNode.RELATION_ROOT : McpCapabilityNode.RELATION_DELEGATES_TO_PARENT);
        if (parentRemoteName != null) node.put("parentToolName", parentRemoteName);
        return ToolMetadata.builder().metadata(Map.of(
            "serviceId", "chatchat-mcp-server",
            "remoteToolName", remoteName,
            McpCapabilityHierarchy.METADATA_KEY, node
        )).build();
    }

    @Test
    void metadataFamilyMatchingCannotSelectTheCurrentStepAsItsOwnPredecessor() {
        String query = "mcp_vendor_server_capability";
        String execute = "mcp_vendor_linux_execute";
        ToolRegistry registry = mock(ToolRegistry.class);
        when(registry.getWorkflowRole(query)).thenReturn(ToolWorkflowRole.TEMPLATE_DISCOVERY);
        when(registry.getWorkflowRole(execute)).thenReturn(ToolWorkflowRole.TEMPLATE_EXECUTION);
        when(registry.getToolMetadata(query)).thenReturn(metadata(
            ToolWorkflowRole.TEMPLATE_DISCOVERY, "mcp.ssh-template.v1"));
        when(registry.getToolMetadata(execute)).thenReturn(metadata(
            ToolWorkflowRole.TEMPLATE_EXECUTION, "mcp.ssh-template.v1"));
        AgentWorkflowDecisionEngine metadataEngine = new AgentWorkflowDecisionEngine(registry);
        Map<String, Object> workflow = Map.of("steps", List.of(
            Map.of("step", 1, "tool", query, "required", true),
            Map.of("step", 2, "tool", execute, "required", true)
        ));

        WorkflowMandatoryResolution result = metadataEngine.resolveWorkflowMandatoryTools(
            List.of(query, execute), Map.of("mcpWorkflow", workflow), "inspect docker server");

        assertThat(result.tools()).containsExactly(query, execute);
        assertThat(result.authoritativeDag().get(0).dependsOnTools()).isEmpty();
        assertThat(result.authoritativeDag().get(1).dependsOnTools()).containsExactly(query);
    }

    private ToolMetadata metadata(ToolWorkflowRole role, String family) {
        return ToolMetadata.builder().metadata(Map.of(
            ToolWorkflowContract.METADATA_KEY,
            ToolWorkflowContract.declaration(role, family, "executionContext")
        )).build();
    }

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
    void capabilityBridgeRepairsDiscoveryBeforeLinuxExecution() {
        String asset = "mcp_chatchat_mcp_server_server_asset_query";
        String query = "mcp_chatchat_mcp_server_server_capability_query";
        String execute = "mcp_chatchat_mcp_server_linux_command_execute";
        Map<String, Object> workflow = Map.of(
            "steps", List.of(
                Map.of("step", 1, "tool", asset, "required", true),
                Map.of("step", 2, "tool", execute, "required", true),
                Map.of("step", 3, "tool", query, "required", true)
            )
        );

        WorkflowMandatoryResolution result = engine.resolveWorkflowMandatoryTools(
            List.of(asset, query, execute), Map.of("mcpWorkflow", workflow), "inspect docker server");

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
