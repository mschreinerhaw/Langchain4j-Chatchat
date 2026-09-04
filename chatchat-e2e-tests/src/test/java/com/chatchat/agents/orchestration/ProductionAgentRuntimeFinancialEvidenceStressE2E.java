package com.chatchat.agents.orchestration;

import com.chatchat.agents.orchestration.answer.AgentAnswerFinalizer;
import com.chatchat.agents.orchestration.answer.AnswerDecisionEngine;
import com.chatchat.agents.orchestration.planning.validation.AgentRuntimeGuard;

import com.chatchat.agents.runtime.tool.ToolRuntimeExecution;
import com.chatchat.agents.runtime.tool.ToolRuntimeProperties;
import com.chatchat.agents.runtime.tool.ToolRuntimeRequest;
import com.chatchat.agents.runtime.tool.ToolRuntimeService;
import com.chatchat.agents.runtime.answer.DefaultAgentAnswerReviewer;
import com.chatchat.agents.tool.ToolRegistry;
import com.chatchat.common.interaction.InteractionToolTrace;
import com.chatchat.common.tool.ToolInput;
import com.chatchat.common.tool.ToolMetadata;
import com.chatchat.common.tool.ToolOutput;
import com.chatchat.common.tool.ToolParameter;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
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
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** High-contention release regression for the financial marker and partial-evidence fixes. */
class ProductionAgentRuntimeFinancialEvidenceStressE2E {

    private static final int REQUESTS = 192;

    @Test
    void concurrentConfiguredToolParametersRemainIsolatedAndSchemaDriven() {
        String randomNamespace = UUID.randomUUID().toString().replace("-", "");
        String toolName = "mcp_" + randomNamespace + "_web_search";
        ToolRegistry registry = mock(ToolRegistry.class);
        when(registry.getToolMetadata(toolName)).thenReturn(ToolMetadata.builder()
            .id(toolName)
            .title("runtime-discovered-search")
            .categories(List.of("mcp"))
            .parameters(List.of(
                ToolParameter.builder().name("query").type("string").required(true).build(),
                ToolParameter.builder().name("financial_data_required").type("boolean").build()))
            .build());
        Map<String, ToolInput> captured = new ConcurrentHashMap<>();
        when(registry.executeEnhancedTool(eq(toolName), any())).thenAnswer(invocation -> {
            ToolInput input = invocation.getArgument(1);
            captured.put(input.getRequestId(), input);
            return ToolOutput.success(Map.of(
                "requestMarker", input.getParameters().get("query"),
                "financialDataRequired", input.getParameters().get("financial_data_required")));
        });

        ToolRuntimeProperties properties = new ToolRuntimeProperties();
        properties.setDefaultRetryAttempts(0);
        properties.setExecutionCorePoolSize(16);
        properties.setExecutionMaxPoolSize(48);
        properties.setExecutionQueueCapacity(256);
        ToolRuntimeService runtime = new ToolRuntimeService(
            registry, new ObjectMapper(), properties, List.of(), List.of());
        ExecutorService callers = Executors.newFixedThreadPool(48);
        try {
            assertTimeoutPreemptively(Duration.ofSeconds(30), () -> {
                var futures = IntStream.range(0, REQUESTS).mapToObj(index -> callers.submit(() -> {
                    String requestId = "financial-pressure-" + index;
                    String marker = "runtime-query-" + index + "-" + UUID.randomUUID();
                    ToolRuntimeExecution execution = runtime.execute(ToolRuntimeRequest.builder()
                        .toolName(toolName).runtimeMode("agent_chat").requestId(requestId)
                        .conversationId("conversation-" + index).tenantId("tenant-" + index % 7)
                        .userId("user-" + index).allowedTools(List.of(toolName))
                        .attributes(Map.of("requiredToolParameters", Map.of(
                            toolName, Map.of("financial_data_required", true))))
                        .toolInput(ToolInput.builder().parameters(Map.of(
                            "query", marker,
                            "financial_data_required", false
                        )).build()).build());
                    assertThat(execution.output().isSuccess()).isTrue();
                    assertThat(execution.audit())
                        .containsEntry("runtimeRequiredToolParametersApplied",
                            List.of("financial_data_required"));
                    return Map.entry(requestId, marker);
                })).toList();

                List<Map.Entry<String, String>> expected = new ArrayList<>();
                for (var future : futures) expected.add(future.get(20, TimeUnit.SECONDS));
                assertThat(expected).hasSize(REQUESTS);
                assertThat(captured).hasSize(REQUESTS);
                expected.forEach(entry -> {
                    ToolInput input = captured.get(entry.getKey());
                    assertThat(input).isNotNull();
                    assertThat(input.getParameters())
                        .containsEntry("query", entry.getValue())
                        .containsEntry("financial_data_required", true);
                    assertThat(input.getContext())
                        .containsEntry("runtimeRequiredToolParametersApplied",
                            List.of("financial_data_required"));
                });
            });
        } finally {
            callers.shutdownNow();
            runtime.shutdown();
        }
    }

