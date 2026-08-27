package com.chatchat.e2e;

import com.chatchat.agents.orchestration.AgentOrchestrator;
import com.chatchat.agents.runtime.AgentRunRequest;
import com.chatchat.agents.runtime.AgentRunResult;
import com.chatchat.agents.runtime.run.AgentRunStatus;
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
 * Cross-layer release gate for the Runtime OS repairs. Randomized tool identities ensure
 * the scenarios are satisfied through published contracts and workflow semantics.
 */
class ProductionAgentRuntimeOsRepairE2E {

    @Test
    void compilesNestedPlannerArgumentsAndRecoversOneStructuredContractFailure() {
        Scenario scenario = scenario(false);
        try {
            AgentRunResult result = scenario.orchestrator().execute(scenario.request(5_000L));

            assertThat(result.status()).isEqualTo(AgentRunStatus.COMPLETED);
            assertThat(result.answer()).contains(scenario.marker());
            assertThat(result.metadata())
                .containsEntry("runStatus", "COMPLETED")
                .containsEntry("answerStatus", "SUCCESS")
                .containsEntry("workflowStatus", "COMPLETED")
                .containsEntry("publicStatus", "SUCCESS")
                .containsEntry("contractVersion", "ui_response_v2")
                .containsEntry("mandatoryWorkflowCompleted", true);
            assertThat(scenario.invocations())
                .containsExactly(
                    scenario.assetTool(),
                    scenario.sqlMetadataTool(),
                    scenario.enterpriseMetadataTool(),
                    scenario.enterpriseMetadataTool()
                );
            assertThat(scenario.enterpriseCalls()).hasValue(2);
            assertThat(scenario.enterpriseInputs()).allSatisfy(input -> assertThat(input)
                .containsKeys("query", "queryTerms")
                .doesNotContainKey("filters"));
            assertThat(scenario.enterpriseContexts().get(1))
                .containsKey("runtimeContractRepair");
            assertThat(result.metadata())
                .doesNotContainKey("authoritativeRuntimeFallbackAfterInvalidPlan");
        } finally {
            scenario.runtime().shutdown();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void terminalMandatoryFailureProducesPartialResultInsteadOfMissingTerminalState() {
        Scenario scenario = scenario(true);
        try {
            AgentRunResult result = scenario.orchestrator().execute(scenario.request(5_000L));

            assertThat(result.status()).isEqualTo(AgentRunStatus.COMPLETED);
            assertThat(result.answer()).contains("已执行并失败");
            assertThat(result.metadata())
                .containsEntry("runStatus", "COMPLETED")
                .containsEntry("answerStatus", "PARTIAL")
                .containsEntry("workflowStatus", "FAILED_REQUIRED_EVIDENCE")
                .containsEntry("publicStatus", "PARTIAL_SUCCESS")
                .containsEntry("mandatoryWorkflowTerminal", true)
                .containsEntry("mandatoryWorkflowPending", false);
            assertThat((List<String>) result.metadata().get("failedMandatoryTools"))
                .containsExactly(scenario.enterpriseMetadataTool());
            Map<String, Object> states =
                (Map<String, Object>) result.metadata().get("mandatoryToolStates");
            assertThat((Map<String, Object>) states.get(scenario.enterpriseMetadataTool()))
                .containsEntry("attempted", true)
                .containsEntry("terminal", true)
                .containsEntry("successful", false)
                .containsEntry("evidenceAccepted", false);
            assertThat(scenario.enterpriseCalls()).hasValue(2);
        } finally {
            scenario.runtime().shutdown();
        }
    }

    @Test
    void requestDeadlineStopsPlannerAndProjectsBudgetExhaustion() {
        String token = token();
        ToolRegistry registry = mock(ToolRegistry.class);
        ToolRuntimeProperties properties = runtimeProperties();
        ToolRuntimeService runtime = new ToolRuntimeService(
            registry, new ObjectMapper(), properties, List.of(), List.of());
        ChatModel slowModel = new ChatModel() {
            @Override
            public String chat(String prompt) {
                try {
                    Thread.sleep(5_000L);
                    return "{\"action\":\"final\",\"answer\":\"late\"}";
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    return "{\"action\":\"final\",\"answer\":\"interrupted\"}";
                }
            }
        };
        AgentOrchestrator orchestrator = new AgentOrchestrator(
            slowModel, registry, runtime, new ObjectMapper(), new ModelsConfig());
        long startedAt = System.currentTimeMillis();
        try {
            AgentRunResult result = orchestrator.execute(AgentRunRequest.builder()
                .runId("deadline_" + token)
                .query("deadline " + token)
                .tenantId("tenant_" + token)
                .availableTools(List.of())
                .requestId("request_" + token)
                .conversationId("conversation_" + token)
                .userId("user_" + token)
                .timeoutMs(50L)
                .build());

            assertThat(System.currentTimeMillis() - startedAt).isLessThan(2_000L);
            assertThat(result.status()).isEqualTo(AgentRunStatus.COMPLETED);
            assertThat(result.answer()).isEmpty();
            assertThat(result.stopReason()).isEqualTo("time_budget_exhausted");
            assertThat(result.metadata())
                .containsEntry("workflowStatus", "BUDGET_EXHAUSTED")
                .containsEntry("publicStatus", "TIME_BUDGET_EXHAUSTED")
                .containsEntry("contractVersion", "ui_response_v2");
        } finally {
            runtime.shutdown();
        }
    }

    private Scenario scenario(boolean enterpriseAlwaysFails) {
        String token = token();
        String prefix = "mcp_" + token + "_";
        String assetTool = prefix + "database_asset_search";
        String sqlMetadataTool = prefix + "sql_metadata_search";
        String enterpriseMetadataTool = prefix + "enterprise_metadata_search";
        String marker = "RUNTIME_OS_" + token;
        List<String> invocations = new CopyOnWriteArrayList<>();
        List<Map<String, Object>> enterpriseInputs = new CopyOnWriteArrayList<>();
        List<Map<String, Object>> enterpriseContexts = new CopyOnWriteArrayList<>();
        AtomicInteger enterpriseCalls = new AtomicInteger();
        ToolRegistry registry = mock(ToolRegistry.class);

        for (String tool : List.of(assetTool, sqlMetadataTool, enterpriseMetadataTool)) {
            when(registry.hasTool(tool)).thenReturn(true);
            when(registry.getToolMetadata(tool)).thenReturn(metadata(tool));
        }
        when(registry.executeEnhancedTool(any(), any())).thenAnswer(call -> {
            String tool = call.getArgument(0);
            ToolInput input = call.getArgument(1);
            invocations.add(tool);
            if (assetTool.equals(tool)) {
                return ToolOutput.success(Map.of(
                    "schemaVersion", "asset_query_result.v1",
                    "success", true,
                    "returnedCount", 1,
                    "assets", List.of(Map.of("asset", Map.of(
                        "id", "asset_" + token,
                        "name", "warehouse_" + token,
                        "environment", "DEV"
                    )))
                ));
            }
            if (sqlMetadataTool.equals(tool)) {
                return ToolOutput.success(Map.of(
                    "schemaVersion", "sql_metadata_search_result.v1",
                    "success", true,
                    "tables", List.of(Map.of(
                        "tableName", "position_" + token,
                        "columns", List.of(Map.of("name", "position_id", "type", "STRING"))
                    ))
                ));
            }
            if (enterpriseMetadataTool.equals(tool)) {
                enterpriseInputs.add(new LinkedHashMap<>(input.getParameters()));
                enterpriseContexts.add(new LinkedHashMap<>(input.getContext()));
                int attempt = enterpriseCalls.incrementAndGet();
                if (enterpriseAlwaysFails || attempt == 1) {
                    ToolOutput failure = ToolOutput.failure("Published input contract requires canonical evidence");
                    failure.setExceptionType("MCP_TOOL_ERROR");
                    failure.setData(Map.of(
                        "category", "INPUT_CONTRACT_ERROR",
                        "errorCode", "ENTERPRISE_INPUT_REQUIRED"
                    ));
                    failure.setMetadata(new LinkedHashMap<>(Map.of("retryable", false)));
                    return failure;
                }
                return ToolOutput.success(Map.of(
                    "schemaVersion", "enterprise_metadata_search_result.v3",
                    "success", true,
                    "fields", List.of(Map.of(
                        "businessName", "Position identifier",
                        "technicalName", "position_id",
                        "dataType", "STRING"
                    ))
                ));
            }
            return ToolOutput.failure("Unexpected tool " + tool);
        });

        ToolRuntimeService runtime = new ToolRuntimeService(
            registry, new ObjectMapper(), runtimeProperties(), List.of(), List.of());
        ChatModel model = new ScenarioModel(
            assetTool, sqlMetadataTool, enterpriseMetadataTool, marker);
        AgentOrchestrator orchestrator = new AgentOrchestrator(
            model, registry, runtime, new ObjectMapper(), new ModelsConfig());
        return new Scenario(
            token, marker, assetTool, sqlMetadataTool, enterpriseMetadataTool,
            invocations, enterpriseInputs, enterpriseContexts, enterpriseCalls,
            runtime, orchestrator
        );
    }

    private ToolMetadata metadata(String toolName) {
        return ToolMetadata.builder()
            .id(toolName)
            .title("Runtime generated metadata capability")
            .description("Runtime generated read-only metadata capability")
            .operationType("read")
            .riskLevel("low")
            .runtimeLevel("readonly")
            .categories(List.of("mcp"))
            .metadata(Map.of("inputSchema", Map.of(
                "type", "object",
                "properties", Map.of(
                    "query", Map.of("type", "string"),
                    "queryTerms", Map.of(
                        "type", "array",
                        "items", Map.of("type", "string"),
                        "aliases", List.of("keywords")
                    ),
                    "limit", Map.of("type", "integer")
                ),
                "required", List.of("query"),
                "additionalProperties", false
            )))
            .build();
    }

    private ToolRuntimeProperties runtimeProperties() {
        ToolRuntimeProperties properties = new ToolRuntimeProperties();
        properties.setDefaultRetryAttempts(0);
        properties.setEnforceAllowedTools(true);
        properties.setDefaultToolTimeoutMs(5_000L);
        return properties;
    }

    private String token() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private record Scenario(
        String token,
        String marker,
        String assetTool,
        String sqlMetadataTool,
        String enterpriseMetadataTool,
        List<String> invocations,
        List<Map<String, Object>> enterpriseInputs,
        List<Map<String, Object>> enterpriseContexts,
        AtomicInteger enterpriseCalls,
        ToolRuntimeService runtime,
        AgentOrchestrator orchestrator
    ) {
        AgentRunRequest request(long timeoutMs) {
            Map<String, Object> workflow = Map.of(
                "enabled", true,
                "workflow", "metadata_" + token,
                "executionStrategy", Map.of(
                    "mode", "sequential",
                    "stopOnError", true,
                    "maxSteps", 5,
                    "latencyBudgetMs", 4_000
                ),
                "steps", List.of(
                    Map.of("id", "asset", "tool", assetTool, "required", true),
                    Map.of("id", "sql", "tool", sqlMetadataTool, "required", true,
                        "dependsOn", List.of("asset")),
                    Map.of("id", "enterprise", "tool", enterpriseMetadataTool, "required", true,
                        "dependsOn", List.of("sql"))
                )
            );
            return AgentRunRequest.builder()
                .runId("run_" + token)
                .query("Design metadata " + marker)
                .tenantId("tenant_" + token)
                .availableTools(List.of(assetTool, sqlMetadataTool, enterpriseMetadataTool))
                .systemPrompt("Execute the configured metadata workflow.")
                .skillId("runtime_os_repair")
                .requestId("request_" + token)
                .conversationId("conversation_" + token)
                .userId("user_" + token)
                .timeoutMs(timeoutMs)
                .attributes(Map.of("mcpWorkflow", workflow))
                .build();
        }
    }

    private static final class ScenarioModel implements ChatModel {
        private final String assetTool;
        private final String sqlMetadataTool;
        private final String enterpriseMetadataTool;
        private final String marker;
        private final ObjectMapper objectMapper = new ObjectMapper();

        private ScenarioModel(String assetTool,
                              String sqlMetadataTool,
                              String enterpriseMetadataTool,
                              String marker) {
            this.assetTool = assetTool;
            this.sqlMetadataTool = sqlMetadataTool;
            this.enterpriseMetadataTool = enterpriseMetadataTool;
            this.marker = marker;
        }

        @Override
        public String chat(String prompt) {
            if (prompt.contains("runtime reviewer for one completed MCP tool call")) {
                return "{\"satisfied\":true,\"reason\":\"evidence accepted\",\"confidence\":1.0}";
            }
            if (prompt.contains("final step-by-step answer synthesizer")) {
                return "Completed " + marker + " from runtime evidence.";
            }
            if (prompt.contains("final answer quality reviewer")) {
                return "{\"accepted\":true,\"feedback\":\"grounded\",\"revisedAnswer\":\"\"}";
            }
            if (prompt.contains("Build a precise retrieval profile")) {
                return "{\"profile\":{\"intent\":\"" + marker + "\",\"terms\":[\""
                    + marker + "\"]},\"arguments\":{},\"argumentEvidence\":{}}";
            }
            return plannerResponse();
        }

        private String plannerResponse() {
            try {
                List<Map<String, Object>> steps = new ArrayList<>();
                steps.add(step(1, assetTool, List.of()));
                steps.add(step(2, sqlMetadataTool, List.of(1)));
                steps.add(step(3, enterpriseMetadataTool, List.of(2)));
                steps.add(Map.of(
                    "id", 4,
                    "action_type", "final_answer",
                    "tool_name", "",
                    "input", Map.of("answer", "Completed " + marker),
                    "depends_on", List.of(3)
                ));
                return objectMapper.writeValueAsString(Map.of(
                    "version", "1.0",
                    "intent", Map.of("type", "workflow", "goal", marker, "risk_level", "low"),
                    "context", Map.of(
                        "key_facts", List.of(),
                        "assumptions", List.of(),
                        "missing_info", List.of(),
                        "constraints", List.of()
                    ),
                    "plan", Map.of("steps", steps),
                    "execution_policy", Map.of(
                        "max_steps", 4,
                        "allow_parallel", false,
                        "allow_tool", List.of(assetTool, sqlMetadataTool, enterpriseMetadataTool),
                        "deny_tool", List.of(),
                        "max_rewrite_times", 0,
                        "fallback_mode", "partial_result",
                        "latency_budget_ms", 4_000
                    ),
                    "review", Map.of(
                        "self_check", Map.of(
                            "completeness_score", 0.9,
                            "hallucination_risk", 0.1,
                            "tool_sufficiency", false,
                            "missing_steps", List.of()
                        ),
                        "fallback_plan", List.of()
                    )
                ));
            } catch (Exception ex) {
                throw new IllegalStateException(ex);
            }
        }

        private Map<String, Object> step(int id, String tool, List<Integer> dependencies) {
            return Map.of(
                "id", id,
                "action_type", "mcp_tool",
                "tool_name", tool,
                "input", Map.of(
                    "filters", Map.of(
                        "query", "Discover " + marker,
                        "keywords", List.of("position", marker)
                    ),
                    "limit", "20"
                ),
                "depends_on", dependencies
            );
        }
    }
}
