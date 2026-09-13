package com.chatchat.agents.runtime.plan.template;

import com.chatchat.agents.runtime.plan.InterpretationPlan;
import com.chatchat.agents.runtime.plan.InterpretationPlanOptimizer;
import com.chatchat.agents.runtime.plan.InterpretationPlanValidator;
import com.chatchat.agents.tool.ToolRegistry;
import com.chatchat.common.tool.ToolMetadata;
import com.chatchat.common.tool.ToolWorkflowContract;
import com.chatchat.common.tool.ToolWorkflowRole;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TemplateWorkflowPluginRegistryTest {

    @Test
    void sqlOwnsRuntimeRoutingButApiDoesNot() {
        TemplateWorkflowPlugin sql = new SqlTemplateWorkflowPlugin();
        TemplateWorkflowPlugin api = new ApiTemplateWorkflowPlugin();

        assertThat(sql.runtimeOwnsExecutionInput("executionContext")).isTrue();
        assertThat(sql.runtimeOwnsExecutionInput("mcp_execution_context")).isTrue();
        assertThat(api.runtimeOwnsExecutionInput("executionContext")).isFalse();
        assertThat(api.runtimeOwnsExecutionInput("parameters")).isTrue();
    }

    @Test
    void apiPluginOwnsOnlyItsAssetWorkflowAndInjectsCanonicalBinding() {
        String apiDiscovery = "opaque-api-discovery";
        String sqlDiscovery = "opaque-sql-discovery";
        String apiExecution = "opaque-api-execution";
        ToolRegistry tools = registry(Map.of(
            apiDiscovery, metadata(ToolWorkflowRole.TEMPLATE_DISCOVERY,
                "mcp.api-template-discovery.v1", "api_service"),
            sqlDiscovery, metadata(ToolWorkflowRole.TEMPLATE_DISCOVERY,
                "mcp.sql-template.v1", "database"),
            apiExecution, metadata(ToolWorkflowRole.TEMPLATE_EXECUTION,
                "mcp.api-template.v1", "api_service")
        ));
        InterpretationPlan source = plan(List.of(
            step(1, apiDiscovery, List.of()),
            step(2, sqlDiscovery, List.of()),
            step(3, apiExecution, List.of())
        ));

        InterpretationPlan optimized = new InterpretationPlanOptimizer(tools).optimize(source).plan();

        InterpretationPlan.Step execution = optimized.steps().stream()
            .filter(step -> apiExecution.equals(step.toolName())).findFirst().orElseThrow();
        InterpretationPlan.Step discovery = optimized.steps().stream()
            .filter(step -> apiDiscovery.equals(step.toolName())).findFirst().orElseThrow();
        assertThat(execution.dependsOn()).containsExactly(discovery.id());
        assertThat(optimized.plan().bindings()).singleElement().satisfies(binding -> {
            assertThat(binding.from()).isEqualTo(discovery.id());
            assertThat(binding.to()).isEqualTo(execution.id());
            assertThat(binding.outputPath()).isEqualTo("$.templates[0].templateId");
            assertThat(binding.inputField()).isEqualTo("$.templateId");
        });
    }

    @Test
    void injectedPluginCanOverrideBindingContractWithoutChangingRuntime() {
        TemplateWorkflowPlugin custom = new TemplateWorkflowPlugin() {
            public String id() { return "custom-asset.v1"; }
            public int priority() { return 100; }
            public boolean supports(TemplateWorkflowTool tool) {
                return tool != null && "vendor.custom.v1".equals(tool.protocolFamily());
            }
            public String templateIdOutputPath() { return "$.selection.id"; }
            public String templateIdInputPath() { return "$.contractId"; }
        };
        String asset = "custom-asset";
        String discovery = "custom-discovery";
        String execution = "custom-execution";
        ToolRegistry tools = registry(Map.of(
            asset, metadata(ToolWorkflowRole.ASSET_DISCOVERY, "vendor.custom.v1", "custom"),
            discovery, metadata(ToolWorkflowRole.TEMPLATE_DISCOVERY, "vendor.custom.v1", "custom"),
            execution, metadata(ToolWorkflowRole.TEMPLATE_EXECUTION, "vendor.custom.v1", "custom")
        ));
        TemplateWorkflowPluginRegistry plugins = new TemplateWorkflowPluginRegistry(List.of(custom));

        InterpretationPlan optimized = new InterpretationPlanOptimizer(tools, plugins)
            .optimize(plan(List.of(step(1, asset, List.of()), step(2, discovery, List.of()),
                step(3, execution, List.of())))).plan();

        assertThat(optimized.plan().bindings()).singleElement().satisfies(binding -> {
            assertThat(binding.outputPath()).isEqualTo("$.selection.id");
            assertThat(binding.inputField()).isEqualTo("$.contractId");
        });
        InterpretationPlanValidator.ValidationResult validation =
            new InterpretationPlanValidator(plugins).validate(
                optimized, tools, Set.of(asset, discovery, execution));
        assertThat(validation.issues()).isEmpty();
    }

    @Test
    void keepsSelectedTemplateGroupChildAndAuthorizesItAsParentCompanion() {
        String parent = "api_template_query";
        String child = "customer_service_template_query";
        String executor = "api_template_execute";
        ToolRegistry tools = registry(Map.of(
            parent, metadata(ToolWorkflowRole.TEMPLATE_DISCOVERY,
                "mcp.api-template-discovery.v1", "api_service"),
            child, metadata(ToolWorkflowRole.TEMPLATE_DISCOVERY,
                "mcp.authorized-template-query.v1", "api_service", Map.of(
                    "parentRemoteToolName", parent)),
            executor, metadata(ToolWorkflowRole.TEMPLATE_EXECUTION,
                "mcp.api-template.v1", "api_service", Map.of(
                    "templateDiscoveryTool", parent))
        ));
        InterpretationPlan source = plan(List.of(
            step(1, child, List.of()),
            step(2, executor, List.of())
        ));

        InterpretationPlanOptimizer optimizer = new InterpretationPlanOptimizer(tools);
        InterpretationPlan optimized = optimizer.optimize(source).plan();

        InterpretationPlan.Step execution = optimized.steps().stream()
            .filter(step -> executor.equals(step.toolName())).findFirst().orElseThrow();
        assertThat(execution.dependsOn()).contains(1);
        assertThat(optimized.steps()).noneMatch(step -> parent.equals(step.toolName()));
        assertThat(optimizer.runtimeCompanionTools(optimized)).containsExactly(child);
    }

    private ToolRegistry registry(Map<String, ToolMetadata> metadata) {
        ToolRegistry registry = mock(ToolRegistry.class);
        when(registry.getAllToolNames()).thenReturn(metadata.keySet());
        metadata.forEach((name, value) -> {
            when(registry.getToolMetadata(name)).thenReturn(value);
            when(registry.getWorkflowRole(name)).thenReturn(
                ToolWorkflowContract.resolveRole(name, value));
        });
        return registry;
    }

    private ToolMetadata metadata(ToolWorkflowRole role, String family, String assetType) {
        return metadata(role, family, assetType, Map.of());
    }

    private ToolMetadata metadata(ToolWorkflowRole role, String family, String assetType,
                                  Map<String, Object> extra) {
        Map<String, Object> values = new java.util.LinkedHashMap<>(extra);
        values.put(ToolWorkflowContract.METADATA_KEY,
            ToolWorkflowContract.declaration(role, family, "input"));
        values.put("assetType", assetType);
        return ToolMetadata.builder().metadata(values).build();
    }

    private InterpretationPlan.Step step(int id, String tool, List<Integer> dependencies) {
        return new InterpretationPlan.Step(id, "mcp_tool", tool, Map.of(), dependencies, null, null);
    }

    private InterpretationPlan plan(List<InterpretationPlan.Step> steps) {
        List<InterpretationPlan.Step> executable = new java.util.ArrayList<>(steps);
        int finalId = steps.stream().map(InterpretationPlan.Step::id).max(Integer::compareTo).orElse(0) + 1;
        Integer terminalId = steps.isEmpty() ? null : steps.get(steps.size() - 1).id();
        executable.add(new InterpretationPlan.Step(finalId, "final_answer", "", Map.of(),
            terminalId == null ? List.of() : List.of(terminalId), null, null));
        return new InterpretationPlan("1.0",
            new InterpretationPlan.Intent("tool_execution", "test", "low"),
            new InterpretationPlan.Context(List.of(), List.of(), List.of(), List.of()),
            new InterpretationPlan.Plan(executable),
            new InterpretationPlan.ExecutionPolicy(8, false,
                steps.stream().map(InterpretationPlan.Step::toolName).toList(), List.of(), 30_000),
            new InterpretationPlan.Review(
                new InterpretationPlan.SelfCheck(1.0, 0.0, false, List.of()), List.of()));
    }
}
