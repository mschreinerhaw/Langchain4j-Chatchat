package com.chatchat.e2e;

import com.chatchat.agents.runtime.McpPolicyProperties;
import com.chatchat.agents.runtime.McpWorkflowProperties;
import com.chatchat.agents.runtime.ToolRuntimeProperties;
import com.chatchat.agents.runtime.ToolRuntimeService;
import com.chatchat.agents.runtime.plan.InterpretationPlan;
import com.chatchat.agents.runtime.plan.InterpretationPlanRuntime;
import com.chatchat.agents.runtime.plan.InterpretationPlanValidator;
import com.chatchat.agents.tool.ToolRegistry;
import com.chatchat.common.tool.ToolInput;
import com.chatchat.common.tool.ToolMetadata;
import com.chatchat.common.tool.ToolOutput;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProductionAgentMcpE2E {

    @Test
    void executesNewlyRegisteredCapabilityAcrossRuntimeAndMcpWithRetryAndTenantContext() {
        String namespace = "mcp_tenant_" + System.nanoTime() + "_release_gateway_";
        String discoveryTool = namespace + "database_query_template_query";
        String executionTool = namespace + "sql_query_execute";
        String templateId = "release_dynamic_template_" + System.nanoTime();
        AtomicInteger executionAttempts = new AtomicInteger();
        List<ToolInput> executionInputs = new ArrayList<>();

        ToolRegistry registry = mock(ToolRegistry.class);
        when(registry.hasTool(any())).thenReturn(true);
        when(registry.getToolMetadata(any())).thenAnswer(invocation -> ToolMetadata.builder()
            .id(invocation.getArgument(0))
            .title(invocation.getArgument(0))
            .riskLevel("low")
            .categories(List.of("mcp"))
            .build());
        when(registry.executeEnhancedTool(any(), any())).thenAnswer(invocation -> {
            String toolName = invocation.getArgument(0);
            ToolInput input = invocation.getArgument(1);
            if (discoveryTool.equals(toolName)) {
                return ToolOutput.success(discoveryResult(templateId));
            }
            if (!executionTool.equals(toolName)) {
                return ToolOutput.failure("unexpected E2E tool: " + toolName);
            }
            executionInputs.add(input);
            if (executionAttempts.incrementAndGet() == 1) {
                return ToolOutput.failure("temporary MCP transport failure");
            }
            return ToolOutput.success(Map.of(
                "schemaVersion", "database_query_result.v1",
                "templateId", templateId,
                "rowCount", 1,
                "rows", List.of(Map.of("metric", "release-ready", "value", 1))
            ));
        });

        ToolRuntimeProperties properties = new ToolRuntimeProperties();
        properties.setDefaultRetryAttempts(1);
        properties.setCircuitBreakerFailureThreshold(5);
        ToolRuntimeService toolRuntime = new ToolRuntimeService(
            registry,
            new ObjectMapper(),
            properties,
            new McpPolicyProperties(),
            new McpWorkflowProperties(),
            List.of(),
            List.of()
        );
        InterpretationPlanRuntime runtime = new InterpretationPlanRuntime(
            toolRuntime,
            new InterpretationPlanValidator(),
            request -> {
                Integer next = request.remainingStepIds().stream().sorted().findFirst().orElse(null);
                return Integer.valueOf(3).equals(next)
                    ? InterpretationPlanRuntime.DagDecision.finalAnswer(next, "release evidence ready", "E2E evidence complete")
                    : InterpretationPlanRuntime.DagDecision.executeStep(next, "execute governed E2E step");
            }
        );

        InterpretationPlanRuntime.ExecutionResult result = runtime.execute(
            new InterpretationPlanRuntime.ExecutionRequest(
                plan(discoveryTool, executionTool),
                registry,
                List.of(discoveryTool, executionTool),
                "tenant-release-e2e",
                "request-release-e2e",
                "conversation-release-e2e",
                "user-release-e2e",
                Map.of("originalUserQuery", "validate a newly registered production capability")
            )
        );

        assertThat(result.success())
            .as("status=%s error=%s metadata=%s", result.status(), result.errorMessage(), result.metadata())
            .isTrue();
        assertThat(executionAttempts).hasValue(2);
        assertThat(executionInputs).hasSize(2).allSatisfy(input -> {
            assertThat(input.getParameters())
                .containsEntry("template", templateId)
                .containsEntry("templateId", templateId);
            assertThat(input.getContext())
                .containsEntry("tenantId", "tenant-release-e2e")
                .containsEntry("userId", "user-release-e2e")
                .containsEntry("requestId", "request-release-e2e")
                .containsEntry("conversationId", "conversation-release-e2e");
        });
        assertThat(result.steps()).anySatisfy(step -> {
            if (Integer.valueOf(2).equals(step.stepId())) {
                assertThat(step.success()).isTrue();
                assertThat(step.output().toString()).contains("release-ready", templateId);
            }
        });
    }

    private Map<String, Object> discoveryResult(String templateId) {
        Map<String, Object> template = new LinkedHashMap<>();
        template.put("templateId", templateId);
        template.put("requiredParameters", List.of());
        template.put("parameterSchema", Map.of(
            "type", "object",
            "properties", Map.of(),
            "required", List.of()
        ));
        template.put("parameterContract", Map.of(
            "executionTool", "sql_query_execute",
            "argumentContainer", "sql_query_execute.parameters"
        ));
        template.put("executionContext", Map.of("assetName", "release-database", "env", "E2E"));
        return Map.of(
            "schemaVersion", "template_query_result.v1",
            "targetKind", "business_database_query",
            "returnedCount", 1,
            "templates", List.of(template)
        );
    }

    private InterpretationPlan plan(String discoveryTool, String executionTool) {
        return new InterpretationPlan(
            "1.0",
            new InterpretationPlan.Intent("release_e2e", "Validate dynamic capability execution", "low"),
            new InterpretationPlan.Context(List.of(), List.of(), List.of(), List.of("Use discovery-owned contracts")),
            new InterpretationPlan.Plan(
                List.of(
                    new InterpretationPlan.Step(
                        1, "mcp_tool", discoveryTool,
                        Map.of(
                            "finalDecision", "business_database_query",
                            "candidates", List.of(Map.of("targetKind", "business_database_query", "confidence", 0.99)),
                            "filters", Map.of("intent", "release readiness"),
                            "limit", 20
                        ),
                        List.of(),
                        new InterpretationPlan.OutputContract("object", "template_query_result.v1"),
                        null
                    ),
                    new InterpretationPlan.Step(
                        2, "mcp_tool", executionTool,
                        Map.of("purpose", "release readiness", "parameters", Map.of()),
                        List.of(1),
                        new InterpretationPlan.OutputContract("object", "database_query_result.v1"),
                        null
                    ),
                    new InterpretationPlan.Step(
                        3, "final_answer", "",
                        Map.of(
                            "answer", "release evidence ready",
                            "artifact_contract", Map.of(
                                "schema_version", "runtime_artifact_contract.v1",
                                "artifact_type", "release_evidence",
                                "delivery_state", "complete"
                            )
                        ),
                        List.of(2), null, null
                    )
                ),
                List.of(),
                List.of(new InterpretationPlan.Binding(
                    1, "$.templates[0].templateId", 2, "templateId", "jsonpath", true
                )),
                null
            ),
            new InterpretationPlan.ExecutionPolicy(
                3, false, List.of(discoveryTool, executionTool), List.of(), 30_000
            ),
            new InterpretationPlan.Review(
                new InterpretationPlan.SelfCheck(0.95, 0.05, true, List.of()),
                List.of("Never claim readiness without execution evidence")
            )
        );
    }
}
