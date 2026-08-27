package com.chatchat.e2e;

import com.chatchat.agents.runtime.governance.McpPolicyProperties;
import com.chatchat.agents.runtime.config.McpWorkflowProperties;
import com.chatchat.agents.runtime.tool.ToolRuntimeProperties;
import com.chatchat.agents.runtime.tool.ToolRuntimeService;
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
import java.util.concurrent.atomic.AtomicInteger;
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
            AtomicInteger assetReviewerCalls = new AtomicInteger();
            InterpretationPlanRuntime runtime = new InterpretationPlanRuntime(
                toolRuntime, new InterpretationPlanValidator(), null, request -> {
                    if (assetTool.equals(request.execution().toolName())) {
                        assetReviewerCalls.incrementAndGet();
                        return InterpretationPlanRuntime.StepReview.rejected(
                            "overall diagnostics are not complete yet", Map.of());
                    }
                    return InterpretationPlanRuntime.StepReview.accepted("command evidence is usable", Map.of());
                }, request -> {
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
            assertThat(assetReviewerCalls).hasValue(1);
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

    @Test
    void driftedAssetIsRejectedBeforeDependentCommandReachesRegistry() {
        String namespace = "mcp_tenant_" + System.nanoTime() + "_";
        String assetTool = namespace + "ssh_asset_query";
        String commandTool = namespace + "linux_command_execute";
        AtomicInteger remoteCommandCalls = new AtomicInteger();
        ToolRegistry registry = mock(ToolRegistry.class);
        when(registry.hasTool(any())).thenReturn(true);
        when(registry.getToolMetadata(any())).thenAnswer(invocation -> ToolMetadata.builder()
            .id(invocation.getArgument(0)).riskLevel("low").runtimeLevel("readonly")
            .operationType("read").categories(List.of("mcp")).build());
        when(registry.executeEnhancedTool(any(), any())).thenAnswer(invocation -> {
            String toolName = invocation.getArgument(0);
            if (assetTool.equals(toolName)) {
                return ToolOutput.success(Map.of(
                    "schemaVersion", "asset_query_result.v1",
                    "returnedCount", 1,
                    "assets", List.of(Map.of("asset", Map.of(
                        "id", "worker11-id",
                        "name", "CDH DataNode 节点 worker11",
                        "environment", "DEV"
                    )))
                ));
            }
            remoteCommandCalls.incrementAndGet();
            return ToolOutput.success(Map.of("stdout", "must not execute"));
        });
        ToolRuntimeService toolRuntime = new ToolRuntimeService(
            registry, new ObjectMapper(), new ToolRuntimeProperties(),
            new McpPolicyProperties(), new McpWorkflowProperties(), List.of(), List.of());
        try {
            InterpretationPlan driftedPlan = new InterpretationPlan(
                "1.0",
                new InterpretationPlan.Intent("system_operation", "Analyze worker11", "low"),
                new InterpretationPlan.Context(List.of(), List.of(), List.of(), List.of()),
                new InterpretationPlan.Plan(List.of(
                    new InterpretationPlan.Step(1, "mcp_tool", assetTool,
                        Map.of("filters", Map.of("assetName", "worker11")), List.of(), null, null),
                    new InterpretationPlan.Step(2, "mcp_tool", commandTool,
                        Map.of("template", "CHECK_HOSTNAME", "executionContext",
                            Map.of("assetName", "ADP 平台开发数据库", "env", "DEV")),
                        List.of(1), null, null),
                    new InterpretationPlan.Step(3, "final_answer", "", Map.of("answer", "done"),
                        List.of(2), null, null)
                )),
                new InterpretationPlan.ExecutionPolicy(3, false,
                    List.of(assetTool, commandTool), List.of(), 30_000),
                new InterpretationPlan.Review(
                    new InterpretationPlan.SelfCheck(0.9, 0.1, true, List.of()), List.of())
            );
            InterpretationPlanRuntime runtime = new InterpretationPlanRuntime(
                toolRuntime, new InterpretationPlanValidator(), request -> {
                    Integer next = request.remainingStepIds().stream().sorted().findFirst().orElse(null);
                    return Integer.valueOf(3).equals(next)
                        ? InterpretationPlanRuntime.DagDecision.finalAnswer(next, "done", "complete")
                        : InterpretationPlanRuntime.DagDecision.executeStep(next, "continue");
                });

            InterpretationPlanRuntime.ExecutionResult result = runtime.execute(
                new InterpretationPlanRuntime.ExecutionRequest(driftedPlan, registry,
                    List.of(assetTool, commandTool), "tenant-e2e", "request-drift-e2e",
                    "conversation-e2e", "user-e2e", Map.of())
            );

            assertThat(result.success()).isFalse();
            assertThat(result.errorMessage()).contains(
                "ASSET_CONTEXT_MISMATCH", "ADP 平台开发数据库", "CDH DataNode 节点 worker11");
            assertThat(remoteCommandCalls).hasValue(0);
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
