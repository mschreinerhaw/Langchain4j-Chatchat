package com.chatchat.e2e;

import com.chatchat.agents.runtime.governance.McpPolicyProperties;
import com.chatchat.agents.runtime.config.McpWorkflowProperties;
import com.chatchat.agents.runtime.tool.ToolRuntimeProperties;
import com.chatchat.agents.runtime.tool.ToolRuntimeService;
import com.chatchat.agents.runtime.plan.InterpretationPlan;
import com.chatchat.agents.runtime.plan.InterpretationPlanRuntime;
import com.chatchat.agents.runtime.plan.InterpretationPlanValidator;
import com.chatchat.agents.tool.ToolRegistry;
import com.chatchat.common.tool.ToolInput;
import com.chatchat.common.tool.ToolMetadata;
import com.chatchat.common.tool.ToolOutput;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Release stress gate for discovery-contract continuity under oversized concurrent MCP results. */
class ProductionTemplateContractContinuityStressE2E {

    private static final int REQUESTS = 192;

    @Test
    void concurrentDynamicTemplatesRemainExecutableAfterDiscoveryOutputExternalization() {
        String namespace = "mcp_" + UUID.randomUUID().toString().replace("-", "") + "_";
        String discoveryTool = namespace + "database_query_template_query";
        String executionTool = namespace + "sql_query_execute";
        ToolRegistry registry = mock(ToolRegistry.class);
        when(registry.hasTool(any())).thenReturn(true);
        when(registry.getToolMetadata(any())).thenAnswer(invocation -> ToolMetadata.builder()
            .id(invocation.getArgument(0)).riskLevel("low").categories(List.of("mcp")).build());

        AtomicInteger discoveryCalls = new AtomicInteger();
        AtomicInteger executionCalls = new AtomicInteger();
        Map<String, String> executedTemplates = new ConcurrentHashMap<>();
        when(registry.executeEnhancedTool(any(), any())).thenAnswer(invocation -> {
            String toolName = invocation.getArgument(0);
            ToolInput input = invocation.getArgument(1);
            if (discoveryTool.equals(toolName)) {
                discoveryCalls.incrementAndGet();
                Map<String, Object> filters = map(input.getParameters().get("filters"));
                String templateId = String.valueOf(filters.get("intent"));
                return ToolOutput.success(Map.of(
                    "schemaVersion", "template_query_result.dynamic.v1",
                    "templates", List.of(Map.ofEntries(
                        Map.entry("templateId", templateId),
                        Map.entry("name", "runtime discovered " + templateId),
                        Map.entry("description", "x".repeat(100_000)),
                        Map.entry("parameterSchema", Map.of(
                            "type", "object", "properties", Map.of(), "required", List.of())),
                        Map.entry("sqlExecutionBinding", Map.of(
                            "toolName", "sql_query_execute",
                            "templateId", templateId,
                            "executionContext", Map.of(
                                "assetName", "dynamic-market-" + templateId,
                                "env", "STRESS")))
                    ))
                ));
            }
            if (executionTool.equals(toolName)) {
                executionCalls.incrementAndGet();
                String templateId = String.valueOf(input.getParameters().get("templateId"));
                executedTemplates.put(input.getRequestId(), templateId);
                return ToolOutput.success(Map.of("templateId", templateId, "rowCount", 1));
            }
            return ToolOutput.failure("unexpected dynamic tool");
        });

        ToolRuntimeProperties properties = new ToolRuntimeProperties();
        properties.setMaxOutputBytes(8_192);
        properties.setMaxOutputPreviewChars(1_000);
        properties.setDefaultRetryAttempts(0);
        properties.setExecutionCorePoolSize(16);
        properties.setExecutionMaxPoolSize(48);
        properties.setExecutionQueueCapacity(512);
        ToolRuntimeService toolRuntime = new ToolRuntimeService(
            registry, new ObjectMapper(), properties,
            new McpPolicyProperties(), new McpWorkflowProperties(), List.of(), List.of());
        InterpretationPlanRuntime planRuntime = new InterpretationPlanRuntime(
            toolRuntime,
            new InterpretationPlanValidator(),
            request -> {
                Integer next = request.remainingStepIds().stream().sorted().findFirst().orElse(null);
                return Integer.valueOf(3).equals(next)
                    ? InterpretationPlanRuntime.DagDecision.finalAnswer(next, "done", "stress evidence complete")
                    : InterpretationPlanRuntime.DagDecision.executeStep(next, "execute dynamic contract step");
            });
        ExecutorService callers = Executors.newFixedThreadPool(48);
        try {
            assertTimeoutPreemptively(Duration.ofSeconds(60), () -> {
                var futures = IntStream.range(0, REQUESTS).mapToObj(index -> callers.submit(() -> {
                    String requestId = "template-contract-stress-" + index;
                    String templateId = "dynamic_template_" + index + "_" + UUID.randomUUID();
                    InterpretationPlanRuntime.ExecutionResult result = planRuntime.execute(
                        new InterpretationPlanRuntime.ExecutionRequest(
                            plan(discoveryTool, executionTool, templateId),
                            registry, List.of(discoveryTool, executionTool),
                            "tenant-" + index % 11, requestId, "conversation-" + index,
                            "user-" + index, Map.of()));
                    assertThat(result.success())
                        .as("request=%s template=%s error=%s", requestId, templateId, result.errorMessage())
                        .isTrue();
                    assertThat(result.steps()).allSatisfy(step ->
                        assertThat(step.errorMessage() == null
                            || !step.errorMessage().contains("TEMPLATE_CONTRACT_RESOLUTION_FAILED")).isTrue());
                    return Map.entry(requestId, templateId);
                })).toList();

                List<Map.Entry<String, String>> expected = new ArrayList<>();
                for (var future : futures) expected.add(future.get(45, TimeUnit.SECONDS));
                assertThat(expected).hasSize(REQUESTS);
                expected.forEach(entry -> assertThat(executedTemplates)
                    .containsEntry(entry.getKey(), entry.getValue()));
            });
            assertThat(discoveryCalls).hasValue(REQUESTS);
            assertThat(executionCalls).hasValue(REQUESTS);
        } finally {
            callers.shutdownNow();
            toolRuntime.shutdown();
        }
    }

    private InterpretationPlan plan(String discoveryTool, String executionTool, String templateId) {
        return new InterpretationPlan(
            "1.0",
            new InterpretationPlan.Intent("dynamic_query", "execute " + templateId, "low"),
            new InterpretationPlan.Context(List.of(), List.of(), List.of(), List.of()),
            new InterpretationPlan.Plan(List.of(
                new InterpretationPlan.Step(1, "mcp_tool", discoveryTool,
                    Map.of("filters", Map.of("intent", templateId), "limit", 10), List.of(), null, null),
                new InterpretationPlan.Step(2, "mcp_tool", executionTool,
                    Map.of("templateId", templateId, "parameters", Map.of()), List.of(1), null, null),
                new InterpretationPlan.Step(3, "final_answer", "", Map.of("answer", "done"),
                    List.of(2), null, null)
            )),
            new InterpretationPlan.ExecutionPolicy(
                3, false, List.of(discoveryTool, executionTool), List.of(), 30_000),
            new InterpretationPlan.Review(
                new InterpretationPlan.SelfCheck(0.95, 0.05, true, List.of()), List.of())
        );
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> raw ? (Map<String, Object>) raw : Map.of();
    }
}