    @Test
    void concurrentExtremeEvidenceCombinationsNeverEraseUsableAnalysisOrLeakRequests() {
        AgentAnswerFinalizer finalizer = new AgentAnswerFinalizer(
            null,
            new AgentRuntimeGuard(12, "cancelled", "maxSteps", "maxToolCalls", "timeoutMs", "deadlineAt")
        );
        ExecutorService workers = Executors.newFixedThreadPool(32);
        try {
            assertTimeoutPreemptively(Duration.ofSeconds(30), () -> {
                var futures = IntStream.range(0, REQUESTS).mapToObj(index -> workers.submit(() -> {
                    String marker = "ANALYSIS_MARKER_" + index + "_" + UUID.randomUUID();
                    int scenario = index % 4;
                    List<InteractionToolTrace> traces = evidenceScenario(index, scenario);
                    Map<String, Object> metadata = new ConcurrentHashMap<>();
                    if (scenario == 1 || scenario == 2) {
                        metadata.put("stopReason", "evidence_partial_analysis");
                    }
                    AgentOrchestrator.AgentExecutionResult result = finalizer.finishExecution(
                        "# Runtime analysis " + marker + "\n\nBounded risk analysis based on currently usable evidence.",
                        traces,
                        metadata,
                        List.of("runtime observation " + marker)
                    );
                    assertThat(result.answer()).contains(marker);
                    if (scenario == 3) {
                        assertThat(result.metadata()).containsEntry("mcpResultEvidenceAvailability", "AVAILABLE");
                    } else {
                        assertThat(result.answer()).contains("数据覆盖说明");
                        assertThat(result.metadata()).containsEntry("evidenceLimitedAnalysisPreserved", true);
                    }
                    return Map.entry(marker, result.answer());
                })).toList();

                List<Map.Entry<String, String>> answers = new ArrayList<>();
                for (var future : futures) answers.add(future.get(20, TimeUnit.SECONDS));
                assertThat(answers).hasSize(REQUESTS);
                for (Map.Entry<String, String> answer : answers) {
                    answers.stream().map(Map.Entry::getKey).filter(marker -> !marker.equals(answer.getKey()))
                        .forEach(other -> assertThat(answer.getValue()).doesNotContain(other));
                }
            });
        } finally {
            workers.shutdownNow();
        }
    }

