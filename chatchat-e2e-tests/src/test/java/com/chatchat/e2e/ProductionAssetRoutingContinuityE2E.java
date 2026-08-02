package com.chatchat.e2e;

import com.chatchat.agents.runtime.McpPolicyProperties;
import com.chatchat.agents.runtime.McpWorkflowProperties;
import com.chatchat.agents.runtime.ToolRuntimeProperties;
import com.chatchat.agents.runtime.ToolRuntimeService;
import com.chatchat.agents.runtime.plan.InterpretationPlan;
import com.chatchat.agents.runtime.plan.InterpretationPlanRuntime;
import com.chatchat.agents.runtime.plan.InterpretationPlanValidator;
import com.chatchat.agents.tool.ToolRegistry;
import com.chatchat.common.tool.ToolMetadata;
import com.chatchat.common.tool.ToolOutput;
import com.chatchat.common.tool.ToolParameter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProductionAssetRoutingContinuityE2E {

    @Test
    void externalizedAssetEvidenceStillRoutesDependentCommandToDiscoveredTarget() {
        String namespace = "mcp_tenant_" + System.nanoTime() + "_";
        String assetTool = namespace + "ssh_asset_query";
        String commandTool = namespace + "linux_command_execute";
        AtomicReference<Map<String, Object>> commandArguments = new AtomicReference<>();

        ToolRegistry registry = mock(ToolRegistry.class);
        when(registry.hasTool(any())).thenReturn(true);
        when(registry.getToolMetadata(assetTool)).thenReturn(ToolMetadata.builder()
            .id(assetTool).riskLevel("low").runtimeLevel("readonly").operationType("read")
            .categories(List.of("mcp")).build());
        when(registry.getToolMetadata(commandTool)).thenReturn(ToolMetadata.builder()
            .id(commandTool).riskLevel("low").runtimeLevel("readonly").operationType("read")
            .categories(List.of("mcp")).parameters(List.of(
                ToolParameter.builder().name("template").type("string").required(true).build(),
                ToolParameter.builder().name("executionContext").type("object").required(true).build()
            )).build());
        when(registry.executeEnhancedTool(any(), any())).thenAnswer(invocation -> {
            String toolName = invocation.getArgument(0);
            com.chatchat.common.tool.ToolInput input = invocation.getArgument(1);
            if (assetTool.equals(toolName)) {
                return ToolOutput.success(Map.of(
                    "schemaVersion", "asset_query_result.v1",
                    "returnedCount", 1,
                    "assets", List.of(Map.of("asset", Map.of(
                        "id", "asset-e2e-17",
                        "name", "docker-database-simulator",
                        "displayName", "Docker database simulator",
                        "environment", "DEV",
                        "toolName", "tenant_registered_ssh_tool",
                        "largeRedactedMetadata", "x".repeat(100_000)
                    )))
                ));
            }
            commandArguments.set(Map.copyOf(input.getParameters()));
            return ToolOutput.success(Map.of(
                "schemaVersion", "command_result.v1",
                "stdout", "Filesystem Size Used Avail Use% Mounted on\n/dev/sda1 100G 42G 58G 42% /"
            ));
        });

        ToolRuntimeProperties properties = new ToolRuntimeProperties();
        properties.setMaxOutputBytes(16_384);
        ToolRuntimeService toolRuntime = new ToolRuntimeService(
            registry, new ObjectMapper(), properties,
            new McpPolicyProperties(), new McpWorkflowProperties(), List.of(), List.of());
        try {
            InterpretationPlanRuntime runtime = new InterpretationPlanRuntime(
                toolRuntime, new InterpretationPlanValidator(), request -> {
                    Integer next = request.remainingStepIds().stream().sorted().findFirst().orElse(null);
                    return Integer.valueOf(3).equals(next)
                        ? InterpretationPlanRuntime.DagDecision.finalAnswer(next, "disk evidence collected", "complete")
                        : InterpretationPlanRuntime.DagDecision.executeStep(next, "continue");
                });
            InterpretationPlanRuntime.ExecutionResult result = runtime.execute(
                new InterpretationPlanRuntime.ExecutionRequest(plan(assetTool, commandTool), registry,
                    List.of(assetTool, commandTool), "tenant-e2e", "request-e2e",
                    "conversation-e2e", "user-e2e",
                    Map.of("originalUserQuery", "Analyze this Docker database simulator disk usage")));

            assertThat(result.success()).as(result.errorMessage()).isTrue();
            assertThat(commandArguments.get()).isNotNull();
            Map<?, ?> context = (Map<?, ?>) commandArguments.get().get("executionContext");
            assertThat(context.get("assetId")).isEqualTo("asset-e2e-17");
            assertThat(context.get("assetName")).isEqualTo("docker-database-simulator");
            assertThat(context.get("env")).isEqualTo("DEV");
            assertThat(result.steps()).anySatisfy(step -> {
                if (Integer.valueOf(2).equals(step.stepId())) {
                    assertThat(step.output().toString()).contains("42%", "/dev/sda1");
                }
            });
        } finally {
            toolRuntime.shutdown();
        }
    }

    private InterpretationPlan plan(String assetTool, String commandTool) {
        return new InterpretationPlan(
            "1.0",
            new InterpretationPlan.Intent("system_operation", "Analyze target disk usage", "low"),
            new InterpretationPlan.Context(List.of(), List.of(), List.of(), List.of()),
            new InterpretationPlan.Plan(List.of(
                new InterpretationPlan.Step(1, "mcp_tool", assetTool,
                    Map.of("filters", Map.of("intent", "Docker database simulator")), List.of(), null, null),
                new InterpretationPlan.Step(2, "mcp_tool", commandTool,
                    Map.of("template", "CHECK_DISK", "executionContext", Map.of()), List.of(1), null, null),
                new InterpretationPlan.Step(3, "final_answer", "",
                    Map.of("answer", "disk evidence collected"), List.of(2), null, null)
            ), List.of(), List.of(
                new InterpretationPlan.Binding(1, "$.assets[0].asset.name", 2,
                    "executionContext.assetName", "jsonpath", true)
            ), null),
            new InterpretationPlan.ExecutionPolicy(3, false, List.of(assetTool, commandTool), List.of(), 30_000),
            new InterpretationPlan.Review(
                new InterpretationPlan.SelfCheck(0.9, 0.1, true, List.of()),
                List.of("Use command evidence"))
        );
    }
}
