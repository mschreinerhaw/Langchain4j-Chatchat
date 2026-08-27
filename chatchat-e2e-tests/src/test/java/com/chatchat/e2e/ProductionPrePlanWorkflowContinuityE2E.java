package com.chatchat.e2e;

import com.chatchat.agents.orchestration.AgentOrchestrator;
import com.chatchat.agents.orchestration.evidence.EvidenceTrustEvaluator;
import com.chatchat.agents.runtime.observation.AgentObservation;
import com.chatchat.agents.runtime.AgentRunRequest;
import com.chatchat.agents.runtime.observation.DefaultAgentObservationPipeline;
import com.chatchat.agents.runtime.store.InMemoryAgentRunStore;
import com.chatchat.agents.runtime.governance.McpPolicyProperties;
import com.chatchat.agents.runtime.config.McpWorkflowProperties;
import com.chatchat.agents.runtime.tool.ToolRuntimeProperties;
import com.chatchat.agents.runtime.tool.ToolRuntimeService;
import com.chatchat.agents.tool.ToolRegistry;
import com.chatchat.common.config.ModelsConfig;
import com.chatchat.common.tool.ToolInput;
import com.chatchat.common.tool.ToolMetadata;
import com.chatchat.common.tool.ToolOutput;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Release regression for a workflow whose first tool has a persisted terminal observation before
 * the remaining tools are returned as an InterpretationPlan. Every identity is generated at
 * runtime so the test cannot be satisfied by a maintained MCP namespace, asset or template id.
 */
class ProductionPrePlanWorkflowContinuityE2E {

    @Test
    void prePlanToolCompletionReachesTheFirstDagDependencyWithoutBusinessHardcoding() throws Exception {
        Scenario scenario = scenario(false);
        try {
            AgentOrchestrator.AgentExecutionResult result = scenario.execute();

            assertThat(result.toolTraces())
                .extracting(trace -> trace.getToolName())
                .containsExactly(scenario.templateTool(), scenario.executorTool());
            assertThat(result.toolTraces()).allSatisfy(trace -> {
                assertThat(trace.isSuccess()).isTrue();
                assertThat(trace.getErrorMessage() == null ? "" : trace.getErrorMessage())
                    .doesNotContain("required previous steps", "dependency not completed");
            });
            assertThat(scenario.invocations())
                .containsExactly(scenario.templateTool(), scenario.executorTool());
            assertThat(scenario.runStore().events(scenario.request().getRunId()).toString())
                .contains(scenario.assetTool(), scenario.templateTool(), scenario.executorTool())
                .doesNotContain("required previous steps");
        } finally {
            scenario.runtime().shutdown();
        }
    }

    @Test
    void failedPrePlanStepCannotBeConvertedIntoSuccessfulDagEvidence() throws Exception {
        Scenario scenario = scenario(true);
        try {
            AgentOrchestrator.AgentExecutionResult result = scenario.execute();

            assertThat(scenario.invocations())
                .doesNotContain(scenario.templateTool(), scenario.executorTool());
            assertThat(result.answer() + " " + result.metadata() + " "
                + scenario.runStore().events(scenario.request().getRunId()))
                .containsAnyOf("required previous steps", "workflow", "mandatory", "failed");
        } finally {
            scenario.runtime().shutdown();
        }
    }