    @Test
    void concurrentModelDriftIsReanalyzedFromEachRequestsCompleteEvidence() {
        AgentAnswerFinalizer finalizer = new AgentAnswerFinalizer(
            new DefaultAgentAnswerReviewer(new ObjectMapper()),
            new AgentRuntimeGuard(12, "cancelled", "maxSteps", "maxToolCalls", "timeoutMs", "deadlineAt")
        );
        ChatModel repairModel = new ChatModel() {
            @Override
            public String chat(String prompt) {
                if (!prompt.contains("model_analysis_repair_v1")) {
                    return "{}";
                }
                int markerStart = prompt.indexOf("EVIDENCE_MARKER_");
                int markerEnd = markerStart;
                while (markerEnd < prompt.length()
                    && (Character.isLetterOrDigit(prompt.charAt(markerEnd))
                        || prompt.charAt(markerEnd) == '_')) {
                    markerEnd++;
                }
                String marker = markerStart < 0 ? "MISSING" : prompt.substring(markerStart, markerEnd);
                return "{\"accepted\":false,"
                    + "\"feedback\":\"Candidate drifted beyond executed evidence\","
                    + "\"issues\":[\"unsupported inference\"],\"suggestions\":[],"
                    + "\"revisedAnswer\":\"Reanalyzed directly from " + marker + "\"}";
            }
        };
        ExecutorService workers = Executors.newFixedThreadPool(32);
        try {
            assertTimeoutPreemptively(Duration.ofSeconds(30), () -> {
                var futures = IntStream.range(0, REQUESTS).mapToObj(index -> workers.submit(() -> {
                    String marker = "EVIDENCE_MARKER_" + index + "_" + UUID.randomUUID()
                        .toString().replace("-", "");
                    Map<String, Object> metadata = new ConcurrentHashMap<>();
                    metadata.put("modelEvidenceReviewRewriteAllowed", true);
                    metadata.put("modelAnalysisReviewContext",
                        "Executed plan attempts (1): successful output value=" + marker);

                    AgentOrchestrator.AgentExecutionResult result = finalizer.finishReviewedAnswer(
                        repairModel,
                        "Analyze the executed result",
                        null,
                        List.of(),
                        metadata,
                        List.of("candidate synthesis completed"),
                        "A broad conclusion invented by the first model " + index,
                        () -> false,
                        "attempts_exhausted"
                    );

                    assertThat(result.answer())
                        .contains("Reanalyzed directly from " + marker)
                        .doesNotContain("broad conclusion invented");
                    assertThat(result.metadata())
                        .containsEntry("answerDecision", AnswerDecisionEngine.REVIEWER_REWRITE)
                        .containsEntry("answerReviewAuthority", "evidence_analysis_repair")
                        .containsEntry("modelAnalysisReviewContextApplied", true)
                        .doesNotContainKey("modelAnalysisReviewContext");
                    return Map.entry(marker, result.answer());
                })).toList();

                List<Map.Entry<String, String>> answers = new ArrayList<>();
                for (var future : futures) answers.add(future.get(20, TimeUnit.SECONDS));
                assertThat(answers).hasSize(REQUESTS);
                for (Map.Entry<String, String> answer : answers) {
                    answers.stream().map(Map.Entry::getKey).filter(marker -> !marker.equals(answer.getKey()))
                        .forEach(other -> assertThat(answer.getValue()).doesNotContain(other));
                }
            });
        } finally {
            workers.shutdownNow();
        }
    }

    private List<InteractionToolTrace> evidenceScenario(int index, int scenario) {
        InteractionToolTrace available = InteractionToolTrace.builder()
            .toolName("runtime_search_" + UUID.randomUUID())
            .success(true)
            .output("{\"success\":true,\"results\":[{\"sequence\":" + index + ",\"value\":\"usable\"}]}")
            .build();
        if (scenario == 0) {
            return List.of(available, InteractionToolTrace.builder()
                .toolName("runtime_financial_" + UUID.randomUUID()).success(false)
                .errorMessage("simulated timeout").build());
        }
        if (scenario == 1) {
            return List.of(InteractionToolTrace.builder()
                .toolName("runtime_malformed_" + UUID.randomUUID()).success(true)
                .output("{\"success\":true,\"results\":[").build());
        }
        if (scenario == 2) {
            return List.of(InteractionToolTrace.builder()
                .toolName("runtime_empty_" + UUID.randomUUID()).success(true)
                .output("{\"success\":true,\"results\":[],\"count\":0}").build());
        }
        return List.of(available);
    }
}
