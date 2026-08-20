package com.chatchat.e2e;

import com.chatchat.agents.orchestration.AgentOrchestrator;
import com.chatchat.agents.runtime.AgentRunRequest;
import com.chatchat.agents.runtime.AgentRunResult;
import com.chatchat.agents.runtime.AgentRunStatus;
import com.chatchat.agents.runtime.McpPolicyProperties;
import com.chatchat.agents.runtime.McpWorkflowProperties;
import com.chatchat.agents.runtime.ToolRuntimeProperties;
import com.chatchat.agents.runtime.ToolRuntimeService;
import com.chatchat.agents.runtime.batch.ToolCallBatchSchema;
import com.chatchat.agents.runtime.plan.InterpretationPlan;
import com.chatchat.agents.tool.ToolRegistry;
import com.chatchat.common.config.ModelsConfig;
import com.chatchat.common.tool.ToolInput;
import com.chatchat.common.tool.ToolMetadata;
import com.chatchat.common.tool.ToolOutput;
import com.chatchat.common.tool.ToolParameter;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Product-boundary regression for discovery-to-execution continuity when a model-side
 * result reviewer is unavailable. All target and template identities are generated per run;
 * Runtime must derive execution arguments from governed tool evidence rather than fixture names.
 */
class ProductionMcpDiagnosticRequestContinuityE2E {

    @Test
    @SuppressWarnings("unchecked")
    void simulatedAgentRequestKeepsCanonicalAssetAndDiscoveredTemplatesThroughExecution() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String toolNamespace = "mcp_runtime_" + suffix + "_";
        String assetTool = toolNamespace + "ssh_asset_query";
        String templateTool = toolNamespace + "ssh_template_query";
        String executorTool = toolNamespace + "linux_command_execute";
        String assetId = "asset-" + suffix;
        String assetName = "runtime-target-" + suffix;
        String environment = "E2E-" + suffix.substring(0, 8);
        String registeredTransport = "registered-transport-" + suffix;
        List<DiagnosticSpec> diagnostics = List.of(
            new DiagnosticSpec("resource_cpu", "cpu_usage", "resource", "cpu usage"),
            new DiagnosticSpec("container_usage", "container_resource_usage", "platform", "container resource usage")
        );
        List<String> discoveredTemplateIds = diagnostics.stream()
            .map(spec -> "template_" + suffix + "_" + spec.checkId())
            .toList();
        List<String> inventedTemplateIds = diagnostics.stream()
            .map(spec -> "model_guess_" + UUID.randomUUID())
            .toList();

        ObjectMapper objectMapper = new ObjectMapper();
        ToolRegistry registry = mock(ToolRegistry.class);
        configureMetadata(registry, assetTool, templateTool, executorTool);
        List<Map<String, Object>> executorInvocations = new CopyOnWriteArrayList<>();
        when(registry.executeEnhancedTool(any(), any())).thenAnswer(invocation -> {
            String toolName = invocation.getArgument(0);
            ToolInput input = invocation.getArgument(1);
            if (assetTool.equals(toolName)) {
                return ToolOutput.success(assetResult(
                    assetId, assetName, environment, registeredTransport, suffix));
            }
            if (templateTool.equals(toolName)) {
                return ToolOutput.success(templateResult(
                    assetId, assetName, environment, registeredTransport,
                    executorTool, diagnostics, discoveredTemplateIds));
            }
            if (executorTool.equals(toolName)) {
                Map<String, Object> arguments = new LinkedHashMap<>(input.getParameters());
                executorInvocations.add(Map.copyOf(arguments));
                String templateId = String.valueOf(arguments.get("templateId"));
                return ToolOutput.success(Map.of(
                    "schemaVersion", "diagnostic_command_result.v1",
                    "templateId", templateId,
                    "observed", true,
                    "metrics", Map.of("sample", executorInvocations.size())
                ));
            }
            return ToolOutput.failure("Unexpected tool: " + toolName);
        });