    private Scenario scenario(boolean failAsset) throws Exception {
        String generatedNamespace = "mcp_runtime_" + UUID.randomUUID().toString().replace("-", "") + "_";
        String assetTool = generatedNamespace + "asset_discovery";
        String templateTool = generatedNamespace + "template_discovery";
        String executorTool = generatedNamespace + "query_execute";
        String workflowName = "workflow_" + UUID.randomUUID().toString().replace("-", "");
        String runId = "run-" + UUID.randomUUID();
        ObjectMapper mapper = new ObjectMapper();
        ToolRegistry registry = mock(ToolRegistry.class);
        for (String tool : List.of(assetTool, templateTool, executorTool)) {
            when(registry.hasTool(tool)).thenReturn(true);
            when(registry.getToolMetadata(tool)).thenReturn(ToolMetadata.builder()
                .id(tool)
                .title("Generated runtime capability")
                .description("Generated workflow capability used by the release regression")
                .runtimeLevel("readonly")
                .riskLevel("low")
                .operationType("read")
                .categories(List.of("mcp"))
                .build());
        }
        List<String> invocations = new CopyOnWriteArrayList<>();
        when(registry.executeEnhancedTool(any(), any())).thenAnswer(call -> {
            String tool = call.getArgument(0);
            ToolInput input = call.getArgument(1);
            invocations.add(tool);
            if (assetTool.equals(tool)) {
                return failAsset
                    ? ToolOutput.failure("generated asset discovery failure")
                    : ToolOutput.success(Map.of(
                        "schemaVersion", "asset_discovery_result.v1",
                        "assets", List.of(Map.of("id", "asset-" + runId, "name", "target-" + runId))));
            }
            if (templateTool.equals(tool)) {
                return ToolOutput.success(Map.of(
                    "schemaVersion", "template_discovery_result.v1",
                    "templates", List.of(Map.of("templateId", "template-" + runId))));
            }
            if (executorTool.equals(tool)) {
                return ToolOutput.success(Map.of(
                    "schemaVersion", "query_result.v1",
                    "observed", true,
                    "input", input.getParameters()));
            }
            return ToolOutput.failure("unexpected generated tool " + tool);
        });

        ToolRuntimeProperties properties = new ToolRuntimeProperties();
        properties.setDefaultRetryAttempts(0);
        properties.setEnforceAllowedTools(true);
        ToolRuntimeService runtime = new ToolRuntimeService(
            registry, mapper, properties,
            new McpPolicyProperties(), new McpWorkflowProperties(), List.of(), List.of());
        PrePlanThenDagModel model = new PrePlanThenDagModel(mapper, templateTool, executorTool);
        InMemoryAgentRunStore runStore = new InMemoryAgentRunStore();
        AgentOrchestrator orchestrator = new AgentOrchestrator(
            model, registry, runtime, mapper, new ModelsConfig(),
            new EvidenceTrustEvaluator(), runStore, new DefaultAgentObservationPipeline());

        Map<String, Object> workflow = new LinkedHashMap<>();
        workflow.put("enabled", true);
        workflow.put("workflow", workflowName);
        workflow.put("executionStrategy", Map.of(
            "mode", "sequential", "stopOnError", true, "maxSteps", 6));
        workflow.put("steps", List.of(
            Map.of("step", 1, "tool", assetTool, "required", true,
                "confirmation", "auto_execute"),
            Map.of("step", 2, "tool", templateTool, "required", true,
                "confirmation", "auto_execute", "dependsOn", List.of(assetTool)),
            Map.of("step", 3, "tool", executorTool, "required", true,
                "confirmation", "auto_execute", "dependsOn", List.of(assetTool, templateTool))
        ));
        AgentRunRequest request = AgentRunRequest.builder()
            .runId(runId)
            .requestId("request-" + runId)
            .conversationId("conversation-" + runId)
            .tenantId("tenant-" + runId)
            .userId("user-" + runId)
            .query("Execute the generated governed diagnostic workflow")
            .systemPrompt("Use only generated runtime workflow evidence.")
            .availableTools(List.of(assetTool, templateTool, executorTool))
            .attributes(Map.of("mcpWorkflow", workflow))
            .maxSteps(6)
            .maxToolCalls(6)
            .build();
        runStore.start(request);
        runStore.recordObservation(runId, AgentObservation.builder()
            .type(failAsset ? "tool_failure" : "tool")
            .source(assetTool)
            .content(failAsset
                ? "Generated pre-plan discovery failed."
                : "Generated pre-plan discovery completed.")
            .metadata(Map.of(
                "structuredRuntimeObservation", true,
                "toolName", assetTool,
                "success", !failAsset
            ))
            .build());
        return new Scenario(
            orchestrator, runtime, runStore, request,
            assetTool, templateTool, executorTool, invocations);
    }

    private record Scenario(AgentOrchestrator orchestrator,
                            ToolRuntimeService runtime,
                            InMemoryAgentRunStore runStore,
                            AgentRunRequest request,
                            String assetTool,
                            String templateTool,
                            String executorTool,
                            List<String> invocations) {
        AgentOrchestrator.AgentExecutionResult execute() {
            Map<String, Object> attributes = new LinkedHashMap<>(request.getAttributes());
            attributes.put("__agentRunId", request.getRunId());
            attributes.put("__agentMaxSteps", request.getMaxSteps());
            attributes.put("__agentMaxToolCalls", request.getMaxToolCalls());
            return orchestrator.executeAgent(
                request.getQuery(), request.getTenantId(), request.getAvailableTools(),
                request.getSystemPrompt(), request.getModelName(), request.getBoundDocumentIds(),
                request.getBoundDocumentTags(), request.getSkillId(), request.getRequestId(),
                request.getConversationId(), request.getUserId(), request.getWebSearchResultLimit(),
                request.getRequiredToolNames(), request.isRequireBoundToolCall(), attributes);
        }
    }

