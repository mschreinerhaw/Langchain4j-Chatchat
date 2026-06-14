package com.chatchat.agents.orchestration;

import com.chatchat.agents.runtime.ToolRuntimeService;
import com.chatchat.agents.runtime.ToolRuntimeProperties;
import com.chatchat.agents.runtime.AgentRunRequest;
import com.chatchat.agents.runtime.AgentRunResult;
import com.chatchat.agents.runtime.AgentRunStatus;
import com.chatchat.agents.runtime.InMemoryAgentRunStore;
import com.chatchat.agents.tool.ToolRegistry;
import com.chatchat.common.config.ModelsConfig;
import com.chatchat.common.interaction.InteractionToolTrace;
import com.chatchat.common.tool.ToolMetadata;
import com.chatchat.common.tool.ToolOutput;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Queue;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentOrchestratorTest {

    @Test
    void interpretationPlanRunsThroughDagRuntimePipeline() {
        QueueChatModel chatModel = new QueueChatModel(
            """
                {
                  "version": "1.0",
                  "intent": {"type": "document_retrieval", "goal": "Use document evidence", "risk_level": "low"},
                  "context": {"key_facts": [], "assumptions": [], "missing_info": [], "constraints": ["Use registered tools"]},
                  "plan": {
                    "steps": [
                      {"id": 1, "action_type": "mcp_tool", "tool_name": "document_search", "input": {"query": "internal definition"}, "depends_on": []},
                      {"id": 2, "action_type": "final_answer", "tool_name": "", "input": {"answer": "Use the internal definition handbook."}, "depends_on": [1]}
                    ]
                  },
                  "execution_policy": {"max_steps": 2, "allow_parallel": false, "allow_tool": ["document_search"], "deny_tool": [], "timeout_ms": 30000},
                  "review": {"self_check": {"completeness_score": 0.9, "hallucination_risk": 0.1, "tool_sufficiency": true, "missing_steps": []}, "fallback_plan": []}
                }
                """,
            "{\"accepted\":true,\"feedback\":\"The answer follows the plan.\",\"revisedAnswer\":\"\"}"
        );
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.hasTool("document_search")).thenReturn(true);
        when(toolRegistry.getToolMetadata("document_search")).thenReturn(ToolMetadata.builder()
            .id("document_search")
            .title("Document Search")
            .description("Search internal documents")
            .riskLevel("low")
            .build());
        when(toolRegistry.executeEnhancedTool(eq("document_search"), any())).thenReturn(documentSearchOutput());
        AgentOrchestrator orchestrator = new AgentOrchestrator(
            chatModel,
            toolRegistry,
            new ToolRuntimeService(toolRegistry, new ObjectMapper(), toolRuntimeProperties(), List.of(), List.of()),
            new ObjectMapper(),
            new ModelsConfig()
        );

        AgentOrchestrator.AgentExecutionResult result = orchestrator.executeAgent(
            "What is the internal definition?",
            "tenant-1",
            List.of("document_search"),
            "Use internal evidence.",
            null,
            List.of(),
            List.of(),
            "research",
            "req-plan-pipeline",
            "conv-plan-pipeline",
            "user-1",
            10,
            List.of(),
            false
        );

        assertThat(result.answer()).isEqualTo("Use the internal definition handbook.");
        assertThat(result.toolTraces()).extracting(InteractionToolTrace::getToolName)
            .containsExactly("document_search");
        assertThat(result.metadata())
            .containsEntry("interpretationPlanPipeline", true)
            .containsEntry("interpretationPlanInitialSuccess", true)
            .containsEntry("stopReason", "interpretation_plan_completed");
    }

    @Test
    void interpretationPlanFailureTriggersRewriteAndRunsRewrittenPlan() {
        QueueChatModel chatModel = new QueueChatModel(
            """
                {
                  "version": "1.0",
                  "intent": {"type": "document_retrieval", "goal": "Use document evidence", "risk_level": "low"},
                  "context": {"key_facts": [], "assumptions": [], "missing_info": [], "constraints": ["Use registered tools"]},
                  "plan": {
                    "steps": [
                      {"id": 1, "action_type": "mcp_tool", "tool_name": "document_search", "input": {"query": "internal definition"}, "depends_on": []},
                      {"id": 2, "action_type": "final_answer", "tool_name": "", "input": {"answer": "Use document evidence."}, "depends_on": [1]}
                    ]
                  },
                  "execution_policy": {"max_steps": 2, "allow_parallel": false, "allow_tool": ["document_search"], "deny_tool": [], "timeout_ms": 30000},
                  "review": {"self_check": {"completeness_score": 0.7, "hallucination_risk": 0.2, "tool_sufficiency": false, "missing_steps": []}, "fallback_plan": []}
                }
                """,
            """
                {
                  "version": "1.0",
                  "intent": {"type": "web_search", "goal": "Recover with web evidence", "risk_level": "low"},
                  "context": {"key_facts": ["document_search failed"], "assumptions": [], "missing_info": [], "constraints": ["Use registered tools"]},
                  "plan": {
                    "steps": [
                      {"id": 1, "action_type": "mcp_tool", "tool_name": "web_search", "input": {"query": "internal definition public evidence"}, "depends_on": []},
                      {"id": 2, "action_type": "final_answer", "tool_name": "", "input": {"answer": "Use web evidence fallback."}, "depends_on": [1]}
                    ]
                  },
                  "execution_policy": {"max_steps": 2, "allow_parallel": false, "allow_tool": ["web_search"], "deny_tool": ["document_search"], "timeout_ms": 30000},
                  "review": {"self_check": {"completeness_score": 0.8, "hallucination_risk": 0.2, "tool_sufficiency": true, "missing_steps": []}, "fallback_plan": []}
                }
                """,
            "{\"accepted\":true,\"feedback\":\"The rewritten answer follows available evidence.\",\"revisedAnswer\":\"\"}"
        );
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.hasTool("document_search")).thenReturn(true);
        when(toolRegistry.hasTool("web_search")).thenReturn(true);
        when(toolRegistry.getToolMetadata("document_search")).thenReturn(ToolMetadata.builder()
            .id("document_search")
            .title("Document Search")
            .description("Search internal documents")
            .riskLevel("low")
            .build());
        when(toolRegistry.getToolMetadata("web_search")).thenReturn(ToolMetadata.builder()
            .id("web_search")
            .title("Web Search")
            .description("Search web pages")
            .riskLevel("low")
            .build());
        when(toolRegistry.executeEnhancedTool(eq("document_search"), any())).thenReturn(ToolOutput.failure("document backend down"));
        when(toolRegistry.executeEnhancedTool(eq("web_search"), any())).thenReturn(webSearchOutput());
        AgentOrchestrator orchestrator = new AgentOrchestrator(
            chatModel,
            toolRegistry,
            new ToolRuntimeService(toolRegistry, new ObjectMapper(), toolRuntimeProperties(), List.of(), List.of()),
            new ObjectMapper(),
            new ModelsConfig()
        );

        AgentOrchestrator.AgentExecutionResult result = orchestrator.executeAgent(
            "What is the internal definition?",
            "tenant-1",
            List.of("document_search", "web_search"),
            "Recover safely if internal evidence is unavailable.",
            null,
            List.of(),
            List.of(),
            "research",
            "req-plan-rewrite",
            "conv-plan-rewrite",
            "user-1",
            10,
            List.of(),
            false
        );

        assertThat(result.answer()).isEqualTo("Use web evidence fallback.");
        assertThat(result.toolTraces()).extracting(InteractionToolTrace::getToolName)
            .containsExactly("document_search", "web_search");
        assertThat(result.metadata())
            .containsEntry("interpretationPlanRewriteAttempted", true)
            .containsEntry("interpretationPlanRewriteValid", true)
            .containsEntry("interpretationPlanRewriteSuccess", true)
            .containsEntry("stopReason", "interpretation_plan_rewritten");
    }

    @Test
    void interpretationPlanHonorsZeroRewriteBudgetAndFallsBack() {
        QueueChatModel chatModel = new QueueChatModel(
            """
                {
                  "version": "1.0",
                  "intent": {"type": "document_retrieval", "goal": "Use document evidence", "risk_level": "low"},
                  "context": {"key_facts": [], "assumptions": [], "missing_info": [], "constraints": ["Use registered tools"]},
                  "plan": {
                    "steps": [
                      {"id": 1, "action_type": "mcp_tool", "tool_name": "document_search", "input": {"query": "internal definition"}, "depends_on": []},
                      {"id": 2, "action_type": "final_answer", "tool_name": "", "input": {"answer": "Use document evidence."}, "depends_on": [1]}
                    ]
                  },
                  "execution_policy": {"max_steps": 2, "allow_parallel": false, "allow_tool": ["document_search"], "deny_tool": [], "timeout_ms": 30000, "max_rewrite_times": 0, "fallback_mode": "partial_result"},
                  "review": {"self_check": {"completeness_score": 0.7, "hallucination_risk": 0.2, "tool_sufficiency": false, "missing_steps": []}, "fallback_plan": []}
                }
                """,
            "Internal evidence is unavailable, so only a partial result can be provided.",
            "{\"accepted\":true,\"feedback\":\"The fallback is honest about missing evidence.\",\"revisedAnswer\":\"\"}"
        );
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.hasTool("document_search")).thenReturn(true);
        when(toolRegistry.getToolMetadata("document_search")).thenReturn(ToolMetadata.builder()
            .id("document_search")
            .title("Document Search")
            .description("Search internal documents")
            .riskLevel("low")
            .build());
        when(toolRegistry.executeEnhancedTool(eq("document_search"), any())).thenReturn(ToolOutput.failure("document backend down"));
        AgentOrchestrator orchestrator = new AgentOrchestrator(
            chatModel,
            toolRegistry,
            new ToolRuntimeService(toolRegistry, new ObjectMapper(), toolRuntimeProperties(), List.of(), List.of()),
            new ObjectMapper(),
            new ModelsConfig()
        );

        AgentOrchestrator.AgentExecutionResult result = orchestrator.executeAgent(
            "What is the internal definition?",
            "tenant-1",
            List.of("document_search"),
            "Do not rewrite when budget is exhausted.",
            null,
            List.of(),
            List.of(),
            "research",
            "req-plan-no-rewrite",
            "conv-plan-no-rewrite",
            "user-1",
            10,
            List.of(),
            false
        );

        assertThat(result.answer()).isEqualTo("Internal evidence is unavailable, so only a partial result can be provided.");
        assertThat(result.metadata())
            .containsEntry("interpretationPlanRewriteBudgetExceeded", true)
            .containsEntry("interpretationPlanFallbackMode", "partial_result")
            .containsEntry("stopReason", "interpretation_plan_failed");
        assertThat(result.metadata()).doesNotContainKey("interpretationPlanRewriteAttempted");
    }

    @Test
    void executeRuntimeRequestReturnsTypedRunResult() {
        QueueChatModel chatModel = new QueueChatModel(
            "{\"action\":\"tool\",\"toolName\":\"document_search\",\"arguments\":{\"query\":\"internal definition\"},\"reason\":\"Need internal evidence\"}",
            "{\"action\":\"final\",\"answer\":\"Use the internal definition handbook.\"}",
            "{\"accepted\":true,\"feedback\":\"The answer uses document evidence.\",\"revisedAnswer\":\"\"}"
        );
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.getToolMetadata("document_search")).thenReturn(ToolMetadata.builder()
            .id("document_search")
            .title("Document Search")
            .description("Search internal documents")
            .build());
        when(toolRegistry.executeEnhancedTool(eq("document_search"), any())).thenReturn(documentSearchOutput());
        ToolRuntimeService toolRuntimeService = new ToolRuntimeService(
            toolRegistry,
            new ObjectMapper(),
            toolRuntimeProperties(),
            List.of(),
            List.of()
        );
        AgentOrchestrator orchestrator = new AgentOrchestrator(
            chatModel,
            toolRegistry,
            toolRuntimeService,
            new ObjectMapper(),
            new ModelsConfig()
        );

        AgentRunResult result = orchestrator.execute(AgentRunRequest.builder()
            .query("What is the internal definition?")
            .tenantId("tenant-1")
            .availableTools(List.of("document_search"))
            .systemPrompt("Use internal evidence.")
            .skillId("research")
            .requestId("req-runtime-1")
            .conversationId("conv-runtime-1")
            .userId("user-1")
            .webSearchResultLimit(10)
            .build());

        assertThat(result.answer()).isEqualTo("Use the internal definition handbook.");
        assertThat(result.stopReason()).isEqualTo("final_answer");
        assertThat(result.toolTraces())
            .extracting(InteractionToolTrace::getToolName)
            .containsExactly("document_search");
        assertThat(result.steps().stream().map(step -> step.action()).toList())
            .containsExactly("tool", "final");
        assertThat(result.observations().stream().map(observation -> observation.type()).toList())
            .contains("document_evidence");
        assertThat(result.metadata())
            .containsEntry("runtimeContractVersion", "agent_runtime_v1")
            .containsEntry("toolTraceCount", 1);
    }

    @Test
    void plannerSufficientSignalAllowsFinalBeforeNonWorkflowRequiredTool() {
        QueueChatModel chatModel = new QueueChatModel(
            "{\"action\":\"final\",\"answer\":\"The existing context is enough.\",\"reason\":\"No extra lookup needed\",\"sufficient\":true}",
            "{\"accepted\":true,\"feedback\":\"The answer is sufficiently grounded.\",\"revisedAnswer\":\"\"}"
        );
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.getToolMetadata("document_search")).thenReturn(ToolMetadata.builder()
            .id("document_search")
            .title("Document Search")
            .description("Search internal documents")
            .build());
        ToolRuntimeService toolRuntimeService = new ToolRuntimeService(
            toolRegistry,
            new ObjectMapper(),
            toolRuntimeProperties(),
            List.of(),
            List.of()
        );
        AgentOrchestrator orchestrator = new AgentOrchestrator(
            chatModel,
            toolRegistry,
            toolRuntimeService,
            new ObjectMapper(),
            new ModelsConfig()
        );

        AgentOrchestrator.AgentExecutionResult result = orchestrator.executeAgent(
            "Use existing context only.",
            "tenant-1",
            List.of("document_search"),
            "Do not use tools unless needed.",
            null,
            List.of(),
            List.of(),
            "research",
            "req-sufficient-final",
            "conv-sufficient-final",
            "user-1",
            10,
            List.of("document_search"),
            true,
            Map.of()
        );

        assertThat(result.answer()).isEqualTo("The existing context is enough.");
        assertThat(result.toolTraces()).isEmpty();
        assertThat(result.metadata())
            .containsEntry("finalDecisionReason", "PLANNER_SUFFICIENT")
            .containsEntry("plannerSufficient", true)
            .containsEntry("stopReason", "final_answer");
        verify(toolRegistry, never()).executeEnhancedTool(eq("document_search"), any());
    }

    @Test
    void runtimeRunStorePersistsRunLifecycleEvents() {
        QueueChatModel chatModel = new QueueChatModel(
            "{\"action\":\"tool\",\"toolName\":\"document_search\",\"arguments\":{\"query\":\"internal definition\"},\"reason\":\"Need internal evidence\"}",
            "{\"action\":\"final\",\"answer\":\"Use the persisted run evidence.\"}",
            "{\"accepted\":true,\"feedback\":\"The answer uses document evidence.\",\"revisedAnswer\":\"\"}"
        );
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.getToolMetadata("document_search")).thenReturn(ToolMetadata.builder()
            .id("document_search")
            .title("Document Search")
            .description("Search internal documents")
            .build());
        when(toolRegistry.executeEnhancedTool(eq("document_search"), any())).thenReturn(documentSearchOutput());
        InMemoryAgentRunStore runStore = new InMemoryAgentRunStore();
        AgentOrchestrator orchestrator = new AgentOrchestrator(
            chatModel,
            toolRegistry,
            new ToolRuntimeService(
                toolRegistry,
                new ObjectMapper(),
                toolRuntimeProperties(),
                List.of(),
                List.of()
            ),
            new ObjectMapper(),
            new ModelsConfig(),
            new EvidenceTrustEvaluator(),
            runStore
        );

        AgentRunResult result = orchestrator.execute(AgentRunRequest.builder()
            .runId("run-store-1")
            .query("What is the internal definition?")
            .tenantId("tenant-1")
            .availableTools(List.of("document_search"))
            .systemPrompt("Use internal evidence.")
            .requestId("req-run-store-1")
            .conversationId("conv-run-store-1")
            .userId("user-1")
            .build());

        assertThat(result.runId()).isEqualTo("run-store-1");
        assertThat(result.status()).isEqualTo(AgentRunStatus.COMPLETED);
        assertThat(result.events()).extracting(event -> event.type().name())
            .contains("RUN_STARTED", "STEP_RECORDED", "OBSERVATION_RECORDED", "RUN_COMPLETED");
        assertThat(runStore.find("run-store-1")).isPresent();
        assertThat(runStore.find("run-store-1").orElseThrow().status()).isEqualTo(AgentRunStatus.COMPLETED);
        assertThat(runStore.find("run-store-1").orElseThrow().steps()).hasSize(2);
        assertThat(runStore.events("run-store-1")).hasSize(result.events().size());
    }

    @Test
    void executeRuntimeRequestReturnsCancelledRunWhenCancellationIsRequested() {
        QueueChatModel chatModel = new QueueChatModel();
        InMemoryAgentRunStore runStore = new InMemoryAgentRunStore();
        AgentOrchestrator orchestrator = new AgentOrchestrator(
            chatModel,
            mock(ToolRegistry.class),
            new ToolRuntimeService(
                mock(ToolRegistry.class),
                new ObjectMapper(),
                toolRuntimeProperties(),
                List.of(),
                List.of()
            ),
            new ObjectMapper(),
            new ModelsConfig(),
            new EvidenceTrustEvaluator(),
            runStore
        );

        AgentRunResult result = orchestrator.execute(AgentRunRequest.builder()
            .runId("run-cancel-1")
            .query("Cancel this run")
            .tenantId("tenant-1")
            .availableTools(List.of())
            .requestId("req-cancel-1")
            .attributes(Map.of("__agentCancellation", (BooleanSupplier) () -> true))
            .build());

        assertThat(result.status()).isEqualTo(AgentRunStatus.CANCELLED);
        assertThat(result.stopReason()).isEqualTo("cancelled");
        assertThat(result.events()).extracting(event -> event.type().name())
            .contains("RUN_STARTED", "RUN_CANCELLED");
        assertThat(runStore.find("run-cancel-1")).isPresent();
        assertThat(runStore.find("run-cancel-1").orElseThrow().status()).isEqualTo(AgentRunStatus.CANCELLED);
    }

    @Test
    void executeRuntimeRequestHonorsMaxStepsBudget() {
        QueueChatModel chatModel = new QueueChatModel(
            "{\"action\":\"tool\",\"toolName\":\"document_search\",\"arguments\":{\"query\":\"internal definition\"},\"reason\":\"Need internal evidence\"}",
            "Fallback answer from one observed tool.",
            "{\"accepted\":true,\"feedback\":\"The answer uses the observed tool result.\",\"revisedAnswer\":\"\"}"
        );
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.getToolMetadata("document_search")).thenReturn(ToolMetadata.builder()
            .id("document_search")
            .title("Document Search")
            .description("Search internal documents")
            .build());
        when(toolRegistry.executeEnhancedTool(eq("document_search"), any())).thenReturn(documentSearchOutput());
        AgentOrchestrator orchestrator = new AgentOrchestrator(
            chatModel,
            toolRegistry,
            new ToolRuntimeService(
                toolRegistry,
                new ObjectMapper(),
                toolRuntimeProperties(),
                List.of(),
                List.of()
            ),
            new ObjectMapper(),
            new ModelsConfig()
        );

        AgentRunResult result = orchestrator.execute(AgentRunRequest.builder()
            .runId("run-max-steps-1")
            .query("What is the internal definition?")
            .tenantId("tenant-1")
            .availableTools(List.of("document_search"))
            .requestId("req-max-steps-1")
            .maxSteps(1)
            .build());

        assertThat(result.status()).isEqualTo(AgentRunStatus.COMPLETED);
        assertThat(result.answer()).isEqualTo("Fallback answer from one observed tool.");
        assertThat(result.stopReason()).isEqualTo("max_steps_or_fallback");
        assertThat(result.metadata())
            .containsEntry("maxSteps", 1)
            .containsEntry("steps", 1);
        assertThat(result.toolTraces())
            .extracting(InteractionToolTrace::getToolName)
            .containsExactly("document_search");
    }

    @Test
    void executeRuntimeRequestHonorsMaxToolCallsBudget() {
        QueueChatModel chatModel = new QueueChatModel(
            "{\"action\":\"tool\",\"toolName\":\"document_search\",\"arguments\":{\"query\":\"internal definition\"},\"reason\":\"Need internal evidence\"}",
            "Cannot call tools under the current runtime budget.",
            "{\"accepted\":true,\"feedback\":\"The answer explains the runtime limit.\",\"revisedAnswer\":\"\"}"
        );
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.getToolMetadata("document_search")).thenReturn(ToolMetadata.builder()
            .id("document_search")
            .title("Document Search")
            .description("Search internal documents")
            .build());
        AgentOrchestrator orchestrator = new AgentOrchestrator(
            chatModel,
            toolRegistry,
            new ToolRuntimeService(
                toolRegistry,
                new ObjectMapper(),
                toolRuntimeProperties(),
                List.of(),
                List.of()
            ),
            new ObjectMapper(),
            new ModelsConfig()
        );

        AgentRunResult result = orchestrator.execute(AgentRunRequest.builder()
            .runId("run-max-tool-calls-1")
            .query("What is the internal definition?")
            .tenantId("tenant-1")
            .availableTools(List.of("document_search"))
            .requestId("req-max-tool-calls-1")
            .maxToolCalls(0)
            .build());

        assertThat(result.status()).isEqualTo(AgentRunStatus.COMPLETED);
        assertThat(result.answer()).isEqualTo("Cannot call tools under the current runtime budget.");
        assertThat(result.stopReason()).isEqualTo("tool_budget_exceeded");
        assertThat(result.toolTraces()).isEmpty();
        assertThat(result.metadata())
            .containsEntry("maxToolCalls", 0)
            .containsEntry("toolBudgetExceeded", true)
            .containsEntry("requestedToolAfterBudget", "document_search");
        verify(toolRegistry, never()).executeEnhancedTool(eq("document_search"), any());
    }

    @Test
    void executeRuntimeRequestCancelsRunWhenTimeoutExpires() {
        ChatModel chatModel = new SlowChatModel(
            20L,
            "{\"action\":\"final\",\"answer\":\"This answer should arrive too late.\"}"
        );
        InMemoryAgentRunStore runStore = new InMemoryAgentRunStore();
        AgentOrchestrator orchestrator = new AgentOrchestrator(
            chatModel,
            mock(ToolRegistry.class),
            new ToolRuntimeService(
                mock(ToolRegistry.class),
                new ObjectMapper(),
                toolRuntimeProperties(),
                List.of(),
                List.of()
            ),
            new ObjectMapper(),
            new ModelsConfig(),
            new EvidenceTrustEvaluator(),
            runStore
        );

        AgentRunResult result = orchestrator.execute(AgentRunRequest.builder()
            .runId("run-timeout-1")
            .query("Timeout this run")
            .tenantId("tenant-1")
            .availableTools(List.of())
            .requestId("req-timeout-1")
            .timeoutMs(1L)
            .build());

        assertThat(result.status()).isEqualTo(AgentRunStatus.CANCELLED);
        assertThat(result.stopReason()).isEqualTo("cancelled");
        assertThat(result.errorMessage()).isEqualTo("Agent run timed out");
        assertThat(result.events()).extracting(event -> event.type().name())
            .contains("RUN_STARTED", "RUN_CANCELLED");
        assertThat(runStore.find("run-timeout-1")).isPresent();
        assertThat(runStore.find("run-timeout-1").orElseThrow().status()).isEqualTo(AgentRunStatus.CANCELLED);
    }

    @Test
    void executeRuntimeRequestReturnsFailedRunWhenPlannerThrows() {
        ChatModel chatModel = new FailingChatModel("planner boom");
        InMemoryAgentRunStore runStore = new InMemoryAgentRunStore();
        AgentOrchestrator orchestrator = new AgentOrchestrator(
            chatModel,
            mock(ToolRegistry.class),
            new ToolRuntimeService(
                mock(ToolRegistry.class),
                new ObjectMapper(),
                toolRuntimeProperties(),
                List.of(),
                List.of()
            ),
            new ObjectMapper(),
            new ModelsConfig(),
            new EvidenceTrustEvaluator(),
            runStore
        );

        AgentRunResult result = orchestrator.execute(AgentRunRequest.builder()
            .runId("run-fail-1")
            .query("Fail this run")
            .tenantId("tenant-1")
            .availableTools(List.of())
            .requestId("req-fail-1")
            .build());

        assertThat(result.status()).isEqualTo(AgentRunStatus.FAILED);
        assertThat(result.stopReason()).isEqualTo("failed");
        assertThat(result.errorMessage()).isEqualTo("planner boom");
        assertThat(result.events()).extracting(event -> event.type().name())
            .contains("RUN_STARTED", "RUN_FAILED");
        assertThat(runStore.find("run-fail-1")).isPresent();
        assertThat(runStore.find("run-fail-1").orElseThrow().status()).isEqualTo(AgentRunStatus.FAILED);
    }

    @Test
    void revisesFinalAnswerWhenReviewerRejectsIt() {
        QueueChatModel chatModel = new QueueChatModel(
            "{\"action\":\"final\",\"answer\":\"Please check the MySQL setup section in the deployment document.\"}",
            "{\"accepted\":false,\"feedback\":\"The answer did not directly provide the initialization steps.\",\"revisedAnswer\":\"Initialize LiveData by creating the MySQL database and user, then import schema.sql, update the datasource config, and restart the service to verify connectivity.\"}"
        );
        AgentOrchestrator orchestrator = newOrchestrator(chatModel);

        AgentOrchestrator.AgentExecutionResult result = orchestrator.executeAgent(
            "How do I initialize the LiveData database?",
            "tenant-1",
            List.of(),
            "You are the LiveData Studio operations assistant.",
            null,
            List.of(),
            List.of(),
            "livedata_ops",
            "req-1",
            "conv-1",
            "user-1",
            10,
            List.of(),
            false
        );

        assertThat(result.answer()).contains("schema.sql");
        assertThat(result.metadata())
            .containsEntry("answerReviewStatus", "revised")
            .containsEntry("answerReviewFeedback", "The answer did not directly provide the initialization steps.");
    }

    @Test
    void keepsFinalAnswerWhenReviewerAcceptsIt() {
        QueueChatModel chatModel = new QueueChatModel(
            "{\"action\":\"final\",\"answer\":\"Initialization steps: create the database, run schema.sql, configure the connection string, restart the service, and verify the health endpoint.\"}",
            "{\"accepted\":true,\"feedback\":\"The answer directly addresses the user request.\",\"revisedAnswer\":\"\"}"
        );
        AgentOrchestrator orchestrator = newOrchestrator(chatModel);

        AgentOrchestrator.AgentExecutionResult result = orchestrator.executeAgent(
            "How do I initialize the LiveData database?",
            "tenant-1",
            List.of(),
            "You are the LiveData Studio operations assistant.",
            null,
            List.of(),
            List.of(),
            "livedata_ops",
            "req-2",
            "conv-2",
            "user-1",
            10,
            List.of(),
            false
        );

        assertThat(result.answer()).isEqualTo("Initialization steps: create the database, run schema.sql, configure the connection string, restart the service, and verify the health endpoint.");
        assertThat(result.metadata())
            .containsEntry("answerReviewStatus", "accepted")
            .containsEntry("answerReviewFeedback", "The answer directly addresses the user request.");
    }

    @Test
    void includesWebCitationMapInPromptAfterWebSearch() {
        CapturingQueueChatModel chatModel = new CapturingQueueChatModel(
            "{\"action\":\"tool\",\"toolName\":\"web_search\",\"arguments\":{\"query\":\"AI citation audit\"},\"reason\":\"Need web evidence\"}",
            "{\"action\":\"final\",\"answer\":\"客户可以通过页面引用核验来源。[网页1]\"}",
            "{\"accepted\":true,\"feedback\":\"The answer cites web evidence.\",\"revisedAnswer\":\"\"}"
        );
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.getToolMetadata("web_search")).thenReturn(ToolMetadata.builder()
            .id("web_search")
            .title("Web Search")
            .description("Search web pages")
            .build());
        when(toolRegistry.executeEnhancedTool(eq("web_search"), any())).thenReturn(webSearchOutput());
        ToolRuntimeService toolRuntimeService = new ToolRuntimeService(
            toolRegistry,
            new ObjectMapper(),
            toolRuntimeProperties(),
            List.of(),
            List.of()
        );
        AgentOrchestrator orchestrator = new AgentOrchestrator(
            chatModel,
            toolRegistry,
            toolRuntimeService,
            new ObjectMapper(),
            new ModelsConfig()
        );

        AgentOrchestrator.AgentExecutionResult result = orchestrator.executeAgent(
            "How can customers audit cited web answers?",
            "tenant-1",
            List.of("web_search"),
            "Use evidence.",
            null,
            List.of(),
            List.of(),
            "research",
            "req-web-1",
            "conv-web-1",
            "user-1",
            10,
            List.of(),
            false
        );

        assertThat(result.answer()).contains("[网页1]");
        assertThat(chatModel.messages()).hasSize(3);
        assertThat(chatModel.messages().get(1))
            .contains("Web citation map")
            .contains("[网页1] Audit trail for AI answers - https://example.com/audit")
            .contains("append the matching [网页N] label");
        assertThat(chatModel.messages().get(2))
            .contains("web citation labels such as [网页1]")
            .contains("https://example.com/audit");
    }

    @Test
    void searchAndExtractUsesUnifiedEvidenceTrustAndCitationPipeline() {
        CapturingQueueChatModel chatModel = new CapturingQueueChatModel(
            "{\"action\":\"tool\",\"toolName\":\"search_and_extract\",\"arguments\":{\"query\":\"agent evidence runtime\"},\"reason\":\"Need structured web evidence\"}",
            "{\"action\":\"final\",\"answer\":\"Structured web evidence supports the runtime claim [网页1].\"}",
            "{\"accepted\":true,\"feedback\":\"The answer cites trusted web evidence.\",\"revisedAnswer\":\"\"}"
        );
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.getToolMetadata("search_and_extract")).thenReturn(ToolMetadata.builder()
            .id("search_and_extract")
            .title("Search And Extract")
            .description("Generate structured web evidence")
            .build());
        when(toolRegistry.executeEnhancedTool(eq("search_and_extract"), any())).thenReturn(searchAndExtractOutput());
        ToolRuntimeService toolRuntimeService = new ToolRuntimeService(
            toolRegistry,
            new ObjectMapper(),
            toolRuntimeProperties(),
            List.of(),
            List.of()
        );
        AgentOrchestrator orchestrator = new AgentOrchestrator(
            chatModel,
            toolRegistry,
            toolRuntimeService,
            new ObjectMapper(),
            new ModelsConfig()
        );

        AgentOrchestrator.AgentExecutionResult result = orchestrator.executeAgent(
            "Verify the agent evidence runtime with web evidence.",
            "tenant-1",
            List.of("search_and_extract"),
            "Use evidence.",
            null,
            List.of(),
            List.of(),
            "research",
            "req-search-extract-1",
            "conv-search-extract-1",
            "user-1",
            10,
            List.of(),
            false
        );

        assertThat(result.answer()).contains("[网页1]");
        assertThat(chatModel.messages()).hasSize(3);
        assertThat(chatModel.messages().get(1))
            .contains("Evidence trust policy")
            .contains("usable=1")
            .contains("ignoredLowScore=1")
            .contains("Web citation map")
            .contains("Trusted runtime evidence - https://docs.example.com/evidence")
            .doesNotContain("https://docs.example.com/low-quality");
        assertThat(chatModel.messages().get(2))
            .contains("Evidence trust policy")
            .contains("https://docs.example.com/evidence")
            .doesNotContain("https://docs.example.com/low-quality");
    }

    @Test
    void documentWebVerificationAllowsDocumentSearchBeforeMandatoryWebTool() {
        String mcpWebSearch = "mcp_chatchat_mcp_server_web_search";
        QueueChatModel chatModel = new QueueChatModel(
            "{\"action\":\"tool\",\"toolName\":\"document_search\",\"arguments\":{\"query\":\"internal definition\"},\"reason\":\"Need internal evidence first\"}",
            "{\"action\":\"tool\",\"toolName\":\"mcp_chatchat_mcp_server_web_search\",\"arguments\":{\"query\":\"internal definition\"},\"reason\":\"Validate public evidence\"}",
            "{\"action\":\"final\",\"answer\":\"Use internal documents first, then public web verification.\"}",
            "{\"accepted\":true,\"feedback\":\"The answer separates document and web evidence.\",\"revisedAnswer\":\"\"}"
        );
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.getToolMetadata("document_search")).thenReturn(ToolMetadata.builder()
            .id("document_search")
            .title("Document Search")
            .description("Search internal documents")
            .build());
        when(toolRegistry.getToolMetadata(mcpWebSearch)).thenReturn(ToolMetadata.builder()
            .id(mcpWebSearch)
            .title("MCP Web Search")
            .description("Search web pages")
            .build());
        when(toolRegistry.executeEnhancedTool(eq("document_search"), any())).thenReturn(documentSearchOutput());
        when(toolRegistry.executeEnhancedTool(eq(mcpWebSearch), any())).thenReturn(webSearchOutput());
        ToolRuntimeService toolRuntimeService = new ToolRuntimeService(
            toolRegistry,
            new ObjectMapper(),
            toolRuntimeProperties(),
            List.of(),
            List.of()
        );
        AgentOrchestrator orchestrator = new AgentOrchestrator(
            chatModel,
            toolRegistry,
            toolRuntimeService,
            new ObjectMapper(),
            new ModelsConfig()
        );

        AgentOrchestrator.AgentExecutionResult result = orchestrator.executeAgent(
            "What is the internal definition? Please verify online.",
            "tenant-1",
            List.of("document_search", mcpWebSearch),
            "Use internal documents first.",
            null,
            List.of("doc-1"),
            List.of(),
            "research",
            "req-doc-web-1",
            "conv-doc-web-1",
            "user-1",
            10,
            List.of(mcpWebSearch),
            true
        );

        assertThat(result.toolTraces())
            .extracting(InteractionToolTrace::getToolName)
            .containsExactly("document_search", mcpWebSearch);
        assertThat(result.metadata())
            .containsEntry("documentWebVerificationRequired", true)
            .containsEntry("mandatoryTools", List.of("document_search", mcpWebSearch));
    }

    @Test
    void resumesPendingToolExecutionAfterConfirmation() {
        CapturingQueueChatModel chatModel = new CapturingQueueChatModel(
            "{\"action\":\"final\",\"answer\":\"Use the confirmed document evidence.\"}",
            "{\"accepted\":true,\"feedback\":\"The answer used the confirmed tool observation.\",\"revisedAnswer\":\"\"}"
        );
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.getToolMetadata("document_search")).thenReturn(ToolMetadata.builder()
            .id("document_search")
            .title("Document Search")
            .description("Search internal documents")
            .build());
        when(toolRegistry.executeEnhancedTool(eq("document_search"), any())).thenReturn(documentSearchOutput());
        ToolRuntimeService toolRuntimeService = new ToolRuntimeService(
            toolRegistry,
            new ObjectMapper(),
            toolRuntimeProperties(),
            List.of(),
            List.of()
        );
        AgentOrchestrator orchestrator = new AgentOrchestrator(
            chatModel,
            toolRegistry,
            toolRuntimeService,
            new ObjectMapper(),
            new ModelsConfig()
        );

        AgentOrchestrator.AgentExecutionResult result = orchestrator.executeAgent(
            "Continue after confirmation.",
            "tenant-1",
            List.of("document_search"),
            "Use internal evidence.",
            null,
            List.of(),
            List.of(),
            "research",
            "req-confirm-resume",
            "conv-confirm-resume",
            "user-1",
            10,
            List.of(),
            false,
            Map.of(
                "mcpConfirmation", Map.of("approved", true),
                "mcpPendingToolExecution", Map.of(
                    "toolName", "document_search",
                    "input", Map.of("query", "Kafka Connect 安全认证与启动"),
                    "executionPlan", Map.of("reason", "Confirmed by user")
                )
            )
        );

        assertThat(result.toolTraces())
            .extracting(InteractionToolTrace::getToolName)
            .containsExactly("document_search");
        assertThat(result.metadata())
            .containsEntry("resumedPendingToolExecution", true)
            .containsEntry("resumedPendingTool", "document_search");
        assertThat(chatModel.messages().get(0))
            .contains("Confirmed pending Tool document_search succeeded")
            .contains("Document evidence snippets")
            .contains("Internal Definition Handbook");
    }

    @Test
    void pendingDocumentSearchCompletionAllowsNextWorkflowWebSearch() {
        String documentSearch = "mcp_chatchat_mcp_server_document_search";
        String webSearch = "mcp_chatchat_mcp_server_web_search";
        CapturingQueueChatModel chatModel = new CapturingQueueChatModel(
            "Document evidence and web evidence are both available.",
            "{\"accepted\":true,\"feedback\":\"The answer used both observations.\",\"revisedAnswer\":\"\"}"
        );
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.getToolMetadata(documentSearch)).thenReturn(ToolMetadata.builder()
            .id(documentSearch)
            .title("MCP Document Search")
            .description("Search internal documents")
            .build());
        when(toolRegistry.getToolMetadata(webSearch)).thenReturn(ToolMetadata.builder()
            .id(webSearch)
            .title("MCP Web Search")
            .description("Search web pages")
            .build());
        when(toolRegistry.executeEnhancedTool(eq(documentSearch), any())).thenReturn(documentSearchOutput());
        when(toolRegistry.executeEnhancedTool(eq(webSearch), any())).thenReturn(webSearchOutput());
        ToolRuntimeService toolRuntimeService = new ToolRuntimeService(
            toolRegistry,
            new ObjectMapper(),
            toolRuntimeProperties(),
            List.of(),
            List.of()
        );
        AgentOrchestrator orchestrator = new AgentOrchestrator(
            chatModel,
            toolRegistry,
            toolRuntimeService,
            new ObjectMapper(),
            new ModelsConfig()
        );
        Map<String, Object> workflowConfig = Map.of(
            "enabled", true,
            "workflow", "document-web-verification",
            "executionStrategy", Map.of("mode", "sequential", "stopOnError", true),
            "steps", List.of(
                Map.of("step", 1, "tool", documentSearch, "required", true, "confirmation", "auto_execute"),
                Map.of("step", 2, "tool", webSearch, "required", true, "confirmation", "auto_execute")
            )
        );

        AgentOrchestrator.AgentExecutionResult result = orchestrator.executeAgent(
            "Kafka Connect 安全认证与启动",
            "tenant-1",
            List.of(documentSearch, webSearch),
            "Use document evidence, then web verification.",
            null,
            List.of("doc-1"),
            List.of(),
            "livedata_ops",
            "req-confirm-doc-web",
            "conv-confirm-doc-web",
            "user-1",
            10,
            List.of(webSearch),
            true,
            Map.of(
                "mcpWorkflow", workflowConfig,
                "mcpConfirmation", Map.of("approved", true),
                "mcpPendingToolExecution", Map.of(
                    "toolName", documentSearch,
                    "input", Map.of("query", "Kafka Connect 安全认证与启动"),
                    "executionPlan", Map.of("workflow", "document-web-verification", "reason", "Confirmed by user")
                )
            )
        );

        assertThat(result.toolTraces())
            .extracting(InteractionToolTrace::getToolName)
            .containsExactly(documentSearch, webSearch);
        assertThat(result.toolTraces())
            .allMatch(InteractionToolTrace::isSuccess);
        assertThat(result.metadata())
            .containsEntry("resumedPendingToolExecution", true);
        assertThat(chatModel.messages().get(0))
            .contains("Confirmed pending Tool " + documentSearch + " succeeded")
            .contains("Document evidence snippets");
    }

    @Test
    void workflowConfigRequiredStepsDriveMandatoryToolsAndUseFullMcpToolNames() {
        String mcpDocumentSearch = "mcp_chatchat_mcp_server_document_search";
        String mcpWebSearch = "mcp_chatchat_mcp_server_web_search";
        CapturingQueueChatModel chatModel = new CapturingQueueChatModel(
            "Workflow summary uses internal and web observations.",
            "{\"accepted\":true,\"feedback\":\"The answer follows the workflow.\",\"revisedAnswer\":\"\"}"
        );
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.getToolMetadata(mcpDocumentSearch)).thenReturn(ToolMetadata.builder()
            .id(mcpDocumentSearch)
            .title("MCP Document Search")
            .description("Search internal documents")
            .build());
        when(toolRegistry.getToolMetadata(mcpWebSearch)).thenReturn(ToolMetadata.builder()
            .id(mcpWebSearch)
            .title("MCP Web Search")
            .description("Search web pages")
            .build());
        when(toolRegistry.executeEnhancedTool(eq(mcpDocumentSearch), any())).thenReturn(documentSearchOutput());
        when(toolRegistry.executeEnhancedTool(eq(mcpWebSearch), any())).thenReturn(webSearchOutput());
        ToolRuntimeService toolRuntimeService = new ToolRuntimeService(
            toolRegistry,
            new ObjectMapper(),
            toolRuntimeProperties(),
            List.of(),
            List.of()
        );
        AgentOrchestrator orchestrator = new AgentOrchestrator(
            chatModel,
            toolRegistry,
            toolRuntimeService,
            new ObjectMapper(),
            new ModelsConfig()
        );
        Map<String, Object> workflowConfig = Map.of(
            "enabled", true,
            "executionStrategy", Map.of("mode", "sequential", "stopOnError", true),
            "steps", List.of(
                Map.of("step", 1, "tool", mcpDocumentSearch, "required", true),
                Map.of("step", 2, "tool", mcpWebSearch, "required", true)
            )
        );

        AgentOrchestrator.AgentExecutionResult result = orchestrator.executeAgent(
            "PushGateway 与 Prometheus 指标集成?",
            "tenant-1",
            List.of(mcpWebSearch, mcpDocumentSearch),
            "Use internal documents before web verification.",
            null,
            List.of("doc-1"),
            List.of(),
            "livedata_ops",
            "req-workflow-doc-web",
            "conv-workflow-doc-web",
            "user-1",
            10,
            List.of(mcpWebSearch),
            true,
            Map.of("mcpWorkflow", workflowConfig)
        );

        assertThat(result.toolTraces())
            .extracting(InteractionToolTrace::getToolName)
            .containsExactly(mcpDocumentSearch, mcpWebSearch);
        assertThat(result.metadata())
            .containsEntry("workflowMandatoryTools", List.of(mcpDocumentSearch, mcpWebSearch))
            .containsEntry("mandatoryTools", List.of(mcpDocumentSearch, mcpWebSearch))
            .containsEntry("runtimeEnforcedMcpWorkflow", true);
        assertThat(chatModel.messages().get(0))
            .contains("Mandatory workflow execution Tool " + mcpDocumentSearch + " succeeded")
            .contains("Mandatory workflow execution Tool " + mcpWebSearch + " succeeded");
    }

    @Test
    void workflowRuntimeOverridesPlannerWhenItSkipsConfiguredToolOrder() {
        String profileTool = "mcp_crm_customer_profile";
        String assetTool = "mcp_crm_customer_assets";
        CapturingQueueChatModel chatModel = new CapturingQueueChatModel(
            "Profile and assets were both checked.",
            "{\"accepted\":true,\"feedback\":\"The answer follows the configured workflow.\",\"revisedAnswer\":\"\"}"
        );
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.getToolMetadata(profileTool)).thenReturn(ToolMetadata.builder()
            .id(profileTool)
            .title("Customer Profile")
            .description("Fetch customer profile")
            .build());
        when(toolRegistry.getToolMetadata(assetTool)).thenReturn(ToolMetadata.builder()
            .id(assetTool)
            .title("Customer Assets")
            .description("Fetch customer assets")
            .build());
        when(toolRegistry.executeEnhancedTool(eq(profileTool), any())).thenReturn(ToolOutput.success(Map.of("customer_id", "c-001")));
        when(toolRegistry.executeEnhancedTool(eq(assetTool), any())).thenReturn(ToolOutput.success(Map.of("aum", 1200000)));
        ToolRuntimeService toolRuntimeService = new ToolRuntimeService(
            toolRegistry,
            new ObjectMapper(),
            toolRuntimeProperties(),
            List.of(),
            List.of()
        );
        AgentOrchestrator orchestrator = new AgentOrchestrator(
            chatModel,
            toolRegistry,
            toolRuntimeService,
            new ObjectMapper(),
            new ModelsConfig()
        );
        Map<String, Object> workflowConfig = Map.of(
            "enabled", true,
            "workflow", "customer_review",
            "executionStrategy", Map.of("mode", "sequential", "stopOnError", true),
            "steps", List.of(
                Map.of("step", 1, "tool", profileTool, "required", true, "confirmation", "auto_execute"),
                Map.of("step", 2, "tool", assetTool, "required", true, "confirmation", "auto_execute", "dependsOn", List.of(profileTool))
            )
        );

        AgentOrchestrator.AgentExecutionResult result = orchestrator.executeAgent(
            "Review customer c-001.",
            "tenant-1",
            List.of(profileTool, assetTool),
            "Follow the selected Agent tool workflow.",
            null,
            List.of(),
            List.of(),
            "customer_review",
            "req-workflow-override",
            "conv-workflow-override",
            "user-1",
            10,
            List.of(),
            true,
            Map.of("mcpWorkflow", workflowConfig)
        );

        assertThat(result.toolTraces())
            .extracting(InteractionToolTrace::getToolName)
            .containsExactly(profileTool, assetTool);
        assertThat(result.metadata())
            .containsEntry("workflowMandatoryTools", List.of(profileTool, assetTool))
            .containsEntry("runtimeEnforcedMcpWorkflow", true)
            .doesNotContainKey("workflowToolOverrides");
    }

    @Test
    @SuppressWarnings("unchecked")
    void workflowConditionCanShortCircuitMandatoryStepAndContinueDependentFlow() {
        String profileTool = "mcp_crm_customer_profile";
        String assetTool = "mcp_crm_customer_assets";
        CapturingQueueChatModel chatModel = new CapturingQueueChatModel(
            "Asset check completed after skipping profile by policy condition.",
            "{\"accepted\":true,\"feedback\":\"The conditional workflow was respected.\",\"revisedAnswer\":\"\"}"
        );
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.getToolMetadata(profileTool)).thenReturn(ToolMetadata.builder()
            .id(profileTool)
            .title("Customer Profile")
            .description("Fetch customer profile")
            .build());
        when(toolRegistry.getToolMetadata(assetTool)).thenReturn(ToolMetadata.builder()
            .id(assetTool)
            .title("Customer Assets")
            .description("Fetch customer assets")
            .build());
        when(toolRegistry.executeEnhancedTool(eq(assetTool), any())).thenReturn(ToolOutput.success(Map.of("aum", 1200000)));
        ToolRuntimeService toolRuntimeService = new ToolRuntimeService(
            toolRegistry,
            new ObjectMapper(),
            toolRuntimeProperties(),
            List.of(),
            List.of()
        );
        AgentOrchestrator orchestrator = new AgentOrchestrator(
            chatModel,
            toolRegistry,
            toolRuntimeService,
            new ObjectMapper(),
            new ModelsConfig()
        );
        Map<String, Object> workflowConfig = Map.of(
            "enabled", true,
            "workflow", "conditional_customer_review",
            "executionStrategy", Map.of("mode", "sequential", "stopOnError", true),
            "steps", List.of(
                Map.of("step", 1, "tool", profileTool, "required", true, "condition", "needsProfile == true"),
                Map.of("step", 2, "tool", assetTool, "required", true, "dependsOn", List.of(profileTool))
            )
        );

        AgentOrchestrator.AgentExecutionResult result = orchestrator.executeAgent(
            "Review customer assets only.",
            "tenant-1",
            List.of(profileTool, assetTool),
            "Follow the selected Agent tool workflow.",
            null,
            List.of(),
            List.of(),
            "customer_review",
            "req-workflow-condition",
            "conv-workflow-condition",
            "user-1",
            10,
            List.of(),
            true,
            Map.of(
                "mcpWorkflow", workflowConfig,
                "workflowContext", Map.of("needsProfile", false)
            )
        );

        assertThat(result.toolTraces())
            .extracting(InteractionToolTrace::getToolName)
            .containsExactly(assetTool);
        assertThat(result.metadata())
            .containsEntry("workflowSkippedTools", List.of(profileTool))
            .containsEntry("workflowMandatoryTools", List.of(assetTool))
            .containsEntry("runtimeEnforcedMcpWorkflow", true);
        List<Map<String, Object>> skipDecisions = (List<Map<String, Object>>) result.metadata().get("workflowSkipDecisions");
        assertThat(skipDecisions)
            .singleElement()
            .satisfies(decision -> assertThat(decision)
                .containsEntry("tool", profileTool)
                .containsEntry("status", "SKIPPED")
                .containsEntry("reason", "CONDITION_NOT_MET")
                .containsEntry("condition", "needsProfile == true")
                .containsEntry("evaluated", false));
        verify(toolRegistry, never()).executeEnhancedTool(eq(profileTool), any());
    }

    @Test
    void workflowParallelRequiredToolsMustAllCompleteBeforeFinalAnswer() {
        String documentSearch = "mcp_xxx_document_search";
        String knowledgeSearch = "mcp_xxx_knowledge_search";
        CapturingQueueChatModel chatModel = new CapturingQueueChatModel(
            "Both internal tools have been observed.",
            "{\"accepted\":true,\"feedback\":\"All required tools were observed.\",\"revisedAnswer\":\"\"}"
        );
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.getToolMetadata(documentSearch)).thenReturn(ToolMetadata.builder()
            .id(documentSearch)
            .title("MCP Document Search")
            .description("Search internal documents")
            .build());
        when(toolRegistry.getToolMetadata(knowledgeSearch)).thenReturn(ToolMetadata.builder()
            .id(knowledgeSearch)
            .title("MCP Knowledge Search")
            .description("Search internal knowledge")
            .build());
        when(toolRegistry.executeEnhancedTool(eq(documentSearch), any())).thenReturn(documentSearchOutput());
        when(toolRegistry.executeEnhancedTool(eq(knowledgeSearch), any())).thenReturn(documentSearchOutput());
        ToolRuntimeService toolRuntimeService = new ToolRuntimeService(
            toolRegistry,
            new ObjectMapper(),
            toolRuntimeProperties(),
            List.of(),
            List.of()
        );
        AgentOrchestrator orchestrator = new AgentOrchestrator(
            chatModel,
            toolRegistry,
            toolRuntimeService,
            new ObjectMapper(),
            new ModelsConfig()
        );
        Map<String, Object> workflowConfig = Map.of(
            "enabled", true,
            "executionStrategy", Map.of("mode", "hybrid", "stopOnError", true),
            "steps", List.of(
                Map.of(
                    "step", 1,
                    "name", "internal_retrieval",
                    "parallelSteps", List.of(documentSearch, knowledgeSearch),
                    "required", true
                )
            )
        );

        AgentOrchestrator.AgentExecutionResult result = orchestrator.executeAgent(
            "Summarize internal evidence.",
            "tenant-1",
            List.of(documentSearch, knowledgeSearch),
            "Use all required internal tools.",
            null,
            List.of(),
            List.of(),
            "research",
            "req-parallel-required",
            "conv-parallel-required",
            "user-1",
            10,
            List.of(),
            true,
            Map.of("mcpWorkflow", workflowConfig)
        );

        assertThat(result.answer()).isEqualTo("Both internal tools have been observed.");
        assertThat(result.toolTraces())
            .extracting(InteractionToolTrace::getToolName)
            .containsExactly(documentSearch, knowledgeSearch);
        assertThat(result.metadata())
            .containsEntry("workflowMandatoryTools", List.of(documentSearch, knowledgeSearch))
            .containsEntry("missingMandatoryTools", List.of())
            .containsEntry("runtimeEnforcedMcpWorkflow", true);
        assertThat(chatModel.messages().get(0))
            .contains("Mandatory workflow execution Tool " + documentSearch + " succeeded")
            .contains("Mandatory workflow execution Tool " + knowledgeSearch + " succeeded");
    }

    private AgentOrchestrator newOrchestrator(ChatModel chatModel) {
        return new AgentOrchestrator(
            chatModel,
            mock(ToolRegistry.class),
            new ToolRuntimeService(
                mock(ToolRegistry.class),
                new ObjectMapper(),
                toolRuntimeProperties(),
                List.of(),
                List.of()
            ),
            new ObjectMapper(),
            new ModelsConfig()
        );
    }

    private ToolOutput webSearchOutput() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("reference_urls", List.of("https://example.com/audit"));
        result.put("results", List.of(Map.of(
            "rank", 1,
            "title", "Audit trail for AI answers",
            "url", "https://example.com/audit",
            "snippet", "Shows how web citations prove which URL supports an answer."
        )));
        return ToolOutput.success(result);
    }

    private ToolOutput searchAndExtractOutput() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("query", "agent evidence runtime");
        result.put("provider", "evidence");
        result.put("count", 2);
        result.put("evidence_chunks", List.of(
            Map.of(
                "chunk_id", "trusted-1",
                "title", "Trusted runtime evidence",
                "source_url", "https://docs.example.com/evidence",
                "domain", "docs.example.com",
                "score", 0.92,
                "content", "The agent evidence runtime is supported and available for structured web evidence."
            ),
            Map.of(
                "chunk_id", "low-1",
                "title", "Low quality runtime note",
                "source_url", "https://docs.example.com/low-quality",
                "domain", "docs.example.com",
                "score", 0.12,
                "content", "Low quality evidence that should not be cited."
            )
        ));
        return ToolOutput.success(result);
    }

    private ToolOutput documentSearchOutput() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("results", List.of(Map.of(
            "docId", "doc-1",
            "title", "Internal Definition Handbook",
            "snippet", "Internal definitions should be answered from enterprise documents first."
        )));
        return ToolOutput.success(result);
    }

    private ToolRuntimeProperties toolRuntimeProperties() {
        ToolRuntimeProperties properties = new ToolRuntimeProperties();
        properties.setEnforceAllowedTools(true);
        properties.setCircuitBreakerFailureThreshold(3);
        properties.setCircuitBreakerOpenSeconds(30);
        return properties;
    }

    private static final class QueueChatModel implements ChatModel {
        private final Queue<String> responses = new ArrayDeque<>();

        private QueueChatModel(String... responses) {
            this.responses.addAll(List.of(responses));
        }

        @Override
        public String chat(String message) {
            assertThat(message).isNotBlank();
            assertThat(responses).isNotEmpty();
            return responses.remove();
        }
    }

    private static final class CapturingQueueChatModel implements ChatModel {
        private final Queue<String> responses = new ArrayDeque<>();
        private final List<String> messages = new ArrayList<>();

        private CapturingQueueChatModel(String... responses) {
            this.responses.addAll(List.of(responses));
        }

        @Override
        public String chat(String message) {
            assertThat(message).isNotBlank();
            assertThat(responses).isNotEmpty();
            messages.add(message);
            return responses.remove();
        }

        private List<String> messages() {
            return messages;
        }
    }

    private static final class FailingChatModel implements ChatModel {
        private final String message;

        private FailingChatModel(String message) {
            this.message = message;
        }

        @Override
        public String chat(String message) {
            throw new IllegalStateException(this.message);
        }
    }

    private static final class SlowChatModel implements ChatModel {
        private final long sleepMs;
        private final String response;

        private SlowChatModel(long sleepMs, String response) {
            this.sleepMs = sleepMs;
            this.response = response;
        }

        @Override
        public String chat(String message) {
            assertThat(message).isNotBlank();
            try {
                Thread.sleep(sleepMs);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            return response;
        }
    }
}
