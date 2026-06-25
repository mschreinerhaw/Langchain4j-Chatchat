package com.chatchat.agents.runtime.plan;

import com.chatchat.agents.runtime.AgentRunEvent;
import com.chatchat.agents.runtime.AgentRunEventType;
import com.chatchat.agents.runtime.InMemoryAgentRunStore;
import com.chatchat.agents.runtime.ToolRuntimeExecution;
import com.chatchat.agents.runtime.ToolRuntimeRequest;
import com.chatchat.agents.runtime.ToolRuntimeService;
import com.chatchat.agents.tool.ToolRegistry;
import com.chatchat.common.tool.ToolMetadata;
import com.chatchat.common.tool.ToolParameter;
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

        InterpretationPlanRuntime runtime = new InterpretationPlanRuntime(
            toolRuntimeService,
            new InterpretationPlanValidator(),
            scriptedController(List.of(List.of(1, 2), List.of(3)))
        );

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
        InterpretationPlanRuntime runtime = new InterpretationPlanRuntime(
            toolRuntimeService,
            new InterpretationPlanValidator(),
            scriptedController(List.of(List.of(1), List.of(2)))
        );

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
            request -> InterpretationPlanRuntime.StepReview.rejected("evidence is empty", Map.of("reviewed", true)),
            scriptedController(List.of(List.of(1), List.of(2)))
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
            request -> {
                if ("web_search".equals(request.execution().toolName())) {
                    return InterpretationPlanRuntime.StepReview.accepted(
                        "candidate selected",
                        Map.of("selectedUrls", List.of("https://example.com/page"))
                    );
                }
                return InterpretationPlanRuntime.StepReview.accepted("content usable", Map.of());
            },
            scriptedController(List.of(List.of(1), List.of(2), List.of(3)))
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
    void resolvesPlanBindingIntoDownstreamToolInput() {
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.hasTool("web_search")).thenReturn(true);
        when(toolRegistry.hasTool("crawl_url")).thenReturn(true);
        when(toolRegistry.getToolMetadata("web_search")).thenReturn(ToolMetadata.builder()
            .id("web_search")
            .riskLevel("low")
            .parameters(List.of(ToolParameter.builder()
                .name("query")
                .type("string")
                .required(true)
                .build()))
            .build());
        when(toolRegistry.getToolMetadata("crawl_url")).thenReturn(ToolMetadata.builder()
            .id("crawl_url")
            .riskLevel("low")
            .parameters(List.of(ToolParameter.builder()
                .name("url")
                .type("string")
                .required(true)
                .build()))
            .build());
        ToolRuntimeService toolRuntimeService = mock(ToolRuntimeService.class);
        when(toolRuntimeService.execute(any())).thenAnswer(invocation -> {
            ToolRuntimeRequest request = invocation.getArgument(0);
            if ("web_search".equals(request.getToolName())) {
                return new ToolRuntimeExecution(
                    ToolOutput.success(Map.of("results", List.of(Map.of(
                        "title", "Market News",
                        "url", "https://example.com/market",
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
            new InterpretationPlan.Intent("web_search", "Search then crawl", "low"),
            context(),
            new InterpretationPlan.Plan(
                List.of(
                    new InterpretationPlan.Step(1, "mcp_tool", "web_search", Map.of("query", "今天市场热点"), List.of(), null, null),
                    new InterpretationPlan.Step(2, "mcp_tool", "crawl_url", Map.of(), List.of(1), null, null),
                    new InterpretationPlan.Step(3, "final_answer", "", Map.of("answer", "done"), List.of(2), null, null)
                ),
                List.of(),
                List.of(new InterpretationPlan.Binding(1, "$.results[0].url", 2, "url", "jsonpath", true)),
                null
            ),
            new InterpretationPlan.ExecutionPolicy(3, false, List.of("web_search", "crawl_url"), List.of(), 30000),
            review()
        );
        InterpretationPlanRuntime runtime = new InterpretationPlanRuntime(
            toolRuntimeService,
            new InterpretationPlanValidator(),
            scriptedController(List.of(List.of(1), List.of(2), List.of(3)))
        );

        InterpretationPlanRuntime.ExecutionResult result = runtime.execute(new InterpretationPlanRuntime.ExecutionRequest(
            plan,
            toolRegistry,
            List.of("web_search", "crawl_url"),
            "tenant-1",
            "req-binding",
            "conv-binding",
            "user-1",
            Map.of()
        ));

        ArgumentCaptor<ToolRuntimeRequest> captor = ArgumentCaptor.forClass(ToolRuntimeRequest.class);
        verify(toolRuntimeService, times(2)).execute(captor.capture());
        assertThat(result.success()).isTrue();
        assertThat(captor.getAllValues().get(1).getToolName()).isEqualTo("crawl_url");
        assertThat(captor.getAllValues().get(1).getToolInput().getParameters())
            .containsEntry("url", "https://example.com/market");
    }

    @Test
    void acceptsAssetDiscoveryLocallyAndExecutesDependentLinuxCommand() {
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.hasTool("mcp_chatchat_mcp_server_asset_query")).thenReturn(true);
        when(toolRegistry.hasTool("mcp_chatchat_mcp_server_linux_command_execute")).thenReturn(true);
        when(toolRegistry.getToolMetadata("mcp_chatchat_mcp_server_asset_query"))
            .thenReturn(ToolMetadata.builder().id("mcp_chatchat_mcp_server_asset_query").riskLevel("low").build());
        when(toolRegistry.getToolMetadata("mcp_chatchat_mcp_server_linux_command_execute"))
            .thenReturn(ToolMetadata.builder()
                .id("mcp_chatchat_mcp_server_linux_command_execute")
                .riskLevel("medium")
                .parameters(List.of(
                    ToolParameter.builder().name("template").type("string").required(true).build(),
                    ToolParameter.builder().name("executionContext").type("object").required(true).build()
                ))
                .build());
        ToolRuntimeService toolRuntimeService = mock(ToolRuntimeService.class);
        when(toolRuntimeService.execute(any())).thenAnswer(invocation -> {
            ToolRuntimeRequest request = invocation.getArgument(0);
            if ("mcp_chatchat_mcp_server_asset_query".equals(request.getToolName())) {
                return new ToolRuntimeExecution(
                    ToolOutput.success(Map.of(
                        "schemaVersion", "asset_query_result.v1",
                        "success", true,
                        "returnedCount", 1,
                        "assets", List.of(Map.of(
                            "asset", Map.of(
                                "name", "docker_service",
                                "environment", "DEV",
                                "toolName", "ssh_docker_service"
                            ),
                            "capabilities", Map.of(
                                "allowedCommandTemplates", List.of("CHECK_SYSTEM_OVERVIEW")
                            )
                        ))
                    )),
                    ToolMetadata.builder().id(request.getToolName()).build(),
                    null,
                    "success",
                    Map.of()
                );
            }
            return new ToolRuntimeExecution(
                ToolOutput.success(Map.of("status", "ok")),
                ToolMetadata.builder().id(request.getToolName()).build(),
                null,
                "success",
                Map.of()
            );
        });
        AtomicInteger assetReviewCalls = new AtomicInteger();
        InterpretationPlanRuntime.StepResultReviewer reviewer = request -> {
            if ("mcp_chatchat_mcp_server_asset_query".equals(request.execution().toolName())) {
                assetReviewCalls.incrementAndGet();
                return InterpretationPlanRuntime.StepReview.rejected("asset discovery has no load metrics", Map.of());
            }
            return InterpretationPlanRuntime.StepReview.accepted("command output usable", Map.of());
        };
        InterpretationPlan plan = new InterpretationPlan(
            "1.0",
            new InterpretationPlan.Intent("system_operation", "Analyze docker_service load", "medium"),
            context(),
            new InterpretationPlan.Plan(
                List.of(
                    new InterpretationPlan.Step(1, "mcp_tool", "mcp_chatchat_mcp_server_asset_query",
                        Map.of("filters", Map.of("assetName", "docker_service"), "limit", 10), List.of(), null, null),
                    new InterpretationPlan.Step(2, "mcp_tool", "mcp_chatchat_mcp_server_linux_command_execute",
                        Map.of("template", "CHECK_SYSTEM_OVERVIEW", "executionContext", Map.of()), List.of(1), null, null),
                    new InterpretationPlan.Step(3, "final_answer", "", Map.of("answer", "done"), List.of(2), null, null)
                ),
                List.of(),
                List.of(
                    new InterpretationPlan.Binding(1, "$.assets[0].asset.environment", 2, "executionContext.env", "jsonpath", true),
                    new InterpretationPlan.Binding(1, "$.assets[0].asset.name", 2, "executionContext.assetName", "jsonpath", true)
                ),
                null
            ),
            new InterpretationPlan.ExecutionPolicy(
                3,
                false,
                List.of("mcp_chatchat_mcp_server_asset_query", "mcp_chatchat_mcp_server_linux_command_execute"),
                List.of(),
                30000
            ),
            review()
        );
        InterpretationPlanRuntime runtime = new InterpretationPlanRuntime(
            toolRuntimeService,
            new InterpretationPlanValidator(),
            null,
            reviewer,
            scriptedController(List.of(List.of(1), List.of(2), List.of(3)))
        );

        InterpretationPlanRuntime.ExecutionResult result = runtime.execute(new InterpretationPlanRuntime.ExecutionRequest(
            plan,
            toolRegistry,
            List.of("mcp_chatchat_mcp_server_asset_query", "mcp_chatchat_mcp_server_linux_command_execute"),
            "tenant-1",
            "req-asset-linux",
            "conv-asset-linux",
            "user-1",
            Map.of()
        ));

        assertThat(result.success())
            .as(result.status() + ": " + result.errorMessage() + " steps=" + result.steps())
            .isTrue();
        ArgumentCaptor<ToolRuntimeRequest> captor = ArgumentCaptor.forClass(ToolRuntimeRequest.class);
        verify(toolRuntimeService, times(2)).execute(captor.capture());
        Map<?, ?> linuxParameters = captor.getAllValues().get(1).getToolInput().getParameters();
        Map<?, ?> executionContext = (Map<?, ?>) linuxParameters.get("executionContext");
        assertThat(assetReviewCalls).hasValue(0);
        assertThat(captor.getAllValues().get(1).getToolName()).isEqualTo("mcp_chatchat_mcp_server_linux_command_execute");
        assertThat(linuxParameters.get("template")).isEqualTo("CHECK_SYSTEM_OVERVIEW");
        assertThat(executionContext.get("assetName")).isEqualTo("docker_service");
        assertThat(executionContext.get("env")).isEqualTo("DEV");
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
        InterpretationPlanRuntime runtime = new InterpretationPlanRuntime(
            toolRuntimeService,
            new InterpretationPlanValidator(),
            scriptedController(List.of(List.of(1), List.of(2)))
        );

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
    void acceptsAssetTypeEdgeContractFromAssetEnvelopeWhenQueryScopeOmitted() throws Exception {
        InterpretationPlanRuntime runtime = new InterpretationPlanRuntime(
            mock(ToolRuntimeService.class),
            new InterpretationPlanValidator(),
            scriptedController(List.of())
        );
        Map<String, Object> output = Map.of(
            "schemaVersion", "asset_query_result.v1",
            "success", true,
            "returnedCount", 1,
            "assets", List.of(Map.of(
                "schemaVersion", "asset_metadata.v1",
                "kind", "asset",
                "asset", Map.of(
                    "type", "ssh_host",
                    "name", "TDH scheduler server"
                ),
                "capabilities", Map.of(
                    "allowedCommandTemplateIds", List.of("CHECK_JAVA_PROCESS")
                )
            ))
        );
        var method = InterpretationPlanRuntime.class.getDeclaredMethod(
            "checkContract",
            InterpretationPlan.EdgeContract.class,
            Object.class
        );
        method.setAccessible(true);
        Object check = method.invoke(
            runtime,
            new InterpretationPlan.EdgeContract(1, 2, "assetType", "string", true),
            output
        );
        var success = check.getClass().getDeclaredMethod("success");
        success.setAccessible(true);

        assertThat(success.invoke(check)).isEqualTo(true);
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
            runStore,
            scriptedController(List.of(List.of(1), List.of(2)))
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
        assertThat(result.metadata())
            .containsEntry("protocolVersion", InterpretationExecutionProtocol.VERSION)
            .containsEntry("executionTraceId", "run-event-dag::interpretation_plan");
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
        assertThat(events.stream()
            .filter(event -> event.type() == AgentRunEventType.OBSERVATION_RECORDED)
            .map(event -> event.payload().get("metadata"))
            .map(metadata -> (Map<?, ?>) metadata)
            .anyMatch(metadata -> InterpretationExecutionProtocol.VERSION.equals(metadata.get("protocolVersion"))
                && "run-event-dag::interpretation_plan".equals(metadata.get("executionTraceId"))
                && "controller_decision".equals(metadata.get("lifecyclePhase"))
                && metadata.get("decision") instanceof Map<?, ?>
                && metadata.get("guardResult") instanceof Map<?, ?>))
            .isTrue();
    }

    @Test
    void doesNotReplayExecutionLockedStepAfterRewrite() {
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.hasTool("document_search")).thenReturn(true);
        when(toolRegistry.getToolMetadata("document_search")).thenReturn(ToolMetadata.builder().riskLevel("low").build());
        ToolRuntimeService toolRuntimeService = mock(ToolRuntimeService.class);
        when(toolRuntimeService.execute(any())).thenReturn(new ToolRuntimeExecution(
            ToolOutput.success(Map.of("results", List.of("locked evidence"))),
            ToolMetadata.builder().id("document_search").build(),
            null,
            "success",
            Map.of()
        ));
        InMemoryAgentRunStore runStore = new InMemoryAgentRunStore();
        InterpretationPlanRuntime.StepResultReviewer reviewer = request -> InterpretationPlanRuntime.StepReview.accepted(
            "sufficient evidence",
            Map.of("evidenceEvaluation", Map.of(
                "relevance", 0.95,
                "answerability", 0.95,
                "usefulness", "HIGH"
            ))
        );
        AtomicInteger firstDecision = new AtomicInteger();
        InterpretationPlanRuntime firstRuntime = new InterpretationPlanRuntime(
            toolRuntimeService,
            new InterpretationPlanValidator(),
            runStore,
            reviewer,
            request -> firstDecision.incrementAndGet() == 1
                ? InterpretationPlanRuntime.DagDecision.executeStep(1, "collect evidence")
                : InterpretationPlanRuntime.DagDecision.rewritePlan("rewrite after locked evidence")
        );

        InterpretationPlanRuntime.ExecutionResult rewriteRequested = firstRuntime.execute(new InterpretationPlanRuntime.ExecutionRequest(
            serialPlan(),
            toolRegistry,
            List.of("document_search"),
            "tenant-1",
            "req-lock-rewrite",
            "conv-lock-rewrite",
            "user-1",
            Map.of("__agentRunId", "run-lock-rewrite")
        ));

        assertThat(rewriteRequested.success()).isFalse();
        assertThat(rewriteRequested.status()).isEqualTo("DAG_REWRITE_REQUESTED");
        assertThat(rewriteRequested.steps()).extracting(InterpretationPlanRuntime.StepExecution::stepId)
            .containsExactly(1);
        Map<?, ?> executionLock = (Map<?, ?>) rewriteRequested.steps().get(0).metadata().get("executionLock");
        assertThat(executionLock.get("contractVersion")).isEqualTo("evidence_execution_lock_v1");
        assertThat(executionLock.get("lock")).isEqualTo(true);
        assertThat(executionLock.get("lockLevel")).isEqualTo("HARD");
        assertThat(executionLock.get("reason")).isEqualTo("sufficient_evidence");
        assertThat(executionLock.get("lockedSteps")).isEqualTo(List.of(1));
        assertThat(((Map<?, ?>) executionLock.get("executionConstraints")).get("blocked_tools"))
            .isEqualTo(List.of("document_search"));
        assertThat(((Map<?, ?>) executionLock.get("executionConstraints")).get("allow_only"))
            .isEqualTo(List.of("final_answer"));
        Map<?, ?> lockGraph = (Map<?, ?>) executionLock.get("lockGraph");
        assertThat(lockGraph.get("lockGraphVersion")).isEqualTo("evidence_execution_lock_v2");
        assertThat((List<?>) lockGraph.get("locks")).hasSize(1);
        assertThat(((Map<?, ?>) ((List<?>) lockGraph.get("locks")).get(0)).get("type")).isEqualTo("HARD");
        assertThat(((Map<?, ?>) lockGraph.get("dagFreeze")).get("status")).isEqualTo("FULLY_FROZEN");
        assertThat(((Map<?, ?>) lockGraph.get("propagation")).get("nodeWeights")).isInstanceOf(Map.class);

        InterpretationPlanRuntime secondRuntime = new InterpretationPlanRuntime(
            toolRuntimeService,
            new InterpretationPlanValidator(),
            runStore,
            reviewer,
            request -> {
                assertThat(request.completedStepIds()).contains(1);
                assertThat(request.remainingStepIds()).doesNotContain(1);
                assertThat(request.remainingStepIds()).containsExactly(3);
                return InterpretationPlanRuntime.DagDecision.executeStep(3, "continue from locked evidence");
            }
        );

        InterpretationPlanRuntime.ExecutionResult completed = secondRuntime.execute(new InterpretationPlanRuntime.ExecutionRequest(
            rewrittenPlanWithRepeatedSearch(),
            toolRegistry,
            List.of("document_search"),
            "tenant-1",
            "req-lock-rewrite-2",
            "conv-lock-rewrite",
            "user-1",
            Map.of("__agentRunId", "run-lock-rewrite")
        ));

        assertThat(completed.success()).isTrue();
        assertThat(completed.finalAnswer()).isEqualTo("done");
        assertThat(completed.steps()).extracting(InterpretationPlanRuntime.StepExecution::stepId)
            .containsExactly(3);
        verify(toolRuntimeService, times(1)).execute(any());
    }

    private InterpretationPlan rewrittenPlanWithRepeatedSearch() {
        return new InterpretationPlan(
            "1.0",
            new InterpretationPlan.Intent("document_retrieval", "Collect internal evidence", "low"),
            context(),
            new InterpretationPlan.Plan(List.of(
                new InterpretationPlan.Step(1, "mcp_tool", "document_search", Map.of("query", "internal"), List.of(), null, null),
                new InterpretationPlan.Step(3, "mcp_tool", "document_search", Map.of("query", "internal retry"), List.of(), null, null),
                new InterpretationPlan.Step(2, "final_answer", "", Map.of("answer", "done"), List.of(1), null, null)
            )),
            new InterpretationPlan.ExecutionPolicy(3, false, List.of("document_search"), List.of(), 30000),
            review()
        );
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

    private InterpretationPlanRuntime.DagExecutionController scriptedController(List<List<Integer>> waves) {
        AtomicInteger index = new AtomicInteger();
        return request -> {
            int current = index.getAndIncrement();
            if (waves == null || current >= waves.size()) {
                return InterpretationPlanRuntime.DagDecision.abort("No scripted DAG decision remains");
            }
            List<Integer> stepIds = waves.get(current);
            if (stepIds.size() > 1) {
                return InterpretationPlanRuntime.DagDecision.executeParallelSteps(stepIds, "scripted parallel decision");
            }
            return InterpretationPlanRuntime.DagDecision.executeStep(stepIds.get(0), "scripted decision");
        };
    }
}