    private static final class PrePlanThenDagModel implements ChatModel {
        private final ObjectMapper mapper;
        private final String templateTool;
        private final String executorTool;

        private PrePlanThenDagModel(ObjectMapper mapper,
                                    String templateTool,
                                    String executorTool) {
            this.mapper = mapper;
            this.templateTool = templateTool;
            this.executorTool = executorTool;
        }

        @Override
        public String chat(String message) {
            if (message.contains("Build a precise retrieval profile")) {
                return "{\"profile\":{\"intent\":\"generated discovery\",\"terms\":[]},"
                    + "\"arguments\":{},\"argumentEvidence\":{}}";
            }
            if (message.contains("Agent Runtime DAG execution controller")) {
                List<Integer> remaining = integersFromLine(message, "remaining_step_ids:");
                return "{\"action\":\"execute_step\",\"step_ids\":[" + remaining.get(0)
                    + "],\"reason\":\"continue generated workflow\",\"confidence\":1.0}";
            }
            if (message.contains("runtime reviewer for one completed MCP tool call")) {
                return "{\"satisfied\":true,\"iteration_sufficient\":true,"
                    + "\"reason\":\"generated evidence is complete\",\"evidence_used\":[{\"basis\":\"tool result\"}],"
                    + "\"missing_evidence\":[],\"conflicts\":[],\"next_actions\":[],\"confidence\":1.0}";
            }
            if (message.contains("final step-by-step answer synthesizer")) {
                return "Generated workflow completed from runtime evidence.";
            }
            if (message.contains("final answer quality reviewer")) {
                return "{\"accepted\":true,\"feedback\":\"grounded\",\"revisedAnswer\":\"\"}";
            }
            try {
                return mapper.writeValueAsString(plan());
            } catch (Exception ex) {
                throw new IllegalStateException(ex);
            }
        }

        private Map<String, Object> plan() {
            return Map.of(
                "version", "1.0",
                "intent", Map.of("type", "system_operation",
                    "goal", "complete generated workflow", "risk_level", "low"),
                "context", Map.of("key_facts", List.of(), "assumptions", List.of(),
                    "missing_info", List.of(), "constraints", List.of("use governed evidence")),
                "plan", Map.of("steps", List.of(
                    Map.of("id", 1, "action_type", "mcp_tool", "tool_name", templateTool,
                        "input", Map.of("query", "generated template"), "depends_on", List.of()),
                    Map.of("id", 2, "action_type", "mcp_tool", "tool_name", executorTool,
                        "input", Map.of("query", "generated execution"), "depends_on", List.of(1)),
                    Map.of("id", 3, "action_type", "final_answer", "tool_name", "",
                        "input", Map.of("answer", "Generated workflow completed from runtime evidence."),
                        "depends_on", List.of(2))
                )),
                "execution_policy", Map.of(
                    "max_steps", 3, "allow_parallel", false,
                    "allow_tool", List.of(templateTool, executorTool),
                    "deny_tool", List.of(), "max_rewrite_times", 0,
                    "fallback_mode", "partial_result"),
                "review", Map.of(
                    "self_check", Map.of("completeness_score", 0.95,
                        "hallucination_risk", 0.05, "tool_sufficiency", true,
                        "missing_steps", List.of()),
                    "fallback_plan", List.of())
            );
        }

        private static List<Integer> integersFromLine(String message, String prefix) {
            int start = message.indexOf(prefix);
            int end = message.indexOf('\n', start);
            String line = end < 0
                ? message.substring(start + prefix.length())
                : message.substring(start + prefix.length(), end);
            List<Integer> values = new ArrayList<>();
            for (String token : line.replace("[", "").replace("]", "").split(",")) {
                if (!token.isBlank()) {
                    values.add(Integer.parseInt(token.trim()));
                }
            }
            return values;
        }
    }
}
