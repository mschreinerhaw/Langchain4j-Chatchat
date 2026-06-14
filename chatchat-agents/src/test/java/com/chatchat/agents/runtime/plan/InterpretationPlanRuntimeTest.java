package com.chatchat.agents.runtime.plan;

import com.chatchat.agents.runtime.AgentRunEvent;
import com.chatchat.agents.runtime.AgentRunEventType;
import com.chatchat.agents.runtime.InMemoryAgentRunStore;
import com.chatchat.agents.runtime.ToolRuntimeExecution;
import com.chatchat.agents.runtime.ToolRuntimeRequest;
import com.chatchat.agents.runtime.ToolRuntimeService;
import com.chatchat.agents.tool.ToolRegistry;
import com.chatchat.common.tool.ToolMetadata;
import com.chatchat.common.tool.ToolOutput;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InterpretationPlanRuntimeTest {

    @Test
    void executesReadyToolStepsInParallelAndThenFinalAnswer() {
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.hasTool("document_search")).thenReturn(true);
        when(toolRegistry.hasTool("web_search")).thenReturn(true);
        when(toolRegistry.getToolMetadata(any())).thenReturn(ToolMetadata.builder().riskLevel("low").build());
        ToolRuntimeService toolRuntimeService = mock(ToolRuntimeService.class);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maxActive = new AtomicInteger();
        when(toolRuntimeService.execute(any())).thenAnswer(invocation -> {
            int current = active.incrementAndGet();
            maxActive.updateAndGet(value -> Math.max(value, current));
            Thread.sleep(50);
            active.decrementAndGet();
            ToolRuntimeRequest request = invocation.getArgument(0);
            return new ToolRuntimeExecution(
                ToolOutput.success(Map.of("tool", request.getToolName())),
                ToolMetadata.builder().id(request.getToolName()).build(),
                null,
                "success",
                Map.of("tool", request.getToolName())
            );
        });

        InterpretationPlanRuntime runtime = new InterpretationPlanRuntime(toolRuntimeService, new InterpretationPlanValidator());

        InterpretationPlanRuntime.ExecutionResult result = runtime.execute(new InterpretationPlanRuntime.ExecutionRequest(
            parallelPlan(),
            toolRegistry,
            List.of("document_search", "web_search"),
            "tenant-1",
            "req-plan-runtime",
            "conv-plan-runtime",
            "user-1",
            Map.of()
        ));

        assertThat(result.success()).isTrue();
        assertThat(result.finalAnswer()).isEqualTo("done");
        assertThat(result.steps()).extracting(InterpretationPlanRuntime.StepExecution::stepId)
            .containsExactly(1, 2, 3);
        assertThat(maxActive.get()).isGreaterThan(1);
        verify(toolRuntimeService, times(2)).execute(any());
    }

    @Test
    void stopsDagWhenToolStepFails() {
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.hasTool("document_search")).thenReturn(true);
        when(toolRegistry.getToolMetadata("document_search")).thenReturn(ToolMetadata.builder().riskLevel("low").build());
        ToolRuntimeService toolRuntimeService = mock(ToolRuntimeService.class);
        when(toolRuntimeService.execute(any())).thenReturn(new ToolRuntimeExecution(
            ToolOutput.failure("backend down"),
            ToolMetadata.builder().id("document_search").build(),
            null,
            "failed",
            Map.of()
        ));
        InterpretationPlanRuntime runtime = new InterpretationPlanRuntime(toolRuntimeService, new InterpretationPlanValidator());

        InterpretationPlanRuntime.ExecutionResult result = runtime.execute(new InterpretationPlanRuntime.ExecutionRequest(
            serialPlan(),
            toolRegistry,
            List.of("document_search"),
            "tenant-1",
            "req-plan-runtime-fail",
            "conv-plan-runtime-fail",
            "user-1",
            Map.of()
        ));

        assertThat(result.success()).isFalse();
        assertThat(result.status()).isEqualTo("STEP_FAILED");
        assertThat(result.errorMessage()).isEqualTo("backend down");
        assertThat(result.steps()).extracting(InterpretationPlanRuntime.StepExecution::stepId)
            .containsExactly(1);
    }

    @Test
    void stopsDagWhenModelReviewRejectsToolResult() {
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.hasTool("document_search")).thenReturn(true);
        when(toolRegistry.getToolMetadata("document_search")).thenReturn(ToolMetadata.builder().riskLevel("low").build());
        ToolRuntimeService toolRuntimeService = mock(ToolRuntimeService.class);
        when(toolRuntimeService.execute(any())).thenReturn(new ToolRuntimeExecution(
            ToolOutput.success(Map.of("results", List.of())),
            ToolMetadata.builder().id("document_search").build(),
            null,
            "success",
            Map.of()
        ));
        InterpretationPlanRuntime runtime = new InterpretationPlanRuntime(
            toolRuntimeService,
            new InterpretationPlanValidator(),
            new InterpretationPlanOptimizer(),
            null,
            null,
            request -> InterpretationPlanRuntime.StepReview.rejected("evidence is empty", Map.of("reviewed", true))
        );

        InterpretationPlanRuntime.ExecutionResult result = runtime.execute(new InterpretationPlanRuntime.ExecutionRequest(
            serialPlan(),
            toolRegistry,
            List.of("document_search"),
            "tenant-1",
            "req-review-reject",
            "conv-review-reject",
            "user-1",
            Map.of()
        ));

        assertThat(result.success()).isFalse();
        assertThat(result.status()).isEqualTo("STEP_FAILED");
        assertThat(result.errorMessage()).contains("Tool result rejected by model review");
        assertThat(result.steps()).extracting(InterpretationPlanRuntime.StepExecution::stepId)
            .containsExactly(1);
        assertThat(result.steps().get(0).metadata())
            .containsEntry("toolResultReviewSatisfied", false)
            .containsEntry("reviewed", true);
    }

    @Test
    void feedsReviewedWebSearchUrlIntoCrawlerStep() {
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.hasTool("web_search")).thenReturn(true);
        when(toolRegistry.hasTool("crawl_url")).thenReturn(true);
        when(toolRegistry.getToolMetadata(any())).thenReturn(ToolMetadata.builder().riskLevel("low").build());
        ToolRuntimeService toolRuntimeService = mock(ToolRuntimeService.class);
        when(toolRuntimeService.execute(any())).thenAnswer(invocation -> {
            ToolRuntimeRequest request = invocation.getArgument(0);
            if ("web_search".equals(request.getToolName())) {
                return new ToolRuntimeExecution(
                    ToolOutput.success(Map.of("results", List.of(Map.of(
                        "title", "Example",
                        "url", "https://example.com/page",
                        "snippet", "candidate"
                    )))),
                    ToolMetadata.builder().id("web_search").build(),
                    null,
                    "success",
                    Map.of()
                );
            }
            return new ToolRuntimeExecution(
                ToolOutput.success(Map.of("content", "full page")),
                ToolMetadata.builder().id("crawl_url").build(),
                null,
                "success",
                Map.of()
            );
        });
        InterpretationPlan plan = new InterpretationPlan(
            "1.0",
            new InterpretationPlan.Intent("web_search", "Collect full web evidence", "low"),
            context(),
            new InterpretationPlan.Plan(List.of(
                new InterpretationPlan.Step(1, "mcp_tool", "web_search", Map.of("query", "example"), List.of(), null, null),
                new InterpretationPlan.Step(2, "mcp_tool", "crawl_url", Map.of(), List.of(1), null, null),
                new InterpretationPlan.Step(3, "final_answer", "", Map.of("answer", "done"), List.of(2), null, null)
            )),
            new InterpretationPlan.ExecutionPolicy(3, false, List.of("web_search", "crawl_url"), List.of(), 30000),
            review()
        );
        InterpretationPlanRuntime runtime = new InterpretationPlanRuntime(
            toolRuntimeService,
            new InterpretationPlanValidator(),
            new InterpretationPlanOptimizer(),
            null,
            null,
            request -> {
                if ("web_search".equals(request.execution().toolName())) {
                    return InterpretationPlanRuntime.StepReview.accepted(
                        "candidate selected",
                        Map.of("selectedUrls", List.of("https://example.com/page"))
                    );
                }
                return InterpretationPlanRuntime.StepReview.accepted("content usable", Map.of());
            }
        );

        InterpretationPlanRuntime.ExecutionResult result = runtime.execute(new InterpretationPlanRuntime.ExecutionRequest(
            plan,
            toolRegistry,
            List.of("web_search", "crawl_url"),
            "tenant-1",
            "req-crawl-url",
            "conv-crawl-url",
            "user-1",
            Map.of()
        ));

        ArgumentCaptor<ToolRuntimeRequest> captor = ArgumentCaptor.forClass(ToolRuntimeRequest.class);
        verify(toolRuntimeService, times(2)).execute(captor.capture());
        assertThat(result.success()).isTrue();
        assertThat(captor.getAllValues().get(1).getToolName()).isEqualTo("crawl_url");
        assertThat(captor.getAllValues().get(1).getToolInput().getParameters())
            .containsEntry("url", "https://example.com/page");
    }

    @Test
    void failsWhenEdgeContractRequiredFieldIsMissing() {
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.hasTool("document_search")).thenReturn(true);
        when(toolRegistry.getToolMetadata("document_search")).thenReturn(ToolMetadata.builder().riskLevel("low").build());
        ToolRuntimeService toolRuntimeService = mock(ToolRuntimeService.class);
        when(toolRuntimeService.execute(any())).thenReturn(new ToolRuntimeExecution(
            ToolOutput.success(Map.of("items", List.of("x"))),
            ToolMetadata.builder().id("document_search").build(),
            null,
            "success",
            Map.of()
        ));
        InterpretationPlan plan = new InterpretationPlan(
            "1.0",
            new InterpretationPlan.Intent("document_retrieval", "Collect internal evidence", "low"),
            context(),
            new InterpretationPlan.Plan(
                List.of(
                    new InterpretationPlan.Step(1, "mcp_tool", "document_search", Map.of("query", "internal"), List.of(), null, null),
                    new InterpretationPlan.Step(2, "final_answer", "", Map.of("answer", "done"), List.of(1), null, null)
                ),
                List.of(new InterpretationPlan.EdgeContract(1, 2, "data.results", "array", true))
            ),
            new InterpretationPlan.ExecutionPolicy(2, false, List.of("document_search"), List.of(), 30000),
            review()
        );
        InterpretationPlanRuntime runtime = new InterpretationPlanRuntime(toolRuntimeService, new InterpretationPlanValidator());

        InterpretationPlanRuntime.ExecutionResult result = runtime.execute(new InterpretationPlanRuntime.ExecutionRequest(
            plan,
            toolRegistry,
            List.of("document_search"),
            "tenant-1",
            "req-edge-contract",
            "conv-edge-contract",
            "user-1",
            Map.of()
        ));

        assertThat(result.success()).isFalse();
        assertThat(result.status()).isEqualTo("EDGE_CONTRACT_FAILED");
        assertThat(result.errorMessage()).contains("missing required field data.results");
    }

    @Test
    void recordsStructuredEventsForDagStepsWhenRunIdIsAvailable() {
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.hasTool("document_search")).thenReturn(true);
        when(toolRegistry.getToolMetadata("document_search")).thenReturn(ToolMetadata.builder().riskLevel("low").build());
        ToolRuntimeService toolRuntimeService = mock(ToolRuntimeService.class);
        when(toolRuntimeService.execute(any())).thenReturn(new ToolRuntimeExecution(
            ToolOutput.success(Map.of("results", List.of("internal evidence"))),
            ToolMetadata.builder().id("document_search").build(),
            null,
            "success",
            Map.of()
        ));
        InMemoryAgentRunStore runStore = new InMemoryAgentRunStore();
        InterpretationPlanRuntime runtime = new InterpretationPlanRuntime(
            toolRuntimeService,
            new InterpretationPlanValidator(),
            runStore
        );

        InterpretationPlanRuntime.ExecutionResult result = runtime.execute(new InterpretationPlanRuntime.ExecutionRequest(
            serialPlan(),
            toolRegistry,
            List.of("document_search"),
            "tenant-1",
            "req-event-dag",
            "conv-event-dag",
            "user-1",
            Map.of("__agentRunId", "run-event-dag")
        ));

        assertThat(result.success()).isTrue();
        assertThat(result.finalAnswer()).isEqualTo("done");
        List<AgentRunEvent> events = runStore.events("run-event-dag");
        assertThat(events).extracting(AgentRunEvent::type)
            .contains(AgentRunEventType.STEP_RECORDED, AgentRunEventType.OBSERVATION_RECORDED);
        assertThat(events.stream()
            .filter(event -> event.type() == AgentRunEventType.OBSERVATION_RECORDED)
            .map(event -> event.payload().get("metadata"))
            .map(metadata -> (Map<?, ?>) metadata)
            .anyMatch(metadata -> Integer.valueOf(1).equals(metadata.get("interpretationPlanStepId"))
                && Boolean.TRUE.equals(metadata.get("success"))
                && "document_search".equals(metadata.get("toolName"))))
            .isTrue();
    }

    private InterpretationPlan parallelPlan() {
        return new InterpretationPlan(
            "1.0",
            new InterpretationPlan.Intent("mixed", "Collect internal and web evidence", "low"),
            context(),
            new InterpretationPlan.Plan(List.of(
                new InterpretationPlan.Step(1, "mcp_tool", "document_search", Map.of("query", "internal"), List.of(), null, null),
                new InterpretationPlan.Step(2, "mcp_tool", "web_search", Map.of("query", "public"), List.of(), null, null),
                new InterpretationPlan.Step(3, "final_answer", "", Map.of("answer", "done"), List.of(1, 2), null, null)
            )),
            new InterpretationPlan.ExecutionPolicy(3, true, List.of("document_search", "web_search"), List.of(), 30000),
            review()
        );
    }

    private InterpretationPlan serialPlan() {
        return new InterpretationPlan(
            "1.0",
            new InterpretationPlan.Intent("document_retrieval", "Collect internal evidence", "low"),
            context(),
            new InterpretationPlan.Plan(List.of(
                new InterpretationPlan.Step(1, "mcp_tool", "document_search", Map.of("query", "internal"), List.of(), null, null),
                new InterpretationPlan.Step(2, "final_answer", "", Map.of("answer", "done"), List.of(1), null, null)
            )),
            new InterpretationPlan.ExecutionPolicy(2, false, List.of("document_search"), List.of(), 30000),
            review()
        );
    }

    private InterpretationPlan.Context context() {
        return new InterpretationPlan.Context(List.of(), List.of(), List.of(), List.of());
    }

    private InterpretationPlan.Review review() {
        return new InterpretationPlan.Review(
            new InterpretationPlan.SelfCheck(0.8, 0.1, true, List.of()),
            List.of()
        );
    }
}
