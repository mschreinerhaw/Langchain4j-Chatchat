package com.chatchat.e2e;

import com.chatchat.agents.orchestration.AgentOrchestrator;
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
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * End-to-end release gate proving that user-configured workflow semantics survive
 * several incompatible model plan shapes. Runtime identities are generated for each
 * execution so production code cannot satisfy the test with known names or ids.
 */
class ProductionAuthoritativeWorkflowModelDriftExtremeE2E {

    @Test
    void completesUserTaskAfterOmissionDuplicationReorderingAliasAndCycleDrift() {
        String token = UUID.randomUUID().toString().replace("-", "");
        String prefix = "mcp_runtime_" + token + "_";
        String assetTool = prefix + "api_asset_query";
        String templateTool = prefix + "api_template_query";
        String executeTool = prefix + "api_template_execute";
        String templateId = "template_" + token;
        String assetName = "asset_" + token;
        String userMarker = "USER_TASK_" + token;
        List<String> invocations = new CopyOnWriteArrayList<>();
        List<Map<String, Object>> invocationInputs = new CopyOnWriteArrayList<>();

        ToolRegistry registry = mock(ToolRegistry.class);
        for (String tool : List.of(assetTool, templateTool, executeTool)) {
            when(registry.hasTool(tool)).thenReturn(true);
            when(registry.getToolMetadata(tool)).thenReturn(ToolMetadata.builder()
                .id(tool)
                .title("Runtime generated governed tool")
                .description("Runtime generated read-only workflow capability")
                .operationType("read")
                .riskLevel("low")
                .runtimeLevel("readonly")
                .categories(List.of("mcp"))
                .build());
        }
        when(registry.executeEnhancedTool(any(), any())).thenAnswer(call -> {
            String tool = call.getArgument(0);
            ToolInput input = call.getArgument(1);
            invocations.add(tool);
            invocationInputs.add(new LinkedHashMap<>(input.getParameters()));
            if (assetTool.equals(tool)) {
                return ToolOutput.success(Map.of(
                    "schemaVersion", "tool_result_summary.v1",
                    "summaryTruncated", true,
                    "routingProjection", Map.of(
                        "returnedCount", 1,
                        "assets", List.of(Map.of("asset", Map.of(
                            "id", "id_" + token,
                            "name", assetName,
                            "environment", "DEV"))),
                        "queryIr", Map.of("asset", Map.of("selected", Map.of(
                            "id", "id_" + token,
                            "name", assetName,
                            "environment", "DEV")))
                    )
                ));
            }
            if (templateTool.equals(tool)) {
                return ToolOutput.success(Map.of(
                    "schemaVersion", "tool_result_summary.v1",
                    "summaryTruncated", true,
                    "routingProjection", Map.of(
                        "returnedCount", 1,
                        "queryIr", Map.of("asset", Map.of("selected", Map.of(
                            "id", "id_" + token,
                            "name", assetName,
                            "environment", "DEV"))),
                        "templates", List.of(Map.of(
                            "templateId", templateId,
                            "parameterSchema", Map.of(
                                "type", "object", "properties", Map.of(), "required", List.of()),
                            "parameterContract", Map.of("executionTool", "api_template_execute")
                        ))
                    )
                ));
            }
            if (executeTool.equals(tool)) {
                return ToolOutput.success(Map.of(
                    "schemaVersion", "tool_execution_result.v1",
                    "success", true,
                    "taskMarker", userMarker,
                    "records", List.of(Map.of("status", "completed", "marker", userMarker))
                ));
            }
            return ToolOutput.failure("Unexpected non-workflow tool: " + tool);
        });

        ToolRuntimeProperties properties = new ToolRuntimeProperties();
        properties.setDefaultRetryAttempts(0);
        properties.setEnforceAllowedTools(true);
        ToolRuntimeService runtime = new ToolRuntimeService(
            registry,
            new ObjectMapper(),
            properties,
            new McpPolicyProperties(),
            new McpWorkflowProperties(),
            List.of(),
            List.of()
        );
        ExtremeDriftModel model = new ExtremeDriftModel(
            assetTool, templateTool, executeTool, userMarker);
        AgentOrchestrator orchestrator = new AgentOrchestrator(
            model, registry, runtime, new ObjectMapper(), new ModelsConfig());

        Map<String, Object> workflow = new LinkedHashMap<>();
        workflow.put("enabled", true);
        workflow.put("workflow", "workflow_" + token);
        workflow.put("steps", List.of(
            Map.of("id", "discover", "tool", assetTool, "required", true),
            Map.of("id", "select", "tool", templateTool, "required", true,
                "dependsOn", List.of("discover")),
            Map.of("id", "execute", "tool", executeTool, "required", true,
                "dependsOn", List.of("select"))
        ));
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("mcpWorkflow", workflow);
        attributes.put("__agentMaxSteps", 1);
        attributes.put("__agentMaxToolCalls", 8);
        attributes.put("originalUserQuery", "Complete " + userMarker + " through the configured workflow");

        try {
            AgentOrchestrator.AgentExecutionResult result = orchestrator.executeAgent(
                "Complete " + userMarker + " through the configured workflow",
                "tenant_" + token,
                List.of(assetTool, templateTool, executeTool),
                "The user-configured workflow is authoritative.",
                null,
                List.of(),
                List.of(),
                "general",
                "request_" + token,
                "conversation_" + token,
                "user_" + token,
                10,
                List.of(),
                false,
                attributes
            );

            assertThat(model.plannerResponses()).hasValueGreaterThanOrEqualTo(3);
            assertThat(model.emittedDriftKinds())
                .contains("OMITTED_EARLY_FINAL", "DUPLICATED_REVERSED", "ALIASED_CYCLIC_UNKNOWN");
            assertThat(invocations).containsExactly(assetTool, templateTool, executeTool);
            assertThat(invocationInputs).hasSize(3);
            assertThat(invocationInputs.get(2))
                .containsEntry("templateId", templateId)
                .doesNotContainValue("invented-template");
            assertThat(result.toolTraces()).allSatisfy(trace -> assertThat(trace.isSuccess()).isTrue());
            assertThat(result.metadata())
                .containsEntry("mandatoryWorkflowCompleted", true)
                .containsEntry("mandatoryWorkflowRecoveredAfterPlan", true)
                .containsEntry("missingMandatoryTools", List.of())
                .doesNotContainKeys("fatalExecutionBlocked", "mandatoryWorkflowBlocked");
            assertThat(result.answer()).contains(userMarker);
        } finally {
            runtime.shutdown();
        }
    }