        InterpretationPlan plan = plan(
            assetTool, templateTool, executorTool, diagnostics, inventedTemplateIds, environment);
        ReviewerUnavailableChatModel model = new ReviewerUnavailableChatModel(
            objectMapper.writeValueAsString(plan));
        ToolRuntimeProperties runtimeProperties = new ToolRuntimeProperties();
        runtimeProperties.setMaxOutputBytes(12_000);
        runtimeProperties.setMaxOutputPreviewChars(2_000);
        runtimeProperties.setDefaultRetryAttempts(0);
        ToolRuntimeService toolRuntime = new ToolRuntimeService(
            registry, objectMapper, runtimeProperties,
            new McpPolicyProperties(), new McpWorkflowProperties(), List.of(), List.of());
        AgentOrchestrator orchestrator = new AgentOrchestrator(
            model, registry, toolRuntime, objectMapper, new ModelsConfig());

        try {
            String userQuery = "Analyze sustained resource alerts for " + assetName
                + " in environment " + environment;
            AgentRunResult result = orchestrator.execute(AgentRunRequest.builder()
                .runId("run-" + suffix)
                .requestId("request-" + suffix)
                .conversationId("conversation-" + suffix)
                .tenantId("tenant-" + suffix)
                .userId("user-" + suffix)
                .query(userQuery)
                .systemPrompt("Use only governed discovery and diagnostic evidence.")
                .availableTools(List.of(assetTool, templateTool, executorTool))
                .maxSteps(4)
                .maxToolCalls(8)
                .build());

            assertThat(result.status()).isEqualTo(AgentRunStatus.COMPLETED);
            assertThat(result.errorMessage()).isNull();
            assertThat(result.toolTraces())
                .extracting(trace -> trace.getToolName())
                .contains(assetTool, templateTool, executorTool);
            assertThat(result.toolTraces()).allSatisfy(trace -> {
                assertThat(trace.getErrorMessage() == null ? "" : trace.getErrorMessage())
                    .doesNotContain("Ambiguous", "execution context", "required input");
            });

            assertThat(model.reviewerCalls()).isGreaterThanOrEqualTo(2);
            assertThat(executorInvocations).hasSize(diagnostics.size());
            assertThat(executorInvocations)
                .extracting(arguments -> String.valueOf(arguments.get("templateId")))
                .containsExactlyInAnyOrderElementsOf(discoveredTemplateIds)
                .doesNotContainAnyElementsOf(inventedTemplateIds);
            assertThat(executorInvocations).allSatisfy(arguments -> {
                assertThat(arguments)
                    .containsKeys("template", "templateId", "parameters", "executionContext");
                Map<String, Object> context = (Map<String, Object>) arguments.get("executionContext");
                assertThat(context)
                    .containsEntry("assetId", assetId)
                    .containsEntry("assetName", assetName)
                    .containsEntry("env", environment)
                    .containsEntry("assetToolName", registeredTransport);
            });
            assertThat(result.metadata().toString())
                .doesNotContain("mandatoryWorkflowMissingRequiredInputs", "Ambiguous SSH host");
        } finally {
            toolRuntime.shutdown();
        }
    }

    private void configureMetadata(ToolRegistry registry,
                                   String assetTool,
                                   String templateTool,
                                   String executorTool) {
        when(registry.hasTool(any())).thenReturn(true);
        when(registry.getToolMetadata(assetTool)).thenReturn(metadata(assetTool, List.of(), Map.of()));
        when(registry.getToolMetadata(templateTool)).thenReturn(metadata(templateTool, List.of(), Map.of()));
        Map<String, Object> singleCallSchema = Map.of(
            "type", "object",
            "properties", Map.of(
                "template", Map.of("type", "string"),
                "parameters", Map.of("type", "object"),
                "executionContext", Map.of("type", "object")
            ),
            "required", List.of("template", "executionContext")
        );
        when(registry.getToolMetadata(executorTool)).thenReturn(metadata(
            executorTool,
            List.of(
                ToolParameter.builder().name("template").type("string").required(true).build(),
                ToolParameter.builder().name("executionContext").type("object").required(true).build()
            ),
            Map.of("inputSchema", ToolCallBatchSchema.augment(executorTool, singleCallSchema))
        ));
    }

    private ToolMetadata metadata(String toolName,
                                  List<ToolParameter> parameters,
                                  Map<String, Object> metadata) {
        return ToolMetadata.builder()
            .id(toolName)
            .title(toolName)
            .description("Generated governed E2E capability")
            .riskLevel("low")
            .runtimeLevel("readonly")
            .operationType("read")
            .categories(List.of("mcp"))
            .parameters(parameters)
            .metadata(metadata)
            .build();
    }

    private Map<String, Object> assetResult(String assetId,
                                            String assetName,
                                            String environment,
                                            String registeredTransport,
                                            String suffix) {
        List<Map<String, Object>> assets = new ArrayList<>();
        assets.add(Map.of(
            "asset", Map.of(
                "id", assetId,
                "name", assetName,
                "displayName", "Generated target " + suffix,
                "environment", environment,
                "toolName", registeredTransport
            ),
            "routingHints", Map.of("executionContext", Map.of(
                "assetId", assetId, "assetName", assetName, "env", environment))
        ));
        for (int index = 0; index < 3; index++) {
            assets.add(Map.of("asset", Map.of(
                "id", "decoy-" + index + "-" + suffix,
                "name", "decoy-" + index + "-" + suffix,
                "environment", environment,
                "toolName", "decoy-transport-" + index
            )));
        }
        return Map.of(
            "schemaVersion", "asset_query_result.v1",
            "success", true,
            "returnedCount", assets.size(),
            "assets", assets,
            "queryIr", Map.of("asset", Map.of(
                "selected", Map.of(
                    "id", assetId,
                    "name", assetName,
                    "environment", environment
                )
            )),
            "unrelatedLargeMetadata", "x".repeat(30_000)
        );
    }

    private Map<String, Object> templateResult(String assetId,
                                               String assetName,
                                               String environment,
                                               String registeredTransport,
                                               String executorTool,
                                               List<DiagnosticSpec> diagnostics,
                                               List<String> templateIds) {
        List<Map<String, Object>> templates = new ArrayList<>();
        for (int index = 0; index < diagnostics.size(); index++) {
            DiagnosticSpec diagnostic = diagnostics.get(index);
            String templateId = templateIds.get(index);
            templates.add(Map.ofEntries(
                Map.entry("templateId", templateId),
                Map.entry("name", diagnostic.templateIdentity()),
                Map.entry("capability", diagnostic.capability()),
                Map.entry("description", "y".repeat(20_000)),
                Map.entry("parameterSchema", Map.of(
                    "type", "object", "properties", Map.of(), "required", List.of())),
                Map.entry("executionBinding", Map.of(
                    "toolName", executorTool,
                    "templateId", templateId,
                    "executionContext", Map.of("assetId", assetId)))
            ));
        }
        return Map.of(
            "schemaVersion", "template_query_result.v1",
            "success", true,
            "returnedCount", templates.size(),
            "queryIr", Map.of(
                "asset", Map.of(
                    "scoped", true,
                    "selected", Map.of(
                        "id", assetId,
                        "name", assetName,
                        "title", assetName,
                        "environment", environment,
                        "toolName", registeredTransport
                    )
                ),
                "templates", Map.of("selectedIds", templateIds)
            ),
            "templates", templates
        );
    }

    private InterpretationPlan plan(String assetTool,
                                    String templateTool,
                                    String executorTool,
                                    List<DiagnosticSpec> diagnostics,
                                    List<String> inventedTemplateIds,
                                    String environment) {
        List<Map<String, Object>> inventedCalls = new ArrayList<>();
        for (int index = 0; index < diagnostics.size(); index++) {
            inventedCalls.add(Map.of(
                "callId", diagnostics.get(index).checkId(),
                "toolName", executorTool,
                "arguments", Map.of(
                    "templateId", inventedTemplateIds.get(index),
                    "executionContext", Map.of("env", environment),
                    "parameters", Map.of()
                )
            ));
        }
        List<InterpretationPlan.DiagnosticCheck> checks = diagnostics.stream()
            .map(spec -> new InterpretationPlan.DiagnosticCheck(
                spec.checkId(), spec.capability(), spec.dimension(), true,
                diagnostics.indexOf(spec) + 1, List.of(3)))
            .toList();
        return new InterpretationPlan(
            "1.0",
            new InterpretationPlan.Intent("system_diagnostic", "Analyze generated runtime alerts", "low"),
            new InterpretationPlan.Context(List.of(), List.of(), List.of(),
                List.of("Use only discovered assets and authorized templates")),
            new InterpretationPlan.Plan(
                List.of(
                    new InterpretationPlan.Step(1, "mcp_tool", assetTool,
                        Map.of("filters", Map.of("intent", "target resource alert", "env", environment)),
                        List.of(), null, null),
                    new InterpretationPlan.Step(2, "mcp_tool", templateTool,
                        Map.of("filters", Map.of("intent", "resource diagnostic"), "limit", 10),
                        List.of(1), null, null),
                    new InterpretationPlan.Step(3, "mcp_tool", executorTool,
                        Map.of(
                            "batchId", "model-proposed-batch",
                            "executionMode", "SEQUENTIAL",
                            "stopOnFailure", false,
                            "calls", inventedCalls
                        ),
                        List.of(2), null, null),
                    new InterpretationPlan.Step(4, "final_answer", "",
                        Map.of("answer", "Diagnostics completed from governed runtime evidence."),
                        List.of(3), null, null)
                ),
                List.of(
                    new InterpretationPlan.EdgeContract(
                        1, 2, "$.assets[0].asset.name", "string", true),
                    new InterpretationPlan.EdgeContract(
                        1, 2, "$.assets[0].asset.environment", "string", true)
                ),
                List.of(),
                List.of(
                    new InterpretationPlan.Binding(1, "$.assets[0].asset.name", 2,
                        "filters.assetName", "jsonpath", true),
                    new InterpretationPlan.Binding(1, "$.assets[0].asset.environment", 2,
                        "filters.env", "jsonpath", true)
                ),
                new InterpretationPlan.Stability(
                    List.of(1, 2, 3), List.of(assetTool, templateTool, executorTool),
                    true, List.of("final_answer")),
                new InterpretationPlan.DiagnosticProfile(
                    "generated_runtime_diagnostic", "host", checks)
            ),
            new InterpretationPlan.ExecutionPolicy(
                4, false, List.of(assetTool, templateTool, executorTool), List.of(),
                30_000, 1, "partial_result", Map.of(), 10.0, 120_000, 0.9),
            new InterpretationPlan.Review(
                new InterpretationPlan.SelfCheck(0.9, 0.1, true, List.of()), List.of())
        );
    }

    private record DiagnosticSpec(String checkId,
                                  String capability,
                                  String dimension,
                                  String templateIdentity) {
    }

    private static final class ReviewerUnavailableChatModel implements ChatModel {
        private final String initialPlan;
        private final AtomicInteger initialCalls = new AtomicInteger();
        private final AtomicInteger reviewerCalls = new AtomicInteger();

        private ReviewerUnavailableChatModel(String initialPlan) {
            this.initialPlan = initialPlan;
        }

        @Override
        public String chat(String message) {
            if (message.contains("runtime reviewer for one completed MCP tool call")) {
                reviewerCalls.incrementAndGet();
                return """
                    {"error":{"message":"simulated reviewer unavailable","code":"upstream_unavailable"}}
                    """;
            }
            if (message.contains("Agent Runtime DAG execution controller")) {
                List<Integer> remaining = integersFromLine(message, "remaining_step_ids:");
                if (remaining.isEmpty()) {
                    return "{\"action\":\"abort\",\"reason\":\"No remaining steps\"}";
                }
                return "{\"action\":\"execute_step\",\"step_ids\":[" + remaining.get(0)
                    + "],\"reason\":\"continue simulated request\",\"confidence\":1.0}";
            }
            if (message.contains("final step-by-step answer synthesizer")) {
                return "Diagnostics completed from governed runtime evidence.";
            }
            if (message.contains("answer quality")) {
                return "{\"accepted\":true,\"feedback\":\"grounded\",\"revisedAnswer\":\"\"}";
            }
            if (initialCalls.getAndIncrement() == 0) {
                return initialPlan;
            }
            return "{\"error\":{\"message\":\"simulated auxiliary model unavailable\"}}";
        }

        private int reviewerCalls() {
            return reviewerCalls.get();
        }

        private static List<Integer> integersFromLine(String message, String prefix) {
            int start = message.indexOf(prefix);
            if (start < 0) {
                return List.of();
            }
            int end = message.indexOf('\n', start);
            String line = end < 0
                ? message.substring(start + prefix.length())
                : message.substring(start + prefix.length(), end);
            Set<Integer> values = new java.util.LinkedHashSet<>();
            for (String token : line.replace("[", "").replace("]", "").split(",")) {
                try {
                    if (!token.isBlank()) {
                        values.add(Integer.parseInt(token.trim()));
                    }
                } catch (NumberFormatException ignored) {
                    // The controller protocol is validated by Runtime; ignore non-numeric decorations.
                }
            }
            return List.copyOf(values);
        }
    }
}