    private static final class ExtremeDriftModel implements ChatModel {
        private final String assetTool;
        private final String templateTool;
        private final String executeTool;
        private final String userMarker;
        private final AtomicInteger plannerResponses = new AtomicInteger();
        private final List<String> emittedDriftKinds = new ArrayList<>();

        private ExtremeDriftModel(String assetTool,
                                  String templateTool,
                                  String executeTool,
                                  String userMarker) {
            this.assetTool = assetTool;
            this.templateTool = templateTool;
            this.executeTool = executeTool;
            this.userMarker = userMarker;
        }

        @Override
        public String chat(String prompt) {
            if (prompt.contains("Build a precise retrieval profile")) {
                return "{\"profile\":{\"intent\":\"" + userMarker
                    + "\",\"terms\":[\"" + userMarker
                    + "\"]},\"arguments\":{},\"argumentEvidence\":{}}";
            }
            if (prompt.contains("final step-by-step answer synthesizer")) {
                return "Completed " + userMarker + " from the authoritative workflow evidence.";
            }
            if (prompt.contains("final answer quality reviewer")) {
                return "{\"accepted\":true,\"feedback\":\"grounded\",\"revisedAnswer\":\"\"}";
            }
            if (prompt.contains("runtime reviewer for one completed MCP tool call")) {
                return "{\"satisfied\":false,\"iteration_sufficient\":false,"
                    + "\"reason\":\"model drift rejects valid intermediate evidence\","
                    + "\"missing_evidence\":[\"downstream node\"],\"conflicts\":[],"
                    + "\"next_actions\":[],\"confidence\":0.1}";
            }
            int attempt = plannerResponses.getAndIncrement() % 3;
            if (attempt == 0) {
                emittedDriftKinds.add("OMITTED_EARLY_FINAL");
                return envelope(List.of(Map.of(
                    "id", 1,
                    "action_type", "final_answer",
                    "tool_name", "",
                    "input", Map.of("answer", "model stopped before " + userMarker),
                    "depends_on", List.of()
                )), List.of(), 1);
            }
            if (attempt == 1) {
                emittedDriftKinds.add("DUPLICATED_REVERSED");
                return envelope(List.of(
                    step(90, executeTool, Map.of("templateId", "invented-template"), List.of()),
                    step(10, assetTool, Map.of("query", userMarker), List.of(90)),
                    step(91, executeTool, Map.of("template_id", "invented-template"), List.of(10)),
                    finalStep(100, List.of(90, 91))
                ), List.of(executeTool), 2);
            }
            emittedDriftKinds.add("ALIASED_CYCLIC_UNKNOWN");
            String unknown = "mcp_unknown_" + userMarker.toLowerCase();
            return envelope(List.of(
                step(1, templateTool.replace("_", "-"), Map.of("template_ids", List.of("invented")), List.of(2)),
                step(2, unknown, Map.of("query", userMarker), List.of(1)),
                finalStep(3, List.of(2))
            ), List.of(unknown), 1);
        }

        private String envelope(List<Map<String, Object>> steps,
                                List<String> allowTools,
                                int maxSteps) {
            try {
                return new ObjectMapper().writeValueAsString(Map.of(
                    "version", "1.0",
                    "intent", Map.of("type", "workflow", "goal", userMarker, "risk_level", "low"),
                    "context", Map.of("key_facts", List.of(), "assumptions", List.of(),
                        "missing_info", List.of(), "constraints", List.of()),
                    "plan", Map.of("steps", steps),
                    "execution_policy", Map.of(
                        "max_steps", maxSteps,
                        "allow_parallel", false,
                        "allow_tool", allowTools,
                        "deny_tool", List.of(),
                        "max_rewrite_times", 0,
                        "fallback_mode", "partial_result"),
                    "review", Map.of("self_check", Map.of(
                        "completeness_score", 0.1,
                        "hallucination_risk", 0.99,
                        "tool_sufficiency", false,
                        "missing_steps", List.of()), "fallback_plan", List.of())
                ));
            } catch (Exception ex) {
                throw new IllegalStateException(ex);
            }
        }

        private Map<String, Object> step(int id,
                                         String tool,
                                         Map<String, Object> input,
                                         List<Integer> dependencies) {
            return Map.of(
                "id", id,
                "action_type", "mcp_tool",
                "tool_name", tool,
                "input", input,
                "depends_on", dependencies
            );
        }

        private Map<String, Object> finalStep(int id, List<Integer> dependencies) {
            return Map.of(
                "id", id,
                "action_type", "final_answer",
                "tool_name", "",
                "input", Map.of("answer", "model final before " + userMarker),
                "depends_on", dependencies
            );
        }

        private AtomicInteger plannerResponses() {
            return plannerResponses;
        }

        private List<String> emittedDriftKinds() {
            return emittedDriftKinds;
        }
    }
}
