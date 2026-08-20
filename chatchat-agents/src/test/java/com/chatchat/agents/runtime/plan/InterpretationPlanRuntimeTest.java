package com.chatchat.agents.runtime.plan;

import com.chatchat.agents.runtime.AgentRunEvent;
import com.chatchat.agents.runtime.AgentRunEventType;
import com.chatchat.agents.runtime.AgentObservation;
import com.chatchat.agents.runtime.InMemoryAgentRunStore;
import com.chatchat.agents.runtime.ToolRuntimeExecution;
import com.chatchat.agents.runtime.ToolRuntimeRequest;
import com.chatchat.agents.runtime.ToolRuntimeService;
import com.chatchat.agents.runtime.batch.ToolCallBatchResult;
import com.chatchat.agents.runtime.batch.ToolCallResult;
import com.chatchat.agents.runtime.toolcall.ContextualToolArgumentResolver;
import com.chatchat.agents.tool.ToolRegistry;
import com.chatchat.common.tool.ToolMetadata;
import com.chatchat.common.tool.ToolParameter;
import com.chatchat.common.tool.ToolOutput;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InterpretationPlanRuntimeTest {

    @Test
    void persistsMonotonicNodeAttemptLifecycleAndExposesCommittedIdentity() {
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        InterpretationPlan plan = new InterpretationPlan(
            "1.0",
            new InterpretationPlan.Intent("answer", "answer", "low"),
            context(),
            new InterpretationPlan.Plan(List.of(
                new InterpretationPlan.Step(1, "final_answer", "", Map.of("answer", "done"),
                    List.of(), null, null)
            )),
            new InterpretationPlan.ExecutionPolicy(1, false, List.of(), List.of(), 10_000),
            review()
        );
        List<NodeAttemptStore.State> transitions = new ArrayList<>();
        AtomicInteger revisions = new AtomicInteger();
        NodeAttemptStore store = new NodeAttemptStore() {
            @Override
            public AttemptSnapshot create(AttemptCommand command) {
                transitions.add(State.CREATED);
                return new AttemptSnapshot("attempt-1", command.tenantId(), command.runId(),
                    command.nodeId(), 1, State.CREATED, 0L, Instant.now(), Instant.now());
            }

            @Override
            public AttemptSnapshot transition(String tenantId, String attemptId, State expectedState,
                                              State targetState, String reason, Map<String, Object> metadata) {
                assertThat(transitions.get(transitions.size() - 1)).isEqualTo(expectedState);
                assertThat(expectedState.mayTransitionTo(targetState)).isTrue();
                transitions.add(targetState);
                return new AttemptSnapshot(attemptId, tenantId, "request-attempt", 1, 1,
                    targetState, revisions.incrementAndGet(), Instant.now(), Instant.now());
            }

            @Override
            public BarrierResult commitBarrier(BarrierCommand command) {
                assertThat(transitions).endsWith(State.PREPARED);
                transitions.add(State.COMMITTED);
                return new BarrierResult(command.executionEpoch(), true, List.of(
                    new AttemptSnapshot("attempt-1", command.tenantId(), command.runId(), 1, 1,
                        State.COMMITTED, revisions.incrementAndGet(), Instant.now(), Instant.now())
                ));
            }
        };
        InterpretationPlanRuntime runtime = new InterpretationPlanRuntime(
            mock(ToolRuntimeService.class), new InterpretationPlanValidator(),
            scriptedController(List.of(List.of(1))));
        runtime.setNodeAttemptStore(store);

        InterpretationPlanRuntime.ExecutionResult result = runtime.execute(
            new InterpretationPlanRuntime.ExecutionRequest(
                plan, toolRegistry, List.of(), "tenant", "request-attempt", "conversation", "user", Map.of()));

        assertThat(result.success()).isTrue();
        assertThat(transitions).containsExactly(
            NodeAttemptStore.State.CREATED,
            NodeAttemptStore.State.READY,
            NodeAttemptStore.State.RUNNING,
            NodeAttemptStore.State.PREPARED,
            NodeAttemptStore.State.COMMITTED
        );
        assertThat(result.steps()).singleElement().satisfies(step -> assertThat(step.metadata())
            .containsEntry("nodeAttemptId", "attempt-1")
            .containsEntry("nodeAttemptNumber", 1)
            .containsEntry("nodeAttemptState", "COMMITTED")
            .containsEntry("commitBarrier", "SATISFIED")
            .containsEntry("committedEvidence", true));
        assertThat(NodeAttemptStore.State.COMMITTED.mayTransitionTo(NodeAttemptStore.State.RUNNING)).isFalse();
    }

    @Test
    void commitBarrierFailureDiscardsPreparedEvidenceBeforeFinalAnalysis() {
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        InterpretationPlan plan = new InterpretationPlan(
            "1.0", new InterpretationPlan.Intent("answer", "answer", "low"), context(),
            new InterpretationPlan.Plan(List.of(
                new InterpretationPlan.Step(1, "final_answer", "", Map.of("answer", "must not leak"),
                    List.of(), null, null)
            )),
            new InterpretationPlan.ExecutionPolicy(1, false, List.of(), List.of(), 10_000), review());
        AtomicReference<NodeAttemptStore.State> state = new AtomicReference<>(NodeAttemptStore.State.CREATED);
        NodeAttemptStore store = new NodeAttemptStore() {
            @Override
            public AttemptSnapshot create(AttemptCommand command) {
                return snapshot(command.tenantId(), command.runId(), State.CREATED, 0L);
            }

            @Override
            public AttemptSnapshot transition(String tenantId, String attemptId, State expectedState,
                                              State targetState, String reason, Map<String, Object> metadata) {
                assertThat(state.get()).isEqualTo(expectedState);
                state.set(targetState);
                return snapshot(tenantId, "request-barrier-failure", targetState, targetState.ordinal());
            }

            @Override
            public BarrierResult commitBarrier(BarrierCommand command) {
                throw new IllegalStateException("simulated atomic barrier rejection");
            }

            private AttemptSnapshot snapshot(String tenantId, String runId, State current, long revision) {
                return new AttemptSnapshot("attempt-rejected", tenantId, runId, 1, 1,
                    current, revision, Instant.now(), Instant.now());
            }
        };
        InterpretationPlanRuntime runtime = new InterpretationPlanRuntime(
            mock(ToolRuntimeService.class), new InterpretationPlanValidator(),
            scriptedController(List.of(List.of(1))));
        runtime.setNodeAttemptStore(store);

        InterpretationPlanRuntime.ExecutionResult result = runtime.execute(
            new InterpretationPlanRuntime.ExecutionRequest(
                plan, toolRegistry, List.of(), "tenant", "request-barrier-failure",
                "conversation", "user", Map.of()));

        assertThat(result.success()).isFalse();
        assertThat(result.finalAnswer()).isNull();
        assertThat(state.get()).isEqualTo(NodeAttemptStore.State.FAILED);
        assertThat(result.steps()).singleElement().satisfies(step -> {
            assertThat(step.success()).isFalse();
            assertThat(step.output()).isNull();
            assertThat(step.finalAnswer()).isNull();
            assertThat(step.errorMessage()).contains("COMMIT_BARRIER_REJECTED");
            assertThat(step.metadata())
                .containsEntry("commitBarrier", "REJECTED")
                .containsEntry("committedEvidence", false);
        });
    }

    @Test
    void commitsParallelEpochAtomicallyBeforeWritingCheckpoints() {
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.hasTool(any())).thenReturn(true);
        when(toolRegistry.getToolMetadata(any())).thenReturn(ToolMetadata.builder().riskLevel("low").build());
        ToolRuntimeService tools = mock(ToolRuntimeService.class);
        when(tools.execute(any())).thenAnswer(invocation -> {
            ToolRuntimeRequest request = invocation.getArgument(0);
            return new ToolRuntimeExecution(
                ToolOutput.success(Map.of("tool", request.getToolName())),
                ToolMetadata.builder().id(request.getToolName()).build(), null, "success", Map.of());
        });
        InMemoryAgentRunStore checkpoints = new InMemoryAgentRunStore();
        RecordingNodeAttemptStore attempts = new RecordingNodeAttemptStore();
        InterpretationPlanRuntime runtime = new InterpretationPlanRuntime(
            tools, new InterpretationPlanValidator(), checkpoints, null,
            scriptedController(List.of(List.of(1, 2), List.of(3))));
        runtime.setNodeAttemptStore(attempts);

        InterpretationPlanRuntime.ExecutionResult result = runtime.execute(
            new InterpretationPlanRuntime.ExecutionRequest(
                parallelPlan(), toolRegistry, List.of("document_search", "web_search"),
                "tenant-1", "request-parallel-barrier", "conversation", "user", Map.of()));

        assertThat(result.success()).isTrue();
        assertThat(attempts.barriers()).extracting(command -> command.requiredAttemptIds().size())
            .containsExactly(2, 1);
        assertThat(result.steps()).allSatisfy(step -> assertThat(step.metadata())
            .containsEntry("nodeAttemptState", "COMMITTED")
            .containsEntry("commitBarrier", "SATISFIED")
            .containsEntry("committedEvidence", true));
        assertThat(checkpoints.planStepCheckpoints("request-parallel-barrier"))
            .extracting(PlanStepCheckpoint::stepId)
            .containsExactlyInAnyOrder(1, 2, 3);
    }

    @Test
    void preservesCommittedIndependentEvidenceWhenParallelSiblingFails() {
        String failingTool = "orders_query";
        String successfulTool = "positions_query";
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.hasTool(any())).thenReturn(true);
        when(toolRegistry.getToolMetadata(any())).thenReturn(ToolMetadata.builder().riskLevel("low").build());
        ToolRuntimeService tools = mock(ToolRuntimeService.class);
        when(tools.execute(any())).thenAnswer(invocation -> {
            ToolRuntimeRequest request = invocation.getArgument(0);
            ToolOutput output = failingTool.equals(request.getToolName())
                ? ToolOutput.failure("orders unavailable")
                : ToolOutput.success(Map.of("records", List.of(Map.of("symbol", "600000"))));
            return new ToolRuntimeExecution(output,
                ToolMetadata.builder().id(request.getToolName()).build(), null,
                output.isSuccess() ? "success" : "failed", Map.of());
        });
        InterpretationPlan plan = new InterpretationPlan(
            "1.0", new InterpretationPlan.Intent("analysis", "partial evidence", "low"), context(),
            new InterpretationPlan.Plan(List.of(
                new InterpretationPlan.Step(1, "mcp_tool", failingTool, Map.of(), List.of(), null, null),
                new InterpretationPlan.Step(2, "mcp_tool", successfulTool, Map.of(), List.of(), null, null),
                new InterpretationPlan.Step(3, "final_answer", "", Map.of("answer", "partial result"),
                    List.of(2), null, null)
            )),
            new InterpretationPlan.ExecutionPolicy(
                3, true, List.of(failingTool, successfulTool), List.of(), 30_000), review());
        InMemoryAgentRunStore checkpoints = new InMemoryAgentRunStore();
        RecordingNodeAttemptStore attempts = new RecordingNodeAttemptStore();
        InterpretationPlanRuntime runtime = new InterpretationPlanRuntime(
            tools, new InterpretationPlanValidator(), checkpoints, null,
            scriptedController(List.of(List.of(1, 2), List.of(3))));
        runtime.setNodeAttemptStore(attempts);

        InterpretationPlanRuntime.ExecutionResult result = runtime.execute(
            new InterpretationPlanRuntime.ExecutionRequest(
                plan, toolRegistry, List.of(failingTool, successfulTool),
                "tenant-1", "request-partial-barrier", "conversation", "user", Map.of(
                    DagGovernanceContractProvider.CONTRACT_ATTRIBUTE, Map.of(
                        "rules", Map.of("execution", Map.of("continueIndependentBranches", true))
                    )
                )));

        assertThat(result.success()).isTrue();
        assertThat(result.status()).isEqualTo("completed_with_partial_evidence");
        assertThat(result.finalAnswer()).isEqualTo("partial result");
        assertThat(result.steps()).filteredOn(step -> step.stepId() == 1).singleElement()
            .satisfies(step -> assertThat(step.metadata()).containsEntry("nodeAttemptState", "FAILED"));
        assertThat(result.steps()).filteredOn(step -> step.stepId() == 2).singleElement()
            .satisfies(step -> assertThat(step.metadata())
                .containsEntry("nodeAttemptState", "COMMITTED")
                .containsEntry("committedEvidence", true));
        assertThat(checkpoints.planStepCheckpoints("request-partial-barrier"))
            .extracting(PlanStepCheckpoint::stepId)
            .containsExactlyInAnyOrder(2, 3);
    }

    @Test
    void emptyToolResultDoesNotFailModelDataEdgeIntoFinalAnswer() {
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.hasTool("metadata_search")).thenReturn(true);
        when(toolRegistry.getToolMetadata("metadata_search"))
            .thenReturn(ToolMetadata.builder().id("metadata_search").riskLevel("low").build());
        ToolRuntimeService toolRuntimeService = mock(ToolRuntimeService.class);
        when(toolRuntimeService.execute(any())).thenReturn(new ToolRuntimeExecution(
            ToolOutput.success(Map.of(
                "totalMatched", 0,
                "tableCatalog", List.of(),
                "results", List.of()
            )),
            ToolMetadata.builder().id("metadata_search").build(),
            null,
            "success",
            Map.of()
        ));
        InterpretationPlan plan = new InterpretationPlan(
            "1.0",
            new InterpretationPlan.Intent("review", "review available metadata", "low"),
            context(),
            new InterpretationPlan.Plan(
                List.of(
                    new InterpretationPlan.Step(1, "mcp_tool", "metadata_search",
                        Map.of(), List.of(), null, null),
                    new InterpretationPlan.Step(2, "final_answer", "",
                        Map.of("answer", "report the evidence gap"), List.of(1), null, null)
                ),
                List.of(new InterpretationPlan.EdgeContract(
                    1, 2, "tables[0]", "object", true)),
                List.of(),
                List.of(),
                null,
                null
            ),
            new InterpretationPlan.ExecutionPolicy(
                2, false, List.of("metadata_search"), List.of(), 10_000),
            review()
        );
        InterpretationPlanRuntime runtime = new InterpretationPlanRuntime(
            toolRuntimeService,
            new InterpretationPlanValidator(),
            scriptedController(List.of(List.of(1), List.of(2)))
        );

        InterpretationPlanRuntime.ExecutionResult result = runtime.execute(
            new InterpretationPlanRuntime.ExecutionRequest(
                plan, toolRegistry, List.of("metadata_search"),
                "tenant", "request", "conversation", "user", Map.of(
                    DagGovernanceContractProvider.CONTRACT_ATTRIBUTE, Map.of(
                        "contractId", "runtime_dag_governance.v3",
                        "contractVersion", "runtime_dag_governance.v3",
                        "checksumSha256", "sha-v3",
                        "rules", Map.of("immutable", true)
                    )
                )
            ));

        assertThat(result.success())
            .withFailMessage("status=%s error=%s metadata=%s", result.status(), result.errorMessage(), result.metadata())
            .isTrue();
        assertThat(result.status()).isEqualTo("completed");
        assertThat(result.finalAnswer()).isEqualTo("report the evidence gap");
        assertThat(result.metadata())
            .containsEntry("dagGovernanceContractId", "runtime_dag_governance.v3")
            .containsEntry("dagGovernanceContractVersion", "runtime_dag_governance.v3")
            .containsEntry("dagGovernanceContractChecksum", "sha-v3");
        assertThat(result.steps()).noneMatch(step -> step.errorMessage() != null
            && step.errorMessage().contains("EDGE_CONTRACT_FAILED"));
    }

    @Test
    void recoversMissingDirectToolArgumentFromCompletedEvidenceAndPublishesRepair() {
        String sourceTool = "portfolio_snapshot_query";
        String targetTool = "market_observation_query";
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.hasTool(any())).thenReturn(true);
        when(toolRegistry.getToolMetadata(sourceTool)).thenReturn(
            ToolMetadata.builder().id(sourceTool).riskLevel("low").build());
        when(toolRegistry.getToolMetadata(targetTool)).thenReturn(ToolMetadata.builder()
            .id(targetTool)
            .riskLevel("low")
            .metadata(Map.of("inputSchema", Map.of(
                "type", "object",
                "required", List.of("symbol"),
                "properties", Map.of("symbol", Map.of(
                    "type", "string", "aliases", List.of("stockCode"))))))
            .build());
        AtomicReference<ToolRuntimeRequest> targetRequest = new AtomicReference<>();
        ToolRuntimeService toolRuntimeService = mock(ToolRuntimeService.class);
        when(toolRuntimeService.execute(any())).thenAnswer(invocation -> {
            ToolRuntimeRequest request = invocation.getArgument(0);
            Object output;
            if (sourceTool.equals(request.getToolName())) {
                output = Map.of("positions", List.of(Map.of("stockCode", "600839")));
            } else {
                targetRequest.set(request);
                output = Map.of("quote", 12.34);
            }
            return new ToolRuntimeExecution(
                ToolOutput.success(output),
                ToolMetadata.builder().id(request.getToolName()).build(),
                null, "success", Map.of());
        });
        InterpretationPlan plan = new InterpretationPlan(
            "1.0",
            new InterpretationPlan.Intent("tool_chain", "analyze portfolio market data", "low"),
            context(),
            new InterpretationPlan.Plan(List.of(
                new InterpretationPlan.Step(1, "mcp_tool", sourceTool, Map.of(), List.of(), null, null),
                new InterpretationPlan.Step(2, "mcp_tool", targetTool, Map.of(), List.of(1), null, null),
                new InterpretationPlan.Step(3, "final_answer", "", Map.of("answer", "done"),
                    List.of(2), null, null)
            ), List.of(), List.of(), List.of(), null, null),
            new InterpretationPlan.ExecutionPolicy(
                3, false, List.of(sourceTool, targetTool), List.of(), 30_000),
            review());
        InterpretationPlanRuntime runtime = new InterpretationPlanRuntime(
            toolRuntimeService, new InterpretationPlanValidator(),
            scriptedController(List.of(List.of(1), List.of(2), List.of(3))));

        InterpretationPlanRuntime.ExecutionResult result = runtime.execute(
            new InterpretationPlanRuntime.ExecutionRequest(
                plan, toolRegistry, List.of(sourceTool, targetTool),
                "tenant", "request-context-repair", "conversation", "user",
                Map.of("originalUserQuery", "analyze my position")));

        assertThat(result.success()).isTrue();
        assertThat(targetRequest.get().getToolInput().getParameters())
            .containsEntry("symbol", "600839")
            .doesNotContainKey("__runtimeContextParameterRecovery")
            .doesNotContainKey(ContextualToolArgumentResolver.MODEL_EVIDENCE_FIELD);
        assertThat(result.steps()).filteredOn(step -> step.stepId() == 2)
            .singleElement()
            .satisfies(step -> {
                assertThat(step.metadata()).containsEntry("eventKind", "DAG_REPAIR")
                    .containsEntry("eventState", "APPLIED");
                assertThat(((Map<?, ?>) step.metadata().get("repairEvent")).get("repairCode"))
                    .isEqualTo("CONTEXT_PARAMETER_EVIDENCE_APPLIED");
            });
    }

    @Test
    void newsSearchUsesOriginalTodayQueryAndRuntimeOwnedDateRange() throws Exception {
        InterpretationPlanRuntime runtime = new InterpretationPlanRuntime(
            mock(ToolRuntimeService.class), new InterpretationPlanValidator(), scriptedController(List.of()));
        Method method = InterpretationPlanRuntime.class.getDeclaredMethod(
            "normalizeNewsSearchInput", InterpretationPlan.Step.class,
            InterpretationPlanRuntime.ExecutionRequest.class, Map.class);
        method.setAccessible(true);
        String userQuery = "\u8bf7\u6839\u636e\u4eca\u65e5\u8d22\u7ecf\u8d44\u8baf\u751f\u6210A\u80a1\u6536\u76d8\u590d\u76d8";
        Map<String, Object> input = new java.util.LinkedHashMap<>();
        input.put("query", "2025\u5e744\u67088\u65e5 A\u80a1\u6536\u76d8\u590d\u76d8");
        input.put("time_range", "today");
        input.put("category", "finance");
        InterpretationPlan.Step step = new InterpretationPlan.Step(
            1, "mcp_tool", "mcp_chatchat_mcp_server_news_search", Map.of(), List.of(), null, null);
        InterpretationPlanRuntime.ExecutionRequest request = new InterpretationPlanRuntime.ExecutionRequest(
            null, null, List.of(), "tenant", "request", "conversation", "user",
            Map.of("originalUserQuery", userQuery, "timezone", "Asia/Shanghai"));

        method.invoke(runtime, step, request, input);

        java.time.ZoneId zone = java.time.ZoneId.of("Asia/Shanghai");
        java.time.LocalDate today = java.time.LocalDate.now(zone);
        assertThat(input.get("query")).isEqualTo(userQuery);
        assertThat(java.time.Instant.parse(String.valueOf(input.get("startTime"))))
            .isEqualTo(today.atStartOfDay(zone).toInstant());
        assertThat(java.time.Instant.parse(String.valueOf(input.get("endTime"))))
            .isAfter(today.atStartOfDay(zone).toInstant());
        assertThat(input).doesNotContainKeys("time_range", "category");
    }

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
    void publishesDiagnosticCoverageAndEvidenceAssessmentInExecutionMetadata() {
        String databaseTool = "database_status_query";
        String hostTool = "host_resource_query";
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.hasTool(databaseTool)).thenReturn(true);
        when(toolRegistry.hasTool(hostTool)).thenReturn(true);
        when(toolRegistry.getToolMetadata(any())).thenReturn(ToolMetadata.builder().riskLevel("low").build());
        ToolRuntimeService toolRuntimeService = mock(ToolRuntimeService.class);
        when(toolRuntimeService.execute(any())).thenAnswer(invocation -> {
            ToolRuntimeRequest request = invocation.getArgument(0);
            Object output = databaseTool.equals(request.getToolName())
                ? Map.of(
                    "status", "OPEN",
                    "diagnosticAssessment", Map.of(
                        "availability", Map.of("score", 100, "confidence", 0.95)
                    )
                )
                : Map.of("cpuPct", 21, "memoryPct", 47, "diskPct", 62);
            return new ToolRuntimeExecution(
                ToolOutput.success(output),
                ToolMetadata.builder().id(request.getToolName()).build(),
                null,
                "success",
                Map.of()
            );
        });
        InterpretationPlan plan = new InterpretationPlan(
            "1.0",
            new InterpretationPlan.Intent("tool_chain", "analyze database and host health", "low"),
            context(),
            new InterpretationPlan.Plan(
                List.of(
                    new InterpretationPlan.Step(
                        1, "mcp_tool", databaseTool,
                        Map.of("diagnosticCapability", "instance_status"),
                        List.of(), null, null
                    ),
                    new InterpretationPlan.Step(
                        2, "mcp_tool", hostTool,
                        Map.of("diagnosticCapability", "resource_usage"),
                        List.of(1), null, null
                    ),
                    new InterpretationPlan.Step(
                        3, "final_answer", "", Map.of("answer", "partial"), List.of(2), null, null
                    )
                ),
                List.of(),
                List.of(),
                List.of(),
                null,
                new InterpretationPlan.DiagnosticProfile(
                    "environment_health_check",
                    "mixed",
                    List.of(
                        new InterpretationPlan.DiagnosticCheck(
                            "database_availability", "instance_status", "availability", true, 1, List.of(1)
                        ),
                        new InterpretationPlan.DiagnosticCheck(
                            "session_pressure", "session_overview", "performance", true, 2, List.of()
                        ),
                        new InterpretationPlan.DiagnosticCheck(
                            "host_resources", "resource_usage", "capacity", true, 3, List.of(2)
                        )
                    )
                )
            ),
            new InterpretationPlan.ExecutionPolicy(
                3, false, List.of(databaseTool, hostTool), List.of(), 30000
            ),
            review()
        );
        InterpretationPlanRuntime runtime = new InterpretationPlanRuntime(
            toolRuntimeService,
            new InterpretationPlanValidator(),
            scriptedController(List.of(List.of(1), List.of(2), List.of(3)))
        );

        InterpretationPlanRuntime.ExecutionResult result = runtime.execute(
            new InterpretationPlanRuntime.ExecutionRequest(
                plan,
                toolRegistry,
                List.of(databaseTool, hostTool),
                "tenant-1",
                "req-diagnostic-coverage",
                "conv-diagnostic-coverage",
                "user-1",
                Map.of()
            )
        );

        assertThat(result.success()).isTrue();
        assertThat(result.metadata().get("diagnosticRun"))
            .isInstanceOfSatisfying(DiagnosticRun.class, run -> {
                assertThat(run.coverage())
                    .isEqualTo(new DiagnosticRun.Coverage(3, 2, 0, 1, 0.667));
                assertThat(run.checks()).filteredOn(check -> "session_pressure".equals(check.checkId()))
                    .singleElement()
                    .satisfies(check -> {
                        assertThat(check.status()).isEqualTo("missing");
                        assertThat(check.reason()).isEqualTo("execution_budget_exhausted");
                    });
                assertThat(run.assessment().overallStatus()).isEqualTo("INSUFFICIENT_EVIDENCE");
                assertThat(run.assessment().dimensions().get("availability").score()).isEqualTo(100.0);
            });
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
    void continuesIndependentBranchesWhenDependencyAllowsPartialEvidence() {
        String failingTool = "orders_query";
        String succeedingTool = "positions_query";
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.hasTool(any())).thenReturn(true);
        when(toolRegistry.getToolMetadata(any())).thenReturn(ToolMetadata.builder().riskLevel("low").build());
        ToolRuntimeService toolRuntimeService = mock(ToolRuntimeService.class);
        when(toolRuntimeService.execute(any())).thenAnswer(invocation -> {
            ToolRuntimeRequest request = invocation.getArgument(0);
            ToolOutput output = failingTool.equals(request.getToolName())
                ? ToolOutput.failure("orders backend unavailable")
                : ToolOutput.success(Map.of("records", List.of(Map.of("symbol", "600000"))));
            return new ToolRuntimeExecution(
                output,
                ToolMetadata.builder().id(request.getToolName()).build(),
                null,
                output.isSuccess() ? "success" : "failed",
                Map.of()
            );
        });
        InterpretationPlan plan = new InterpretationPlan(
            "1.0",
            new InterpretationPlan.Intent("data_query", "collect independent customer dimensions", "low"),
            context(),
            new InterpretationPlan.Plan(
                List.of(
                    new InterpretationPlan.Step(1, "mcp_tool", failingTool, Map.of(), List.of(), null, null),
                    new InterpretationPlan.Step(2, "mcp_tool", succeedingTool, Map.of(), List.of(), null, null),
                    new InterpretationPlan.Step(3, "final_answer", "", Map.of("answer", "partial result"),
                        List.of(1, 2), null, null)
                ),
                List.of(),
                List.of(
                    new InterpretationPlan.DependencyContract(
                        1, 3, true, null, "answer may use partial order evidence",
                        "continue_with_partial_evidence"),
                    new InterpretationPlan.DependencyContract(
                        2, 3, true, null, "answer uses position evidence",
                        "continue_with_partial_evidence")
                ),
                List.of(),
                null
            ),
            new InterpretationPlan.ExecutionPolicy(
                3, false, List.of(failingTool, succeedingTool), List.of(), 30_000),
            review()
        );
        InterpretationPlanRuntime runtime = new InterpretationPlanRuntime(
            toolRuntimeService,
            new InterpretationPlanValidator(),
            scriptedController(List.of(List.of(1), List.of(2), List.of(3)))
        );

        InterpretationPlanRuntime.ExecutionResult result = runtime.execute(
            new InterpretationPlanRuntime.ExecutionRequest(
                plan, toolRegistry, List.of(failingTool, succeedingTool), "tenant-1",
                "req-partial-continuation", "conv-partial-continuation", "user-1", Map.of()
            )
        );

        assertThat(result.success()).isTrue();
        assertThat(result.status()).isEqualTo("completed_with_partial_evidence");
        assertThat(result.finalAnswer()).isEqualTo("partial result");
        assertThat(result.steps()).extracting(InterpretationPlanRuntime.StepExecution::stepId)
            .containsExactly(1, 2, 3);
        assertThat(result.metadata())
            .containsEntry("continuedFailureStepIds", List.of(1))
            .containsEntry("partialEvidence", true);
        verify(toolRuntimeService, times(2)).execute(any());
    }

    @Test
    void databaseGovernanceIsolatesFailedRegionAndContinuesIndependentBranch() {
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.hasTool(any())).thenReturn(true);
        when(toolRegistry.getToolMetadata(any())).thenReturn(
            ToolMetadata.builder().riskLevel("low").build());
        ToolRuntimeService toolRuntimeService = mock(ToolRuntimeService.class);
        when(toolRuntimeService.execute(any())).thenAnswer(invocation -> {
            ToolRuntimeRequest request = invocation.getArgument(0);
            ToolOutput output = "failing_query".equals(request.getToolName())
                ? ToolOutput.failure("source unavailable")
                : ToolOutput.success(Map.of("records", List.of(Map.of("value", 1))));
            return new ToolRuntimeExecution(output,
                ToolMetadata.builder().id(request.getToolName()).build(), null,
                output.isSuccess() ? "success" : "failed", Map.of());
        });
        InterpretationPlan plan = new InterpretationPlan(
            "1.0",
            new InterpretationPlan.Intent("analysis", "isolate failed evidence branch", "low"),
            context(),
            new InterpretationPlan.Plan(
                List.of(
                    new InterpretationPlan.Step(1, "mcp_tool", "failing_query", Map.of(), List.of(), null, null),
                    new InterpretationPlan.Step(2, "mcp_tool", "independent_query", Map.of(), List.of(), null, null),
                    new InterpretationPlan.Step(3, "mcp_tool", "blocked_query", Map.of(), List.of(1), null, null),
                    new InterpretationPlan.Step(4, "final_answer", "",
                        Map.of("answer", "independent evidence complete"), List.of(2), null, null)
                ),
                List.of(), List.of(), List.of(), null
            ),
            new InterpretationPlan.ExecutionPolicy(
                4, false, List.of("failing_query", "independent_query", "blocked_query"), List.of(), 30_000),
            review()
        );
        InterpretationPlanRuntime runtime = new InterpretationPlanRuntime(
            toolRuntimeService, new InterpretationPlanValidator(),
            scriptedController(List.of(List.of(1), List.of(2), List.of(4)))
        );
        Map<String, Object> rules = new LinkedHashMap<>(DagGovernanceContractProvider.defaultV1Rules());

        InterpretationPlanRuntime.ExecutionResult result = runtime.execute(
            new InterpretationPlanRuntime.ExecutionRequest(
                plan, toolRegistry, List.of("failing_query", "independent_query", "blocked_query"),
                "tenant", "request", "conversation", "user", Map.of(
                    DagGovernanceContractProvider.CONTRACT_ATTRIBUTE, Map.of(
                        "contractId", DagGovernanceContractProvider.INITIAL_VERSION,
                        "contractVersion", DagGovernanceContractProvider.INITIAL_VERSION,
                        "checksumSha256", "test-checksum",
                        "rules", rules
                    )
                )
            )
        );

        assertThat(result.success())
            .withFailMessage("status=%s error=%s metadata=%s", result.status(),
                result.errorMessage(), result.metadata())
            .isTrue();
        assertThat(result.status()).isEqualTo("completed_with_partial_evidence");
        assertThat(result.steps()).extracting(InterpretationPlanRuntime.StepExecution::stepId)
            .containsExactly(1, 2, 4);
        assertThat(result.metadata())
            .containsEntry("continuedFailureStepIds", List.of(1))
            .containsEntry("failureRegionSkippedStepIds", List.of(3));
        verify(toolRuntimeService, times(2)).execute(any());
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
    void preservesPartialSqlResultWhenModelReviewRejectsIt() {
        String toolName = "mcp_chatchat_mcp_server_sql_query_execute";
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.hasTool(toolName)).thenReturn(true);
        when(toolRegistry.getToolMetadata(toolName)).thenReturn(ToolMetadata.builder().riskLevel("low").build());
        ToolRuntimeService toolRuntimeService = mock(ToolRuntimeService.class);
        when(toolRuntimeService.execute(any())).thenReturn(new ToolRuntimeExecution(
            ToolOutput.success(Map.of(
                "schemaVersion", "tool_execution_result.v1",
                "kind", "sql_query",
                "success", true,
                "payload", Map.of(
                    "columns", List.of("STAT_DATE", "TOTAL_MARKET_VALUE", "AVG_CHANGE_PCT"),
                    "rows", List.of(Map.of(
                        "STAT_DATE", "2026-07-04",
                        "TOTAL_MARKET_VALUE", 1000,
                        "AVG_CHANGE_PCT", 0.0123
                    )),
                    "rowCount", 1
                )
            )),
            ToolMetadata.builder().id(toolName).build(),
            null,
            "success",
            Map.of()
        ));
        InterpretationPlanRuntime runtime = new InterpretationPlanRuntime(
            toolRuntimeService,
            new InterpretationPlanValidator(),
            new InterpretationPlanOptimizer(),
            null,
            request -> InterpretationPlanRuntime.StepReview.rejected(
                "missing advancers and decliners",
                Map.of("reviewed", true)
            ),
            scriptedController(List.of(List.of(1), List.of(2)))
        );

        InterpretationPlanRuntime.ExecutionResult result = runtime.execute(new InterpretationPlanRuntime.ExecutionRequest(
            sqlQueryPlan(toolName),
            toolRegistry,
            List.of(toolName),
            "tenant-1",
            "req-sql-partial",
            "conv-sql-partial",
            "user-1",
            Map.of()
        ));

        assertThat(result.success()).as("status=%s error=%s steps=%s", result.status(), result.errorMessage(), result.steps()).isTrue();
        assertThat(result.status()).isEqualTo("completed");
        assertThat(result.steps()).extracting(InterpretationPlanRuntime.StepExecution::stepId)
            .containsExactly(1, 2);
        assertThat(result.steps().get(0).metadata())
            .containsEntry("toolResultReviewSatisfied", false)
            .containsEntry("toolResultReviewPartialAccepted", true)
            .containsEntry("partialEvidence", true);
    }

    @Test
    void preservesSuccessfulStructuredEvidenceWhenModelReviewNeedsMoreEvidence() {
        String toolName = "future_enterprise_standard_search";
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.hasTool(toolName)).thenReturn(true);
        when(toolRegistry.getToolMetadata(toolName))
            .thenReturn(ToolMetadata.builder().id(toolName).riskLevel("low").build());
        ToolRuntimeService toolRuntimeService = mock(ToolRuntimeService.class);
        when(toolRuntimeService.execute(any())).thenReturn(new ToolRuntimeExecution(
            ToolOutput.success(Map.of(
                "schemaVersion", "future_evidence.v1",
                "success", true,
                "count", 20,
                "evidenceObjectCount", 20,
                "records", List.of(Map.of("standard", "ADS naming"))
            )),
            ToolMetadata.builder().id(toolName).build(),
            null,
            "success",
            Map.of()
        ));
        InterpretationPlan plan = new InterpretationPlan(
            "1.0",
            new InterpretationPlan.Intent("governance", "evaluate design", "low"),
            context(),
            new InterpretationPlan.Plan(List.of(
                new InterpretationPlan.Step(1, "mcp_tool", toolName, Map.of(), List.of(), null, null),
                new InterpretationPlan.Step(2, "final_answer", "", Map.of("answer", "done"), List.of(1), null, null)
            )),
            new InterpretationPlan.ExecutionPolicy(2, false, List.of(toolName), List.of(), 30_000),
            review()
        );
        InterpretationPlanRuntime runtime = new InterpretationPlanRuntime(
            toolRuntimeService,
            new InterpretationPlanValidator(),
            null,
            request -> InterpretationPlanRuntime.StepReview.rejected(
                "returned evidence is useful but not sufficient for the complete conclusion",
                Map.of(
                    "evidenceIterationSufficient", false,
                    "missingEvidence", List.of("physical metadata"),
                    "nextActions", List.of(Map.of("intent", "retrieve missing evidence"))
                )
            ),
            scriptedController(List.of(List.of(1), List.of(2)))
        );

        InterpretationPlanRuntime.ExecutionResult result = runtime.execute(
            new InterpretationPlanRuntime.ExecutionRequest(
                plan, toolRegistry, List.of(toolName),
                "tenant-1", "request-partial-standard", "conversation-1", "user-1", Map.of()
            ));

        assertThat(result.success()).isTrue();
        assertThat(result.steps()).extracting(InterpretationPlanRuntime.StepExecution::stepId)
            .containsExactly(1, 2);
        assertThat(result.steps().get(0).success()).isTrue();
        assertThat(result.steps().get(0).errorMessage()).isNull();
        assertThat(result.steps().get(0).metadata())
            .containsEntry("toolResultReviewSatisfied", false)
            .containsEntry("toolExecutionStatus", "SUCCEEDED")
            .containsEntry("evidenceSufficiency", "INSUFFICIENT")
            .containsEntry("stepFulfillmentStatus", "PARTIAL")
            .containsEntry("modelReviewExecutionStatusOverridePrevented", true)
            .containsEntry("partialEvidence", true);
    }

    @Test
    void factChecksTemplateDiscoveryWhenMcpResultIsJsonString() throws Exception {
        String templateQueryResult = """
            {
              "success": true,
              "returnedCount": 1,
              "templates": [
                {
                  "templateId": "MYSQL_INNODB_STATUS",
                  "name": "MySQL InnoDB engine status"
                }
              ]
            }
            """;
        InterpretationPlanRuntime runtime = new InterpretationPlanRuntime(
            mock(ToolRuntimeService.class),
            new InterpretationPlanValidator(),
            mock(InterpretationPlanRuntime.DagExecutionController.class)
        );
        Method method = InterpretationPlanRuntime.class.getDeclaredMethod(
            "localToolResultReview",
            InterpretationPlan.Step.class,
            InterpretationPlanRuntime.StepExecution.class
        );
        method.setAccessible(true);
        InterpretationPlan.Step step = new InterpretationPlan.Step(
            1,
            "mcp_tool",
            "mcp_chatchat_mcp_server_sql_datasource_template_query",
            Map.of("filters", Map.of("intent", "InnoDB status"), "limit", 5),
            List.of(),
            null,
            null
        );
        InterpretationPlanRuntime.StepExecution execution = new InterpretationPlanRuntime.StepExecution(
            1,
            "mcp_tool",
            "mcp_chatchat_mcp_server_sql_datasource_template_query",
            true,
            templateQueryResult,
            null,
            null,
            null,
            5
        );

        InterpretationPlanRuntime.StepReview review =
            (InterpretationPlanRuntime.StepReview) method.invoke(runtime, step, execution);

        assertThat(review).isNotNull();
        assertThat(review.satisfied()).isTrue();
        assertThat(review.metadata())
            .containsEntry("localFactCheckHasEvidence", true)
            .containsEntry("localFactCheckEvidenceType", "template_discovery")
            .containsEntry("templateDiscoveryReturnedCount", 1);
    }

    @Test
    void rejectsTechnicallySuccessfulEmptyTemplateDiscoveryBeforeDependentExecution() throws Exception {
        InterpretationPlanRuntime runtime = new InterpretationPlanRuntime(
            mock(ToolRuntimeService.class),
            new InterpretationPlanValidator(),
            mock(InterpretationPlanRuntime.DagExecutionController.class)
        );
        Method method = InterpretationPlanRuntime.class.getDeclaredMethod(
            "localToolResultReview",
            InterpretationPlan.Step.class,
            InterpretationPlanRuntime.StepExecution.class
        );
        method.setAccessible(true);
        InterpretationPlan.Step step = new InterpretationPlan.Step(
            2, "mcp_tool", "mcp_chatchat_mcp_server_database_ops_template_search",
            Map.of("filters", Map.of("assetName", "248测试数据库", "env", "DEV")),
            List.of(1), null, null
        );
        InterpretationPlanRuntime.StepExecution execution = new InterpretationPlanRuntime.StepExecution(
            2, "mcp_tool", step.toolName(), true,
            Map.of("success", true, "returnedCount", 0, "templates", List.of()),
            null, null, null, 5
        );

        InterpretationPlanRuntime.StepReview review =
            (InterpretationPlanRuntime.StepReview) method.invoke(runtime, step, execution);

        assertThat(review).isNotNull();
        assertThat(review.satisfied()).isFalse();
        assertThat(review.reason()).contains("NO_MATCHING_TEMPLATE");
        assertThat(review.metadata())
            .containsEntry("transportSuccess", true)
            .containsEntry("operationSuccess", true)
            .containsEntry("businessSatisfied", false)
            .containsEntry("resultCode", "NO_MATCHING_TEMPLATE")
            .containsEntry("templateDiscoveryReturnedCount", 0);
    }

    @Test
    void clauseLimitTemplateFailureRequestsModelKeywordRewriteBeforeExecution() throws Exception {
        InterpretationPlanRuntime runtime = new InterpretationPlanRuntime(
            mock(ToolRuntimeService.class),
            new InterpretationPlanValidator(),
            mock(InterpretationPlanRuntime.DagExecutionController.class)
        );
        Method method = InterpretationPlanRuntime.class.getDeclaredMethod(
            "localToolResultReview",
            InterpretationPlan.Step.class,
            InterpretationPlanRuntime.StepExecution.class
        );
        method.setAccessible(true);
        InterpretationPlan.Step step = new InterpretationPlan.Step(
            1, "mcp_tool", "mcp_chatchat_mcp_server_database_query_template_query",
            Map.of("filters", Map.of("intent", "融资融券数据观察")),
            List.of(), null, null
        );
        InterpretationPlanRuntime.StepExecution execution = new InterpretationPlanRuntime.StepExecution(
            1, "mcp_tool", step.toolName(), true,
            Map.of(
                "success", true,
                "status", "MODEL_REVIEW_REQUIRED",
                "resultCode", "QUERY_CLAUSE_LIMIT_EXCEEDED",
                "returnedCount", 0,
                "templates", List.of(),
                "retrievalReview", Map.of(
                    "nextAction", "REWRITE_TEMPLATE_SEARCH_KEYWORDS_AND_RETRY"
                )
            ),
            null, null, null, 5
        );

        InterpretationPlanRuntime.StepReview review =
            (InterpretationPlanRuntime.StepReview) method.invoke(runtime, step, execution);

        assertThat(review).isNotNull();
        assertThat(review.satisfied()).isFalse();
        assertThat(review.reason()).contains("QUERY_CLAUSE_LIMIT_EXCEEDED", "compact");
        assertThat(review.metadata())
            .containsEntry("resultCode", "QUERY_CLAUSE_LIMIT_EXCEEDED")
            .containsEntry("retryable", true)
            .containsEntry("nextAction", "REWRITE_TEMPLATE_SEARCH_KEYWORDS_AND_RETRY")
            .containsEntry("templateDiscoveryReturnedCount", 0);
    }

    @Test
    void factChecksUnifiedWebSearchWhenItContainsActualFinancialRows() throws Exception {
        InterpretationPlanRuntime runtime = new InterpretationPlanRuntime(
            mock(ToolRuntimeService.class),
            new InterpretationPlanValidator(),
            mock(InterpretationPlanRuntime.DagExecutionController.class)
        );
        Method method = InterpretationPlanRuntime.class.getDeclaredMethod(
            "localToolResultReview",
            InterpretationPlan.Step.class,
            InterpretationPlanRuntime.StepExecution.class
        );
        method.setAccessible(true);
        InterpretationPlan.Step step = new InterpretationPlan.Step(
            1, "mcp_tool", "mcp_chatchat_mcp_server_web_search",
            Map.of("query", "A-share market"), List.of(), null, null
        );
        InterpretationPlanRuntime.StepExecution execution = new InterpretationPlanRuntime.StepExecution(
            1, "mcp_tool", "mcp_chatchat_mcp_server_web_search", true,
            Map.of("structuredContent", Map.of(
                "structuredObservationCount", 12,
                "structuredData", List.of(Map.of("dataset", "observations", "count", 12)))),
            null, null, null, 5
        );

        InterpretationPlanRuntime.StepReview review =
            (InterpretationPlanRuntime.StepReview) method.invoke(runtime, step, execution);

        assertThat(review).isNotNull();
        assertThat(review.satisfied()).isTrue();
        assertThat(review.metadata())
            .containsEntry("localFactCheckHasEvidence", true)
            .containsEntry("localFactCheckEvidenceType", "structured_data_observations")
            .containsEntry("structuredObservationCount", 12);

        Method skipReviewMethod = InterpretationPlanRuntime.class.getDeclaredMethod(
            "shouldSkipModelReviewAfterLocalFactCheck",
            Map.class
        );
        skipReviewMethod.setAccessible(true);
        assertThat((boolean) skipReviewMethod.invoke(runtime, review.metadata())).isTrue();
    }

    @Test
    void modelReviewProjectsSelectedTemplatesBeforeDependencyBinding() {
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.hasTool("mcp_chatchat_mcp_server_ssh_template_query")).thenReturn(true);
        when(toolRegistry.getToolMetadata(any())).thenReturn(ToolMetadata.builder().riskLevel("low").build());
        ToolRuntimeService toolRuntimeService = mock(ToolRuntimeService.class);
        when(toolRuntimeService.execute(any())).thenReturn(new ToolRuntimeExecution(
            ToolOutput.success(Map.of(
                "schemaVersion", "template_query_result.v1",
                "success", true,
                "returnedCount", 2,
                "templates", List.of(
                    Map.of(
                        "templateId", "CHECK_PROCESS",
                        "parameterSchema", Map.of("type", "object"),
                        "invocationExample", Map.of("template", "CHECK_PROCESS")
                    ),
                    Map.of(
                        "templateId", "CHECK_SERVICE_STATUS",
                        "parameterSchema", Map.of("type", "object"),
                        "invocationExample", Map.of("template", "CHECK_SERVICE_STATUS")
                    )
                )
            )),
            ToolMetadata.builder().id("mcp_chatchat_mcp_server_ssh_template_query").build(),
            null,
            "success",
            Map.of()
        ));
        InterpretationPlan plan = new InterpretationPlan(
            "1.0",
            new InterpretationPlan.Intent("ops", "select ssh template", "low"),
            context(),
            new InterpretationPlan.Plan(List.of(
                new InterpretationPlan.Step(
                    1,
                    "mcp_tool",
                    "mcp_chatchat_mcp_server_ssh_template_query",
                    Map.of("filters", Map.of("intent", "分析MySQL服务器管理进程信息"), "limit", 10),
                    List.of(),
                    null,
                    null
                ),
                new InterpretationPlan.Step(2, "final_answer", "", Map.of("answer", "done"), List.of(1), null, null)
            )),
            new InterpretationPlan.ExecutionPolicy(3, false, List.of("mcp_chatchat_mcp_server_ssh_template_query"), List.of(), 30000),
            review()
        );
        AtomicInteger reviewerCalls = new AtomicInteger();
        InterpretationPlanRuntime runtime = new InterpretationPlanRuntime(
            toolRuntimeService,
            new InterpretationPlanValidator(),
            null,
            request -> {
                reviewerCalls.incrementAndGet();
                return InterpretationPlanRuntime.StepReview.accepted("candidate set semantically matches the request", Map.of(
                    "selectedTemplateIds", List.of("CHECK_PROCESS")
                ));
            },
            scriptedController(List.of(List.of(1), List.of(2)))
        );

        InterpretationPlanRuntime.ExecutionResult result = runtime.execute(new InterpretationPlanRuntime.ExecutionRequest(
            plan,
            toolRegistry,
            List.of("mcp_chatchat_mcp_server_ssh_template_query"),
            "tenant-1",
            "req-template-discovery-skip-review",
            "conv-template-discovery-skip-review",
            "user-1",
            Map.of()
        ));

        assertThat(result.success()).isTrue();
        assertThat(reviewerCalls).hasValue(1);
        assertThat(result.steps().get(0).metadata())
            .containsEntry("templateDiscoveryReturnedCount", 2)
            .containsEntry("toolResultReviewSatisfied", true)
            .containsEntry("runtimeTemplateSelectionApplied", true)
            .containsEntry("runtimeTemplateCandidateCount", 2)
            .containsEntry("runtimeTemplateSelectedCount", 1)
            .doesNotContainKey("toolResultReviewSkipped");
        Map<?, ?> projectedOutput = (Map<?, ?>) result.steps().get(0).output();
        assertThat(projectedOutput.get("returnedCount")).isEqualTo(1);
        List<?> projectedTemplates = (List<?>) projectedOutput.get("templates");
        assertThat(projectedTemplates).hasSize(1);
        assertThat(((Map<?, ?>) projectedTemplates.get(0)).get("templateId"))
            .isEqualTo("CHECK_PROCESS");
        assertThat(projectedOutput.get("runtimeTemplateSelection").toString())
            .contains("selectionAuthority=runtime_evidence_model_review", "candidateCount=2");
    }

    @Test
    void authoritativeWorkflowStillRequiresSemanticAssetReview() {
        String assetTool = "mcp_runtime_api_asset_query";
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.hasTool(assetTool)).thenReturn(true);
        when(toolRegistry.getToolMetadata(any())).thenReturn(
            ToolMetadata.builder().id(assetTool).riskLevel("low").build());
        ToolRuntimeService toolRuntimeService = mock(ToolRuntimeService.class);
        when(toolRuntimeService.execute(any())).thenReturn(new ToolRuntimeExecution(
            ToolOutput.success(Map.of(
                "schemaVersion", "asset_query_result.v1",
                "success", true,
                "returnedCount", 2,
                "assets", List.of(
                    Map.of("asset", Map.of("id", "asset-a", "name", "customer-a")),
                    Map.of("asset", Map.of("id", "asset-b", "name", "customer-b"))
                )
            )),
            ToolMetadata.builder().id(assetTool).build(),
            null,
            "success",
            Map.of()
        ));
        InterpretationPlan plan = new InterpretationPlan(
            "1.0",
            new InterpretationPlan.Intent("data_query", "run configured workflow", "low"),
            context(),
            new InterpretationPlan.Plan(List.of(
                new InterpretationPlan.Step(1, "mcp_tool", assetTool, Map.of(), List.of(), null, null),
                new InterpretationPlan.Step(2, "final_answer", "", Map.of("answer", "done"), List.of(1), null, null)
            )),
            new InterpretationPlan.ExecutionPolicy(2, false, List.of(assetTool), List.of(), 30000),
            review()
        );
        AtomicInteger reviewerCalls = new AtomicInteger();
        InterpretationPlanRuntime runtime = new InterpretationPlanRuntime(
            toolRuntimeService,
            new InterpretationPlanValidator(),
            null,
            request -> {
                reviewerCalls.incrementAndGet();
                return InterpretationPlanRuntime.StepReview.accepted("asset-b matches the requested target", Map.of(
                    "selectedAssetIds", List.of("asset-b"),
                    "assetEvaluations", List.of(
                        Map.of("asset_id", "asset-a", "decision", "reject", "relevance", 0.1),
                        Map.of("asset_id", "asset-b", "decision", "accept", "relevance", 1.0)
                    )
                ));
            },
            scriptedController(List.of(List.of(1), List.of(2)))
        );

        InterpretationPlanRuntime.ExecutionResult result = runtime.execute(
            new InterpretationPlanRuntime.ExecutionRequest(
                plan,
                toolRegistry,
                List.of(assetTool),
                "tenant-1",
                "req-authoritative-discovery",
                "conv-authoritative-discovery",
                "user-1",
                Map.of("authoritativeWorkflowDag", List.of(
                    Map.of("id", "asset", "tool", assetTool, "dependsOnTools", List.of())
                ))
            )
        );

        assertThat(result.success()).isTrue();
        assertThat(reviewerCalls).hasValue(1);
        assertThat(result.steps().get(0).metadata())
            .containsEntry("assetDiscoveryReturnedCount", 2)
            .containsEntry("toolResultReviewSatisfied", true)
            .containsEntry("runtimeAssetSelectionApplied", true)
            .containsEntry("runtimeAssetSelectedCount", 1)
            .doesNotContainKey("toolResultReviewSkipped");
    }

    @Test
    void blocksTemplateDiscoveryWhenModelReviewerIsUnavailable() {
        String toolName = "mcp_chatchat_mcp_server_ssh_template_query";
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.hasTool(toolName)).thenReturn(true);
        when(toolRegistry.getToolMetadata(any())).thenReturn(
            ToolMetadata.builder().id(toolName).riskLevel("low").build());
        ToolRuntimeService toolRuntimeService = mock(ToolRuntimeService.class);
        when(toolRuntimeService.execute(any())).thenReturn(new ToolRuntimeExecution(
            ToolOutput.success(Map.of(
                "schemaVersion", "tool_result_summary.v1",
                "summaryTruncated", true,
                "preview", Map.of(
                    "routingProjection", Map.of(
                        "schemaVersion", "template_query_result.v1",
                        "success", true,
                        "returnedCount", 2,
                        "templates", List.of(
                            Map.of("templateId", "check_cpu"),
                            Map.of("templateId", "check_docker_overview")
                        )
                    )
                )
            )),
            ToolMetadata.builder().id(toolName).build(), null, "success", Map.of()
        ));
        InterpretationPlan plan = new InterpretationPlan(
            "1.0",
            new InterpretationPlan.Intent("ops", "discover diagnostics", "low"),
            context(),
            new InterpretationPlan.Plan(List.of(
                new InterpretationPlan.Step(1, "mcp_tool", toolName,
                    Map.of("filters", Map.of("intent", "cpu docker")), List.of(), null, null),
                new InterpretationPlan.Step(2, "final_answer", "",
                    Map.of("answer", "done"), List.of(1), null, null)
            )),
            new InterpretationPlan.ExecutionPolicy(2, false, List.of(toolName), List.of(), 30_000),
            review()
        );
        InterpretationPlanRuntime runtime = new InterpretationPlanRuntime(
            toolRuntimeService,
            new InterpretationPlanValidator(),
            null,
            request -> InterpretationPlanRuntime.StepReview.rejected(
                "Tool result reviewer was unavailable",
                Map.of("toolResultReviewUnavailable", true)
            ),
            scriptedController(List.of(List.of(1), List.of(2)))
        );

        InterpretationPlanRuntime.ExecutionResult result = runtime.execute(
            new InterpretationPlanRuntime.ExecutionRequest(
                plan, toolRegistry, List.of(toolName), "tenant", "request-review-unavailable",
                "conversation", "user", Map.of()
            )
        );

        assertThat(result.success()).isFalse();
        assertThat(result.steps().get(0).metadata())
            .containsEntry("localFactCheckHasEvidence", true)
            .containsEntry("toolResultReviewUnavailable", true)
            .containsEntry("semanticCandidateReviewSatisfied", false);
        assertThat(result.errorMessage()).contains("model did not select a template id");
        assertThat(result.steps().get(0).output().toString())
            .contains("check_cpu", "check_docker_overview");
    }

    @Test
    void dependentExecutionBindsRuntimeSelectedTemplateInsteadOfMcpFirstCandidate() {
        String discoveryTool = "mcp_chatchat_mcp_server_database_query_template_query";
        String executionTool = "mcp_chatchat_mcp_server_sql_query_execute";
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.hasTool(any())).thenReturn(true);
        when(toolRegistry.getToolMetadata(any())).thenAnswer(invocation ->
            ToolMetadata.builder().id(invocation.getArgument(0)).riskLevel("low").build());
        java.util.concurrent.atomic.AtomicReference<Map<String, Object>> executionInput =
            new java.util.concurrent.atomic.AtomicReference<>();
        ToolRuntimeService toolRuntimeService = mock(ToolRuntimeService.class);
        when(toolRuntimeService.execute(any())).thenAnswer(invocation -> {
            ToolRuntimeRequest request = invocation.getArgument(0);
            Object data;
            if (discoveryTool.equals(request.getToolName())) {
                data = Map.of(
                    "schemaVersion", "template_query_result.v1",
                    "returnedCount", 2,
                    "templates", List.of(
                        Map.of(
                            "templateId", "UNRELATED_FIRST",
                            "mcpToolName", executionTool,
                            "parameterSchema", Map.of("type", "object")
                        ),
                        Map.of(
                            "templateId", "MARGIN_BALANCE_SELECTED",
                            "mcpToolName", executionTool,
                            "parameterSchema", Map.of("type", "object")
                        )
                    )
                );
            } else {
                executionInput.set(request.getToolInput().getParameters());
                data = Map.of("rows", List.of(Map.of("marginBalance", 100)));
            }
            return new ToolRuntimeExecution(
                ToolOutput.success(data),
                ToolMetadata.builder().id(request.getToolName()).riskLevel("low").build(),
                null,
                "success",
                Map.of()
            );
        });
        InterpretationPlan plan = new InterpretationPlan(
            "1.0",
            new InterpretationPlan.Intent("data_query", "融资融券余额", "low"),
            context(),
            new InterpretationPlan.Plan(
                List.of(
                    new InterpretationPlan.Step(
                        1, "mcp_tool", discoveryTool,
                        Map.of("filters", Map.of("intent", "融资融券余额")),
                        List.of(), null, null
                    ),
                    new InterpretationPlan.Step(
                        2, "mcp_tool", executionTool,
                        Map.of("parameters", Map.of()),
                        List.of(1), null, null
                    ),
                    new InterpretationPlan.Step(
                        3, "final_answer", "", Map.of("answer", "done"), List.of(2), null, null
                    )
                ),
                List.of(),
                List.of(new InterpretationPlan.Binding(
                    1, "$.templates[0].templateId", 2, "templateId", "jsonpath", true
                )),
                null
            ),
            new InterpretationPlan.ExecutionPolicy(
                4, false, List.of(discoveryTool, executionTool), List.of(), 30_000
            ),
            review()
        );
        InterpretationPlanRuntime runtime = new InterpretationPlanRuntime(
            toolRuntimeService,
            new InterpretationPlanValidator(),
            null,
            request -> discoveryTool.equals(request.execution().toolName())
                ? InterpretationPlanRuntime.StepReview.accepted(
                    "margin balance template is the semantic match",
                    Map.of(
                        "selectedTemplateIds", List.of("MARGIN_BALANCE_SELECTED"),
                        "rejectedTemplateIds", List.of("UNRELATED_FIRST")
                    )
                )
                : InterpretationPlanRuntime.StepReview.accepted("execution evidence accepted", Map.of()),
            scriptedController(List.of(List.of(1), List.of(2), List.of(3)))
        );

        InterpretationPlanRuntime.ExecutionResult result = runtime.execute(
            new InterpretationPlanRuntime.ExecutionRequest(
                plan,
                toolRegistry,
                List.of(discoveryTool, executionTool),
                "tenant-1",
                "req-runtime-template-selection",
                "conv-runtime-template-selection",
                "user-1",
                Map.of()
            )
        );

        assertThat(result.success()).isTrue();
        assertThat(executionInput.get())
            .containsEntry("templateId", "MARGIN_BALANCE_SELECTED")
            .containsEntry("template", "MARGIN_BALANCE_SELECTED");
        assertThat(executionInput.get().toString()).doesNotContain("UNRELATED_FIRST");
    }

    @Test
    void rejectedTemplateExecutionPublishesOneRepairContractWithMissingParameters() {
        String executionTool = "mcp_chatchat_mcp_server_api_template_execute";
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.hasTool(executionTool)).thenReturn(true);
        when(toolRegistry.getToolMetadata(executionTool)).thenReturn(
            ToolMetadata.builder().id(executionTool).riskLevel("low").build());
        ToolRuntimeService toolRuntimeService = mock(ToolRuntimeService.class);
        when(toolRuntimeService.execute(any())).thenReturn(new ToolRuntimeExecution(
            ToolOutput.success(Map.of("code", "MISSING_ARGUMENT", "message", "customerId is required")),
            ToolMetadata.builder().id(executionTool).riskLevel("low").build(),
            null,
            "success",
            Map.of()
        ));
        InterpretationPlan plan = new InterpretationPlan(
            "1.0",
            new InterpretationPlan.Intent("api_query", "query customer", "low"),
            context(),
            new InterpretationPlan.Plan(List.of(
                new InterpretationPlan.Step(
                    1,
                    "mcp_tool",
                    executionTool,
                    Map.of("templateId", "CUSTOMER_QUERY", "parameters", Map.of()),
                    List.of(),
                    null,
                    null
                ),
                new InterpretationPlan.Step(
                    2, "final_answer", "", Map.of("answer", "done"), List.of(1), null, null
                )
            )),
            new InterpretationPlan.ExecutionPolicy(
                3, false, List.of(executionTool), List.of(), 30_000
            ),
            review()
        );
        InterpretationPlanRuntime runtime = new InterpretationPlanRuntime(
            toolRuntimeService,
            new InterpretationPlanValidator(),
            null,
            request -> InterpretationPlanRuntime.StepReview.rejected(
                "customerId is required before this template can satisfy the request",
                Map.of(
                    "missingParameters", List.of("customerId"),
                    "retryInputChanges", Map.of(),
                    "templateReselectionRequired", true,
                    "templateExecutionSatisfied", false
                )
            ),
            scriptedController(List.of(List.of(1)))
        );

        InterpretationPlanRuntime.ExecutionResult result = runtime.execute(
            new InterpretationPlanRuntime.ExecutionRequest(
                plan,
                toolRegistry,
                List.of(executionTool),
                "tenant-1",
                "req-template-execution-review",
                "conv-template-execution-review",
                "user-1",
                Map.of("toolResultReviewMaxAttempts", 1)
            )
        );

        assertThat(result.success()).isFalse();
        Map<String, Object> metadata = result.steps().get(0).metadata();
        assertThat(metadata)
            .containsEntry("templateExecutionRetryRequested", true)
            .containsEntry("templateExecutionRetryLimit", 1)
            .containsEntry("templateExecutionMissingParameters", List.of("customerId"))
            .containsEntry("templateReselectionRequired", true);
        assertThat(metadata.get("templateExecutionReview").toString())
            .contains("template_execution_satisfaction.v1", "ONE_REPAIRED_PLAN_EXECUTION",
                "unchangedRetryForbidden=true");
    }

    @Test
    void factChecksSqlMetadataSearchColumns() throws Exception {
        InterpretationPlanRuntime runtime = new InterpretationPlanRuntime(
            mock(ToolRuntimeService.class),
            new InterpretationPlanValidator(),
            mock(InterpretationPlanRuntime.DagExecutionController.class)
        );
        Method method = InterpretationPlanRuntime.class.getDeclaredMethod(
            "localToolResultReview",
            InterpretationPlan.Step.class,
            InterpretationPlanRuntime.StepExecution.class
        );
        method.setAccessible(true);
        InterpretationPlan.Step step = new InterpretationPlan.Step(
            2,
            "mcp_tool",
            "mcp_chatchat_mcp_server_sql_metadata_search",
            Map.of("query", "livebos.os_historystep", "includeColumns", true),
            List.of(1),
            null,
            null
        );
        InterpretationPlanRuntime.StepExecution execution = new InterpretationPlanRuntime.StepExecution(
            2,
            "mcp_tool",
            "mcp_chatchat_mcp_server_sql_metadata_search",
            true,
            Map.of(
                "schemaVersion", "sql_metadata_search_result.v1",
                "success", true,
                "results", List.of(Map.of(
                    "location", Map.of("schema", "livebos", "table", "os_historystep"),
                    "columns", List.of(
                        Map.of("name", "ID", "dataType", "bigint", "columnType", "bigint(20)", "columnKey", "PRI"),
                        Map.of("name", "ENTRY_ID", "dataType", "varchar", "columnType", "varchar(64)", "columnKey", "MUL")
                    )
                ))
            ),
            null,
            null,
            null,
            6
        );

        InterpretationPlanRuntime.StepReview review =
            (InterpretationPlanRuntime.StepReview) method.invoke(runtime, step, execution);

        assertThat(review).isNotNull();
        assertThat(review.satisfied()).isTrue();
        assertThat(review.metadata())
            .containsEntry("localFactCheckHasEvidence", true)
            .containsEntry("localFactCheckEvidenceType", "sql_metadata_search_columns")
            .containsEntry("sqlMetadataFactChecked", true)
            .containsEntry("sqlMetadataColumnCount", 2)
            .containsEntry("sqlMetadataStepId", 2);

        Method skipReviewMethod = InterpretationPlanRuntime.class.getDeclaredMethod(
            "shouldSkipModelReviewAfterLocalFactCheck",
            Map.class
        );
        skipReviewMethod.setAccessible(true);
        assertThat((boolean) skipReviewMethod.invoke(runtime, review.metadata())).isTrue();
    }

    @Test
    @SuppressWarnings("unchecked")
    void retriesOriginalRetrievalInputOnceWhenEnhancedResultIsBelowGate() {
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.hasTool("document_search")).thenReturn(true);
        when(toolRegistry.getToolMetadata("document_search")).thenReturn(
            ToolMetadata.builder().id("document_search").riskLevel("low").build()
        );
        ToolRuntimeService toolRuntimeService = mock(ToolRuntimeService.class);
        AtomicInteger calls = new AtomicInteger();
        when(toolRuntimeService.execute(any())).thenAnswer(invocation -> {
            ToolRuntimeRequest toolRequest = invocation.getArgument(0);
            boolean original = "internal".equals(
                toolRequest.getToolInput().getParameters().get("query")
            );
            calls.incrementAndGet();
            return new ToolRuntimeExecution(
                ToolOutput.success(Map.of("results", original
                    ? List.of(Map.of("id", "doc-1"))
                    : List.of())),
                ToolMetadata.builder().id("document_search").build(),
                null,
                "success",
                Map.of()
            );
        });
        InterpretationPlanRuntime runtime = new InterpretationPlanRuntime(
            toolRuntimeService,
            new InterpretationPlanValidator(),
            null,
            null,
            scriptedController(List.of(List.of(1), List.of(2))),
            request -> {
                Map<String, Object> enriched = new java.util.LinkedHashMap<>(request.input());
                enriched.put("query", "internal policy expanded");
                enriched.put("__modelRetrievalQualityGate", Map.of(
                    "enabled", true,
                    "minimumResultCount", 1,
                    "countPaths", List.of("results"),
                    "changedPaths", List.of("query"),
                    "originalValues", Map.of("query", "internal"),
                    "originallyAbsentPaths", List.of()
                ));
                return enriched;
            }
        );

        InterpretationPlanRuntime.ExecutionResult result = runtime.execute(
            new InterpretationPlanRuntime.ExecutionRequest(
                serialPlan(),
                toolRegistry,
                List.of("document_search"),
                "tenant-1",
                "req-quality-gate",
                "conv-quality-gate",
                "user-1",
                Map.of()
            )
        );

        assertThat(result.success()).isTrue();
        assertThat(calls).hasValue(2);
        assertThat(result.steps().get(0).metadata().get("resolvedInput"))
            .isEqualTo(Map.of("query", "internal"));
        assertThat((Map<String, Object>) result.steps().get(0).metadata()
            .get("retrievalQualityGate"))
            .containsEntry("fallbackExecuted", true)
            .containsEntry("selected", "original");
    }

    @Test
    void recognizesEquivalentTemplateRetrievalScopeAndSkipsDuplicateFallback() throws Exception {
        InterpretationPlanRuntime runtime = new InterpretationPlanRuntime(
            mock(ToolRuntimeService.class),
            new InterpretationPlanValidator(),
            mock(InterpretationPlanRuntime.DagExecutionController.class)
        );
        Method method = InterpretationPlanRuntime.class.getDeclaredMethod(
            "equivalentTemplateRetrievalRequest",
            String.class,
            Map.class,
            Map.class
        );
        method.setAccessible(true);
        Map<String, Object> filters = Map.of(
            "intent", "融资融券余额",
            "category", "market_data",
            "env", "DEV"
        );
        Map<String, Object> enhanced = new java.util.LinkedHashMap<>(Map.of(
            "filters", filters,
            "limit", 10,
            "intentEn", "margin trading balance"
        ));
        Map<String, Object> original = new java.util.LinkedHashMap<>(Map.of(
            "filters", filters,
            "limit", 10
        ));

        assertThat(method.invoke(
            runtime,
            "mcp_chatchat_mcp_server_database_query_template_query",
            enhanced,
            original
        )).isEqualTo(true);
        assertThat(method.invoke(runtime, "document_search", enhanced, original)).isEqualTo(false);
    }

    @Test
    void factChecksCompleteEnterpriseMetadataFieldCoverage() throws Exception {
        InterpretationPlanRuntime runtime = new InterpretationPlanRuntime(
            mock(ToolRuntimeService.class),
            new InterpretationPlanValidator(),
            mock(InterpretationPlanRuntime.DagExecutionController.class)
        );
        Method method = InterpretationPlanRuntime.class.getDeclaredMethod(
            "localToolResultReview",
            InterpretationPlan.Step.class,
            InterpretationPlanRuntime.StepExecution.class
        );
        method.setAccessible(true);
        InterpretationPlan.Step step = new InterpretationPlan.Step(
            3,
            "mcp_tool",
            "mcp_chatchat_mcp_server_enterprise_metadata_search",
            Map.of("tableName", "ads_ids_clr_acc_liab_d_i"),
            List.of(2),
            null,
            null
        );
        InterpretationPlanRuntime.StepExecution execution = new InterpretationPlanRuntime.StepExecution(
            3,
            "mcp_tool",
            "mcp_chatchat_mcp_server_enterprise_metadata_search",
            true,
            Map.of(
                "schemaVersion", "enterprise_metadata_field_discovery.v1",
                "sourceFieldCount", 12,
                "matchedFieldCount", 12,
                "coverage", Map.of(
                    "inputFieldCount", 12,
                    "processedFieldCount", 12,
                    "allFieldsProcessed", true
                )
            ),
            null,
            null,
            null,
            8
        );

        InterpretationPlanRuntime.StepReview review =
            (InterpretationPlanRuntime.StepReview) method.invoke(runtime, step, execution);

        assertThat(review).isNotNull();
        assertThat(review.satisfied()).isTrue();
        assertThat(review.metadata())
            .containsEntry("localFactCheckEvidenceType", "enterprise_metadata_fields")
            .containsEntry("enterpriseMetadataSourceFieldCount", 12)
            .containsEntry("enterpriseMetadataProcessedFieldCount", 12)
            .containsEntry("enterpriseMetadataAllFieldsProcessed", true);
    }

    @Test
    void factChecksAssetDiscoveryWhenMcpResultIsTextEnvelope() throws Exception {
        String assetQueryResult = """
            {
              "success": true,
              "returnedCount": 1,
              "assets": [
                {
                  "asset": {
                    "name": "test_db_248",
                    "environment": "DEV"
                  }
                }
              ]
            }
            """;
        InterpretationPlanRuntime runtime = new InterpretationPlanRuntime(
            mock(ToolRuntimeService.class),
            new InterpretationPlanValidator(),
            mock(InterpretationPlanRuntime.DagExecutionController.class)
        );
        Method method = InterpretationPlanRuntime.class.getDeclaredMethod(
            "localToolResultReview",
            InterpretationPlan.Step.class,
            InterpretationPlanRuntime.StepExecution.class
        );
        method.setAccessible(true);
        InterpretationPlan.Step step = new InterpretationPlan.Step(
            1,
            "mcp_tool",
            "mcp_chatchat_mcp_server_sql_datasource_asset_query",
            Map.of("filters", Map.of("assetName", "test_db_248"), "limit", 5),
            List.of(),
            null,
            null
        );
        InterpretationPlanRuntime.StepExecution execution = new InterpretationPlanRuntime.StepExecution(
            1,
            "mcp_tool",
            "mcp_chatchat_mcp_server_sql_datasource_asset_query",
            true,
            Map.of("content", List.of(Map.of("type", "text", "text", assetQueryResult))),
            null,
            null,
            null,
            5
        );

        InterpretationPlanRuntime.StepReview review =
            (InterpretationPlanRuntime.StepReview) method.invoke(runtime, step, execution);

        assertThat(review).isNotNull();
        assertThat(review.satisfied()).isTrue();
        assertThat(review.metadata())
            .containsEntry("localFactCheckHasEvidence", true)
            .containsEntry("localFactCheckEvidenceType", "asset_discovery")
            .containsEntry("assetDiscoveryReturnedCount", 1);
    }

    @Test
    void factChecksAssetDiscoveryWhenAssetsListExistsDespiteOuterZeroCount() throws Exception {
        InterpretationPlanRuntime runtime = new InterpretationPlanRuntime(
            mock(ToolRuntimeService.class),
            new InterpretationPlanValidator(),
            mock(InterpretationPlanRuntime.DagExecutionController.class)
        );
        Method method = InterpretationPlanRuntime.class.getDeclaredMethod(
            "localToolResultReview",
            InterpretationPlan.Step.class,
            InterpretationPlanRuntime.StepExecution.class
        );
        method.setAccessible(true);
        InterpretationPlan.Step step = new InterpretationPlan.Step(
            1,
            "mcp_tool",
            "mcp_chatchat_mcp_server_sql_datasource_asset_query",
            Map.of("filters", Map.of("assetName", "test_db_248"), "limit", 5),
            List.of(),
            null,
            null
        );
        InterpretationPlanRuntime.StepExecution execution = new InterpretationPlanRuntime.StepExecution(
            1,
            "mcp_tool",
            "mcp_chatchat_mcp_server_sql_datasource_asset_query",
            true,
            Map.of(
                "returnedCount", 0,
                "assets", List.of(Map.of("asset", Map.of("name", "test_db_248", "environment", "DEV")))
            ),
            null,
            null,
            null,
            5
        );

        InterpretationPlanRuntime.StepReview review =
            (InterpretationPlanRuntime.StepReview) method.invoke(runtime, step, execution);

        assertThat(review).isNotNull();
        assertThat(review.satisfied()).isTrue();
        assertThat(review.metadata())
            .containsEntry("localFactCheckHasEvidence", true)
            .containsEntry("localFactCheckEvidenceType", "asset_discovery")
            .containsEntry("assetDiscoveryReturnedCount", 1);
    }

    @Test
    void doesNotFactCheckAssetDiscoveryWhenResultContainsNoAssetEvidence() throws Exception {
        InterpretationPlanRuntime runtime = new InterpretationPlanRuntime(
            mock(ToolRuntimeService.class),
            new InterpretationPlanValidator(),
            mock(InterpretationPlanRuntime.DagExecutionController.class)
        );
        Method method = InterpretationPlanRuntime.class.getDeclaredMethod(
            "localToolResultReview",
            InterpretationPlan.Step.class,
            InterpretationPlanRuntime.StepExecution.class
        );
        method.setAccessible(true);
        InterpretationPlan.Step step = new InterpretationPlan.Step(
            1,
            "mcp_tool",
            "mcp_chatchat_mcp_server_sql_datasource_asset_query",
            Map.of("filters", Map.of("assetName", "test_db_248"), "limit", 5),
            List.of(),
            null,
            null
        );
        InterpretationPlanRuntime.StepExecution execution = new InterpretationPlanRuntime.StepExecution(
            1,
            "mcp_tool",
            "mcp_chatchat_mcp_server_sql_datasource_asset_query",
            true,
            Map.of(
                "returnedCount", 0,
                "assets", List.of(),
                "emptyResultAdvice", Map.of("reason", "no match")
            ),
            null,
            null,
            null,
            5
        );

        InterpretationPlanRuntime.StepReview review =
            (InterpretationPlanRuntime.StepReview) method.invoke(runtime, step, execution);

        assertThat(review).isNull();
    }

    @Test
    void doesNotFactCheckAssetDiscoveryForArbitrarySelectedMap() throws Exception {
        InterpretationPlanRuntime runtime = new InterpretationPlanRuntime(
            mock(ToolRuntimeService.class),
            new InterpretationPlanValidator(),
            mock(InterpretationPlanRuntime.DagExecutionController.class)
        );
        Method method = InterpretationPlanRuntime.class.getDeclaredMethod(
            "localToolResultReview",
            InterpretationPlan.Step.class,
            InterpretationPlanRuntime.StepExecution.class
        );
        method.setAccessible(true);
        InterpretationPlan.Step step = new InterpretationPlan.Step(
            1,
            "mcp_tool",
            "mcp_chatchat_mcp_server_sql_datasource_asset_query",
            Map.of("filters", Map.of("assetName", "test_db_248"), "limit", 5),
            List.of(),
            null,
            null
        );
        InterpretationPlanRuntime.StepExecution execution = new InterpretationPlanRuntime.StepExecution(
            1,
            "mcp_tool",
            "mcp_chatchat_mcp_server_sql_datasource_asset_query",
            true,
            Map.of(
                "returnedCount", 0,
                "assets", List.of(),
                "selected", Map.of("reason", "review required")
            ),
            null,
            null,
            null,
            5
        );

        InterpretationPlanRuntime.StepReview review =
            (InterpretationPlanRuntime.StepReview) method.invoke(runtime, step, execution);

        assertThat(review).isNull();
    }

    @Test
    void factChecksSqlColumnMetadataAsValidStructureEvidence() throws Exception {
        InterpretationPlanRuntime runtime = new InterpretationPlanRuntime(
            mock(ToolRuntimeService.class),
            new InterpretationPlanValidator(),
            mock(InterpretationPlanRuntime.DagExecutionController.class)
        );
        Method method = InterpretationPlanRuntime.class.getDeclaredMethod(
            "localToolResultReview",
            InterpretationPlan.Step.class,
            InterpretationPlanRuntime.StepExecution.class
        );
        method.setAccessible(true);
        InterpretationPlan.Step step = new InterpretationPlan.Step(
            3,
            "mcp_tool",
            "mcp_chatchat_mcp_server_sql_query_execute",
            Map.of("templateId", "MYSQL_TABLE_METADATA"),
            List.of(2),
            null,
            null
        );
        Map<String, Object> output = Map.of(
            "kind", "sql_query",
            "data", Map.of(
                "columns", List.of("COLUMN_NAME", "COLUMN_TYPE", "IS_NULLABLE", "COLUMN_COMMENT"),
                "rowCount", 2,
                "rows", List.of(
                    Map.of("COLUMN_NAME", "DICT_ENTR_CODE", "COLUMN_TYPE", "varchar(8)", "IS_NULLABLE", "YES", "COLUMN_COMMENT", "瀛楀吀鏉＄洰浠ｇ爜"),
                    Map.of("COLUMN_NAME", "SSYS_CODE", "COLUMN_TYPE", "varchar(8)", "IS_NULLABLE", "YES", "COLUMN_COMMENT", "鏉ユ簮绯荤粺浠ｇ爜")
                )
            )
        );
        InterpretationPlanRuntime.StepExecution execution = new InterpretationPlanRuntime.StepExecution(
            3,
            "mcp_tool",
            "mcp_chatchat_mcp_server_sql_query_execute",
            true,
            output,
            null,
            null,
            null,
            173
        );

        InterpretationPlanRuntime.StepReview review =
            (InterpretationPlanRuntime.StepReview) method.invoke(runtime, step, execution);

        assertThat(review).isNotNull();
        assertThat(review.satisfied()).isTrue();
        assertThat(review.metadata())
            .containsEntry("localFactCheckHasEvidence", true)
            .containsEntry("localFactCheckEvidenceType", "sql_column_metadata")
            .containsEntry("sqlMetadataFactChecked", true)
            .containsEntry("sqlMetadataColumnCount", 2);
    }

    @Test
    @SuppressWarnings("unchecked")
    void rejectsTableScopedGlobalTemplateWhenTemplateDiscoveryDidNotReturnTableMetadataTemplate() throws Exception {
        InterpretationPlanRuntime runtime = new InterpretationPlanRuntime(
            mock(ToolRuntimeService.class),
            new InterpretationPlanValidator(),
            mock(InterpretationPlanRuntime.DagExecutionController.class)
        );
        InterpretationPlan.Step step = new InterpretationPlan.Step(
            3,
            "mcp_tool",
            "mcp_chatchat_mcp_server_sql_query_execute",
            Map.of(
                "templateId", "MYSQL_SHOW_STATUS",
                "parameters", Map.of("table_name", "t_ad_dict_entr_supn"),
                "parameterProtocol", userQueryParameterProtocol(
                    3, "MYSQL_SHOW_STATUS", "t_ad_dict_entr_supn",
                    Map.of("table_name", "t_ad_dict_entr_supn")),
                "executionContext", Map.of("assetName", "test_db_248", "env", "DEV")
            ),
            List.of(2),
            null,
            null
        );
        InterpretationPlan plan = new InterpretationPlan(
            "1.0",
            new InterpretationPlan.Intent("sql_metadata", "Analyze table t_ad_dict_entr_supn", "low"),
            context(),
            new InterpretationPlan.Plan(List.of(
                new InterpretationPlan.Step(1, "mcp_tool", "mcp_chatchat_mcp_server_sql_datasource_asset_query",
                    Map.of("filters", Map.of("assetName", "test_db_248"), "limit", 10), List.of(), null, null),
                new InterpretationPlan.Step(2, "mcp_tool", "mcp_chatchat_mcp_server_sql_datasource_template_query",
                    Map.of("filters", Map.of("intent", "table metadata"), "limit", 10), List.of(1), null, null),
                step,
                new InterpretationPlan.Step(4, "final_answer", "", Map.of("answer", "done"), List.of(3), null, null)
            )),
            new InterpretationPlan.ExecutionPolicy(
                4,
                false,
                List.of(
                    "mcp_chatchat_mcp_server_sql_datasource_asset_query",
                    "mcp_chatchat_mcp_server_sql_datasource_template_query",
                    "mcp_chatchat_mcp_server_sql_query_execute"
                ),
                List.of(),
                30000
            ),
            review()
        );
        InterpretationPlanRuntime.ExecutionRequest request = new InterpretationPlanRuntime.ExecutionRequest(
            plan,
            mock(ToolRegistry.class),
            List.of("mcp_chatchat_mcp_server_sql_query_execute"),
            "tenant-1",
            "req-sql-template-repair",
            "conv-sql-template-repair",
            "user-1",
            Map.of(
                "executionTraceId", "trace-sql-template-repair",
                "originalUserQuery", "Analyze table t_ad_dict_entr_supn")
        );
        Method method = InterpretationPlanRuntime.class.getDeclaredMethod(
            "resolvedStepInput",
            InterpretationPlan.Step.class,
            InterpretationPlanRuntime.ExecutionRequest.class,
            Map.class
        );
        method.setAccessible(true);

        assertThatThrownBy(() -> method.invoke(runtime, step, request, Map.of()))
            .hasCauseInstanceOf(IllegalStateException.class)
            .hasRootCauseMessage("SQL_TEMPLATE_TARGET_SCOPE_MISMATCH: template MYSQL_SHOW_STATUS is not table-scoped but tableName=t_ad_dict_entr_supn was provided; planner must select a dialect-specific *_TABLE_METADATA template.");
    }

    @Test
    @SuppressWarnings("unchecked")
    void rejectsOracleInstanceTemplateWhenTemplateDiscoveryDidNotReturnTableMetadataTemplate() throws Exception {
        InterpretationPlanRuntime runtime = new InterpretationPlanRuntime(
            mock(ToolRuntimeService.class),
            new InterpretationPlanValidator(),
            mock(InterpretationPlanRuntime.DagExecutionController.class)
        );
        InterpretationPlan.Step step = new InterpretationPlan.Step(
            3,
            "mcp_tool",
            "mcp_chatchat_mcp_server_sql_query_execute",
            Map.of(
                "templateId", "ORACLE_INSTANCE_STATUS",
                "parameters", Map.of("tableName", "T_AD_DICT_ENTR_SUPN"),
                "parameterProtocol", userQueryParameterProtocol(
                    3, "ORACLE_INSTANCE_STATUS", "T_AD_DICT_ENTR_SUPN",
                    Map.of("tableName", "T_AD_DICT_ENTR_SUPN")),
                "executionContext", Map.of("databaseType", "oracle")
            ),
            List.of(2),
            null,
            null
        );
        Method method = InterpretationPlanRuntime.class.getDeclaredMethod(
            "resolvedStepInput",
            InterpretationPlan.Step.class,
            InterpretationPlanRuntime.ExecutionRequest.class,
            Map.class
        );
        method.setAccessible(true);

        assertThatThrownBy(() -> method.invoke(
            runtime,
            step,
            new InterpretationPlanRuntime.ExecutionRequest(
                minimalPlan(step),
                mock(ToolRegistry.class),
                List.of("mcp_chatchat_mcp_server_sql_query_execute"),
                "tenant-1",
                "req-oracle-template-repair",
                "conv-oracle-template-repair",
                "user-1",
                Map.of("originalUserQuery", "Analyze table T_AD_DICT_ENTR_SUPN")
            ),
            Map.of()
        ))
            .hasCauseInstanceOf(IllegalStateException.class)
            .hasRootCauseMessage("SQL_TEMPLATE_TARGET_SCOPE_MISMATCH: template ORACLE_INSTANCE_STATUS is not table-scoped but tableName=T_AD_DICT_ENTR_SUPN was provided; planner must select a dialect-specific *_TABLE_METADATA template.");
    }

    @Test
    @SuppressWarnings("unchecked")
    void hydratesTableMetadataSchemaFromSqlMetadataSearchResults() throws Exception {
        InterpretationPlanRuntime runtime = new InterpretationPlanRuntime(
            mock(ToolRuntimeService.class),
            new InterpretationPlanValidator(),
            mock(InterpretationPlanRuntime.DagExecutionController.class)
        );
        InterpretationPlan.Step step = new InterpretationPlan.Step(
            3,
            "mcp_tool",
            "mcp_chatchat_mcp_server_sql_query_execute",
            Map.of(
                "templateId", "MYSQL_TABLE_METADATA",
                "parameters", Map.of("tableName", "lbappdeploydetail"),
                "parameterProtocol", userQueryParameterProtocol(
                    3, "MYSQL_TABLE_METADATA", "lbappdeploydetail",
                    Map.of("tableName", "lbappdeploydetail")),
                "executionContext", Map.of("assetName", "test_db_248", "env", "DEV")
            ),
            List.of(1),
            null,
            null
        );
        Map<Integer, InterpretationPlanRuntime.StepExecution> completed = Map.of(
            1,
            new InterpretationPlanRuntime.StepExecution(
                1,
                "mcp_tool",
                "mcp_chatchat_mcp_server_sql_metadata_search",
                true,
                Map.of(
                    "results", List.of(Map.of(
                        "location", Map.of(
                            "database", "rdsm_ad",
                            "schema", "rdsm_ad",
                            "table", "lbappdeploydetail"
                        ),
                        "sqlExecutionBinding", Map.of(
                            "parameters", Map.of(
                                "databaseName", "rdsm_ad",
                                "schemaName", "rdsm_ad",
                                "tableName", "lbappdeploydetail"
                            )
                        ),
                        "score", 0.95
                    ))
                ),
                null,
                null,
                null,
                10
            )
        );
        Method method = InterpretationPlanRuntime.class.getDeclaredMethod(
            "resolvedStepInput",
            InterpretationPlan.Step.class,
            InterpretationPlanRuntime.ExecutionRequest.class,
            Map.class
        );
        method.setAccessible(true);

        Map<String, Object> resolved = (Map<String, Object>) method.invoke(
            runtime,
            step,
            new InterpretationPlanRuntime.ExecutionRequest(
                minimalPlan(step),
                mock(ToolRegistry.class),
                List.of("mcp_chatchat_mcp_server_sql_query_execute"),
                "tenant-1",
                "req-metadata-search-table-location",
                "conv-metadata-search-table-location",
                "user-1",
                Map.of("originalUserQuery", "Analyze lbappdeploydetail")
            ),
            completed
        );

        Map<String, Object> parameters = (Map<String, Object>) resolved.get("parameters");
        assertThat(parameters)
            .containsEntry("tableName", "lbappdeploydetail")
            .containsEntry("schemaName", "rdsm_ad")
            .containsEntry("databaseName", "rdsm_ad")
            .containsEntry("schema", "rdsm_ad")
            .containsEntry("database", "rdsm_ad");
        assertThat(resolved.get("runtimeTableResolution").toString())
            .contains("sql_metadata_search.results", "rdsm_ad", "lbappdeploydetail");
    }

    @Test
    @SuppressWarnings("unchecked")
    void transportsDeclaredDependencyEvidenceWithoutInspectingToolSpecificOutput() throws Exception {
        String toolName = "mcp_test_capability";
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.getToolMetadata(toolName)).thenReturn(ToolMetadata.builder()
            .id(toolName)
            .metadata(Map.of(
                "inputSchema", Map.of(
                    "type", "object",
                    "properties", Map.of(
                        "query", Map.of("type", "string"),
                        "sourceEvidence", Map.of(
                            "type", "array",
                            "items", Map.of("type", "object", "additionalProperties", true)
                        )
                    ),
                    "additionalProperties", false
                ),
                "mcpToolMeta", Map.of(
                    "inputAdapterContract", Map.of(
                        "contractVersion", "runtime_dependency_evidence.v1",
                        "dependencyEvidenceParameter", "sourceEvidence"
                    )
                )
            ))
            .build());
        InterpretationPlanRuntime runtime = new InterpretationPlanRuntime(
            mock(ToolRuntimeService.class),
            new InterpretationPlanValidator(),
            mock(InterpretationPlanRuntime.DagExecutionController.class)
        );
        InterpretationPlan.Step step = new InterpretationPlan.Step(
            2,
            "mcp_tool",
            toolName,
            Map.of("query", "review prior evidence"),
            List.of(1),
            null,
            null
        );
        Map<Integer, InterpretationPlanRuntime.StepExecution> completed = Map.of(
            1,
            new InterpretationPlanRuntime.StepExecution(
                1,
                "mcp_tool",
                "mcp_chatchat_mcp_server_sql_metadata_search",
                true,
                Map.of(
                    "schemaVersion", "sql_metadata_search_result.v1",
                    "topTables", List.of(Map.of(
                        "asset", Map.of("name", "TDH数据仓库"),
                        "location", Map.of(
                            "database", "gdp_ads",
                            "schema", "gdp_ads",
                            "table", "ads_ids_clr_acc_liab_d_i"
                        ),
                        "columns", List.of(
                            Map.of(
                                "name", "client_id",
                                "columnType", "varchar(64)",
                                "comment", "客户编号",
                                "nullable", false
                            ),
                            Map.of(
                                "name", "liability_amount",
                                "columnType", "decimal(18,2)",
                                "comment", "负债金额",
                                "nullable", true
                            )
                        )
                    ))
                ),
                null,
                null,
                null,
                12
            )
        );
        Method method = InterpretationPlanRuntime.class.getDeclaredMethod(
            "resolvedStepInput",
            InterpretationPlan.Step.class,
            InterpretationPlanRuntime.ExecutionRequest.class,
            Map.class
        );
        method.setAccessible(true);

        Map<String, Object> resolved = (Map<String, Object>) method.invoke(
            runtime,
            step,
            new InterpretationPlanRuntime.ExecutionRequest(
                minimalPlan(step),
                toolRegistry,
                List.of(toolName),
                "tenant-1",
                "req-unified-enterprise-metadata",
                "conv-unified-enterprise-metadata",
                "user-1",
                Map.of()
            ),
            completed
        );

        assertThat(resolved).containsEntry("query", "review prior evidence")
            .doesNotContainKeys("fields", "purpose", "targetObject");
        List<Map<String, Object>> sourceEvidence =
            (List<Map<String, Object>>) resolved.get("sourceEvidence");
        assertThat(sourceEvidence).hasSize(1);
        assertThat(sourceEvidence.get(0))
            .containsEntry("stepId", 1)
            .containsEntry("actionType", "mcp_tool")
            .containsEntry("toolName", "mcp_chatchat_mcp_server_sql_metadata_search");
        assertThat(String.valueOf(sourceEvidence.get(0).get("output")))
            .contains("topTables", "columns", "ads_ids_clr_acc_liab_d_i");
    }

    @Test
    @SuppressWarnings("unchecked")
    void promotesNestedExactTableFiltersBeforeCompilingSqlMetadataSearchArguments() throws Exception {
        String toolName = "mcp_chatchat_mcp_server_sql_metadata_search";
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.getToolMetadata(toolName)).thenReturn(ToolMetadata.builder()
            .id(toolName)
            .metadata(Map.of(
                "inputSchema", Map.of(
                    "type", "object",
                    "properties", Map.of(
                        "tableName", Map.of("type", "string"),
                        "assetName", Map.of("type", "string"),
                        "includeColumns", Map.of("type", "boolean"),
                        "limit", Map.of("type", "integer"),
                        "executionContext", Map.of("type", "object", "additionalProperties", true)
                    ),
                    "additionalProperties", false
                )
            ))
            .build());
        InterpretationPlanRuntime runtime = new InterpretationPlanRuntime(
            mock(ToolRuntimeService.class),
            new InterpretationPlanValidator(),
            mock(InterpretationPlanRuntime.DagExecutionController.class)
        );
        InterpretationPlan.Step step = new InterpretationPlan.Step(
            1,
            "mcp_tool",
            toolName,
            Map.of(
                "filters", Map.of(
                    "assetname", "TDH数据仓库",
                    "table_name", "ads_ids_clr_acc_liab_d_i",
                    "include_columns", true
                ),
                "limit", 1
            ),
            List.of(),
            null,
            null
        );
        Method method = InterpretationPlanRuntime.class.getDeclaredMethod(
            "resolvedStepInput",
            InterpretationPlan.Step.class,
            InterpretationPlanRuntime.ExecutionRequest.class,
            Map.class
        );
        method.setAccessible(true);

        Map<String, Object> resolved = (Map<String, Object>) method.invoke(
            runtime,
            step,
            new InterpretationPlanRuntime.ExecutionRequest(
                minimalPlan(step),
                toolRegistry,
                List.of(toolName),
                "tenant-1",
                "req-exact-table",
                "conv-exact-table",
                "user-1",
                Map.of()
            ),
            Map.of()
        );

        assertThat(resolved)
            .containsEntry("tableName", "ads_ids_clr_acc_liab_d_i")
            .containsEntry("assetName", "TDH数据仓库")
            .containsEntry("includeColumns", true)
            .containsEntry("limit", 1)
            .doesNotContainKey("filters");
    }

    @Test
    @SuppressWarnings("unchecked")
    void promotesGenericArgumentEnvelopeUsingEachToolsPublishedSchema() throws Exception {
        String toolName = "mcp_example_record_lookup";
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.getToolMetadata(toolName)).thenReturn(ToolMetadata.builder()
            .id(toolName)
            .metadata(Map.of(
                "inputSchema", Map.of(
                    "type", "object",
                    "properties", Map.of(
                        "recordId", Map.of("type", "string"),
                        "includeDetails", Map.of("type", "boolean")
                    ),
                    "required", List.of("recordId"),
                    "additionalProperties", false
                )
            ))
            .build());
        InterpretationPlanRuntime runtime = new InterpretationPlanRuntime(
            mock(ToolRuntimeService.class),
            new InterpretationPlanValidator(),
            mock(InterpretationPlanRuntime.DagExecutionController.class)
        );
        InterpretationPlan.Step step = new InterpretationPlan.Step(
            1,
            "mcp_tool",
            toolName,
            Map.of(
                "includeDetails", false,
                "modelPayload", Map.of(
                    "record_id", "record-42",
                    "include_details", true,
                    "unknown_model_field", "discard-me"
                )
            ),
            List.of(),
            null,
            null
        );
        Method method = InterpretationPlanRuntime.class.getDeclaredMethod(
            "resolvedStepInput",
            InterpretationPlan.Step.class,
            InterpretationPlanRuntime.ExecutionRequest.class,
            Map.class
        );
        method.setAccessible(true);

        Map<String, Object> resolved = (Map<String, Object>) method.invoke(
            runtime,
            step,
            new InterpretationPlanRuntime.ExecutionRequest(
                minimalPlan(step),
                toolRegistry,
                List.of(toolName),
                "tenant-1",
                "req-generic-envelope",
                "conv-generic-envelope",
                "user-1",
                Map.of()
            ),
            Map.of()
        );

        assertThat(resolved)
            .containsEntry("recordId", "record-42")
            .containsEntry("includeDetails", false)
            .doesNotContainKeys("modelPayload", "unknown_model_field");
    }

    @Test
    @SuppressWarnings("unchecked")
    void promotesNestedPublishedAliasesWithoutToolSpecificRules() throws Exception {
        String toolName = "mcp_example_evidence_search";
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.getToolMetadata(toolName)).thenReturn(ToolMetadata.builder()
            .id(toolName)
            .metadata(Map.of(
                "inputSchema", Map.of(
                    "type", "object",
                    "properties", Map.of(
                        "queryTerms", Map.of(
                            "type", "array",
                            "aliases", List.of("keywords", "keyword"),
                            "acceptedSources", List.of("searchTerms")
                        ),
                        "limit", Map.of("type", "integer")
                    ),
                    "additionalProperties", false
                )
            ))
            .build());
        InterpretationPlanRuntime runtime = new InterpretationPlanRuntime(
            mock(ToolRuntimeService.class),
            new InterpretationPlanValidator(),
            mock(InterpretationPlanRuntime.DagExecutionController.class)
        );
        InterpretationPlan.Step step = new InterpretationPlan.Step(
            1,
            "mcp_tool",
            toolName,
            Map.of("filters", Map.of(
                "keywords", List.of("credit rating", "rating date"),
                "limit", 25,
                "unknown", "discard-me"
            )),
            List.of(),
            null,
            null
        );
        Method method = InterpretationPlanRuntime.class.getDeclaredMethod(
            "resolvedStepInput",
            InterpretationPlan.Step.class,
            InterpretationPlanRuntime.ExecutionRequest.class,
            Map.class
        );
        method.setAccessible(true);

        Map<String, Object> resolved = (Map<String, Object>) method.invoke(
            runtime,
            step,
            new InterpretationPlanRuntime.ExecutionRequest(
                minimalPlan(step), toolRegistry, List.of(toolName),
                "tenant-1", "req-alias-envelope", "conv-alias-envelope", "user-1", Map.of()
            ),
            Map.of()
        );

        assertThat(resolved)
            .containsEntry("queryTerms", List.of("credit rating", "rating date"))
            .containsEntry("limit", 25)
            .doesNotContainKeys("filters", "keywords", "unknown");
    }

    @Test
    @SuppressWarnings("unchecked")
    void preservesEnvelopeWhenToolPublishesItAsPartOfItsContract() throws Exception {
        String toolName = "mcp_example_routed_lookup";
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.getToolMetadata(toolName)).thenReturn(ToolMetadata.builder()
            .id(toolName)
            .metadata(Map.of(
                "inputSchema", Map.of(
                    "type", "object",
                    "properties", Map.of(
                        "filters", Map.of("type", "object", "additionalProperties", true)
                    ),
                    "required", List.of("filters"),
                    "additionalProperties", false
                )
            ))
            .build());
        InterpretationPlanRuntime runtime = new InterpretationPlanRuntime(
            mock(ToolRuntimeService.class),
            new InterpretationPlanValidator(),
            mock(InterpretationPlanRuntime.DagExecutionController.class)
        );
        InterpretationPlan.Step step = new InterpretationPlan.Step(
            1,
            "mcp_tool",
            toolName,
            Map.of("filters", Map.of("tenant", "tenant-a", "keyword", "customer")),
            List.of(),
            null,
            null
        );
        Method method = InterpretationPlanRuntime.class.getDeclaredMethod(
            "resolvedStepInput",
            InterpretationPlan.Step.class,
            InterpretationPlanRuntime.ExecutionRequest.class,
            Map.class
        );
        method.setAccessible(true);

        Map<String, Object> resolved = (Map<String, Object>) method.invoke(
            runtime,
            step,
            new InterpretationPlanRuntime.ExecutionRequest(
                minimalPlan(step),
                toolRegistry,
                List.of(toolName),
                "tenant-1",
                "req-preserve-envelope",
                "conv-preserve-envelope",
                "user-1",
                Map.of()
            ),
            Map.of()
        );

        assertThat(resolved).containsKey("filters");
        assertThat((Map<String, Object>) resolved.get("filters"))
            .containsEntry("tenant", "tenant-a")
            .containsEntry("keyword", "customer");
    }

    @Test
    @SuppressWarnings("unchecked")
    void doesNotApplyToolSpecificMetadataRulesWithoutPublishedContract() throws Exception {
        InterpretationPlanRuntime runtime = new InterpretationPlanRuntime(
            mock(ToolRuntimeService.class),
            new InterpretationPlanValidator(),
            mock(InterpretationPlanRuntime.DagExecutionController.class)
        );
        InterpretationPlan.Step step = new InterpretationPlan.Step(
            1,
            "mcp_tool",
            "mcp_chatchat_mcp_server_enterprise_metadata_search",
            Map.of(
                "tableName", "customer_profile",
                "types", List.of("STANDARD_FIELD"),
                "fields", List.of(
                    Map.of(
                        "name", "customer_name",
                        "cnName", "客户姓名",
                        "type", "varchar(100)",
                        "isNullable", false,
                        "comment", "客户姓名"
                    ),
                    Map.of(
                        "columnName", "customer_status",
                        "chineseName", "客户状态",
                        "columnType", "varchar(8)",
                        "nullable", true,
                        "businessDomain", "客户"
                    )
                )
            ),
            List.of(),
            null,
            null
        );
        Method method = InterpretationPlanRuntime.class.getDeclaredMethod(
            "resolvedStepInput",
            InterpretationPlan.Step.class,
            InterpretationPlanRuntime.ExecutionRequest.class,
            Map.class
        );
        method.setAccessible(true);

        Map<String, Object> resolved = (Map<String, Object>) method.invoke(
            runtime,
            step,
            new InterpretationPlanRuntime.ExecutionRequest(
                minimalPlan(step),
                mock(ToolRegistry.class),
                List.of("mcp_chatchat_mcp_server_enterprise_metadata_search"),
                "tenant-1",
                "req-create-table-metadata",
                "conv-create-table-metadata",
                "user-1",
                Map.of()
            ),
            Map.of()
        );

        assertThat(resolved)
            .containsEntry("tableName", "customer_profile")
            .containsEntry("types", List.of("STANDARD_FIELD"))
            .doesNotContainKeys("query", "purpose", "matchMode", "targetObject", "sourceEvidence");
        List<Map<String, Object>> fields = (List<Map<String, Object>>) resolved.get("fields");
        assertThat(fields).hasSize(2);
        assertThat(fields.get(0))
            .containsEntry("name", "customer_name")
            .containsEntry("cnName", "客户姓名")
            .containsEntry("type", "varchar(100)")
            .containsEntry("isNullable", false)
            .containsEntry("comment", "客户姓名")
            .doesNotContainKeys("fieldName", "fieldCnName", "dataType");
        assertThat(fields.get(1))
            .containsEntry("columnName", "customer_status")
            .containsEntry("chineseName", "客户状态")
            .containsEntry("columnType", "varchar(8)")
            .containsEntry("nullable", true)
            .containsEntry("businessDomain", "客户")
            .doesNotContainKeys("fieldName", "fieldCnName", "dataType", "domain");
    }

    @Test
    void passesBusinessQueryTemplateNameToSqlQueryExecutor() {
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.hasTool(any())).thenReturn(true);
        when(toolRegistry.getToolMetadata(any())).thenAnswer(invocation ->
            ToolMetadata.builder().id(invocation.getArgument(0)).riskLevel("low").build());
        ToolRuntimeService toolRuntimeService = mock(ToolRuntimeService.class);
        when(toolRuntimeService.execute(any())).thenAnswer(invocation -> {
            ToolRuntimeRequest toolRequest = invocation.getArgument(0);
            if ("mcp_chatchat_mcp_server_database_query_template_query".equals(toolRequest.getToolName())) {
                return new ToolRuntimeExecution(
                    ToolOutput.success(Map.of(
                        "assetName", "market-dm",
                        "env", "DEV",
                        "templates", List.of(Map.of(
                            "templateId", "edayQuqtMoni",
                            "mcpToolName", "edayQuqtMoni",
                            "execution", Map.of("mode", "direct_mcp_tool", "callTool", "edayQuqtMoni"),
                            "parameterSchema", Map.of("type", "object", "properties", Map.of(), "required", List.of())
                        ))
                    )),
                    ToolMetadata.builder().id(toolRequest.getToolName()).build(),
                    null,
                    "success",
                    Map.of()
                );
            }
            return new ToolRuntimeExecution(
                ToolOutput.success(Map.of("rows", List.of(Map.of("cnt", 1)))),
                ToolMetadata.builder().id(toolRequest.getToolName()).build(),
                null,
                "success",
                Map.of()
            );
        });
        InterpretationPlanRuntime runtime = new InterpretationPlanRuntime(
            toolRuntimeService,
            new InterpretationPlanValidator(),
            scriptedController(List.of(List.of(1), List.of(2), List.of(3)))
        );

        InterpretationPlanRuntime.ExecutionResult result = runtime.execute(new InterpretationPlanRuntime.ExecutionRequest(
            legacyBusinessQueryPlan(),
            toolRegistry,
            List.of(
                "mcp_chatchat_mcp_server_database_query_template_query",
                "mcp_chatchat_mcp_server_sql_datasource_template_query",
                "mcp_chatchat_mcp_server_sql_query_execute"
            ),
            "tenant-1",
            "req-business-query",
            "conv-business-query",
            "user-1",
            Map.of()
        ));

        assertThat(result.success())
            .as("status=%s error=%s metadata=%s steps=%s", result.status(), result.errorMessage(), result.metadata(), result.steps())
            .isTrue();
        ArgumentCaptor<ToolRuntimeRequest> captor = ArgumentCaptor.forClass(ToolRuntimeRequest.class);
        verify(toolRuntimeService, times(2)).execute(captor.capture());
        assertThat(captor.getAllValues()).extracting(ToolRuntimeRequest::getToolName)
            .containsExactly("mcp_chatchat_mcp_server_database_query_template_query",
                "mcp_chatchat_mcp_server_sql_query_execute");
        assertThat(captor.getAllValues().get(1).getToolInput().getParameters())
            .containsEntry("templateId", "edayQuqtMoni");
        assertThat(captor.getAllValues().get(1).getAllowedTools())
            .doesNotContain("edayQuqtMoni");
    }

    @Test
    void executesSelectedTemplateFromOversizedDiscoveryControlPlaneProjection() {
        String discoveryTool = "mcp_tenant_database_query_template_query";
        String executionTool = "mcp_tenant_sql_query_execute";
        String templateId = "sample_margin_trade_latest";
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.hasTool(any())).thenReturn(true);
        when(toolRegistry.getToolMetadata(any())).thenAnswer(invocation ->
            ToolMetadata.builder().id(invocation.getArgument(0)).riskLevel("low").build());
        ToolRuntimeService toolRuntimeService = mock(ToolRuntimeService.class);
        when(toolRuntimeService.execute(any())).thenAnswer(invocation -> {
            ToolRuntimeRequest request = invocation.getArgument(0);
            if (discoveryTool.equals(request.getToolName())) {
                return new ToolRuntimeExecution(
                    ToolOutput.success(Map.of(
                        "outputTruncated", true,
                        "outputExternal", true,
                        "routingProjection", Map.of("templates", List.of(Map.of(
                            "templateId", templateId,
                            "parameterSchema", Map.of(
                                "type", "object", "properties", Map.of(), "required", List.of()),
                            "sqlExecutionBinding", Map.of(
                                "toolName", "sql_query_execute",
                                "templateId", templateId,
                                "executionContext", Map.of(
                                    "assetName", "financial-market-runtime",
                                    "env", "RUNTIME",
                                    "databaseType", "h2"))
                        )))
                    )),
                    ToolMetadata.builder().id(discoveryTool).build(), null, "success", Map.of());
            }
            return new ToolRuntimeExecution(
                ToolOutput.success(Map.of("rows", List.of(Map.of("observation_date", "2026-07-31")))),
                ToolMetadata.builder().id(executionTool).build(), null, "success", Map.of());
        });
        InterpretationPlan plan = new InterpretationPlan(
            "1.0",
            new InterpretationPlan.Intent("sql_query", "Analyze latest margin trading data", "medium"),
            context(),
            new InterpretationPlan.Plan(List.of(
                new InterpretationPlan.Step(1, "mcp_tool", discoveryTool,
                    Map.of("filters", Map.of("intent", "latest margin trading")), List.of(), null, null),
                new InterpretationPlan.Step(2, "mcp_tool", executionTool,
                    Map.of("templateId", templateId, "parameters", Map.of()), List.of(1), null, null),
                new InterpretationPlan.Step(3, "final_answer", "", Map.of("answer", "done"),
                    List.of(2), null, null)
            )),
            new InterpretationPlan.ExecutionPolicy(
                3, false, List.of(discoveryTool, executionTool), List.of(), 30_000),
            review()
        );
        InterpretationPlanRuntime runtime = new InterpretationPlanRuntime(
            toolRuntimeService,
            new InterpretationPlanValidator(),
            scriptedController(List.of(List.of(1), List.of(2), List.of(3)))
        );

        InterpretationPlanRuntime.ExecutionResult result = runtime.execute(
            new InterpretationPlanRuntime.ExecutionRequest(
                plan, toolRegistry, List.of(discoveryTool, executionTool),
                "tenant-1", "req-margin-projection", "conv-margin-projection", "user-1", Map.of()));

        assertThat(result.success())
            .as("status=%s error=%s steps=%s", result.status(), result.errorMessage(), result.steps())
            .isTrue();
        ArgumentCaptor<ToolRuntimeRequest> requests = ArgumentCaptor.forClass(ToolRuntimeRequest.class);
        verify(toolRuntimeService, times(2)).execute(requests.capture());
        ToolRuntimeRequest executionRequest = requests.getAllValues().get(1);
        assertThat(executionRequest.getToolName()).isEqualTo(executionTool);
        assertThat(executionRequest.getToolInput().getParameters())
            .containsEntry("templateId", templateId)
            .containsEntry("executionContext", Map.of(
                "assetName", "financial-market-runtime",
                "env", "RUNTIME",
                "databaseType", "h2"));
    }

    @Test
    void blocksSqlExecutionWhenTemplateDiscoveryReturnsNoTemplateMetadata() {
        String discoveryTool = "mcp_chatchat_mcp_server_database_query_template_query";
        String executionTool = "mcp_chatchat_mcp_server_sql_query_execute";
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.hasTool(any())).thenReturn(true);
        when(toolRegistry.getToolMetadata(any())).thenAnswer(invocation ->
            ToolMetadata.builder().id(invocation.getArgument(0)).riskLevel("low").build());
        ToolRuntimeService toolRuntimeService = mock(ToolRuntimeService.class);
        when(toolRuntimeService.execute(any())).thenReturn(new ToolRuntimeExecution(
            ToolOutput.success(Map.of(
                "success", true,
                "returnedCount", 1,
                "templates", List.of()
            )),
            ToolMetadata.builder().id(discoveryTool).build(),
            null,
            "success",
            Map.of()
        ));
        InterpretationPlan plan = new InterpretationPlan(
            "1.0",
            new InterpretationPlan.Intent("sql_query", "查询融资融券最新数据", "low"),
            context(),
            new InterpretationPlan.Plan(List.of(
                new InterpretationPlan.Step(
                    1,
                    "mcp_tool",
                    discoveryTool,
                    Map.of("filters", Map.of("intent", "查询融资融券最新数据", "env", "DEV")),
                    List.of(),
                    null,
                    null
                ),
                new InterpretationPlan.Step(
                    2,
                    "mcp_tool",
                    executionTool,
                    Map.of("executionContext", Map.of("env", "DEV"), "parameters", Map.of()),
                    List.of(1),
                    null,
                    null
                ),
                new InterpretationPlan.Step(
                    3,
                    "final_answer",
                    "",
                    Map.of("answer", "done"),
                    List.of(2),
                    null,
                    null
                )
            )),
            new InterpretationPlan.ExecutionPolicy(
                3, false, List.of(discoveryTool, executionTool), List.of(), 30000),
            review()
        );
        InterpretationPlanRuntime runtime = new InterpretationPlanRuntime(
            toolRuntimeService,
            new InterpretationPlanValidator(),
            scriptedController(List.of(List.of(1), List.of(2), List.of(3)))
        );

        InterpretationPlanRuntime.ExecutionResult result = runtime.execute(
            new InterpretationPlanRuntime.ExecutionRequest(
                plan,
                toolRegistry,
                List.of(discoveryTool, executionTool),
                "tenant-1",
                "req-empty-template-binding",
                "conv-empty-template-binding",
                "user-1",
                Map.of()
            )
        );

        assertThat(result.success()).isFalse();
        assertThat(result.errorMessage())
            .contains("TEMPLATE_CONTRACT_RESOLUTION_FAILED", executionTool);
        ArgumentCaptor<ToolRuntimeRequest> requests = ArgumentCaptor.forClass(ToolRuntimeRequest.class);
        verify(toolRuntimeService, times(2)).execute(requests.capture());
        assertThat(requests.getAllValues())
            .extracting(ToolRuntimeRequest::getToolName)
            .containsOnly(discoveryTool)
            .doesNotContain(executionTool);
    }

    @Test
    void hydratesSqlExecutionContextFromBusinessTemplateMetadata() {
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.hasTool(any())).thenReturn(true);
        when(toolRegistry.getToolMetadata(any())).thenAnswer(invocation ->
            ToolMetadata.builder().id(invocation.getArgument(0)).riskLevel("low").build());
        ToolRuntimeService toolRuntimeService = mock(ToolRuntimeService.class);
        when(toolRuntimeService.execute(any())).thenAnswer(invocation -> {
            ToolRuntimeRequest toolRequest = invocation.getArgument(0);
            if ("mcp_chatchat_mcp_server_database_query_template_query".equals(toolRequest.getToolName())) {
                return new ToolRuntimeExecution(
                    ToolOutput.success(Map.of(
                        "templates", List.of(Map.of(
                            "templateId", "edayQuqtMoni",
                            "sqlExecutionBinding", Map.of(
                                "toolName", "sql_query_execute",
                                "executionContext", Map.of(
                                    "assetName", "market-dm",
                                    "env", "DEV",
                                    "databaseType", "dm"
                                )
                            ),
                            "parameterSchema", Map.of("type", "object", "properties", Map.of(), "required", List.of())
                        ))
                    )),
                    ToolMetadata.builder().id(toolRequest.getToolName()).build(),
                    null,
                    "success",
                    Map.of()
                );
            }
            return new ToolRuntimeExecution(
                ToolOutput.success(Map.of("rows", List.of(Map.of("cnt", 1)))),
                ToolMetadata.builder().id(toolRequest.getToolName()).build(),
                null,
                "success",
                Map.of()
            );
        });
        InterpretationPlanRuntime runtime = new InterpretationPlanRuntime(
            toolRuntimeService,
            new InterpretationPlanValidator(),
            scriptedController(List.of(List.of(1), List.of(2), List.of(3)))
        );

        InterpretationPlan plan = new InterpretationPlan(
            "1.0",
            new InterpretationPlan.Intent("data_query", "分析行情波动提醒", "low"),
            new InterpretationPlan.Context(List.of(), List.of(), List.of(), List.of()),
            new InterpretationPlan.Plan(List.of(
                new InterpretationPlan.Step(1, "mcp_tool", "mcp_chatchat_mcp_server_database_query_template_query",
                    Map.of(
                        "finalDecision", "business_database_query",
                        "candidates", List.of(Map.of("targetKind", "business_database_query", "confidence", 0.95)),
                        "filters", Map.of("intent", "行情波动提醒")
                    ), List.of(), null, null),
                new InterpretationPlan.Step(2, "mcp_tool", "mcp_chatchat_mcp_server_sql_query_execute",
                    Map.of("templateId", "edayQuqtMoni", "parameters", Map.of()), List.of(1), null, null),
                new InterpretationPlan.Step(3, "final_answer", "", Map.of("answer", "done"), List.of(2), null, null)
            )),
            new InterpretationPlan.ExecutionPolicy(3, false,
                List.of("mcp_chatchat_mcp_server_database_query_template_query",
                    "mcp_chatchat_mcp_server_sql_query_execute"),
                List.of(),
                30000),
            review()
        );

        InterpretationPlanRuntime.ExecutionResult result = runtime.execute(new InterpretationPlanRuntime.ExecutionRequest(
            plan,
            toolRegistry,
            List.of("mcp_chatchat_mcp_server_database_query_template_query",
                "mcp_chatchat_mcp_server_sql_query_execute"),
            "tenant-1",
            "req-business-query-context",
            "conv-business-query-context",
            "user-1",
            Map.of()
        ));

        assertThat(result.success()).isTrue();
        ArgumentCaptor<ToolRuntimeRequest> captor = ArgumentCaptor.forClass(ToolRuntimeRequest.class);
        verify(toolRuntimeService, times(2)).execute(captor.capture());
        assertThat(captor.getAllValues()).extracting(ToolRuntimeRequest::getToolName)
            .containsExactly("mcp_chatchat_mcp_server_database_query_template_query",
                "mcp_chatchat_mcp_server_sql_query_execute");
        assertThat(captor.getAllValues().get(1).getToolInput().getParameters())
            .containsEntry("executionContext", Map.of(
                "assetName", "market-dm",
                "env", "DEV",
                "databaseType", "dm"
            ));
    }

    @Test
    void hydratesSqlExecutionContextFromDatabaseQuerySearchIndexResults() {
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.hasTool(any())).thenReturn(true);
        when(toolRegistry.getToolMetadata(any())).thenAnswer(invocation ->
            ToolMetadata.builder().id(invocation.getArgument(0)).riskLevel("low").build());
        ToolRuntimeService toolRuntimeService = mock(ToolRuntimeService.class);
        when(toolRuntimeService.execute(any())).thenAnswer(invocation -> {
            ToolRuntimeRequest toolRequest = invocation.getArgument(0);
            if ("mcp_chatchat_mcp_server_database_query_template_query".equals(toolRequest.getToolName())) {
                return new ToolRuntimeExecution(
                    ToolOutput.success(Map.of(
                        "indexType", "database_query",
                        "results", List.of(Map.of(
                            "id", "ds-dm",
                            "sqlExecutionContext", Map.of(
                                "assetName", "达梦测试服务器",
                                "env", "DEV",
                                "environment", "DEV",
                                "databaseType", "dm",
                                "dbType", "dm"
                            ),
                            "associatedTemplates", List.of(Map.of(
                                "templateId", "query_edayQuqtMoni",
                                "mcpToolName", "query_edayQuqtMoni",
                                "sqlExecutionBinding", Map.of(
                                    "toolName", "sql_query_execute",
                                    "templateId", "query_edayQuqtMoni",
                                    "executionContext", Map.of(
                                        "assetName", "达梦测试服务器",
                                        "env", "DEV",
                                        "environment", "DEV",
                                        "databaseType", "dm",
                                        "dbType", "dm"
                                    )
                                ),
                                "parameterSchema", Map.of("type", "object", "properties", Map.of(), "required", List.of())
                            ))
                        ))
                    )),
                    ToolMetadata.builder().id(toolRequest.getToolName()).build(),
                    null,
                    "success",
                    Map.of()
                );
            }
            return new ToolRuntimeExecution(
                ToolOutput.success(Map.of("rows", List.of(Map.of("cnt", 1)))),
                ToolMetadata.builder().id(toolRequest.getToolName()).build(),
                null,
                "success",
                Map.of()
            );
        });
        InterpretationPlanRuntime runtime = new InterpretationPlanRuntime(
            toolRuntimeService,
            new InterpretationPlanValidator(),
            scriptedController(List.of(List.of(1), List.of(2), List.of(3)))
        );

        InterpretationPlan plan = new InterpretationPlan(
            "1.0",
            new InterpretationPlan.Intent("data_query", "行情提醒", "low"),
            new InterpretationPlan.Context(List.of(), List.of(), List.of(), List.of()),
            new InterpretationPlan.Plan(List.of(
                new InterpretationPlan.Step(1, "mcp_tool", "mcp_chatchat_mcp_server_database_query_template_query",
                    Map.of("filters", Map.of("intent", "行情提醒")), List.of(), null, null),
                new InterpretationPlan.Step(2, "mcp_tool", "mcp_chatchat_mcp_server_sql_query_execute",
                    Map.of("templateId", "query_edayQuqtMoni", "parameters", Map.of()), List.of(1), null, null),
                new InterpretationPlan.Step(3, "final_answer", "", Map.of("answer", "done"), List.of(2), null, null)
            )),
            new InterpretationPlan.ExecutionPolicy(3, false,
                List.of("mcp_chatchat_mcp_server_database_query_template_query",
                    "mcp_chatchat_mcp_server_sql_query_execute"),
                List.of(),
                30000),
            review()
        );

        InterpretationPlanRuntime.ExecutionResult result = runtime.execute(new InterpretationPlanRuntime.ExecutionRequest(
            plan,
            toolRegistry,
            List.of("mcp_chatchat_mcp_server_database_query_template_query",
                "mcp_chatchat_mcp_server_sql_query_execute"),
            "tenant-1",
            "req-business-query-search-index",
            "conv-business-query-search-index",
            "user-1",
            Map.of()
        ));

        assertThat(result.success()).isTrue();
        ArgumentCaptor<ToolRuntimeRequest> captor = ArgumentCaptor.forClass(ToolRuntimeRequest.class);
        verify(toolRuntimeService, times(2)).execute(captor.capture());
        assertThat(captor.getAllValues()).extracting(ToolRuntimeRequest::getToolName)
            .containsExactly("mcp_chatchat_mcp_server_database_query_template_query",
                "mcp_chatchat_mcp_server_sql_query_execute");
        assertThat(captor.getAllValues().get(1).getToolInput().getParameters())
            .containsEntry("executionContext", Map.of(
                "assetName", "达梦测试服务器",
                "env", "DEV",
                "environment", "DEV",
                "databaseType", "dm",
                "dbType", "dm"
            ));
    }

    @Test
    void mapsDiscoveredTemplateToolStepToDeclaredExecutor() {
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.hasTool(any())).thenAnswer(invocation -> {
            String tool = invocation.getArgument(0);
            return !"query_edayQuqtMoni".equals(tool);
        });
        when(toolRegistry.getToolMetadata(any())).thenAnswer(invocation ->
            ToolMetadata.builder().id(invocation.getArgument(0)).riskLevel("low").build());
        ToolRuntimeService toolRuntimeService = mock(ToolRuntimeService.class);
        when(toolRuntimeService.execute(any())).thenAnswer(invocation -> {
            ToolRuntimeRequest toolRequest = invocation.getArgument(0);
            if ("mcp_chatchat_mcp_server_database_query_template_query".equals(toolRequest.getToolName())) {
                return new ToolRuntimeExecution(
                    ToolOutput.success(Map.of(
                        "results", List.of(Map.of(
                            "id", "ds-dm",
                            "associatedTemplates", List.of(Map.of(
                                "templateId", "query_edayQuqtMoni",
                                "mcpToolName", "query_edayQuqtMoni",
                                "sqlExecutionBinding", Map.of(
                                    "toolName", "sql_query_execute",
                                    "templateId", "query_edayQuqtMoni",
                                    "executionContext", Map.of(
                                        "assetName", "达梦测试服务器",
                                        "env", "DEV",
                                        "databaseType", "dm"
                                    )
                                )
                            ))
                        ))
                    )),
                    ToolMetadata.builder().id(toolRequest.getToolName()).build(),
                    null,
                    "success",
                    Map.of()
                );
            }
            return new ToolRuntimeExecution(
                ToolOutput.success(Map.of("rows", List.of(Map.of("cnt", 1)))),
                ToolMetadata.builder().id(toolRequest.getToolName()).build(),
                null,
                "success",
                Map.of()
            );
        });
        InterpretationPlanRuntime runtime = new InterpretationPlanRuntime(
            toolRuntimeService,
            new InterpretationPlanValidator(),
            scriptedController(List.of(List.of(1), List.of(2), List.of(3)))
        );

        InterpretationPlan plan = new InterpretationPlan(
            "1.0",
            new InterpretationPlan.Intent("sql_query", "行情波动提醒", "low"),
            new InterpretationPlan.Context(List.of(), List.of(), List.of(), List.of()),
            new InterpretationPlan.Plan(List.of(
                new InterpretationPlan.Step(1, "mcp_tool", "mcp_chatchat_mcp_server_database_query_template_query",
                    Map.of(
                        "finalDecision", "business_database_query",
                        "candidates", List.of(Map.of("targetKind", "business_database_query", "confidence", 0.95)),
                        "filters", Map.of("intent", "行情波动提醒")
                    ), List.of(), null, null),
                new InterpretationPlan.Step(2, "mcp_tool", "query_edayQuqtMoni",
                    Map.of("parameters", Map.of()), List.of(1), null, null),
                new InterpretationPlan.Step(3, "final_answer", "", Map.of("answer", "done"), List.of(2), null, null)
            )),
            new InterpretationPlan.ExecutionPolicy(3, false,
                List.of("mcp_chatchat_mcp_server_database_query_template_query",
                    "mcp_chatchat_mcp_server_sql_query_execute"),
                List.of(),
                30000),
            review()
        );

        InterpretationPlanRuntime.ExecutionResult result = runtime.execute(new InterpretationPlanRuntime.ExecutionRequest(
            plan,
            toolRegistry,
            List.of("mcp_chatchat_mcp_server_database_query_template_query",
                "mcp_chatchat_mcp_server_sql_query_execute"),
            "tenant-1",
            "req-template-placeholder",
            "conv-template-placeholder",
            "user-1",
            Map.of()
        ));

        assertThat(result.success()).isTrue();
        ArgumentCaptor<ToolRuntimeRequest> captor = ArgumentCaptor.forClass(ToolRuntimeRequest.class);
        verify(toolRuntimeService, times(2)).execute(captor.capture());
        assertThat(captor.getAllValues()).extracting(ToolRuntimeRequest::getToolName)
            .containsExactly("mcp_chatchat_mcp_server_database_query_template_query",
                "mcp_chatchat_mcp_server_sql_query_execute");
        assertThat(captor.getAllValues().get(1).getToolInput().getParameters())
            .containsEntry("templateId", "query_edayQuqtMoni")
            .containsEntry("executionContext", Map.of(
                "assetName", "达梦测试服务器",
                "env", "DEV",
                "databaseType", "dm"
            ));
    }

    @Test
    void mapsDiscoveredTemplateToolStepUsingDeclaredExecutionExecutorTool() {
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.hasTool(any())).thenAnswer(invocation -> {
            String tool = invocation.getArgument(0);
            return !"query_mktInfoEvtMoni".equals(tool);
        });
        when(toolRegistry.getToolMetadata(any())).thenAnswer(invocation ->
            ToolMetadata.builder().id(invocation.getArgument(0)).riskLevel("low").build());
        ToolRuntimeService toolRuntimeService = mock(ToolRuntimeService.class);
        when(toolRuntimeService.execute(any())).thenAnswer(invocation -> {
            ToolRuntimeRequest toolRequest = invocation.getArgument(0);
            if ("mcp_chatchat_mcp_server_database_query_template_query".equals(toolRequest.getToolName())) {
                return new ToolRuntimeExecution(
                    ToolOutput.success(Map.of(
                        "results", List.of(Map.of(
                            "id", "ds-dm",
                            "associatedTemplates", List.of(Map.of(
                                "templateId", "query_mktInfoEvtMoni",
                                "mcpToolName", "query_mktInfoEvtMoni",
                                "execution", Map.of(
                                    "mode", "template_execution",
                                    "executorTool", "sql_query_execute",
                                    "template", "query_mktInfoEvtMoni",
                                    "callTool", "query_mktInfoEvtMoni",
                                    "executionContext", Map.of(
                                        "assetName", "dm-market",
                                        "env", "DEV",
                                        "databaseType", "dm"
                                    )
                                )
                            ))
                        ))
                    )),
                    ToolMetadata.builder().id(toolRequest.getToolName()).build(),
                    null,
                    "success",
                    Map.of()
                );
            }
            return new ToolRuntimeExecution(
                ToolOutput.success(Map.of("rows", List.of(Map.of("cnt", 1)))),
                ToolMetadata.builder().id(toolRequest.getToolName()).build(),
                null,
                "success",
                Map.of()
            );
        });
        InterpretationPlanRuntime runtime = new InterpretationPlanRuntime(
            toolRuntimeService,
            new InterpretationPlanValidator(),
            scriptedController(List.of(List.of(1), List.of(2), List.of(3)))
        );

        InterpretationPlan plan = new InterpretationPlan(
            "1.0",
            new InterpretationPlan.Intent("sql_query", "market event monitor", "low"),
            new InterpretationPlan.Context(List.of(), List.of(), List.of(), List.of()),
            new InterpretationPlan.Plan(List.of(
                new InterpretationPlan.Step(1, "mcp_tool", "mcp_chatchat_mcp_server_database_query_template_query",
                    Map.of("filters", Map.of("intent", "market event monitor")), List.of(), null, null),
                new InterpretationPlan.Step(2, "mcp_tool", "query_mktInfoEvtMoni",
                    Map.of("parameters", Map.of()), List.of(1), null, null),
                new InterpretationPlan.Step(3, "final_answer", "", Map.of("answer", "done"), List.of(2), null, null)
            )),
            new InterpretationPlan.ExecutionPolicy(3, false,
                List.of("mcp_chatchat_mcp_server_database_query_template_query",
                    "mcp_chatchat_mcp_server_sql_query_execute"),
                List.of(),
                30000),
            review()
        );

        InterpretationPlanRuntime.ExecutionResult result = runtime.execute(new InterpretationPlanRuntime.ExecutionRequest(
            plan,
            toolRegistry,
            List.of("mcp_chatchat_mcp_server_database_query_template_query",
                "mcp_chatchat_mcp_server_sql_query_execute"),
            "tenant-1",
            "req-template-executor",
            "conv-template-executor",
            "user-1",
            Map.of()
        ));

        assertThat(result.success()).isTrue();
        ArgumentCaptor<ToolRuntimeRequest> captor = ArgumentCaptor.forClass(ToolRuntimeRequest.class);
        verify(toolRuntimeService, times(2)).execute(captor.capture());
        assertThat(captor.getAllValues()).extracting(ToolRuntimeRequest::getToolName)
            .containsExactly("mcp_chatchat_mcp_server_database_query_template_query",
                "mcp_chatchat_mcp_server_sql_query_execute");
        assertThat(captor.getAllValues().get(1).getToolInput().getParameters())
            .containsEntry("templateId", "query_mktInfoEvtMoni")
            .containsEntry("executionContext", Map.of(
                "assetName", "dm-market",
                "env", "DEV",
                "databaseType", "dm"
            ));
    }

    @Test
    void preservesPlannerRoutingDecisionBeforeMcpCall() {
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.hasTool(any())).thenReturn(true);
        when(toolRegistry.getToolMetadata(any())).thenAnswer(invocation ->
            ToolMetadata.builder().id(invocation.getArgument(0)).riskLevel("low").build());
        ToolRuntimeService toolRuntimeService = mock(ToolRuntimeService.class);
        when(toolRuntimeService.execute(any())).thenAnswer(invocation -> {
            ToolRuntimeRequest toolRequest = invocation.getArgument(0);
            return new ToolRuntimeExecution(
                ToolOutput.success(Map.of("results", List.of(Map.of(
                    "id", "ds-dm",
                    "associatedTemplates", List.of(Map.of("templateId", "query_test"))
                )))),
                ToolMetadata.builder().id(toolRequest.getToolName()).build(),
                null,
                "success",
                Map.of()
            );
        });
        InterpretationPlanRuntime runtime = new InterpretationPlanRuntime(
            toolRuntimeService,
            new InterpretationPlanValidator(),
            scriptedController(List.of(List.of(1), List.of(2)))
        );

        InterpretationPlan plan = new InterpretationPlan(
            "1.0",
            new InterpretationPlan.Intent("sql_query", "行情波动提醒", "low"),
            new InterpretationPlan.Context(List.of(), List.of(), List.of(), List.of()),
            new InterpretationPlan.Plan(List.of(
                new InterpretationPlan.Step(1, "mcp_tool", "mcp_chatchat_mcp_server_database_query_template_query",
                    Map.of(
                        "candidates", List.of(Map.of("targetKind", "database", "confidence", 0.95)),
                        "finalDecision", "database",
                        "filters", Map.of("intent", "行情数据发生较大波动时异常提醒数据"),
                        "trace", Map.of("plannerVersion", "v1.1")
                    ), List.of(), null, null),
                new InterpretationPlan.Step(2, "final_answer", "", Map.of("answer", "done"), List.of(1), null, null)
            )),
            new InterpretationPlan.ExecutionPolicy(2, false,
                List.of("mcp_chatchat_mcp_server_database_query_template_query"),
                List.of(),
                30000),
            review()
        );

        InterpretationPlanRuntime.ExecutionResult result = runtime.execute(new InterpretationPlanRuntime.ExecutionRequest(
            plan,
            toolRegistry,
            List.of("mcp_chatchat_mcp_server_database_query_template_query"),
            "tenant-1",
            "req-normalize-business-template-target",
            "conv-normalize-business-template-target",
            "user-1",
            Map.of()
        ));

        assertThat(result.success()).isTrue();
        ArgumentCaptor<ToolRuntimeRequest> captor = ArgumentCaptor.forClass(ToolRuntimeRequest.class);
        verify(toolRuntimeService).execute(captor.capture());
        Map<String, Object> parameters = captor.getValue().getToolInput().getParameters();
        assertThat(parameters).containsEntry("finalDecision", "database");
        assertThat(parameters.get("candidates").toString()).contains("targetKind=database");
        assertThat(parameters.get("trace").toString()).doesNotContain("routingForcedByTypedDiscoveryTool");
    }

    @Test
    void executesExactUserBoundDatabaseOpsTemplateToolWithoutBusinessSubstitution() {
        String requestedTool = "mcp_chatchat_mcp_server_database_ops_template_search";
        String otherTool = "mcp_chatchat_mcp_server_database_query_template_query";
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.hasTool(any())).thenReturn(true);
        when(toolRegistry.getToolMetadata(any())).thenAnswer(invocation ->
            ToolMetadata.builder().id(invocation.getArgument(0)).riskLevel("low").build());
        ToolRuntimeService toolRuntimeService = mock(ToolRuntimeService.class);
        when(toolRuntimeService.execute(any())).thenAnswer(invocation -> {
            ToolRuntimeRequest toolRequest = invocation.getArgument(0);
            return new ToolRuntimeExecution(
                ToolOutput.success(Map.of("templates", List.of(Map.of("templateId", "MYSQL_INNODB_STATUS")))),
                ToolMetadata.builder().id(toolRequest.getToolName()).build(),
                null,
                "success",
                Map.of()
            );
        });
        InterpretationPlanRuntime runtime = new InterpretationPlanRuntime(
            toolRuntimeService,
            new InterpretationPlanValidator(),
            scriptedController(List.of(List.of(1), List.of(2)))
        );
        InterpretationPlan plan = new InterpretationPlan(
            "1.0",
            new InterpretationPlan.Intent("database_ops", "analyze mysql status", "low"),
            context(),
            new InterpretationPlan.Plan(List.of(
                new InterpretationPlan.Step(1, "mcp_tool", requestedTool,
                    Map.of("finalDecision", "database", "filters", Map.of("intent", "market volatility alert")),
                    List.of(), null, null),
                new InterpretationPlan.Step(2, "final_answer", "", Map.of("answer", "done"), List.of(1), null, null)
            )),
            new InterpretationPlan.ExecutionPolicy(2, false, List.of(requestedTool), List.of(), 30000),
            review()
        );

        InterpretationPlanRuntime.ExecutionResult result = runtime.execute(new InterpretationPlanRuntime.ExecutionRequest(
            plan,
            toolRegistry,
            List.of(requestedTool, otherTool),
            "tenant-1",
            "req-bound-database-ops-template",
            "conv-bound-database-ops-template",
            "user-1",
            Map.of()
        ));

        assertThat(result.success()).isTrue();
        ArgumentCaptor<ToolRuntimeRequest> captor = ArgumentCaptor.forClass(ToolRuntimeRequest.class);
        verify(toolRuntimeService).execute(captor.capture());
        assertThat(captor.getValue().getToolName()).isEqualTo(requestedTool);
        assertThat(captor.getValue().getToolInput().getParameters())
            .containsEntry("finalDecision", "database");
    }

    @Test
    @SuppressWarnings("unchecked")
    void repairsUsingExplicitTemplateSemanticMetadataBeforeNameInference() throws Exception {
        InterpretationPlanRuntime runtime = new InterpretationPlanRuntime(
            mock(ToolRuntimeService.class),
            new InterpretationPlanValidator(),
            mock(InterpretationPlanRuntime.DagExecutionController.class)
        );
        InterpretationPlan.Step step = new InterpretationPlan.Step(
            3,
            "mcp_tool",
            "mcp_chatchat_mcp_server_sql_query_execute",
            Map.of(
                "templateId", "GENERIC_INSTANCE_STATUS",
                "parameters", Map.of("tableName", "customer_label"),
                "parameterProtocol", userQueryParameterProtocol(
                    3, "GENERIC_INSTANCE_STATUS", "customer_label",
                    Map.of("tableName", "customer_label"))
            ),
            List.of(2),
            null,
            null
        );
        Map<Integer, InterpretationPlanRuntime.StepExecution> completed = Map.of(
            2,
            new InterpretationPlanRuntime.StepExecution(
                2,
                "mcp_tool",
                "mcp_chatchat_mcp_server_sql_datasource_template_query",
                true,
                Map.of("templates", List.of(
                    Map.of(
                        "templateId", "GENERIC_INSTANCE_STATUS",
                        "databaseType", "postgresql",
                        "semantic", Map.of(
                            "operation", "INSTANCE_DIAGNOSTIC_QUERY",
                            "targetLevel", "INSTANCE",
                            "dialect", "postgresql"
                        )
                    ),
                    Map.of(
                        "templateId", "PG_CUSTOM_TABLE_METADATA",
                        "databaseType", "postgresql",
                        "category", "maintenance_metadata",
                        "parameterSchema", Map.of("required", List.of("tableName")),
                        "semantic", Map.of(
                            "operation", "TABLE_METADATA_QUERY",
                            "targetLevel", "TABLE",
                            "dialect", "postgresql"
                        )
                    )
                )),
                null,
                null,
                null,
                10
            )
        );
        Method method = InterpretationPlanRuntime.class.getDeclaredMethod(
            "resolvedStepInput",
            InterpretationPlan.Step.class,
            InterpretationPlanRuntime.ExecutionRequest.class,
            Map.class
        );
        method.setAccessible(true);

        Map<String, Object> resolved = (Map<String, Object>) method.invoke(
            runtime,
            step,
            new InterpretationPlanRuntime.ExecutionRequest(
                minimalPlan(step),
                mock(ToolRegistry.class),
                List.of("mcp_chatchat_mcp_server_sql_query_execute"),
                "tenant-1",
                "req-semantic-template-repair",
                "conv-semantic-template-repair",
                "user-1",
                Map.of("originalUserQuery", "Analyze customer_label")
            ),
            completed
        );

        assertThat(resolved)
            .containsEntry("templateId", "PG_CUSTOM_TABLE_METADATA")
            .containsEntry("template", "PG_CUSTOM_TABLE_METADATA");
        Map<String, Object> repair = (Map<String, Object>) resolved.get("runtimeTemplateRepair");
        assertThat(repair)
            .containsEntry("fromTemplateId", "GENERIC_INSTANCE_STATUS")
            .containsEntry("toTemplateId", "PG_CUSTOM_TABLE_METADATA");
    }

    @Test
    void rejectsTableScopedGlobalTemplateWhenDialectCannotBeInferred() throws Exception {
        InterpretationPlanRuntime runtime = new InterpretationPlanRuntime(
            mock(ToolRuntimeService.class),
            new InterpretationPlanValidator(),
            mock(InterpretationPlanRuntime.DagExecutionController.class)
        );
        InterpretationPlan.Step step = new InterpretationPlan.Step(
            3,
            "mcp_tool",
            "mcp_chatchat_mcp_server_sql_query_execute",
            Map.of(
                "templateId", "INSTANCE_STATUS",
                "parameters", Map.of("tableName", "T_AD_DICT_ENTR_SUPN"),
                "parameterProtocol", userQueryParameterProtocol(
                    3, "INSTANCE_STATUS", "T_AD_DICT_ENTR_SUPN",
                    Map.of("tableName", "T_AD_DICT_ENTR_SUPN"))
            ),
            List.of(2),
            null,
            null
        );
        Method method = InterpretationPlanRuntime.class.getDeclaredMethod(
            "resolvedStepInput",
            InterpretationPlan.Step.class,
            InterpretationPlanRuntime.ExecutionRequest.class,
            Map.class
        );
        method.setAccessible(true);

        assertThatThrownBy(() -> method.invoke(
            runtime,
            step,
            new InterpretationPlanRuntime.ExecutionRequest(
                minimalPlan(step),
                mock(ToolRegistry.class),
                List.of("mcp_chatchat_mcp_server_sql_query_execute"),
                "tenant-1",
                "req-template-scope-mismatch",
                "conv-template-scope-mismatch",
                "user-1",
                Map.of("originalUserQuery", "Analyze T_AD_DICT_ENTR_SUPN")
            ),
            Map.of()
        ))
            .hasRootCauseMessage("SQL_TEMPLATE_TARGET_SCOPE_MISMATCH: template INSTANCE_STATUS is not table-scoped but tableName=T_AD_DICT_ENTR_SUPN was provided; planner must select a dialect-specific *_TABLE_METADATA template.");
    }

    @Test
    @SuppressWarnings("unchecked")
    void doesNotBindJavaProcessCheckToIoTemplateFromOneGenericToken() throws Exception {
        InterpretationPlanRuntime runtime = new InterpretationPlanRuntime(
            mock(ToolRuntimeService.class),
            new InterpretationPlanValidator(),
            mock(InterpretationPlanRuntime.DagExecutionController.class)
        );
        List<InterpretationPlan.DiagnosticCheck> checks = List.of(
            new InterpretationPlan.DiagnosticCheck(
                "resource_usage", "resource_usage", "host_resource", true, 1, List.of(3)),
            new InterpretationPlan.DiagnosticCheck(
                "java_process", "process_inventory", "java_process", true, 2, List.of(3)),
            new InterpretationPlan.DiagnosticCheck(
                "container_status", "container_inventory", "docker_container", true, 3, List.of(3))
        );
        List<Map<String, Object>> mismatchedTemplates = List.of(
            Map.of("templateId", "CHECK_MEMORY", "name", "Memory status", "description", "Read memory usage"),
            Map.of("templateId", "CHECK_IO_STATUS", "name", "IO status",
                "description", "Read disk IO utilization and process IO statistics"),
            Map.of("templateId", "CHECK_DOCKER_CONTAINERS", "name", "Docker containers",
                "description", "Read docker container inventory")
        );
        Method method = InterpretationPlanRuntime.class.getDeclaredMethod(
            "diagnosticTemplateAssignments", List.class, List.class, Map.class);
        method.setAccessible(true);

        Map<Integer, Integer> mismatched = (Map<Integer, Integer>) method.invoke(
            runtime, checks, mismatchedTemplates, Map.of("java_process", "CHECK_PROCESS"));

        assertThat(mismatched)
            .containsEntry(0, 0)
            .containsEntry(2, 2)
            .doesNotContainKey(1);

        Map<Integer, Integer> mismatchedWithoutHints = (Map<Integer, Integer>) method.invoke(
            runtime, checks, mismatchedTemplates, Map.of());
        assertThat(mismatchedWithoutHints)
            .containsEntry(0, 0)
            .containsEntry(2, 2)
            .doesNotContainKey(1);

        Map<Integer, Integer> wrongExplicitHint = (Map<Integer, Integer>) method.invoke(
            runtime, checks, mismatchedTemplates, Map.of("java_process", "CHECK_IO_STATUS"));
        assertThat(wrongExplicitHint)
            .as("an explicit binding must not override a semantic contradiction")
            .doesNotContainEntry(1, 1);

        List<Map<String, Object>> matchedTemplates = List.of(
            mismatchedTemplates.get(0),
            Map.of("templateId", "CHECK_JAVA_PROCESS", "name", "Java process",
                "description", "Read Java process inventory"),
            mismatchedTemplates.get(2)
        );
        Map<Integer, Integer> matched = (Map<Integer, Integer>) method.invoke(
            runtime, checks, matchedTemplates, Map.of("java_process", "CHECK_PROCESS"));
        assertThat(matched).containsAllEntriesOf(Map.of(0, 0, 1, 1, 2, 2));
    }

    @Test
    @SuppressWarnings("unchecked")
    void diagnosticTemplateHintsPreferResolvedBindingsOverPlannerPlaceholders() throws Exception {
        InterpretationPlanRuntime runtime = new InterpretationPlanRuntime(
            mock(ToolRuntimeService.class),
            new InterpretationPlanValidator(),
            mock(InterpretationPlanRuntime.DagExecutionController.class)
        );
        Map<String, Object> planned = Map.of("calls", List.of(
            Map.of("callId", "resource_usage", "arguments", Map.of(
                "template", "{{bindings.templateResource}}")),
            Map.of("callId", "java_process", "arguments", Map.of(
                "template", "{{bindings.templateJava}}"))
        ));
        Map<String, Object> resolved = Map.of("calls", List.of(
            Map.of("callId", "resource_usage", "requiredMetrics", List.of("memory", "cpu"),
                "arguments", Map.of("template", "CHECK_MEMORY")),
            Map.of("callId", "java_process", "purpose", "process inventory",
                "arguments", Map.of("template", "CHECK_PROCESS"))
        ));
        Method method = InterpretationPlanRuntime.class.getDeclaredMethod(
            "resolvedDiagnosticTemplateHints", Map.class, Map.class);
        method.setAccessible(true);

        Map<String, String> hints = (Map<String, String>) method.invoke(runtime, planned, resolved);

        assertThat(hints).containsExactlyInAnyOrderEntriesOf(Map.of(
            "resource_usage", "CHECK_MEMORY",
            "java_process", "CHECK_PROCESS"
        ));

        Method contextMethod = InterpretationPlanRuntime.class.getDeclaredMethod(
            "resolvedDiagnosticCallContexts", Map.class, Map.class);
        contextMethod.setAccessible(true);
        Map<String, String> contexts = (Map<String, String>) contextMethod.invoke(runtime, planned, resolved);
        assertThat(contexts.get("resource_usage")).contains("memory", "cpu");
        assertThat(contexts.get("java_process")).contains("process inventory");
    }

    @Test
    void prefersExpandedReviewedTemplateSetOverOneToOneDiagnosticGuessing() throws Exception {
        InterpretationPlanRuntime runtime = new InterpretationPlanRuntime(
            mock(ToolRuntimeService.class),
            new InterpretationPlanValidator(),
            mock(InterpretationPlanRuntime.DagExecutionController.class)
        );
        List<InterpretationPlan.DiagnosticCheck> checks = List.of(
            new InterpretationPlan.DiagnosticCheck(
                "runtime_state", "runtime_state", "availability", true, 1, List.of(3)),
            new InterpretationPlan.DiagnosticCheck(
                "resource_state", "resource_state", "capacity", true, 2, List.of(3))
        );
        InterpretationPlanRuntime.StepExecution reviewedDiscovery =
            new InterpretationPlanRuntime.StepExecution(
                2,
                "mcp_tool",
                "mcp_tenant_ssh_template_query",
                true,
                Map.of("templates", List.of(
                    Map.of("templateId", "TEMPLATE_ALPHA"),
                    Map.of("templateId", "TEMPLATE_BETA"),
                    Map.of("templateId", "TEMPLATE_GAMMA")
                )),
                null,
                null,
                null,
                10,
                Map.of("semanticCandidateReviewSatisfied", true)
            );
        Method method = InterpretationPlanRuntime.class.getDeclaredMethod(
            "shouldUseReviewedTemplateBatch", List.class, Map.class);
        method.setAccessible(true);

        assertThat((boolean) method.invoke(runtime, checks, Map.of(2, reviewedDiscovery))).isTrue();

        InterpretationPlanRuntime.StepExecution unreviewedDiscovery =
            new InterpretationPlanRuntime.StepExecution(
                reviewedDiscovery.stepId(),
                reviewedDiscovery.actionType(),
                reviewedDiscovery.toolName(),
                reviewedDiscovery.success(),
                reviewedDiscovery.output(),
                reviewedDiscovery.errorMessage(),
                reviewedDiscovery.toolExecution(),
                reviewedDiscovery.finalAnswer(),
                reviewedDiscovery.durationMs(),
                Map.of("semanticCandidateReviewSatisfied", false)
            );
        assertThat((boolean) method.invoke(runtime, checks, Map.of(2, unreviewedDiscovery))).isFalse();
    }

    @Test
    @SuppressWarnings("unchecked")
    void correctsTemplateDiscoveryAssetFromEvidenceBackedReviewSelection() throws Exception {
        InterpretationPlanRuntime runtime = new InterpretationPlanRuntime(
            mock(ToolRuntimeService.class),
            new InterpretationPlanValidator(),
            mock(InterpretationPlanRuntime.DagExecutionController.class)
        );
        InterpretationPlan.Step step = new InterpretationPlan.Step(
            2,
            "mcp_tool",
            "mcp_chatchat_mcp_server_ssh_template_query",
            Map.of(),
            List.of(1),
            null,
            null
        );
        String selectedAssetId = "asset-cdh";
        InterpretationPlanRuntime.StepExecution assetDiscovery =
            new InterpretationPlanRuntime.StepExecution(
                1,
                "mcp_tool",
                "mcp_chatchat_mcp_server_ssh_asset_query",
                true,
                Map.of("assets", List.of(
                    Map.of("asset", Map.of(
                        "id", "asset-docker",
                        "name", "Docker database host",
                        "environment", "DEV"
                    )),
                    Map.of("asset", Map.of(
                        "id", selectedAssetId,
                        "name", "LiveData scheduler CDH",
                        "displayName", "LiveData scheduler CDH SSH",
                        "toolName", "ssh_cdh",
                        "environment", "DEV"
                    ))
                )),
                null,
                null,
                null,
                10,
                Map.of("nextActions", List.of(Map.of(
                    "tool", "ssh_command_execute",
                    "input_changes", Map.of(
                        "assetId", selectedAssetId,
                        "templateId", "CHECK_IO_STATUS"
                    )
                )))
            );
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("filters", new LinkedHashMap<>(Map.of(
            "intent", "disk IO",
            "assetName", "Docker database host",
            "env", "DEV"
        )));
        Method method = InterpretationPlanRuntime.class.getDeclaredMethod(
            "normalizeDiscoveryRoutingInput",
            InterpretationPlan.Step.class,
            InterpretationPlanRuntime.ExecutionRequest.class,
            Map.class,
            Map.class
        );
        method.setAccessible(true);

        method.invoke(
            runtime,
            step,
            new InterpretationPlanRuntime.ExecutionRequest(
                minimalPlan(step),
                mock(ToolRegistry.class),
                List.of(step.toolName()),
                "tenant-1",
                "req-asset-drift",
                "conv-asset-drift",
                "user-1",
                Map.of("originalUserQuery", "analyze disk IO")
            ),
            Map.of(1, assetDiscovery),
            input
        );

        Map<String, Object> filters = (Map<String, Object>) input.get("filters");
        assertThat(filters)
            .containsEntry("assetName", "LiveData scheduler CDH")
            .containsEntry("env", "DEV");
    }

    @Test
    @SuppressWarnings("unchecked")
    void injectsRoutingTraceForDiscoveryToolWhenPlannerOmittedIt() throws Exception {
        InterpretationPlanRuntime runtime = new InterpretationPlanRuntime(
            mock(ToolRuntimeService.class),
            new InterpretationPlanValidator(),
            mock(InterpretationPlanRuntime.DagExecutionController.class)
        );
        InterpretationPlan.Step step = new InterpretationPlan.Step(
            1,
            "mcp_tool",
            "mcp_chatchat_mcp_server_sql_datasource_asset_query",
            Map.of(
                "candidates", List.of(Map.of("targetKind", "database", "confidence", 0.9)),
                "finalDecision", "database",
                "filters", Map.of("assetName", "test_mysql_database"),
                "limit", 10
            ),
            List.of(),
            null,
            null
        );
        InterpretationPlan plan = new InterpretationPlan(
            "1.0",
            new InterpretationPlan.Intent("sql_query", "Analyze InnoDB", "medium"),
            context(),
            new InterpretationPlan.Plan(List.of(
                step,
                new InterpretationPlan.Step(2, "final_answer", "", Map.of("answer", "done"), List.of(1), null, null)
            )),
            new InterpretationPlan.ExecutionPolicy(
                2,
                false,
                List.of("mcp_chatchat_mcp_server_sql_datasource_asset_query"),
                List.of(),
                30000
            ),
            review()
        );
        InterpretationPlanRuntime.ExecutionRequest request = new InterpretationPlanRuntime.ExecutionRequest(
            plan,
            mock(ToolRegistry.class),
            List.of("mcp_chatchat_mcp_server_sql_datasource_asset_query"),
            "tenant-1",
            "req-routing-trace",
            "conv-routing-trace",
            "user-1",
            Map.of("executionTraceId", "trace-runtime-1")
        );
        Method method = InterpretationPlanRuntime.class.getDeclaredMethod(
            "resolvedStepInput",
            InterpretationPlan.Step.class,
            InterpretationPlanRuntime.ExecutionRequest.class,
            Map.class
        );
        method.setAccessible(true);

        Map<String, Object> resolved = (Map<String, Object>) method.invoke(runtime, step, request, Map.of());

        assertThat(resolved).containsEntry("filtersSchemaVersion", "target_filters.v1");
        assertThat(resolved.get("trace")).isInstanceOf(Map.class);
        Map<String, Object> trace = (Map<String, Object>) resolved.get("trace");
        assertThat(trace)
            .containsEntry("schemaVersion", "routing_trace.v1")
            .containsEntry("source", "interpretation_plan_runtime")
            .containsEntry("executionTraceId", "trace-runtime-1")
            .containsEntry("stepId", 1);
    }

    @Test
    @SuppressWarnings("unchecked")
    void repairsUnsupportedLogicalFiltersFromPublishedMcpContract() throws Exception {
        String toolName = "mcp_chatchat_mcp_server_database_asset_search";
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.getToolMetadata(toolName)).thenReturn(ToolMetadata.builder()
            .id(toolName)
            .metadata(Map.of("mcpToolMeta", Map.of(
                "routingProtocol", Map.of("allowedFilterFields", List.of(
                    "assetname", "intent", "queryterms", "retrievalsignals"
                )),
                "forbiddenConcreteTargetFields", List.of("jdbcUrl", "datasourceId")
            )))
            .build());
        InterpretationPlanRuntime runtime = new InterpretationPlanRuntime(
            mock(ToolRuntimeService.class),
            new InterpretationPlanValidator(),
            mock(InterpretationPlanRuntime.DagExecutionController.class)
        );
        InterpretationPlan.Step step = new InterpretationPlan.Step(
            1,
            "mcp_tool",
            toolName,
            Map.of("filters", Map.of(
                "business_line", "证券",
                "jdbcUrl", "jdbc:mysql://not-forwarded"
            )),
            List.of(),
            null,
            null
        );
        InterpretationPlan plan = new InterpretationPlan(
            "1.0",
            new InterpretationPlan.Intent("data_query", "分析证券持仓市值", "low"),
            context(),
            new InterpretationPlan.Plan(List.of(step)),
            new InterpretationPlan.ExecutionPolicy(1, false, List.of(toolName), List.of(), 30000),
            review()
        );
        InterpretationPlanRuntime.ExecutionRequest request = new InterpretationPlanRuntime.ExecutionRequest(
            plan, toolRegistry, List.of(toolName), "tenant-1", "req-filter-contract",
            "conv-filter-contract", "user-1", Map.of()
        );
        Method method = InterpretationPlanRuntime.class.getDeclaredMethod(
            "resolvedStepInput",
            InterpretationPlan.Step.class,
            InterpretationPlanRuntime.ExecutionRequest.class,
            Map.class
        );
        method.setAccessible(true);

        Map<String, Object> resolved = (Map<String, Object>) method.invoke(runtime, step, request, Map.of());
        Map<String, Object> filters = (Map<String, Object>) resolved.get("filters");

        assertThat(filters).doesNotContainKeys("business_line", "jdbcUrl");
        assertThat((List<String>) filters.get("retrievalSignals"))
            .contains("business_line:证券", "证券")
            .noneMatch(value -> value.contains("jdbc:mysql"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void compilesMcpDiscoveryArgumentsAgainstPublishedInputSchemaInsteadOfQueryPlaceholder() throws Exception {
        String toolName = "mcp_chatchat_mcp_server_database_asset_search";
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.getToolMetadata(toolName)).thenReturn(ToolMetadata.builder()
            .id(toolName)
            // MCP bridges historically exposed this lossy placeholder parameter.
            .parameters(List.of(com.chatchat.common.tool.ToolParameter.builder()
                .name("query").type("string").required(false).build()))
            .metadata(Map.of(
                "inputSchema", Map.of(
                    "type", "object",
                    "properties", Map.of(
                        "filters", Map.of("type", "object", "additionalProperties", true),
                        "trace", Map.of("type", "object", "additionalProperties", true),
                        "filtersSchemaVersion", Map.of("type", "string")
                    ),
                    "required", List.of("filters", "trace"),
                    "additionalProperties", false
                ),
                "mcpToolMeta", Map.of(
                    "routingProtocol", Map.of("allowedFilterFields", List.of("assetname", "env"))
                )
            ))
            .build());
        InterpretationPlanRuntime runtime = new InterpretationPlanRuntime(
            mock(ToolRuntimeService.class),
            new InterpretationPlanValidator(),
            mock(InterpretationPlanRuntime.DagExecutionController.class)
        );
        InterpretationPlan.Step step = new InterpretationPlan.Step(
            1, "mcp_tool", toolName,
            Map.of("filters", Map.of("assetName", "risk-oracle", "env", "DEV")),
            List.of(), null, null
        );
        InterpretationPlan plan = new InterpretationPlan(
            "1.0", new InterpretationPlan.Intent("data_query", "analyze risk-oracle", "low"),
            context(), new InterpretationPlan.Plan(List.of(step)),
            new InterpretationPlan.ExecutionPolicy(1, false, List.of(toolName), List.of(), 30000), review()
        );
        InterpretationPlanRuntime.ExecutionRequest request = new InterpretationPlanRuntime.ExecutionRequest(
            plan, toolRegistry, List.of(toolName), "tenant-1", "req-real-schema",
            "conv-real-schema", "user-1", Map.of("agentRuntimeEnvironment", "DEV")
        );
        Method method = InterpretationPlanRuntime.class.getDeclaredMethod(
            "resolvedStepInput", InterpretationPlan.Step.class,
            InterpretationPlanRuntime.ExecutionRequest.class, Map.class
        );
        method.setAccessible(true);

        Map<String, Object> resolved = (Map<String, Object>) method.invoke(runtime, step, request, Map.of());
        Map<String, Object> filters = (Map<String, Object>) resolved.get("filters");

        assertThat(filters).containsEntry("assetName", "risk-oracle").containsEntry("env", "DEV");
        assertThat(resolved).doesNotContainKey("query");
    }

    @Test
    @SuppressWarnings("unchecked")
    void recoversRequiredAssetEnvironmentFromAgentContextWhenSummaryProjectionOmitsIt() {
        String assetTool = "mcp_chatchat_mcp_server_api_asset_query";
        String templateTool = "mcp_chatchat_mcp_server_api_template_query";
        ToolRegistry registry = mock(ToolRegistry.class);
        when(registry.hasTool(any())).thenReturn(true);
        when(registry.getToolMetadata(any())).thenAnswer(invocation ->
            ToolMetadata.builder().id(invocation.getArgument(0)).riskLevel("low").build());
        AtomicReference<Map<String, Object>> templateInput = new AtomicReference<>();
        ToolRuntimeService service = mock(ToolRuntimeService.class);
        when(service.execute(any())).thenAnswer(invocation -> {
            ToolRuntimeRequest toolRequest = invocation.getArgument(0);
            if (assetTool.equals(toolRequest.getToolName())) {
                return new ToolRuntimeExecution(ToolOutput.success(Map.of(
                    "schemaVersion", "tool_result_summary.v1",
                    "summaryTruncated", true,
                    "resultPresent", true,
                    "preview", "{assets=[{asset={name=customer-api, ... truncated}}]}",
                    "routingProjection", Map.of("assets", List.of(Map.of(
                        "asset", Map.of("id", "asset-1", "name", "customer-api")
                    )))
                )), ToolMetadata.builder().id(assetTool).build(), null, "success", Map.of());
            }
            templateInput.set(toolRequest.getToolInput().getParameters());
            return new ToolRuntimeExecution(ToolOutput.success(Map.of(
                "templates", List.of(Map.of("templateId", "customer-overview"))
            )), ToolMetadata.builder().id(templateTool).build(), null, "success", Map.of());
        });
        InterpretationPlan plan = new InterpretationPlan(
            "1.0",
            new InterpretationPlan.Intent("data_query", "customer overview", "low"),
            context(),
            new InterpretationPlan.Plan(
                List.of(
                    new InterpretationPlan.Step(1, "mcp_tool", assetTool,
                        Map.of("filters", Map.of("intent", "customer overview")), List.of(), null, null),
                    new InterpretationPlan.Step(2, "mcp_tool", templateTool,
                        Map.of("filters", Map.of("intent", "customer overview")), List.of(1), null, null),
                    new InterpretationPlan.Step(3, "final_answer", "", Map.of("answer", "done"),
                        List.of(2), null, null)
                ),
                List.of(new InterpretationPlan.EdgeContract(
                    1, 2, "assets[0].asset.environment", "string", true)),
                List.of(new InterpretationPlan.DependencyContract(
                    1, 2, true, null, "template discovery uses Agent environment", "stop")),
                List.of(new InterpretationPlan.Binding(
                    1, "$.assets[0].asset.environment", 2, "filters.env", "jsonpath", true)),
                null
            ),
            new InterpretationPlan.ExecutionPolicy(
                3, false, List.of(assetTool, templateTool), List.of(), 30_000),
            review()
        );
        InterpretationPlanRuntime runtime = new InterpretationPlanRuntime(
            service, new InterpretationPlanValidator(),
            scriptedController(List.of(List.of(1), List.of(2), List.of(3)))
        );

        InterpretationPlanRuntime.ExecutionResult result = runtime.execute(
            new InterpretationPlanRuntime.ExecutionRequest(
                plan, registry, List.of(assetTool, templateTool), "tenant-1", "req-env-recovery",
                "conv-env-recovery", "user-1", Map.of("agentRuntimeEnvironment", "DEV")
            )
        );

        assertThat(result.success()).as(result.errorMessage()).isTrue();
        assertThat((Map<String, Object>) templateInput.get().get("filters"))
            .containsEntry("env", "DEV");
        assertThat(result.steps()).filteredOn(step -> Integer.valueOf(1).equals(step.stepId()))
            .singleElement()
            .satisfies(step -> assertThat(step.metadata())
                .containsEntry("eventKind", "DAG_REPAIR")
                .containsEntry("eventState", "APPLIED")
                .containsKey("deterministicContractRepairs"));
        assertThat(result.steps()).noneMatch(step -> step.errorMessage() != null
            && step.errorMessage().contains("EDGE_CONTRACT_FAILED"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void compilesLooseTemplateDiscoveryIntentWithoutLosingDiscoveredTemplateIds() throws Exception {
        String toolName = "mcp_chatchat_mcp_server_api_template_query";
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.getToolMetadata(toolName)).thenReturn(ToolMetadata.builder()
            .id(toolName)
            .metadata(Map.of(
                "inputSchema", Map.of(
                    "type", "object",
                    "properties", Map.of(
                        "filters", Map.of("type", "object", "additionalProperties", true),
                        "trace", Map.of("type", "object", "additionalProperties", true),
                        "filtersSchemaVersion", Map.of("type", "string"),
                        "templateIds", Map.of("type", "array", "items", Map.of("type", "string")),
                        "limit", Map.of("type", "integer")
                    ),
                    "required", List.of("filters"),
                    "additionalProperties", false
                ),
                "mcpToolMeta", Map.of("routingProtocol", Map.of(
                    "allowedFilterFields", List.of("intent", "queryterms", "retrievalsignals")
                ))
            ))
            .build());
        InterpretationPlanRuntime runtime = new InterpretationPlanRuntime(
            mock(ToolRuntimeService.class), new InterpretationPlanValidator(),
            mock(InterpretationPlanRuntime.DagExecutionController.class));
        List<String> candidateIds = List.of(
            "livedata_hisJyZjmxls", "livedata_EvtRealOptCptlJour",
            "livedata_EvtRealSecuMargCptlJour");
        InterpretationPlan.Step step = new InterpretationPlan.Step(
            1, "mcp_tool", toolName,
            Map.of("intent", "查询客户资金流水", "templateIds", candidateIds, "limit", 10),
            List.of(), null, null);
        InterpretationPlan plan = new InterpretationPlan(
            "1.0", new InterpretationPlan.Intent("data_query", "查询客户资金流水", "low"),
            context(), new InterpretationPlan.Plan(List.of(step)),
            new InterpretationPlan.ExecutionPolicy(1, false, List.of(toolName), List.of(), 30000), review());
        InterpretationPlanRuntime.ExecutionRequest request = new InterpretationPlanRuntime.ExecutionRequest(
            plan, toolRegistry, List.of(toolName), "tenant-1", "req-template-scope",
            "conv-template-scope", "user-1", Map.of("originalUserQuery", "查询客户资金流水"));
        Method method = InterpretationPlanRuntime.class.getDeclaredMethod(
            "resolvedStepInput", InterpretationPlan.Step.class,
            InterpretationPlanRuntime.ExecutionRequest.class, Map.class);
        method.setAccessible(true);

        Map<String, Object> resolved = (Map<String, Object>) method.invoke(runtime, step, request, Map.of());

        assertThat(resolved.get("templateIds")).isEqualTo(candidateIds);
        assertThat((Map<String, Object>) resolved.get("filters"))
            .containsEntry("intent", "查询客户资金流水");
        assertThat(resolved).containsKeys("trace", "filtersSchemaVersion");
    }

    @Test
    @SuppressWarnings("unchecked")
    void injectsRetrievalIntentForDatabaseDiscoveryWhenPlannerOmittedFilter() throws Exception {
        InterpretationPlanRuntime runtime = new InterpretationPlanRuntime(
            mock(ToolRuntimeService.class),
            new InterpretationPlanValidator(),
            mock(InterpretationPlanRuntime.DagExecutionController.class)
        );
        InterpretationPlan.Step step = new InterpretationPlan.Step(
            1,
            "mcp_tool",
            "mcp_chatchat_mcp_server_sql_datasource_asset_query",
            Map.of(
                "candidates", List.of(Map.of("targetKind", "database", "confidence", 0.85)),
                "finalDecision", "database",
                "filters", Map.of(),
                "limit", 10
            ),
            List.of(),
            null,
            null
        );
        InterpretationPlan plan = new InterpretationPlan(
            "1.0",
            new InterpretationPlan.Intent("data_query", "test_mysql\u6570\u636e\u5e93 connections", "low"),
            new InterpretationPlan.Context(
                List.of("User mentioned test_mysql\u6570\u636e\u5e93"),
                List.of(),
                List.of(),
                List.of()
            ),
            new InterpretationPlan.Plan(List.of(
                step,
                new InterpretationPlan.Step(2, "final_answer", "", Map.of("answer", "done"), List.of(1), null, null)
            )),
            new InterpretationPlan.ExecutionPolicy(
                2,
                false,
                List.of("mcp_chatchat_mcp_server_sql_datasource_asset_query"),
                List.of(),
                30000
            ),
            review()
        );
        InterpretationPlanRuntime.ExecutionRequest request = new InterpretationPlanRuntime.ExecutionRequest(
            plan,
            mock(ToolRegistry.class),
            List.of("mcp_chatchat_mcp_server_sql_datasource_asset_query"),
            "tenant-1",
            "req-routing-asset-name",
            "conv-routing-asset-name",
            "user-1",
            Map.of("executionTraceId", "trace-runtime-asset")
        );
        Method method = InterpretationPlanRuntime.class.getDeclaredMethod(
            "resolvedStepInput",
            InterpretationPlan.Step.class,
            InterpretationPlanRuntime.ExecutionRequest.class,
            Map.class
        );
        method.setAccessible(true);

        Map<String, Object> resolved = (Map<String, Object>) method.invoke(runtime, step, request, Map.of());

        Map<String, Object> filters = (Map<String, Object>) resolved.get("filters");
        assertThat(filters)
            .doesNotContainKey("assetName")
            .containsEntry("intent", "test_mysql\u6570\u636e\u5e93 connections")
            .containsEntry("goal", "test_mysql\u6570\u636e\u5e93 connections");
        assertThat((List<String>) filters.get("queryTerms")).containsExactly("test_mysql\u6570\u636e\u5e93 connections");
        assertThat(resolved.get("trace")).isInstanceOf(Map.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void dropsPlannerEnvironmentExtractedFromAssetProperNameAndRestoresUserQuery() throws Exception {
        InterpretationPlanRuntime runtime = new InterpretationPlanRuntime(
            mock(ToolRuntimeService.class),
            new InterpretationPlanValidator(),
            mock(InterpretationPlanRuntime.DagExecutionController.class)
        );
        InterpretationPlan.Step step = new InterpretationPlan.Step(
            1,
            "mcp_tool",
            "mcp_chatchat_mcp_server_database_asset_search",
            Map.of(
                "finalDecision", "database",
                "filters", Map.of("env", "\u6d4b\u8bd5"),
                "limit", 20
            ),
            List.of(),
            null,
            null
        );
        String userQuery = "\u5206\u6790248\u6d4b\u8bd5\u6570\u636e\u5e93";
        InterpretationPlan plan = new InterpretationPlan(
            "1.0",
            new InterpretationPlan.Intent("data_query", userQuery, "low"),
            context(),
            new InterpretationPlan.Plan(List.of(
                step,
                new InterpretationPlan.Step(2, "final_answer", "", Map.of("answer", "done"), List.of(1), null, null)
            )),
            new InterpretationPlan.ExecutionPolicy(
                2,
                false,
                List.of("mcp_chatchat_mcp_server_database_asset_search"),
                List.of(),
                30000
            ),
            review()
        );
        InterpretationPlanRuntime.ExecutionRequest request = new InterpretationPlanRuntime.ExecutionRequest(
            plan,
            mock(ToolRegistry.class),
            List.of("mcp_chatchat_mcp_server_database_asset_search"),
            "tenant-1",
            "req-env-proper-name",
            "conv-env-proper-name",
            "user-1",
            Map.of("originalUserQuery", userQuery)
        );
        Method method = InterpretationPlanRuntime.class.getDeclaredMethod(
            "resolvedStepInput",
            InterpretationPlan.Step.class,
            InterpretationPlanRuntime.ExecutionRequest.class,
            Map.class
        );
        method.setAccessible(true);

        Map<String, Object> resolved = (Map<String, Object>) method.invoke(runtime, step, request, Map.of());

        Map<String, Object> filters = (Map<String, Object>) resolved.get("filters");
        assertThat(filters)
            .doesNotContainKeys("env", "environment")
            .containsEntry("intent", userQuery)
            .containsEntry("goal", userQuery);
        assertThat((List<String>) filters.get("queryTerms")).containsExactly(userQuery);
    }

    @Test
    @SuppressWarnings("unchecked")
    void keepsCanonicalPlannerEnvironmentWhenUserExplicitlySpecifiedIt() throws Exception {
        InterpretationPlanRuntime runtime = new InterpretationPlanRuntime(
            mock(ToolRuntimeService.class),
            new InterpretationPlanValidator(),
            mock(InterpretationPlanRuntime.DagExecutionController.class)
        );
        InterpretationPlan.Step step = new InterpretationPlan.Step(
            1,
            "mcp_tool",
            "mcp_chatchat_mcp_server_database_asset_search",
            Map.of("finalDecision", "database", "filters", Map.of("env", "test")),
            List.of(),
            null,
            null
        );
        String userQuery = "\u5728TEST\u73af\u5883\u5206\u6790248\u6570\u636e\u5e93";
        InterpretationPlan plan = new InterpretationPlan(
            "1.0",
            new InterpretationPlan.Intent("data_query", userQuery, "low"),
            context(),
            new InterpretationPlan.Plan(List.of(step)),
            new InterpretationPlan.ExecutionPolicy(
                1,
                false,
                List.of("mcp_chatchat_mcp_server_database_asset_search"),
                List.of(),
                30000
            ),
            review()
        );
        InterpretationPlanRuntime.ExecutionRequest request = new InterpretationPlanRuntime.ExecutionRequest(
            plan,
            mock(ToolRegistry.class),
            List.of("mcp_chatchat_mcp_server_database_asset_search"),
            "tenant-1",
            "req-explicit-env",
            "conv-explicit-env",
            "user-1",
            Map.of("originalUserQuery", userQuery)
        );
        Method method = InterpretationPlanRuntime.class.getDeclaredMethod(
            "resolvedStepInput",
            InterpretationPlan.Step.class,
            InterpretationPlanRuntime.ExecutionRequest.class,
            Map.class
        );
        method.setAccessible(true);

        Map<String, Object> resolved = (Map<String, Object>) method.invoke(runtime, step, request, Map.of());

        Map<String, Object> filters = (Map<String, Object>) resolved.get("filters");
        assertThat(filters.get("env")).isEqualTo("TEST");
    }

    @Test
    @SuppressWarnings("unchecked")
    void configuredAgentEnvironmentOverridesPlannerDiscoveryEnvironment() throws Exception {
        InterpretationPlanRuntime runtime = new InterpretationPlanRuntime(
            mock(ToolRuntimeService.class),
            new InterpretationPlanValidator(),
            mock(InterpretationPlanRuntime.DagExecutionController.class)
        );
        InterpretationPlan.Step step = new InterpretationPlan.Step(
            1,
            "mcp_tool",
            "mcp_chatchat_mcp_server_database_asset_search",
            Map.of("finalDecision", "database", "filters", Map.of("env", "TEST")),
            List.of(),
            null,
            null
        );
        String userQuery = "在TEST环境分析数据库";
        InterpretationPlan plan = new InterpretationPlan(
            "1.0",
            new InterpretationPlan.Intent("data_query", userQuery, "low"),
            context(),
            new InterpretationPlan.Plan(List.of(step)),
            new InterpretationPlan.ExecutionPolicy(
                1,
                false,
                List.of("mcp_chatchat_mcp_server_database_asset_search"),
                List.of(),
                30000
            ),
            review()
        );
        InterpretationPlanRuntime.ExecutionRequest request = new InterpretationPlanRuntime.ExecutionRequest(
            plan,
            mock(ToolRegistry.class),
            List.of("mcp_chatchat_mcp_server_database_asset_search"),
            "tenant-1",
            "req-agent-env",
            "conv-agent-env",
            "user-1",
            Map.of(
                "originalUserQuery", userQuery,
                "agentRuntimeEnvironment", "DEV"
            )
        );
        Method method = InterpretationPlanRuntime.class.getDeclaredMethod(
            "resolvedStepInput",
            InterpretationPlan.Step.class,
            InterpretationPlanRuntime.ExecutionRequest.class,
            Map.class
        );
        method.setAccessible(true);

        Map<String, Object> resolved = (Map<String, Object>) method.invoke(runtime, step, request, Map.of());

        assertThat((Map<String, Object>) resolved.get("filters")).containsEntry("env", "DEV");
    }

    @Test
    @SuppressWarnings("unchecked")
    void configuredAgentEnvironmentOverridesSqlExecutionEnvironment() throws Exception {
        InterpretationPlanRuntime runtime = new InterpretationPlanRuntime(
            mock(ToolRuntimeService.class),
            new InterpretationPlanValidator(),
            mock(InterpretationPlanRuntime.DagExecutionController.class)
        );
        InterpretationPlan.Step step = new InterpretationPlan.Step(
            1,
            "mcp_tool",
            "mcp_chatchat_mcp_server_sql_query_execute",
            Map.of(
                "templateId", "MYSQL_STATUS",
                "executionContext", Map.of("assetName", "248-test-db", "env", "TEST")
            ),
            List.of(),
            null,
            null
        );
        InterpretationPlan plan = new InterpretationPlan(
            "1.0",
            new InterpretationPlan.Intent("data_query", "分析数据库", "low"),
            context(),
            new InterpretationPlan.Plan(List.of(step)),
            new InterpretationPlan.ExecutionPolicy(
                1,
                false,
                List.of("mcp_chatchat_mcp_server_sql_query_execute"),
                List.of(),
                30000
            ),
            review()
        );
        InterpretationPlanRuntime.ExecutionRequest request = new InterpretationPlanRuntime.ExecutionRequest(
            plan,
            mock(ToolRegistry.class),
            List.of("mcp_chatchat_mcp_server_sql_query_execute"),
            "tenant-1",
            "req-agent-sql-env",
            "conv-agent-sql-env",
            "user-1",
            Map.of("agentRuntimeEnvironment", "DEV")
        );
        Method method = InterpretationPlanRuntime.class.getDeclaredMethod(
            "resolvedStepInput",
            InterpretationPlan.Step.class,
            InterpretationPlanRuntime.ExecutionRequest.class,
            Map.class
        );
        method.setAccessible(true);

        Map<String, Object> resolved = (Map<String, Object>) method.invoke(runtime, step, request, Map.of());

        assertThat((Map<String, Object>) resolved.get("executionContext"))
            .containsEntry("assetName", "248-test-db")
            .containsEntry("env", "DEV");
    }

    @Test
    @SuppressWarnings("unchecked")
    void injectsRetrievalIntentForSshDiscoveryWhenPlannerOmittedFilter() throws Exception {
        InterpretationPlanRuntime runtime = new InterpretationPlanRuntime(
            mock(ToolRuntimeService.class),
            new InterpretationPlanValidator(),
            mock(InterpretationPlanRuntime.DagExecutionController.class)
        );
        InterpretationPlan.Step step = new InterpretationPlan.Step(
            1,
            "mcp_tool",
            "mcp_chatchat_mcp_server_ssh_asset_query",
            Map.of(
                "candidates", List.of(Map.of("targetKind", "host", "confidence", 0.85)),
                "finalDecision", "host",
                "filters", Map.of(),
                "limit", 10
            ),
            List.of(),
            null,
            null
        );
        InterpretationPlan plan = new InterpretationPlan(
            "1.0",
            new InterpretationPlan.Intent("system_operation", "分析 MySQL服务器 管理进程信息", "low"),
            context(),
            new InterpretationPlan.Plan(List.of(
                step,
                new InterpretationPlan.Step(2, "final_answer", "", Map.of("answer", "done"), List.of(1), null, null)
            )),
            new InterpretationPlan.ExecutionPolicy(
                2,
                false,
                List.of("mcp_chatchat_mcp_server_ssh_asset_query"),
                List.of(),
                30000
            ),
            review()
        );
        InterpretationPlanRuntime.ExecutionRequest request = new InterpretationPlanRuntime.ExecutionRequest(
            plan,
            mock(ToolRegistry.class),
            List.of("mcp_chatchat_mcp_server_ssh_asset_query"),
            "tenant-1",
            "req-routing-ssh-asset-name",
            "conv-routing-ssh-asset-name",
            "user-1",
            Map.of("executionTraceId", "trace-runtime-ssh-asset")
        );
        Method method = InterpretationPlanRuntime.class.getDeclaredMethod(
            "resolvedStepInput",
            InterpretationPlan.Step.class,
            InterpretationPlanRuntime.ExecutionRequest.class,
            Map.class
        );
        method.setAccessible(true);

        Map<String, Object> resolved = (Map<String, Object>) method.invoke(runtime, step, request, Map.of());

        Map<String, Object> filters = (Map<String, Object>) resolved.get("filters");
        assertThat(filters)
            .doesNotContainKey("assetName")
            .containsEntry("intent", "分析 MySQL服务器 管理进程信息")
            .containsEntry("goal", "分析 MySQL服务器 管理进程信息");
        assertThat((List<String>) filters.get("queryTerms")).containsExactly("分析 MySQL服务器 管理进程信息");
        assertThat(resolved.get("trace")).isInstanceOf(Map.class);
    }

    @SuppressWarnings("unchecked")
    void hydratesSqlExecuteContextAndRemovesAssetNameMisboundAsSchemaName() throws Exception {
        InterpretationPlanRuntime runtime = new InterpretationPlanRuntime(
            mock(ToolRuntimeService.class),
            new InterpretationPlanValidator(),
            mock(InterpretationPlanRuntime.DagExecutionController.class)
        );
        InterpretationPlan.Step step = new InterpretationPlan.Step(
            2,
            "mcp_tool",
            "mcp_chatchat_mcp_server_sql_query_execute",
            Map.of(
                "templateId", "MYSQL_TABLE_METADATA",
                "executionContext", Map.of(
                    "assetName", "$.assets[0].asset.name",
                    "env", "$.assets[0].asset.environment"
                ),
                "parameters", Map.of(
                    "tableName", "lbappdeploydetail",
                    "schemaName", "test_db_248"
                )
            ),
            List.of(1),
            null,
            null
        );
        Method method = InterpretationPlanRuntime.class.getDeclaredMethod(
            "resolvedStepInput",
            InterpretationPlan.Step.class,
            InterpretationPlanRuntime.ExecutionRequest.class,
            Map.class
        );
        method.setAccessible(true);

        Map<String, Object> resolved = (Map<String, Object>) method.invoke(
            runtime,
            step,
            new InterpretationPlanRuntime.ExecutionRequest(
                minimalPlan(step),
                mock(ToolRegistry.class),
                List.of("mcp_chatchat_mcp_server_sql_query_execute"),
                "tenant-1",
                "req-sql-context-normalize",
                "conv-sql-context-normalize",
                "user-1",
                Map.of()
            ),
            Map.of(1, completedSqlAssetStep())
        );

        Map<String, Object> executionContext = (Map<String, Object>) resolved.get("executionContext");
        assertThat(executionContext)
            .containsEntry("assetName", "test_db_248")
            .containsEntry("env", "DEV");
        Map<String, Object> parameters = (Map<String, Object>) resolved.get("parameters");
        assertThat(parameters)
            .containsEntry("tableName", "lbappdeploydetail")
            .doesNotContainKey("schemaName");
    }

    @Test
    void rejectsMissingRoutingDecisionWithoutRuntimeInference() throws Exception {
        InterpretationPlanRuntime runtime = new InterpretationPlanRuntime(
            mock(ToolRuntimeService.class),
            new InterpretationPlanValidator(),
            mock(InterpretationPlanRuntime.DagExecutionController.class)
        );
        InterpretationPlan.Step step = new InterpretationPlan.Step(
            1,
            "mcp_tool",
            "mcp_chatchat_mcp_server_sql_datasource_asset_query",
            Map.of(
                "filters", Map.of("assetName", "Local MySQL Test Service"),
                "limit", 10
            ),
            List.of(),
            null,
            null
        );
        InterpretationPlan plan = new InterpretationPlan(
            "1.0",
            new InterpretationPlan.Intent("data_query", "Analyze Local MySQL Test Service InnoDB status", "low"),
            context(),
            new InterpretationPlan.Plan(List.of(
                step,
                new InterpretationPlan.Step(2, "mcp_tool", "mcp_chatchat_mcp_server_sql_datasource_template_query",
                    Map.of("filters", Map.of("intent", "InnoDB status"), "limit", 10), List.of(1), null, null),
                new InterpretationPlan.Step(3, "mcp_tool", "mcp_chatchat_mcp_server_sql_query_execute",
                    Map.of("templateId", "MYSQL_INNODB_STATUS", "parameters", Map.of()), List.of(2), null, null)
            )),
            new InterpretationPlan.ExecutionPolicy(
                3,
                false,
                List.of(
                    "mcp_chatchat_mcp_server_sql_datasource_asset_query",
                    "mcp_chatchat_mcp_server_sql_datasource_template_query",
                    "mcp_chatchat_mcp_server_sql_query_execute"
                ),
                List.of(),
                30000
            ),
            review()
        );
        InterpretationPlanRuntime.ExecutionRequest request = new InterpretationPlanRuntime.ExecutionRequest(
            plan,
            mock(ToolRegistry.class),
            List.of("mcp_chatchat_mcp_server_sql_datasource_asset_query"),
            "tenant-1",
            "req-routing-decision",
            "conv-routing-decision",
            "user-1",
            Map.of("executionTraceId", "trace-runtime-routing-decision")
        );
        Method method = InterpretationPlanRuntime.class.getDeclaredMethod(
            "resolvedStepInput",
            InterpretationPlan.Step.class,
            InterpretationPlanRuntime.ExecutionRequest.class,
            Map.class
        );
        method.setAccessible(true);

        Map<String, Object> resolved = (Map<String, Object>) method.invoke(runtime, step, request, Map.of());
        assertThat(resolved).doesNotContainKeys("finalDecision", "targetKind", "assetType");
    }

    @Test
    @SuppressWarnings("unchecked")
    void doesNotInferRoutingFromDatabaseAndHostDownstreamTools() throws Exception {
        InterpretationPlanRuntime runtime = new InterpretationPlanRuntime(
            mock(ToolRuntimeService.class),
            new InterpretationPlanValidator(),
            mock(InterpretationPlanRuntime.DagExecutionController.class)
        );
        InterpretationPlan.Step step = new InterpretationPlan.Step(
            1,
            "mcp_tool",
            "mcp_chatchat_mcp_server_sql_datasource_asset_query",
            Map.of(
                "filters", Map.of("assetName", "demo-service"),
                "limit", 10
            ),
            List.of(),
            null,
            null
        );
        InterpretationPlan plan = new InterpretationPlan(
            "1.0",
            new InterpretationPlan.Intent("ops_check", "Check service status", "low"),
            context(),
            new InterpretationPlan.Plan(List.of(
                step,
                new InterpretationPlan.Step(2, "mcp_tool", "mcp_chatchat_mcp_server_database_query",
                    Map.of("query", "status"), List.of(1), null, null),
                new InterpretationPlan.Step(3, "mcp_tool", "mcp_chatchat_mcp_server_linux_command_execute",
                    Map.of("command", "systemctl status demo"), List.of(1), null, null)
            )),
            new InterpretationPlan.ExecutionPolicy(
                3,
                false,
                List.of(
                    "mcp_chatchat_mcp_server_sql_datasource_asset_query",
                    "mcp_chatchat_mcp_server_database_query",
                    "mcp_chatchat_mcp_server_linux_command_execute"
                ),
                List.of(),
                30000
            ),
            review()
        );
        InterpretationPlanRuntime.ExecutionRequest request = new InterpretationPlanRuntime.ExecutionRequest(
            plan,
            mock(ToolRegistry.class),
            List.of("mcp_chatchat_mcp_server_sql_datasource_asset_query"),
            "tenant-1",
            "req-routing-ambiguous",
            "conv-routing-ambiguous",
            "user-1",
            Map.of("executionTraceId", "trace-runtime-routing-ambiguous")
        );
        Method method = InterpretationPlanRuntime.class.getDeclaredMethod(
            "resolvedStepInput",
            InterpretationPlan.Step.class,
            InterpretationPlanRuntime.ExecutionRequest.class,
            Map.class
        );
        method.setAccessible(true);

        Map<String, Object> resolved = (Map<String, Object>) method.invoke(runtime, step, request, Map.of());
        assertThat(resolved).doesNotContainKeys("finalDecision", "targetKind", "assetType");
    }

    @Test
    void passesMissingRoutingDecisionThroughWithoutRuntimeInference() {
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.hasTool("mcp_chatchat_mcp_server_sql_datasource_asset_query")).thenReturn(true);
        when(toolRegistry.getToolMetadata(any())).thenReturn(ToolMetadata.builder().riskLevel("low").build());
        ToolRuntimeService toolRuntimeService = mock(ToolRuntimeService.class);
        InterpretationPlanRuntime runtime = new InterpretationPlanRuntime(
            toolRuntimeService,
            new InterpretationPlanValidator(),
            mock(InterpretationPlanRuntime.DagExecutionController.class)
        );
        InterpretationPlan plan = new InterpretationPlan(
            "1.0",
            new InterpretationPlan.Intent("asset_lookup", "Find a service asset", "low"),
            context(),
            new InterpretationPlan.Plan(List.of(
                new InterpretationPlan.Step(1, "mcp_tool", "mcp_chatchat_mcp_server_sql_datasource_asset_query",
                    Map.of("filters", Map.of("assetName", "demo-service"), "limit", 10), List.of(), null, null),
                new InterpretationPlan.Step(2, "final_answer", "",
                    Map.of("answer", "done"), List.of(1), null, null)
            )),
            new InterpretationPlan.ExecutionPolicy(
                3,
                false,
                List.of("mcp_chatchat_mcp_server_sql_datasource_asset_query"),
                List.of(),
                30000
            ),
            review()
        );

        InterpretationPlanRuntime.ExecutionResult result = runtime.execute(new InterpretationPlanRuntime.ExecutionRequest(
            plan,
            toolRegistry,
            List.of("mcp_chatchat_mcp_server_sql_datasource_asset_query"),
            "tenant-1",
            "req-routing-target-required",
            "conv-routing-target-required",
            "user-1",
            Map.of("executionTraceId", "trace-runtime-routing-target-required")
        ));

        assertThat(result.success()).isFalse();
        assertThat(result.status()).isEqualTo("STEP_FAILED");
        assertThat(result.errorMessage()).doesNotContain("ROUTING_TARGET_REQUIRED");
        assertThat(result.steps()).hasSize(1);
        ArgumentCaptor<ToolRuntimeRequest> captor = ArgumentCaptor.forClass(ToolRuntimeRequest.class);
        verify(toolRuntimeService).execute(captor.capture());
        assertThat(captor.getValue().getToolInput().getParameters())
            .doesNotContainKeys("finalDecision", "targetKind", "assetType");
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
    @SuppressWarnings("unchecked")
    void resolvesMatchingBindingPlaceholderAtNestedInputPath() throws Exception {
        InterpretationPlanRuntime runtime = new InterpretationPlanRuntime(
            mock(ToolRuntimeService.class),
            new InterpretationPlanValidator(),
            mock(InterpretationPlanRuntime.DagExecutionController.class)
        );
        InterpretationPlan.Step sourceStep = new InterpretationPlan.Step(
            1, "mcp_tool", "database_asset_search", Map.of(), List.of(), null, null);
        InterpretationPlan.Step targetStep = new InterpretationPlan.Step(
            2,
            "mcp_tool",
            "database_ops_template_search",
            Map.of("filters", Map.of("assetName", "{{bindings.assetName}}", "env", "DEV")),
            List.of(1),
            null,
            null
        );
        InterpretationPlan plan = new InterpretationPlan(
            "1.0",
            new InterpretationPlan.Intent("database_ops", "Inspect database status", "low"),
            context(),
            new InterpretationPlan.Plan(
                List.of(sourceStep, targetStep),
                List.of(),
                List.of(new InterpretationPlan.Binding(
                    1, "$.assets[0].asset.name", 2, "assetName", "jsonpath", true)),
                null
            ),
            new InterpretationPlan.ExecutionPolicy(
                2, false, List.of("database_asset_search", "database_ops_template_search"), List.of(), 30000),
            review()
        );
        Map<Integer, InterpretationPlanRuntime.StepExecution> completed = Map.of(
            1,
            new InterpretationPlanRuntime.StepExecution(
                1,
                "mcp_tool",
                "database_asset_search",
                true,
                Map.of("assets", List.of(Map.of("asset", Map.of("name", "test-database")))),
                null,
                null,
                null,
                10
            )
        );
        Method method = InterpretationPlanRuntime.class.getDeclaredMethod(
            "resolvedStepInput",
            InterpretationPlan.Step.class,
            InterpretationPlanRuntime.ExecutionRequest.class,
            Map.class
        );
        method.setAccessible(true);

        Map<String, Object> resolved = (Map<String, Object>) method.invoke(
            runtime,
            targetStep,
            new InterpretationPlanRuntime.ExecutionRequest(
                plan,
                mock(ToolRegistry.class),
                List.of("database_ops_template_search"),
                "tenant-1",
                "req-nested-binding",
                "conv-nested-binding",
                "user-1",
                Map.of("agentRuntimeEnvironment", "DEV")
            ),
            completed
        );

        assertThat((Map<String, Object>) resolved.get("filters"))
            .containsEntry("assetName", "test-database")
            .containsEntry("env", "DEV");
        assertThat(resolved).containsEntry("assetName", "test-database");
    }

    @Test
    void rejectsUnresolvedBindingPlaceholderBeforeToolExecution() throws Exception {
        InterpretationPlanRuntime runtime = new InterpretationPlanRuntime(
            mock(ToolRuntimeService.class),
            new InterpretationPlanValidator(),
            mock(InterpretationPlanRuntime.DagExecutionController.class)
        );
        Method method = InterpretationPlanRuntime.class.getDeclaredMethod(
            "assertNoUnresolvedBindingPlaceholders", Object.class);
        method.setAccessible(true);

        assertThatThrownBy(() -> method.invoke(
            runtime,
            Map.of("filters", Map.of("assetName", "{{bindings.assetName}}"))))
            .hasCauseInstanceOf(IllegalStateException.class)
            .hasRootCauseMessage("BINDING_FAILED: unresolved binding placeholder at $.filters.assetName");
    }

    @Test
    @SuppressWarnings("unchecked")
    void writesResolvedValueToCompleteNestedBindingPath() throws Exception {
        InterpretationPlanRuntime runtime = new InterpretationPlanRuntime(
            mock(ToolRuntimeService.class),
            new InterpretationPlanValidator(),
            mock(InterpretationPlanRuntime.DagExecutionController.class)
        );
        Method method = InterpretationPlanRuntime.class.getDeclaredMethod(
            "putInputValue", Map.class, String.class, Object.class);
        method.setAccessible(true);
        Map<String, Object> input = new java.util.LinkedHashMap<>();
        input.put("filters", Map.of("env", "DEV"));

        method.invoke(runtime, input, "$.filters.assetName", "248测试数据库");

        assertThat((Map<String, Object>) input.get("filters"))
            .containsEntry("assetName", "248测试数据库")
            .containsEntry("env", "DEV");
        assertThat(input).doesNotContainKey("assetName");
    }

    @Test
    void acceptsInputPathAliasInPlanBindingJson() throws Exception {
        InterpretationPlan.Binding binding = new com.fasterxml.jackson.databind.ObjectMapper().readValue(
            """
                {
                  "from": 1,
                  "output_path": "$.assets[0].asset.name",
                  "to": 2,
                  "input_path": "$.filters.assetName",
                  "type": "jsonpath",
                  "required": true
                }
                """,
            InterpretationPlan.Binding.class
        );

        assertThat(binding.inputField()).isEqualTo("$.filters.assetName");
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
                    new InterpretationPlan.Step(1, "mcp_tool", "web_search", Map.of("query", "example page"), List.of(), null, null),
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
    void resolvesLegacyTemplateBindingPathIntoSqlTemplateInput() {
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.hasTool("mcp_chatchat_mcp_server_sql_datasource_template_query")).thenReturn(true);
        when(toolRegistry.hasTool("mcp_chatchat_mcp_server_sql_query_execute")).thenReturn(true);
        when(toolRegistry.getToolMetadata(any())).thenReturn(ToolMetadata.builder().riskLevel("low").build());
        ToolRuntimeService toolRuntimeService = mock(ToolRuntimeService.class);
        when(toolRuntimeService.execute(any())).thenAnswer(invocation -> {
            ToolRuntimeRequest request = invocation.getArgument(0);
            if ("mcp_chatchat_mcp_server_sql_datasource_template_query".equals(request.getToolName())) {
                return new ToolRuntimeExecution(
                    ToolOutput.success(Map.of("templates", List.of(Map.of(
                        "templateId", "MYSQL_TABLE_METADATA",
                        "name", "MySQL table metadata"
                    )))),
                    ToolMetadata.builder().id(request.getToolName()).build(),
                    null,
                    "success",
                    Map.of()
                );
            }
            return new ToolRuntimeExecution(
                ToolOutput.success(Map.of("status", "ok", "parameters", request.getToolInput().getParameters())),
                ToolMetadata.builder().id(request.getToolName()).build(),
                null,
                "success",
                Map.of()
            );
        });
        InterpretationPlan plan = new InterpretationPlan(
            "1.0",
            new InterpretationPlan.Intent("data_query", "Query table metadata", "low"),
            context(),
            new InterpretationPlan.Plan(
                List.of(
                    new InterpretationPlan.Step(1, "mcp_tool", "mcp_chatchat_mcp_server_sql_datasource_template_query",
                        Map.of(
                            "finalDecision", "database",
                            "filters", Map.of("intent", "table metadata"),
                            "limit", 3
                        ), List.of(), null, null),
                    new InterpretationPlan.Step(2, "mcp_tool", "mcp_chatchat_mcp_server_sql_query_execute",
                        Map.of(
                            "executionContext", Map.of("assetName", "test_mysql_database", "env", "DEV"),
                            "parameters", Map.of("table", "user_info_file"),
                            "parameterProtocol", userQueryParameterProtocol(
                                2, "MYSQL_TABLE_METADATA", "user_info_file",
                                Map.of("table", "user_info_file"))
                        ), List.of(1), null, null),
                    new InterpretationPlan.Step(3, "final_answer", "", Map.of("answer", "done"), List.of(2), null, null)
                ),
                List.of(),
                List.of(new InterpretationPlan.Binding(1, "$[0].id", 2, "template", "jsonpath", true)),
                null
            ),
            new InterpretationPlan.ExecutionPolicy(
                3,
                false,
                List.of("mcp_chatchat_mcp_server_sql_datasource_template_query", "mcp_chatchat_mcp_server_sql_query_execute"),
                List.of(),
                30000
            ),
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
            List.of("mcp_chatchat_mcp_server_sql_datasource_template_query", "mcp_chatchat_mcp_server_sql_query_execute"),
            "tenant-1",
            "req-template-binding",
            "conv-template-binding",
            "user-1",
            Map.of("originalUserQuery", "Query user_info_file table metadata")
        ));

        ArgumentCaptor<ToolRuntimeRequest> captor = ArgumentCaptor.forClass(ToolRuntimeRequest.class);
        verify(toolRuntimeService, times(2)).execute(captor.capture());
        assertThat(result.success()).isTrue();
        assertThat(captor.getAllValues().get(1).getToolInput().getParameters())
            .containsEntry("template", "MYSQL_TABLE_METADATA");
    }

    @Test
    void hydratesSqlExecutionContextFromCompletedAssetDiscoveryWhenPlannerOmittedBinding() {
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.hasTool("mcp_chatchat_mcp_server_sql_datasource_asset_query")).thenReturn(true);
        when(toolRegistry.hasTool("mcp_chatchat_mcp_server_sql_datasource_template_query")).thenReturn(true);
        when(toolRegistry.hasTool("mcp_chatchat_mcp_server_sql_query_execute")).thenReturn(true);
        when(toolRegistry.getToolMetadata(any())).thenReturn(ToolMetadata.builder().riskLevel("low").build());
        ToolRuntimeService toolRuntimeService = mock(ToolRuntimeService.class);
        when(toolRuntimeService.execute(any())).thenAnswer(invocation -> {
            ToolRuntimeRequest request = invocation.getArgument(0);
            if ("mcp_chatchat_mcp_server_sql_datasource_asset_query".equals(request.getToolName())) {
                return new ToolRuntimeExecution(
                    ToolOutput.success(Map.of(
                        "schemaVersion", "asset_query_result.v1",
                        "assets", List.of(Map.of(
                            "asset", Map.of(
                                "name", "閺堫剙婀碝ySQL濞村鐦張宥呭",
                                "environment", "DEV",
                                "databaseRole", "primary"
                            )
                        ))
                    )),
                    ToolMetadata.builder().id(request.getToolName()).build(),
                    null,
                    "success",
                    Map.of()
                );
            }
            if ("mcp_chatchat_mcp_server_sql_datasource_template_query".equals(request.getToolName())) {
                return new ToolRuntimeExecution(
                    ToolOutput.success(Map.of("templates", List.of(Map.of(
                        "templateId", "MYSQL_TABLE_METADATA",
                        "id", "MYSQL_TABLE_METADATA"
                    )))),
                    ToolMetadata.builder().id(request.getToolName()).build(),
                    null,
                    "success",
                    Map.of()
                );
            }
            return new ToolRuntimeExecution(
                ToolOutput.success(Map.of("status", "ok", "parameters", request.getToolInput().getParameters())),
                ToolMetadata.builder().id(request.getToolName()).build(),
                null,
                "success",
                Map.of()
            );
        });
        InterpretationPlan plan = new InterpretationPlan(
            "1.0",
            new InterpretationPlan.Intent("data_query", "Query table metadata", "low"),
            context(),
            new InterpretationPlan.Plan(
                List.of(
                    new InterpretationPlan.Step(1, "mcp_tool", "mcp_chatchat_mcp_server_sql_datasource_asset_query",
                        Map.of("filters", Map.of("assetName", "閺堫剙婀碝ySQL濞村鐦張宥呭"), "finalDecision", "database"), List.of(), null, null),
                    new InterpretationPlan.Step(2, "mcp_tool", "mcp_chatchat_mcp_server_sql_datasource_template_query",
                        Map.of("filters", Map.of("intent", "table metadata"), "finalDecision", "database"), List.of(1), null, null),
                    new InterpretationPlan.Step(3, "mcp_tool", "mcp_chatchat_mcp_server_sql_query_execute",
                        Map.of(
                            "parameters", Map.of("database", "test", "table", "user_info_file"),
                            "parameterProtocol", userQueryParameterProtocol(
                                3, "MYSQL_TABLE_METADATA", "test user_info_file",
                                Map.of("database", "test", "table", "user_info_file"))
                        ), List.of(1, 2), null, null),
                    new InterpretationPlan.Step(4, "final_answer", "", Map.of("answer", "done"), List.of(3), null, null)
                ),
                List.of(),
                List.of(new InterpretationPlan.Binding(2, "$[0].id", 3, "templateId", "jsonpath", true)),
                null
            ),
            new InterpretationPlan.ExecutionPolicy(
                4,
                false,
                List.of(
                    "mcp_chatchat_mcp_server_sql_datasource_asset_query",
                    "mcp_chatchat_mcp_server_sql_datasource_template_query",
                    "mcp_chatchat_mcp_server_sql_query_execute"
                ),
                List.of(),
                30000
            ),
            review()
        );
        InterpretationPlanRuntime runtime = new InterpretationPlanRuntime(
            toolRuntimeService,
            new InterpretationPlanValidator(),
            scriptedController(List.of(List.of(1), List.of(2), List.of(3), List.of(4)))
        );

        InterpretationPlanRuntime.ExecutionResult result = runtime.execute(new InterpretationPlanRuntime.ExecutionRequest(
            plan,
            toolRegistry,
            List.of(
                "mcp_chatchat_mcp_server_sql_datasource_asset_query",
                "mcp_chatchat_mcp_server_sql_datasource_template_query",
                "mcp_chatchat_mcp_server_sql_query_execute"
            ),
            "tenant-1",
            "req-sql-context-from-asset",
            "conv-sql-context-from-asset",
            "user-1",
            Map.of("originalUserQuery", "Query test user_info_file table metadata")
        ));

        ArgumentCaptor<ToolRuntimeRequest> captor = ArgumentCaptor.forClass(ToolRuntimeRequest.class);
        verify(toolRuntimeService, times(3)).execute(captor.capture());
        assertThat(result.success()).isTrue();
        Map<?, ?> sqlInput = captor.getAllValues().get(2).getToolInput().getParameters();
        Map<?, ?> executionContext = (Map<?, ?>) sqlInput.get("executionContext");
        assertThat(sqlInput.get("templateId")).isEqualTo("MYSQL_TABLE_METADATA");
        assertThat(executionContext.get("assetName")).isEqualTo("閺堫剙婀碝ySQL濞村鐦張宥呭");
        assertThat(executionContext.get("env")).isEqualTo("DEV");
        assertThat(executionContext.get("databaseRole")).isEqualTo("primary");
    }

    @Test
    void projectsWholeTemplateDiscoveryObjectToScalarTemplateIdBeforeExecution() {
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.hasTool("mcp_chatchat_mcp_server_sql_datasource_template_query")).thenReturn(true);
        when(toolRegistry.hasTool("mcp_chatchat_mcp_server_sql_query_execute")).thenReturn(true);
        when(toolRegistry.getToolMetadata(any())).thenReturn(ToolMetadata.builder().riskLevel("low").build());
        ToolRuntimeService toolRuntimeService = mock(ToolRuntimeService.class);
        when(toolRuntimeService.execute(any())).thenAnswer(invocation -> {
            ToolRuntimeRequest request = invocation.getArgument(0);
            if ("mcp_chatchat_mcp_server_sql_datasource_template_query".equals(request.getToolName())) {
                return new ToolRuntimeExecution(
                    ToolOutput.success(Map.of(
                        "schemaVersion", "template_query_result.v1",
                        "templates", List.of(Map.of(
                            "schemaVersion", "command_template.v1",
                            "id", "MYSQL_TABLE_METADATA",
                            "templateId", "MYSQL_TABLE_METADATA",
                            "parameterSchema", Map.of(
                                "type", "object",
                                "properties", Map.of(),
                                "required", List.of()
                            ),
                            "invocationExample", Map.of("parameters", Map.of())
                        ))
                    )),
                    ToolMetadata.builder().id(request.getToolName()).build(),
                    null,
                    "success",
                    Map.of()
                );
            }
            return new ToolRuntimeExecution(
                ToolOutput.success(Map.of("status", "ok", "parameters", request.getToolInput().getParameters())),
                ToolMetadata.builder().id(request.getToolName()).build(),
                null,
                "success",
                Map.of()
            );
        });
        InterpretationPlan plan = new InterpretationPlan(
            "1.0",
            new InterpretationPlan.Intent("data_query", "Query table metadata", "low"),
            context(),
            new InterpretationPlan.Plan(
                List.of(
                    new InterpretationPlan.Step(1, "mcp_tool", "mcp_chatchat_mcp_server_sql_datasource_template_query",
                        Map.of("filters", Map.of("intent", "table metadata"), "finalDecision", "database"), List.of(), null, null),
                    new InterpretationPlan.Step(2, "mcp_tool", "mcp_chatchat_mcp_server_sql_query_execute",
                        Map.of(
                            "executionContext", Map.of("assetName", "test_mysql_database", "env", "DEV"),
                            "parameters", Map.of("database", "test", "table", "user_info_file"),
                            "parameterProtocol", userQueryParameterProtocol(
                                2, "MYSQL_TABLE_METADATA", "test user_info_file",
                                Map.of("database", "test", "table", "user_info_file"))
                        ), List.of(1), null, null),
                    new InterpretationPlan.Step(3, "final_answer", "", Map.of("answer", "done"), List.of(2), null, null)
                ),
                List.of(
                    new InterpretationPlan.EdgeContract(1, 2, "templateId", "string", true),
                    new InterpretationPlan.EdgeContract(1, 2, "templates[0].parameterSchema.required", "object", false)
                ),
                List.of(
                    // Regression: an unstable model may bind the whole discovery object. Runtime must
                    // compile it to its scalar id and must never send "{templateId=...}" to MCP.
                    // Schema metadata is intentionally not bound to parameters; the validator now
                    // rejects that planner error before execution.
                    new InterpretationPlan.Binding(1, "$.templates[0]", 2, "templateId", "jsonpath", true)
                ),
                null
            ),
            new InterpretationPlan.ExecutionPolicy(
                3,
                false,
                List.of("mcp_chatchat_mcp_server_sql_datasource_template_query", "mcp_chatchat_mcp_server_sql_query_execute"),
                List.of(),
                30000
            ),
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
            List.of("mcp_chatchat_mcp_server_sql_datasource_template_query", "mcp_chatchat_mcp_server_sql_query_execute"),
            "tenant-1",
            "req-template-edge-contract",
            "conv-template-edge-contract",
            "user-1",
            Map.of("originalUserQuery", "Query test user_info_file table metadata")
        ));

        ArgumentCaptor<ToolRuntimeRequest> captor = ArgumentCaptor.forClass(ToolRuntimeRequest.class);
        verify(toolRuntimeService, times(2)).execute(captor.capture());
        assertThat(result.success()).isTrue();
        assertThat(captor.getAllValues().get(1).getToolInput().getParameters())
            .containsEntry("templateId", "MYSQL_TABLE_METADATA");
        assertThat(captor.getAllValues().get(1).getToolInput().getParameters())
            .containsEntry("template", "MYSQL_TABLE_METADATA");
        assertThat(captor.getAllValues().get(1).getToolInput().getParameters().get("parameters"))
            .isEqualTo(Map.of());
    }

    @Test
    void normalizesTemplateExecutionParameterAliasesFromTemplateSchema() {
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.hasTool("mcp_chatchat_mcp_server_sql_datasource_template_query")).thenReturn(true);
        when(toolRegistry.hasTool("mcp_chatchat_mcp_server_sql_query_execute")).thenReturn(true);
        when(toolRegistry.getToolMetadata(any())).thenReturn(ToolMetadata.builder().riskLevel("low").build());
        ToolRuntimeService toolRuntimeService = mock(ToolRuntimeService.class);
        when(toolRuntimeService.execute(any())).thenAnswer(invocation -> {
            ToolRuntimeRequest request = invocation.getArgument(0);
            if ("mcp_chatchat_mcp_server_sql_datasource_template_query".equals(request.getToolName())) {
                return new ToolRuntimeExecution(
                    ToolOutput.success(Map.of(
                        "templates", List.of(Map.of(
                            "id", "MYSQL_TABLE_METADATA",
                            "templateId", "MYSQL_TABLE_METADATA",
                            "parameterSchema", Map.of(
                                "type", "object",
                                "properties", Map.of("tableName", Map.of("type", "string")),
                                "required", List.of("tableName")
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
                ToolOutput.success(Map.of("status", "ok", "parameters", request.getToolInput().getParameters())),
                ToolMetadata.builder().id(request.getToolName()).build(),
                null,
                "success",
                Map.of()
            );
        });
        InterpretationPlan plan = new InterpretationPlan(
            "1.0",
            new InterpretationPlan.Intent("data_query", "Query table metadata", "low"),
            context(),
            new InterpretationPlan.Plan(
                List.of(
                    new InterpretationPlan.Step(1, "mcp_tool", "mcp_chatchat_mcp_server_sql_datasource_template_query",
                        Map.of("filters", Map.of("intent", "table metadata"), "finalDecision", "database"), List.of(), null, null),
                    new InterpretationPlan.Step(2, "mcp_tool", "mcp_chatchat_mcp_server_sql_query_execute",
                        Map.of(
                            "executionContext", Map.of("assetName", "test_mysql_database", "env", "DEV"),
                            "parameters", Map.of("table_name", "user_info_file"),
                            "parameterProtocol", userQueryParameterProtocol(
                                2, "MYSQL_TABLE_METADATA", "user_info_file",
                                Map.of("table_name", "user_info_file"))
                        ), List.of(1), null, null),
                    new InterpretationPlan.Step(3, "final_answer", "", Map.of("answer", "done"), List.of(2), null, null)
                ),
                List.of(),
                List.of(new InterpretationPlan.Binding(1, "$.templates[0].templateId", 2, "templateId", "jsonpath", true)),
                null
            ),
            new InterpretationPlan.ExecutionPolicy(
                3,
                false,
                List.of("mcp_chatchat_mcp_server_sql_datasource_template_query", "mcp_chatchat_mcp_server_sql_query_execute"),
                List.of(),
                30000
            ),
            review()
        );
        InterpretationPlanRuntime runtime = new InterpretationPlanRuntime(
            toolRuntimeService,
            new InterpretationPlanValidator(),
            scriptedController(List.of(List.of(1), List.of(2), List.of(3)))
        );

        runtime.execute(new InterpretationPlanRuntime.ExecutionRequest(
            plan,
            toolRegistry,
            List.of("mcp_chatchat_mcp_server_sql_datasource_template_query", "mcp_chatchat_mcp_server_sql_query_execute"),
            "tenant-1",
            "req-template-param-alias",
            "conv-template-param-alias",
            "user-1",
            Map.of("originalUserQuery", "Query user_info_file table metadata")
        ));

        ArgumentCaptor<ToolRuntimeRequest> captor = ArgumentCaptor.forClass(ToolRuntimeRequest.class);
        verify(toolRuntimeService, times(2)).execute(captor.capture());
        Map<?, ?> sqlInput = captor.getAllValues().get(1).getToolInput().getParameters();
        Map<?, ?> parameters = (Map<?, ?>) sqlInput.get("parameters");
        assertThat(sqlInput.get("templateId")).isEqualTo("MYSQL_TABLE_METADATA");
        assertThat(parameters.containsKey("table_name")).isFalse();
        assertThat(parameters.get("tableName")).isEqualTo("user_info_file");
    }

    @Test
    void compilesControllerParameterProtocolAgainstRuntimeDiscoveredTemplate() {
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.hasTool(any())).thenReturn(true);
        when(toolRegistry.getToolMetadata(any())).thenReturn(ToolMetadata.builder().riskLevel("low").build());
        ToolRuntimeService toolRuntimeService = mock(ToolRuntimeService.class);
        when(toolRuntimeService.execute(any())).thenAnswer(invocation -> {
            ToolRuntimeRequest request = invocation.getArgument(0);
            if (request.getToolName().contains("template_query")) {
                return new ToolRuntimeExecution(
                    ToolOutput.success(Map.of(
                        "resolvedTradeDate", "20260716",
                        "templates", List.of(Map.of(
                            "templateId", "QUERY_BY_TRADE_DATE",
                            "parameterSchema", Map.of(
                                "type", "object",
                                "properties", Map.of("tradeDate", Map.of("type", "string", "format", "date")),
                                "required", List.of("tradeDate")
                            ),
                            "executionTool", "mcp_chatchat_mcp_server_sql_query_execute"
                        ))
                    )),
                    ToolMetadata.builder().id(request.getToolName()).build(), null, "success", Map.of());
            }
            return new ToolRuntimeExecution(
                ToolOutput.success(Map.of("parameters", request.getToolInput().getParameters())),
                ToolMetadata.builder().id(request.getToolName()).build(), null, "success", Map.of());
        });
        InterpretationPlan plan = new InterpretationPlan(
            "1.0", new InterpretationPlan.Intent("data_query", "query by trade date", "low"), context(),
            new InterpretationPlan.Plan(
                List.of(
                    new InterpretationPlan.Step(1, "mcp_tool", "mcp_chatchat_mcp_server_sql_datasource_template_query",
                        Map.of("filters", Map.of("intent", "query by trade date")), List.of(), null, null),
                    new InterpretationPlan.Step(2, "mcp_tool", "mcp_chatchat_mcp_server_sql_query_execute",
                        Map.of("executionContext", Map.of("assetName", "trade-db", "env", "DEV")), List.of(1), null, null),
                    new InterpretationPlan.Step(3, "final_answer", "", Map.of("answer", "done"), List.of(2), null, null)
                ),
                List.of(),
                List.of(new InterpretationPlan.Binding(1, "$.templates[0].templateId", 2, "templateId", "jsonpath", true)),
                null
            ),
            new InterpretationPlan.ExecutionPolicy(3, false, List.of(
                "mcp_chatchat_mcp_server_sql_datasource_template_query",
                "mcp_chatchat_mcp_server_sql_query_execute"), List.of(), 30000),
            review()
        );
        AtomicInteger controllerCalls = new AtomicInteger();
        InterpretationPlanRuntime.DagExecutionController controller = request -> {
            controllerCalls.incrementAndGet();
            if (request.remainingStepIds().contains(2)) {
                Map<String, Object> protocol = Map.of(
                    "protocol_version", InterpretationExecutionProtocol.TEMPLATE_PARAMETER_PROTOCOL_VERSION,
                    "step_id", 2,
                    "template_id", "MODEL_SELECTED_DATASOURCE_ASSET",
                    "arguments", Map.of("trade_date", Map.of(
                        "value", "20260716",
                        "source", "tool_result",
                        "evidence", Map.of("step_id", 1, "output_path", "$.resolvedTradeDate")
                    )),
                    "unresolved_parameters", List.of()
                );
                return new InterpretationPlanRuntime.DagDecision(
                    InterpretationExecutionProtocol.VERSION, "execute_step", List.of(2),
                    "parameters extracted from user query", null,
                    Map.of("parameterProtocols", List.of(protocol))
                );
            }
            return InterpretationPlanRuntime.DagDecision.finalAnswer(3, "done", "complete");
        };
        InterpretationPlanRuntime runtime = new InterpretationPlanRuntime(
            toolRuntimeService, new InterpretationPlanValidator(), controller);

        InterpretationPlanRuntime.ExecutionResult result = runtime.execute(new InterpretationPlanRuntime.ExecutionRequest(
            plan, toolRegistry, List.of(
                "mcp_chatchat_mcp_server_sql_datasource_template_query",
                "mcp_chatchat_mcp_server_sql_query_execute"),
            "tenant-1", "req-parameter-protocol", "conv-parameter-protocol", "user-1",
            Map.of("requireTemplateParameterProtocol", true)
        ));

        ArgumentCaptor<ToolRuntimeRequest> captor = ArgumentCaptor.forClass(ToolRuntimeRequest.class);
        verify(toolRuntimeService, times(2)).execute(captor.capture());
        Map<?, ?> executorInput = captor.getAllValues().get(1).getToolInput().getParameters();
        assertThat(result.success()).isTrue();
        assertThat(controllerCalls.get()).isGreaterThanOrEqualTo(1);
        assertThat(executorInput.get("templateId")).isEqualTo("QUERY_BY_TRADE_DATE");
        assertThat(executorInput.get("parameters")).isEqualTo(Map.of("tradeDate", "2026-07-16"));
        assertThat(executorInput.containsKey("parameterProtocol")).isFalse();
    }

    @Test
    void defersTheSameModelParameterProtocolForAllTemplateExecutorsUntilFinalRuntimeReview() throws Exception {
        InterpretationPlanRuntime runtime = new InterpretationPlanRuntime(
            mock(ToolRuntimeService.class), new InterpretationPlanValidator(), scriptedController(List.of()));
        Method method = InterpretationPlanRuntime.class.getDeclaredMethod(
            "normalizeModelInvocationEnvelope", InterpretationPlan.Step.class, Map.class);
        method.setAccessible(true);
        for (String toolName : List.of(
            "mcp_chatchat_mcp_server_sql_query_execute",
            "mcp_chatchat_mcp_server_http_request_execute",
            "mcp_chatchat_mcp_server_linux_command_execute",
            "mcp_chatchat_mcp_server_api_template_execute"
        )) {
            Map<String, Object> input = new java.util.LinkedHashMap<>();
            input.put("templateId", "GENERIC_PARAMETERIZED_TEMPLATE");
            input.put("parameterProtocol", Map.of(
                "protocol_version", InterpretationExecutionProtocol.TEMPLATE_PARAMETER_PROTOCOL_VERSION,
                "step_id", 7,
                "template_id", "GENERIC_PARAMETERIZED_TEMPLATE",
                "arguments", Map.of("target", Map.of(
                    "value", "runtime-target",
                    "source", "user_query",
                    "evidence", "target runtime-target"
                )),
                "unresolved_parameters", List.of()
            ));
            InterpretationPlan.Step step = new InterpretationPlan.Step(
                7, "mcp_tool", toolName, Map.of(), List.of(), null, null);

            method.invoke(runtime, step, input);

            assertThat(input.get("templateId")).isEqualTo("GENERIC_PARAMETERIZED_TEMPLATE");
            assertThat(input.get("parameterProtocol")).isNotNull();
            assertThat(input).doesNotContainKey("runtimeParameterProtocolApplied");
        }
    }

    @Test
    void resolvesApiExecutorContractOnlyThroughApiTemplateDiscovery() throws Exception {
        InterpretationPlanRuntime runtime = new InterpretationPlanRuntime(
            mock(ToolRuntimeService.class), new InterpretationPlanValidator(), scriptedController(List.of()));
        Method method = InterpretationPlanRuntime.class.getDeclaredMethod(
            "templateContractDiscoveryTool", String.class, List.class);
        method.setAccessible(true);

        Object discoveryTool = method.invoke(runtime,
            "mcp_chatchat_mcp_server_api_template_execute",
            List.of(
                "mcp_chatchat_mcp_server_sql_datasource_template_query",
                "mcp_chatchat_mcp_server_api_template_query"
            ));

        assertThat(discoveryTool).isEqualTo("mcp_chatchat_mcp_server_api_template_query");
    }

    @Test
    void resolvesHttpExecutorContractOnlyThroughHttpEndpointTemplateDiscovery() throws Exception {
        InterpretationPlanRuntime runtime = new InterpretationPlanRuntime(
            mock(ToolRuntimeService.class), new InterpretationPlanValidator(), scriptedController(List.of()));
        Method method = InterpretationPlanRuntime.class.getDeclaredMethod(
            "templateContractDiscoveryTool", String.class, List.class);
        method.setAccessible(true);

        Object discoveryTool = method.invoke(runtime,
            "mcp_chatchat_mcp_server_http_request_execute",
            List.of(
                "mcp_chatchat_mcp_server_api_template_query",
                "mcp_chatchat_mcp_server_http_endpoint_template_query"
            ));

        assertThat(discoveryTool).isEqualTo("mcp_chatchat_mcp_server_http_endpoint_template_query");
    }

    @Test
    void defersModelTemplateIdAuditUntilRuntimeHasAuthoritativeTemplateMetadata() throws Exception {
        InterpretationPlanRuntime runtime = new InterpretationPlanRuntime(
            mock(ToolRuntimeService.class), new InterpretationPlanValidator(), scriptedController(List.of()));
        Method method = InterpretationPlanRuntime.class.getDeclaredMethod(
            "normalizeModelInvocationEnvelope", InterpretationPlan.Step.class, Map.class);
        method.setAccessible(true);
        Map<String, Object> input = new java.util.LinkedHashMap<>();
        input.put("templateId", "RUNTIME_SELECTED_TEMPLATE");
        input.put("parameterProtocol", Map.of(
            "protocol_version", InterpretationExecutionProtocol.TEMPLATE_PARAMETER_PROTOCOL_VERSION,
            "step_id", 7,
            "template_id", "MODEL_CHANGED_TEMPLATE",
            "arguments", Map.of(),
            "unresolved_parameters", List.of()
        ));
        InterpretationPlan.Step step = new InterpretationPlan.Step(
            7, "mcp_tool", "mcp_chatchat_mcp_server_sql_query_execute", Map.of(), List.of(), null, null);

        method.invoke(runtime, step, input);

        assertThat(input.get("parameterProtocol")).isNotNull();
        assertThat(input).doesNotContainKey("runtimeParameterProtocolApplied");
    }

    @Test
    void runtimeOwnedTemplateBindingOverridesModelProtocolAndInvocationWithoutBusinessHardcoding() throws Exception {
        InterpretationPlanRuntime runtime = new InterpretationPlanRuntime(
            mock(ToolRuntimeService.class), new InterpretationPlanValidator(), scriptedController(List.of()));
        Method method = InterpretationPlanRuntime.class.getDeclaredMethod(
            "normalizeModelInvocationEnvelope", InterpretationPlan.Step.class, Map.class);
        method.setAccessible(true);
        Map<String, Object> input = new java.util.LinkedHashMap<>();
        input.put("templateId", "RUNTIME_DISCOVERED_TEMPLATE");
        input.put("runtimeTemplateBinding", Map.of(
            "schemaVersion", "runtime_template_binding.v1",
            "source", "plan_binding_from_template_discovery",
            "templateId", "RUNTIME_DISCOVERED_TEMPLATE",
            "executorTool", "mcp_chatchat_mcp_server_sql_query_execute"
        ));
        input.put("parameterProtocol", Map.of(
            "protocol_version", InterpretationExecutionProtocol.TEMPLATE_PARAMETER_PROTOCOL_VERSION,
            "step_id", 7,
            "template_id", "MODEL_CONFUSED_ASSET_WITH_TEMPLATE",
            "arguments", Map.of("trade_date", Map.of(
                "value", "20260721",
                "source", "user_query",
                "evidence", "today"
            )),
            "unresolved_parameters", List.of()
        ));
        input.put("invocation", Map.of(
            "templateRef", "MODEL_SECOND_OVERRIDE_ATTEMPT",
            "arguments", Map.of("trade_date", "20260721")
        ));
        InterpretationPlan.Step step = new InterpretationPlan.Step(
            7, "mcp_tool", "mcp_chatchat_mcp_server_sql_query_execute", Map.of(), List.of(), null, null);

        method.invoke(runtime, step, input);

        assertThat(input.get("templateId")).isEqualTo("RUNTIME_DISCOVERED_TEMPLATE");
        assertThat(input.get("template")).isEqualTo("RUNTIME_DISCOVERED_TEMPLATE");
        assertThat(input.get("parameters")).isEqualTo(Map.of("trade_date", "20260721"));
        assertThat(input.get("parameterProtocol")).isNotNull();
        assertThat(input).doesNotContainKey("runtimeParameterProtocolApplied");
    }

    @Test
    void failsBeforeMcpExecutionWhenTemplateRequiredParameterIsMissing() {
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.hasTool("mcp_chatchat_mcp_server_sql_datasource_template_query")).thenReturn(true);
        when(toolRegistry.hasTool("mcp_chatchat_mcp_server_sql_query_execute")).thenReturn(true);
        when(toolRegistry.getToolMetadata(any())).thenReturn(ToolMetadata.builder().riskLevel("low").build());
        ToolRuntimeService toolRuntimeService = mock(ToolRuntimeService.class);
        when(toolRuntimeService.execute(any())).thenAnswer(invocation -> {
            ToolRuntimeRequest request = invocation.getArgument(0);
            if ("mcp_chatchat_mcp_server_sql_datasource_template_query".equals(request.getToolName())) {
                return new ToolRuntimeExecution(
                    ToolOutput.success(Map.of(
                        "templates", List.of(Map.of(
                            "templateId", "MYSQL_TABLE_METADATA",
                            "parameterSchema", Map.of(
                                "type", "object",
                                "properties", Map.of("tableName", Map.of("type", "string")),
                                "required", List.of("tableName")
                            ),
                            "requiredParameters", List.of("tableName")
                        ))
                    )),
                    ToolMetadata.builder().id(request.getToolName()).build(),
                    null,
                    "success",
                    Map.of()
                );
            }
            return new ToolRuntimeExecution(
                ToolOutput.success(Map.of("status", "should-not-run")),
                ToolMetadata.builder().id(request.getToolName()).build(),
                null,
                "success",
                Map.of()
            );
        });
        InterpretationPlan plan = new InterpretationPlan(
            "1.0",
            new InterpretationPlan.Intent("data_query", "Query table metadata", "low"),
            context(),
            new InterpretationPlan.Plan(
                List.of(
                    new InterpretationPlan.Step(1, "mcp_tool", "mcp_chatchat_mcp_server_sql_datasource_template_query",
                        Map.of("filters", Map.of("intent", "table metadata"), "finalDecision", "database"), List.of(), null, null),
                    new InterpretationPlan.Step(2, "mcp_tool", "mcp_chatchat_mcp_server_sql_query_execute",
                        Map.of(
                            "executionContext", Map.of("assetName", "test_mysql_database", "env", "DEV"),
                            "parameters", Map.of()
                        ), List.of(1), null, null),
                    new InterpretationPlan.Step(3, "final_answer", "", Map.of("answer", "done"), List.of(2), null, null)
                ),
                List.of(),
                List.of(new InterpretationPlan.Binding(1, "$.templates[0].templateId", 2, "templateId", "jsonpath", true)),
                null
            ),
            new InterpretationPlan.ExecutionPolicy(
                3,
                false,
                List.of("mcp_chatchat_mcp_server_sql_datasource_template_query", "mcp_chatchat_mcp_server_sql_query_execute"),
                List.of(),
                30000
            ),
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
            List.of("mcp_chatchat_mcp_server_sql_datasource_template_query", "mcp_chatchat_mcp_server_sql_query_execute"),
            "tenant-1",
            "req-template-required-missing",
            "conv-template-required-missing",
            "user-1",
            Map.of("requireTemplateParameterProtocol", true)
        ));

        assertThat(result.success()).isFalse();
        assertThat(result.errorMessage())
            .contains("TEMPLATE_PARAMETER_PROTOCOL_REQUIRED", "tableName");
        verify(toolRuntimeService, times(1)).execute(any());
    }

    @Test
    void reviewsUniqueAssetAndExecutesDependentLinuxCommand() {
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.hasTool("mcp_chatchat_mcp_server_ssh_asset_query")).thenReturn(true);
        when(toolRegistry.hasTool("mcp_chatchat_mcp_server_linux_command_execute")).thenReturn(true);
        when(toolRegistry.getToolMetadata("mcp_chatchat_mcp_server_ssh_asset_query"))
            .thenReturn(ToolMetadata.builder().id("mcp_chatchat_mcp_server_ssh_asset_query").riskLevel("low").build());
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
            if ("mcp_chatchat_mcp_server_ssh_asset_query".equals(request.getToolName())) {
                return new ToolRuntimeExecution(
                    ToolOutput.success(Map.of(
                        "schemaVersion", "asset_query_result.v1",
                        "success", true,
                        "returnedCount", 1,
                        "assets", List.of(Map.of(
                            "asset", Map.of(
                                "name", "Docker Service Host",
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
            if ("mcp_chatchat_mcp_server_ssh_asset_query".equals(request.execution().toolName())) {
                assetReviewCalls.incrementAndGet();
                return InterpretationPlanRuntime.StepReview.accepted(
                    "the returned host matches the requested docker service", Map.of(
                        "selectedAssetIds", List.of("Docker Service Host")
                    ));
            }
            return InterpretationPlanRuntime.StepReview.accepted("command output usable", Map.of());
        };
        InterpretationPlan plan = new InterpretationPlan(
            "1.0",
            new InterpretationPlan.Intent("system_operation", "Analyze docker_service load", "medium"),
            context(),
            new InterpretationPlan.Plan(
                List.of(
                    new InterpretationPlan.Step(1, "mcp_tool", "mcp_chatchat_mcp_server_ssh_asset_query",
                        Map.of("filters", Map.of("assetName", "docker_service"), "limit", 10), List.of(), null, null),
                    new InterpretationPlan.Step(2, "mcp_tool", "mcp_chatchat_mcp_server_linux_command_execute",
                        Map.of("template", "CHECK_SYSTEM_OVERVIEW", "executionContext",
                            Map.of("assetName", "<to-be-bound-from-step1>")), List.of(1), null, null),
                    new InterpretationPlan.Step(3, "final_answer", "", Map.of("answer", "done"), List.of(2), null, null)
                ),
                List.of(),
                List.of(
                    new InterpretationPlan.Binding(1, "$.assets[0].asset.environment", 2,
                        "executionContext.env", "jsonpath", true)
                ),
                null
            ),
            new InterpretationPlan.ExecutionPolicy(
                3,
                false,
                List.of("mcp_chatchat_mcp_server_ssh_asset_query", "mcp_chatchat_mcp_server_linux_command_execute"),
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
            List.of("mcp_chatchat_mcp_server_ssh_asset_query", "mcp_chatchat_mcp_server_linux_command_execute"),
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
        assertThat(assetReviewCalls).hasValue(1);
        assertThat(result.steps().get(0).metadata())
            .containsEntry("localFactCheckHasEvidence", true)
            .containsEntry("assetDiscoveryReturnedCount", 1)
            .containsEntry("semanticCandidateReviewSatisfied", true)
            .doesNotContainKey("toolResultReviewSkipped");
        assertThat(captor.getAllValues().get(1).getToolName()).isEqualTo("mcp_chatchat_mcp_server_linux_command_execute");
        assertThat(linuxParameters.get("template")).isEqualTo("CHECK_SYSTEM_OVERVIEW");
        assertThat(executionContext.get("assetName")).isEqualTo("Docker Service Host");
        assertThat(executionContext.get("env")).isEqualTo("DEV");
    }

    @Test
    void rejectsDependentExecutionWhenPlannedAssetDiffersFromUniqueDiscovery() {
        String assetTool = "mcp_chatchat_mcp_server_ssh_asset_query";
        String commandTool = "mcp_chatchat_mcp_server_linux_command_execute";
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.hasTool(any())).thenReturn(true);
        when(toolRegistry.getToolMetadata(any())).thenAnswer(invocation -> ToolMetadata.builder()
            .id(invocation.getArgument(0)).riskLevel("low").build());
        ToolRuntimeService toolRuntimeService = mock(ToolRuntimeService.class);
        when(toolRuntimeService.execute(any())).thenReturn(new ToolRuntimeExecution(
            ToolOutput.success(Map.of(
                "schemaVersion", "asset_query_result.v1",
                "returnedCount", 1,
                "assets", List.of(Map.of("asset", Map.of(
                    "id", "worker11-id",
                    "name", "CDH DataNode 节点 worker11",
                    "environment", "DEV"
                )))
            )),
            ToolMetadata.builder().id(assetTool).build(), null, "success", Map.of()
        ));
        InterpretationPlan plan = new InterpretationPlan(
            "1.0",
            new InterpretationPlan.Intent("system_operation", "Analyze worker11", "medium"),
            context(),
            new InterpretationPlan.Plan(List.of(
                new InterpretationPlan.Step(1, "mcp_tool", assetTool,
                    Map.of("filters", Map.of("assetName", "worker11")), List.of(), null, null),
                new InterpretationPlan.Step(2, "mcp_tool", commandTool,
                    Map.of("template", "CHECK_HOSTNAME", "executionContext",
                        Map.of("assetName", "ADP 平台开发数据库", "env", "DEV")),
                    List.of(1), null, null),
                new InterpretationPlan.Step(3, "final_answer", "", Map.of("answer", "done"),
                    List.of(2), null, null)
            )),
            new InterpretationPlan.ExecutionPolicy(3, false,
                List.of(assetTool, commandTool), List.of(), 30_000),
            review()
        );
        InterpretationPlanRuntime runtime = new InterpretationPlanRuntime(
            toolRuntimeService,
            new InterpretationPlanValidator(),
            null,
            request -> InterpretationPlanRuntime.StepReview.accepted("worker11 is the requested asset", Map.of(
                "selectedAssetIds", List.of("worker11-id")
            )),
            scriptedController(List.of(List.of(1), List.of(2), List.of(3)))
        );

        InterpretationPlanRuntime.ExecutionResult result = runtime.execute(
            new InterpretationPlanRuntime.ExecutionRequest(plan, toolRegistry,
                List.of(assetTool, commandTool), "tenant", "req-asset-mismatch",
                "conversation", "user", Map.of())
        );

        assertThat(result.success()).isFalse();
        assertThat(result.errorMessage())
            .contains("ASSET_CONTEXT_MISMATCH", "ADP 平台开发数据库", "CDH DataNode 节点 worker11");
        verify(toolRuntimeService, times(1)).execute(any());
    }

    @Test
    void compilesFixedModelInvocationEnvelopeByResolvingMcpTemplateContract() {
        String discoveryTool = "mcp_chatchat_mcp_server_database_ops_template_search";
        String executorTool = "mcp_chatchat_mcp_server_sql_query_execute";
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.hasTool(any())).thenReturn(true);
        when(toolRegistry.getToolMetadata(any())).thenReturn(ToolMetadata.builder().riskLevel("low").build());
        ToolRuntimeService toolRuntimeService = mock(ToolRuntimeService.class);
        when(toolRuntimeService.execute(any())).thenAnswer(invocation -> {
            ToolRuntimeRequest runtimeRequest = invocation.getArgument(0);
            if (discoveryTool.equals(runtimeRequest.getToolName())) {
                return new ToolRuntimeExecution(
                    ToolOutput.success(Map.of("templates", List.of(Map.of(
                        "templateId", "MYSQL_TABLE_METADATA",
                        "parameterSchema", Map.of(
                            "type", "object",
                            "properties", Map.of("tableName", Map.of("type", "string")),
                            "required", List.of("tableName")
                        ),
                        "sqlExecutionBinding", Map.of(
                            "toolName", "sql_query_execute",
                            "executionContext", Map.of("assetName", "248-test-db", "env", "DEV")
                        )
                    )))),
                    ToolMetadata.builder().id(discoveryTool).build(), null, "success", Map.of());
            }
            return new ToolRuntimeExecution(
                ToolOutput.success(Map.of("rows", List.of())),
                ToolMetadata.builder().id(executorTool).build(), null, "success", Map.of());
        });
        InterpretationPlan plan = new InterpretationPlan(
            "1.0",
            new InterpretationPlan.Intent("data_query", "Query table metadata", "low"),
            context(),
            new InterpretationPlan.Plan(
                List.of(
                    new InterpretationPlan.Step(1, "mcp_tool", executorTool, Map.of(
                        "parameterProtocol", userQueryParameterProtocol(
                            1, "MYSQL_TABLE_METADATA", "user_info_file",
                            Map.of("table", "user_info_file")),
                        "toolCall", Map.of(
                            "toolName", executorTool,
                            "action", "MYSQL_TABLE_METADATA",
                            "parameters", Map.of("table", "user_info_file"),
                            "context", Map.of(
                                "purpose", "query table metadata",
                                "stepId", "step-1",
                                "dependsOn", List.of(),
                                "target", Map.of("assetName", "248-test-db", "env", "DEV")
                            )
                        )
                    ), List.of(), null, null),
                    new InterpretationPlan.Step(2, "final_answer", "", Map.of("answer", "done"), List.of(1), null, null)
                ),
                List.of(), List.of(), null
            ),
            new InterpretationPlan.ExecutionPolicy(2, false, List.of(executorTool), List.of(), 30000),
            review()
        );
        InterpretationPlanRuntime runtime = new InterpretationPlanRuntime(
            toolRuntimeService,
            new InterpretationPlanValidator(),
            scriptedController(List.of(List.of(1), List.of(2)))
        );

        InterpretationPlanRuntime.ExecutionResult result = runtime.execute(new InterpretationPlanRuntime.ExecutionRequest(
            plan, toolRegistry, List.of(discoveryTool, executorTool), "tenant-1", "req-compiled-invocation",
            "conv-compiled-invocation", "user-1",
            Map.of("originalUserQuery", "Query user_info_file table metadata")
        ));

        ArgumentCaptor<ToolRuntimeRequest> captor = ArgumentCaptor.forClass(ToolRuntimeRequest.class);
        verify(toolRuntimeService, times(2)).execute(captor.capture());
        assertThat(result.success()).isTrue();
        assertThat(captor.getAllValues().get(0).getRuntimeMode())
            .isEqualTo("interpretation_plan_argument_resolution");
        Map<?, ?> executorInput = captor.getAllValues().get(1).getToolInput().getParameters();
        assertThat(executorInput.get("templateId")).isEqualTo("MYSQL_TABLE_METADATA");
        assertThat(executorInput.get("template")).isEqualTo("MYSQL_TABLE_METADATA");
        assertThat(((Map<?, ?>) executorInput.get("parameters")).get("tableName"))
            .isEqualTo("user_info_file");
        assertThat(executorInput.containsKey("toolCall")).isFalse();
    }

    @Test
    void hydratesMissingLinuxTemplateFromUniqueTemplateDiscoveryResult() {
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.hasTool("mcp_chatchat_mcp_server_ssh_template_query")).thenReturn(true);
        when(toolRegistry.hasTool("mcp_chatchat_mcp_server_linux_command_execute")).thenReturn(true);
        when(toolRegistry.getToolMetadata("mcp_chatchat_mcp_server_ssh_template_query"))
            .thenReturn(ToolMetadata.builder().id("mcp_chatchat_mcp_server_ssh_template_query").riskLevel("low").build());
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
            if ("mcp_chatchat_mcp_server_ssh_template_query".equals(request.getToolName())) {
                return new ToolRuntimeExecution(
                    ToolOutput.success(Map.of(
                        "schemaVersion", "template_query_result.v1",
                        "success", true,
                        "templates", List.of(Map.of(
                            "templateId", "CHECK_SYSTEM_OVERVIEW",
                            "name", "System overview",
                            "parameterContract", Map.of(
                                "executionTool", "linux_command_execute",
                                "required", List.of()
                            ),
                            "invocationExample", Map.of(
                                "tool", "linux_command_execute",
                                "templateId", "CHECK_SYSTEM_OVERVIEW",
                                "parameters", Map.of()
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
        InterpretationPlan plan = new InterpretationPlan(
            "1.0",
            new InterpretationPlan.Intent("system_operation", "Analyze host system status", "medium"),
            context(),
            new InterpretationPlan.Plan(List.of(
                new InterpretationPlan.Step(1, "mcp_tool", "mcp_chatchat_mcp_server_ssh_template_query",
                    Map.of("query", "system overview", "limit", 1), List.of(), null, null),
                new InterpretationPlan.Step(2, "mcp_tool", "mcp_chatchat_mcp_server_linux_command_execute",
                    Map.of("executionContext", Map.of("assetName", "MySQL服务器", "env", "DEV")), List.of(1), null, null),
                new InterpretationPlan.Step(3, "final_answer", "", Map.of("answer", "done"), List.of(2), null, null)
            )),
            new InterpretationPlan.ExecutionPolicy(
                3,
                false,
                List.of("mcp_chatchat_mcp_server_ssh_template_query", "mcp_chatchat_mcp_server_linux_command_execute"),
                List.of(),
                30000
            ),
            review()
        );
        InterpretationPlanRuntime runtime = new InterpretationPlanRuntime(
            toolRuntimeService,
            new InterpretationPlanValidator(),
            null,
            null,
            scriptedController(List.of(List.of(1), List.of(2), List.of(3)))
        );

        InterpretationPlanRuntime.ExecutionResult result = runtime.execute(new InterpretationPlanRuntime.ExecutionRequest(
            plan,
            toolRegistry,
            List.of("mcp_chatchat_mcp_server_ssh_template_query", "mcp_chatchat_mcp_server_linux_command_execute"),
            "tenant-1",
            "req-linux-template-hydration",
            "conv-linux-template-hydration",
            "user-1",
            Map.of()
        ));

        assertThat(result.success())
            .as(result.status() + ": " + result.errorMessage() + " steps=" + result.steps())
            .isTrue();
        ArgumentCaptor<ToolRuntimeRequest> captor = ArgumentCaptor.forClass(ToolRuntimeRequest.class);
        verify(toolRuntimeService, times(2)).execute(captor.capture());
        Map<?, ?> linuxParameters = captor.getAllValues().get(1).getToolInput().getParameters();
        assertThat(linuxParameters.get("template")).isEqualTo("CHECK_SYSTEM_OVERVIEW");
        assertThat(linuxParameters.get("templateId")).isEqualTo("CHECK_SYSTEM_OVERVIEW");
        assertThat(linuxParameters.get("runtimeTemplateBinding").toString())
            .contains("runtime_template_binding.v1", "CHECK_SYSTEM_OVERVIEW");
    }

    @Test
    void requiresReviewerForUniqueDeterministicAssetFacts() {
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.hasTool("mcp_chatchat_mcp_server_sql_datasource_asset_query")).thenReturn(true);
        when(toolRegistry.getToolMetadata("mcp_chatchat_mcp_server_sql_datasource_asset_query"))
            .thenReturn(ToolMetadata.builder().id("mcp_chatchat_mcp_server_sql_datasource_asset_query").riskLevel("low").build());
        ToolRuntimeService toolRuntimeService = mock(ToolRuntimeService.class);
        when(toolRuntimeService.execute(any())).thenReturn(new ToolRuntimeExecution(
            ToolOutput.success(Map.of(
                "schemaVersion", "asset_query_result.v1",
                "success", true,
                "returnedCount", 1,
                "assets", List.of(Map.of(
                    "asset", Map.of(
                        "name", "248-test-db",
                        "environment", "DEV",
                        "toolName", "db_query_mysql_248_test_db"
                    )
                ))
            )),
            ToolMetadata.builder().id("mcp_chatchat_mcp_server_sql_datasource_asset_query").build(),
            null,
            "success",
            Map.of()
        ));
        AtomicInteger reviewerCalls = new AtomicInteger();
        InterpretationPlanRuntime.StepResultReviewer reviewer = request -> {
            reviewerCalls.incrementAndGet();
            return InterpretationPlanRuntime.StepReview.accepted(
                "the candidate matches the requested database asset",
                Map.of("reviewed", true, "selectedAssetIds", List.of("248-test-db"))
            );
        };
        InterpretationPlan plan = new InterpretationPlan(
            "1.0",
            new InterpretationPlan.Intent("sql_metadata", "Analyze 248-test-db", "low"),
            context(),
            new InterpretationPlan.Plan(
                List.of(
                    new InterpretationPlan.Step(1, "mcp_tool", "mcp_chatchat_mcp_server_sql_datasource_asset_query",
                        Map.of(
                            "candidates", List.of(Map.of("targetKind", "database", "confidence", 0.9)),
                            "finalDecision", "database",
                            "filters", Map.of("assetName", "248-test-db"),
                            "limit", 5
                        ), List.of(), null, null),
                    new InterpretationPlan.Step(2, "final_answer", "", Map.of("answer", "done"), List.of(1), null, null)
                ),
                List.of(),
                List.of(),
                null
            ),
            new InterpretationPlan.ExecutionPolicy(
                2,
                false,
                List.of("mcp_chatchat_mcp_server_sql_datasource_asset_query"),
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
            scriptedController(List.of(List.of(1), List.of(2)))
        );

        InterpretationPlanRuntime.ExecutionResult result = runtime.execute(new InterpretationPlanRuntime.ExecutionRequest(
            plan,
            toolRegistry,
            List.of("mcp_chatchat_mcp_server_sql_datasource_asset_query"),
            "tenant-1",
            "req-asset-contradiction",
            "conv-asset-contradiction",
            "user-1",
            Map.of()
        ));

        assertThat(result.success())
            .as(result.status() + ": " + result.errorMessage() + " steps=" + result.steps())
            .isTrue();
        assertThat(reviewerCalls).hasValue(1);
        assertThat(result.finalAnswer()).isEqualTo("done");
        assertThat(result.steps().get(0).metadata())
            .containsEntry("localFactCheckHasEvidence", true)
            .containsEntry("assetDiscoveryReturnedCount", 1)
            .containsEntry("semanticCandidateReviewSatisfied", true)
            .doesNotContainKey("toolResultReviewSkipped");
    }

    @Test
    void hydratesPriorObservationOutputForRewritePlanBindings() {
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.hasTool("mcp_chatchat_mcp_server_sql_datasource_asset_query")).thenReturn(true);
        when(toolRegistry.hasTool("mcp_chatchat_mcp_server_sql_datasource_template_query")).thenReturn(true);
        when(toolRegistry.hasTool("mcp_chatchat_mcp_server_sql_query_execute")).thenReturn(true);
        when(toolRegistry.getToolMetadata(any())).thenAnswer(invocation -> {
            String toolName = invocation.getArgument(0);
            ToolMetadata.ToolMetadataBuilder builder = ToolMetadata.builder()
                .id(toolName)
                .riskLevel("low");
            if ("mcp_chatchat_mcp_server_sql_query_execute".equals(toolName)) {
                builder.parameters(List.of(
                    ToolParameter.builder().name("templateId").type("string").required(true).build(),
                    ToolParameter.builder().name("executionContext").type("object").required(true).build()
                ));
            }
            return builder.build();
        });
        ToolRuntimeService toolRuntimeService = mock(ToolRuntimeService.class);
        when(toolRuntimeService.execute(any())).thenAnswer(invocation -> {
            ToolRuntimeRequest request = invocation.getArgument(0);
            return new ToolRuntimeExecution(
                ToolOutput.success(Map.of("status", "ok", "parameters", request.getToolInput().getParameters())),
                ToolMetadata.builder().id(request.getToolName()).build(),
                null,
                "success",
                Map.of()
            );
        });
        InMemoryAgentRunStore runStore = new InMemoryAgentRunStore();
        String runId = "rewrite-hydrate-run";
        runStore.recordObservation(runId, AgentObservation.builder()
            .type("tool")
            .source("mcp_chatchat_mcp_server_sql_datasource_asset_query")
            .content("asset_query completed")
            .metadata(Map.of(
                "interpretationPlanStepId", 1,
                "interpretationPlanActionType", "mcp_tool",
                "toolName", "mcp_chatchat_mcp_server_sql_datasource_asset_query",
                "success", true,
                "stepOutput", Map.of(
                    "assets", List.of(Map.of(
                        "asset", Map.of("name", "閺堫剙婀碝ySQL濞村鐦張宥呭", "environment", "DEV")
                    ))
                )
            ))
            .build());
        runStore.recordObservation(runId, AgentObservation.builder()
            .type("tool")
            .source("mcp_chatchat_mcp_server_sql_datasource_template_query")
            .content("template_query completed")
            .metadata(Map.of(
                "interpretationPlanStepId", 2,
                "interpretationPlanActionType", "mcp_tool",
                "toolName", "mcp_chatchat_mcp_server_sql_datasource_template_query",
                "success", true,
                "stepOutput", Map.of(
                    "templates", List.of(Map.of("templateId", "MYSQL_INNODB_STATUS"))
                )
            ))
            .build());
        InterpretationPlan plan = new InterpretationPlan(
            "1.0",
            new InterpretationPlan.Intent("sql_query", "Analyze InnoDB status", "low"),
            context(),
            new InterpretationPlan.Plan(
                List.of(
                    new InterpretationPlan.Step(1, "mcp_tool", "mcp_chatchat_mcp_server_sql_datasource_asset_query",
                        Map.of("filters", Map.of("assetName", "閺堫剙婀碝ySQL濞村鐦張宥呭"), "limit", 10), List.of(), null, null),
                    new InterpretationPlan.Step(2, "mcp_tool", "mcp_chatchat_mcp_server_sql_datasource_template_query",
                        Map.of("filters", Map.of("intent", "InnoDB status"), "limit", 10), List.of(1), null, null),
                    new InterpretationPlan.Step(3, "mcp_tool", "mcp_chatchat_mcp_server_sql_query_execute",
                        Map.of("templateId", "", "parameters", Map.of()), List.of(1, 2), null, null),
                    new InterpretationPlan.Step(4, "final_answer", "", Map.of("answer", "done"), List.of(3), null, null)
                ),
                List.of(),
                List.of(
                    new InterpretationPlan.Binding(1, "$.assets[0].asset.name", 3, "executionContext.assetName", "jsonpath", true),
                    new InterpretationPlan.Binding(1, "$.assets[0].asset.environment", 3, "executionContext.env", "jsonpath", false),
                    new InterpretationPlan.Binding(2, "$.templates[0].templateId", 3, "templateId", "jsonpath", true)
                ),
                null
            ),
            new InterpretationPlan.ExecutionPolicy(
                4,
                false,
                List.of(
                    "mcp_chatchat_mcp_server_sql_datasource_asset_query",
                    "mcp_chatchat_mcp_server_sql_datasource_template_query",
                    "mcp_chatchat_mcp_server_sql_query_execute"
                ),
                List.of(),
                30000
            ),
            review()
        );
        InterpretationPlanRuntime runtime = new InterpretationPlanRuntime(
            toolRuntimeService,
            new InterpretationPlanValidator(),
            runStore,
            scriptedController(List.of(List.of(4)))
        );

        InterpretationPlanRuntime.ExecutionResult result = runtime.execute(new InterpretationPlanRuntime.ExecutionRequest(
            plan,
            toolRegistry,
            List.of(
                "mcp_chatchat_mcp_server_sql_datasource_asset_query",
                "mcp_chatchat_mcp_server_sql_datasource_template_query",
                "mcp_chatchat_mcp_server_sql_query_execute"
            ),
            "tenant-1",
            "req-rewrite-hydrate",
            "conv-rewrite-hydrate",
            "user-1",
            Map.of("__agentRunId", runId)
        ));

        assertThat(result.success())
            .as(result.status() + ": " + result.errorMessage() + " steps=" + result.steps())
            .isTrue();
        assertThat(result.steps())
            .extracting(InterpretationPlanRuntime.StepExecution::stepId)
            .containsExactly(3, 4);
        assertThat(result.metadata().get("completedPlanStepIds"))
            .isEqualTo(List.of(1, 2, 3, 4));
        assertThat(result.finalAnswer()).isEqualTo("done");
        ArgumentCaptor<ToolRuntimeRequest> captor = ArgumentCaptor.forClass(ToolRuntimeRequest.class);
        verify(toolRuntimeService, times(1)).execute(captor.capture());
        Map<?, ?> parameters = captor.getValue().getToolInput().getParameters();
        assertThat(((List<?>) captor.getValue().getAttributes().get("workflowCompletedTools")).stream()
            .map(String::valueOf).toList())
            .contains("mcp_chatchat_mcp_server_sql_datasource_asset_query",
                "mcp_chatchat_mcp_server_sql_datasource_template_query");
        Map<?, ?> executionContext = (Map<?, ?>) parameters.get("executionContext");
        assertThat(parameters.get("templateId")).isEqualTo("MYSQL_INNODB_STATUS");
        assertThat(executionContext.get("assetName")).isEqualTo("閺堫剙婀碝ySQL濞村鐦張宥呭");
        assertThat(executionContext.get("env")).isEqualTo("DEV");
    }

    @Test
    void carriesRootTargetAliasAcrossDependentDagTools() {
        String namespace = "mcp_runtime_" + System.nanoTime() + "_";
        String assetTool = namespace + "asset_query";
        String templateTool = namespace + "template_query";
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        for (String tool : List.of(assetTool, templateTool)) {
            when(toolRegistry.hasTool(tool)).thenReturn(true);
            when(toolRegistry.getToolMetadata(tool)).thenReturn(ToolMetadata.builder()
                .id(tool).riskLevel("low").build());
        }
        ToolRuntimeService toolRuntimeService = mock(ToolRuntimeService.class);
        when(toolRuntimeService.execute(any())).thenReturn(new ToolRuntimeExecution(
            ToolOutput.success(Map.of("ok", true)),
            ToolMetadata.builder().id("dynamic").build(),
            null,
            "success",
            Map.of()
        ));
        InterpretationPlan plan = new InterpretationPlan(
            "1.0",
            new InterpretationPlan.Intent("diagnostic", "Inspect generated target", "low"),
            context(),
            new InterpretationPlan.Plan(List.of(
                new InterpretationPlan.Step(1, "mcp_tool", assetTool,
                    Map.of("filters", Map.of("assetName", "generated-target-alias")), List.of(), null, null),
                new InterpretationPlan.Step(2, "mcp_tool", templateTool,
                    Map.of("executionContext", Map.of("assetId", "generated-canonical-id")), List.of(1), null, null),
                new InterpretationPlan.Step(3, "final_answer", "",
                    Map.of("answer", "done"), List.of(2), null, null)
            )),
            new InterpretationPlan.ExecutionPolicy(3, false,
                List.of(assetTool, templateTool), List.of(), 30000),
            review()
        );
        InterpretationPlanRuntime runtime = new InterpretationPlanRuntime(
            toolRuntimeService,
            new InterpretationPlanValidator(),
            scriptedController(List.of(List.of(3)))
        );

        InterpretationPlanRuntime.ExecutionResult result = runtime.execute(
            new InterpretationPlanRuntime.ExecutionRequest(
                plan,
                toolRegistry,
                List.of(assetTool, templateTool),
                "generated-tenant",
                "generated-request",
                "generated-conversation",
                "generated-user",
                Map.of()
            )
        );

        assertThat(result.steps()).extracting(InterpretationPlanRuntime.StepExecution::stepId)
            .contains(1, 2);
        ArgumentCaptor<ToolRuntimeRequest> captor = ArgumentCaptor.forClass(ToolRuntimeRequest.class);
        verify(toolRuntimeService, times(2)).execute(captor.capture());
        ToolRuntimeRequest dependentRequest = captor.getAllValues().get(1);
        assertThat(dependentRequest.getAttributes().get("workflowCompletedTools"))
            .asString().contains(assetTool);
        assertThat(dependentRequest.getAttributes().get("workflowContext"))
            .isEqualTo(Map.of("workflowTargetRef", "generated-target-alias"));
    }

    @Test
    void doesNotReuseCompletedStepIdWhenRewriteChangesTheToolIdentity() {
        String rewrittenTool = "mcp_chatchat_mcp_server_sql_metadata_search";
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.hasTool(rewrittenTool)).thenReturn(true);
        when(toolRegistry.getToolMetadata(rewrittenTool))
            .thenReturn(ToolMetadata.builder().id(rewrittenTool).riskLevel("low").build());
        ToolRuntimeService toolRuntimeService = mock(ToolRuntimeService.class);
        when(toolRuntimeService.execute(any())).thenReturn(new ToolRuntimeExecution(
            ToolOutput.success(Map.of("tableCatalog", List.of(Map.of("tableName", "dwd_var_scr_code_info_d")))),
            ToolMetadata.builder().id(rewrittenTool).build(),
            null,
            "success",
            Map.of()
        ));
        InMemoryAgentRunStore runStore = new InMemoryAgentRunStore();
        String runId = "rewrite-step-identity-run";
        runStore.recordObservation(runId, AgentObservation.builder()
            .type("tool")
            .source("mcp_chatchat_mcp_server_database_asset_search")
            .content("old step 1 completed")
            .metadata(Map.of(
                "interpretationPlanStepId", 1,
                "interpretationPlanActionType", "mcp_tool",
                "toolName", "mcp_chatchat_mcp_server_database_asset_search",
                "success", true,
                "stepOutput", Map.of("assets", List.of(Map.of("name", "TDH数据仓库")))
            ))
            .build());
        InterpretationPlan plan = new InterpretationPlan(
            "1.0",
            new InterpretationPlan.Intent("metadata_search", "Find rewritten table metadata", "low"),
            context(),
            new InterpretationPlan.Plan(List.of(
                new InterpretationPlan.Step(
                    1,
                    "mcp_tool",
                    rewrittenTool,
                    Map.of("schema", "gdp_dwd", "limit", 50, "includeColumns", false),
                    List.of(),
                    null,
                    null
                ),
                new InterpretationPlan.Step(
                    2,
                    "final_answer",
                    "",
                    Map.of("answer", "done"),
                    List.of(1),
                    null,
                    null
                )
            )),
            new InterpretationPlan.ExecutionPolicy(
                2,
                false,
                List.of(rewrittenTool),
                List.of(),
                30000
            ),
            review()
        );
        InterpretationPlanRuntime runtime = new InterpretationPlanRuntime(
            toolRuntimeService,
            new InterpretationPlanValidator(),
            runStore,
            scriptedController(List.of(List.of(2)))
        );

        InterpretationPlanRuntime.ExecutionResult result = runtime.execute(
            new InterpretationPlanRuntime.ExecutionRequest(
                plan,
                toolRegistry,
                List.of(rewrittenTool),
                "tenant-1",
                "req-rewrite-step-identity",
                "conv-rewrite-step-identity",
                "user-1",
                Map.of("__agentRunId", runId)
            )
        );

        assertThat(result.success()).isTrue();
        assertThat(result.steps())
            .extracting(InterpretationPlanRuntime.StepExecution::stepId)
            .containsExactly(1, 2);
        assertThat(result.metadata().get("completedPlanStepIds")).isEqualTo(List.of(1, 2));
        ArgumentCaptor<ToolRuntimeRequest> requestCaptor = ArgumentCaptor.forClass(ToolRuntimeRequest.class);
        verify(toolRuntimeService, times(1)).execute(requestCaptor.capture());
        assertThat(requestCaptor.getValue().getToolName()).isEqualTo(rewrittenTool);
    }

    @Test
    void alwaysExecutesCurrentFinalAnswerStepInsteadOfHydratingAnEarlierRevision() {
        InMemoryAgentRunStore runStore = new InMemoryAgentRunStore();
        String runId = "rewrite-stale-final-answer-run";
        runStore.recordObservation(runId, AgentObservation.builder()
            .type("tool")
            .source("final_answer")
            .content("old plan final answer completed")
            .metadata(Map.of(
                "interpretationPlanStepId", 1,
                "interpretationPlanActionType", "final_answer",
                "toolName", "final_answer",
                "success", true,
                "stepOutput", Map.of("answer", "stale answer")
            ))
            .build());
        InterpretationPlan plan = new InterpretationPlan(
            "1.0",
            new InterpretationPlan.Intent("evidence_synthesis", "Synthesize current evidence", "low"),
            context(),
            new InterpretationPlan.Plan(List.of(
                new InterpretationPlan.Step(
                    1,
                    "final_answer",
                    "final_answer",
                    Map.of("answer", "current answer"),
                    List.of(),
                    null,
                    null
                )
            )),
            new InterpretationPlan.ExecutionPolicy(
                1,
                false,
                List.of(),
                List.of(),
                30000
            ),
            review()
        );
        InterpretationPlanRuntime runtime = new InterpretationPlanRuntime(
            mock(ToolRuntimeService.class),
            new InterpretationPlanValidator(),
            runStore,
            scriptedController(List.of(List.of(1)))
        );

        InterpretationPlanRuntime.ExecutionResult result = runtime.execute(
            new InterpretationPlanRuntime.ExecutionRequest(
                plan,
                mock(ToolRegistry.class),
                List.of(),
                "tenant-1",
                "req-current-final-answer",
                "conv-current-final-answer",
                "user-1",
                Map.of("__agentRunId", runId)
            )
        );

        assertThat(result.success()).isTrue();
        assertThat(result.finalAnswer()).isEqualTo("current answer");
        assertThat(result.steps())
            .extracting(InterpretationPlanRuntime.StepExecution::stepId)
            .containsExactly(1);
        assertThat(result.metadata().get("requiredPlanStepIds")).isEqualTo(List.of(1));
        assertThat(result.metadata().get("completedPlanStepIds")).isEqualTo(List.of(1));
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
        assertThat(result.status()).isEqualTo("STEP_OUTPUT_CONTRACT_FAILED");
        assertThat(result.errorMessage()).contains("missing required field data.results");
        assertThat(result.steps()).hasSize(1);
        assertThat(result.steps().get(0).stepId()).isEqualTo(1);
        assertThat(result.steps().get(0).metadata())
            .containsEntry("outputContractSatisfied", false)
            .containsEntry("repairable", true)
            .containsEntry("repairAction", "rewrite_plan");
    }

    @Test
    void blocksDownstreamWhenStepOutputDoesNotMatchDeclaredContract() {
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.hasTool("document_search")).thenReturn(true);
        when(toolRegistry.getToolMetadata("document_search"))
            .thenReturn(ToolMetadata.builder().riskLevel("low").build());
        ToolRuntimeService toolRuntimeService = mock(ToolRuntimeService.class);
        when(toolRuntimeService.execute(any())).thenReturn(new ToolRuntimeExecution(
            ToolOutput.success("not-json"),
            ToolMetadata.builder().id("document_search").build(),
            null,
            "success",
            Map.of()
        ));
        InterpretationPlan plan = new InterpretationPlan(
            "1.0",
            new InterpretationPlan.Intent("document_retrieval", "Collect structured evidence", "low"),
            context(),
            new InterpretationPlan.Plan(List.of(
                new InterpretationPlan.Step(
                    1,
                    "mcp_tool",
                    "document_search",
                    Map.of("query", "internal"),
                    List.of(),
                    new InterpretationPlan.OutputContract("json", "object with items"),
                    new InterpretationPlan.Validation(true, "schema_check", null)
                ),
                new InterpretationPlan.Step(
                    2, "final_answer", "", Map.of("answer", "done"),
                    List.of(1), null, null
                )
            )),
            new InterpretationPlan.ExecutionPolicy(
                2, false, List.of("document_search"), List.of(), 30_000
            ),
            review()
        );
        InterpretationPlanRuntime runtime = new InterpretationPlanRuntime(
            toolRuntimeService,
            new InterpretationPlanValidator(),
            scriptedController(List.of(List.of(1), List.of(2)))
        );

        InterpretationPlanRuntime.ExecutionResult result = runtime.execute(
            new InterpretationPlanRuntime.ExecutionRequest(
                plan, toolRegistry, List.of("document_search"), "tenant-1",
                "req-output-contract", "conv-output-contract", "user-1", Map.of()
            )
        );

        assertThat(result.success()).isFalse();
        assertThat(result.status()).isEqualTo("STEP_OUTPUT_CONTRACT_FAILED");
        assertThat(result.errorMessage()).contains("expected json").contains("String");
        assertThat(result.steps()).hasSize(1);
        verify(toolRuntimeService, times(1)).execute(any());
    }

    @Test
    void acceptsStructuredMcpResultForTextOutputContract() {
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.hasTool("web_search")).thenReturn(true);
        when(toolRegistry.getToolMetadata("web_search"))
            .thenReturn(ToolMetadata.builder().riskLevel("low").build());
        ToolRuntimeService toolRuntimeService = mock(ToolRuntimeService.class);
        Map<String, Object> structuredResult = new java.util.LinkedHashMap<>();
        structuredResult.put("results", List.of(Map.of(
            "title", "Market update",
            "url", "https://example.test/market"
        )));
        structuredResult.put("query", "A-share market");
        when(toolRuntimeService.execute(any())).thenReturn(new ToolRuntimeExecution(
            ToolOutput.success(structuredResult),
            ToolMetadata.builder().id("web_search").build(),
            null,
            "success",
            Map.of()
        ));
        InterpretationPlan plan = new InterpretationPlan(
            "1.0",
            new InterpretationPlan.Intent("web_search", "Collect market evidence", "low"),
            context(),
            new InterpretationPlan.Plan(List.of(
                new InterpretationPlan.Step(
                    1,
                    "mcp_tool",
                    "web_search",
                    Map.of("query", "A-share market"),
                    List.of(),
                    new InterpretationPlan.OutputContract("text", "news titles and summaries"),
                    null
                ),
                new InterpretationPlan.Step(
                    2, "final_answer", "", Map.of("answer", "done"),
                    List.of(1), null, null
                )
            )),
            new InterpretationPlan.ExecutionPolicy(
                2, false, List.of("web_search"), List.of(), 30_000
            ),
            review()
        );
        InterpretationPlanRuntime runtime = new InterpretationPlanRuntime(
            toolRuntimeService,
            new InterpretationPlanValidator(),
            scriptedController(List.of(List.of(1), List.of(2)))
        );

        InterpretationPlanRuntime.ExecutionResult result = runtime.execute(
            new InterpretationPlanRuntime.ExecutionRequest(
                plan, toolRegistry, List.of("web_search"), "tenant-1",
                "req-text-contract", "conv-text-contract", "user-1", Map.of()
            )
        );

        assertThat(result.success()).isTrue();
        assertThat(result.status()).isEqualTo("completed");
        assertThat(result.steps()).hasSize(2);
        assertThat(result.steps().get(0).output()).isSameAs(structuredResult);
        verify(toolRuntimeService, times(1)).execute(any());
    }

    @Test
    void acceptsLogicalSearchResultContractAsSuccessfulWholeToolOutput() {
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.hasTool("web_search")).thenReturn(true);
        when(toolRegistry.getToolMetadata("web_search")).thenReturn(ToolMetadata.builder().riskLevel("low").build());
        ToolRuntimeService toolRuntimeService = mock(ToolRuntimeService.class);
        when(toolRuntimeService.execute(any())).thenReturn(new ToolRuntimeExecution(
            ToolOutput.success("news evidence and structured financial observations"),
            ToolMetadata.builder().id("web_search").build(),
            null,
            "success",
            Map.of()
        ));
        InterpretationPlan plan = new InterpretationPlan(
            "1.0",
            new InterpretationPlan.Intent("web_search", "Collect market evidence", "low"),
            context(),
            new InterpretationPlan.Plan(
                List.of(
                    new InterpretationPlan.Step(1, "mcp_tool", "web_search", Map.of("query", "market"), List.of(), null, null),
                    new InterpretationPlan.Step(2, "final_answer", "", Map.of("answer", "done"), List.of(1), null, null)
                ),
                List.of(new InterpretationPlan.EdgeContract(1, 2, "搜索结果", "string", true))
            ),
            new InterpretationPlan.ExecutionPolicy(2, false, List.of("web_search"), List.of(), 30000),
            review()
        );
        InterpretationPlanRuntime runtime = new InterpretationPlanRuntime(
            toolRuntimeService,
            new InterpretationPlanValidator(),
            scriptedController(List.of(List.of(1), List.of(2)))
        );

        InterpretationPlanRuntime.ExecutionResult result = runtime.execute(new InterpretationPlanRuntime.ExecutionRequest(
            plan, toolRegistry, List.of("web_search"), "tenant-1", "req-logical-result",
            "conv-logical-result", "user-1", Map.of()
        ));

        assertThat(result.success()).as(result.errorMessage()).isTrue();
    }

    @Test
    @SuppressWarnings("unchecked")
    void normalizesPlannerWebSearchAliasesBeforePublishedSchemaValidation() throws Exception {
        String toolName = "mcp_chatchat_mcp_server_web_search";
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.getToolMetadata(toolName)).thenReturn(ToolMetadata.builder()
            .id(toolName)
            .parameters(List.of(
                ToolParameter.builder().name("query").type("string").required(true).build(),
                ToolParameter.builder().name("num_results").type("integer").required(false).build()
            ))
            .build());
        InterpretationPlanRuntime runtime = new InterpretationPlanRuntime(
            mock(ToolRuntimeService.class),
            new InterpretationPlanValidator(),
            mock(InterpretationPlanRuntime.DagExecutionController.class)
        );
        InterpretationPlan.Step step = new InterpretationPlan.Step(
            1,
            "mcp_tool",
            toolName,
            Map.of(
                "queries", List.of("今天A股行情 2025", "两融余额 最新数据 2025"),
                "max_results", 7,
                "search_type", "hybrid",
                "freshness", "today"
            ),
            List.of(),
            null,
            null
        );
        InterpretationPlan plan = new InterpretationPlan(
            "1.0",
            new InterpretationPlan.Intent("web_search", "总结今天A股行情和两融余额", "low"),
            context(),
            new InterpretationPlan.Plan(List.of(step)),
            new InterpretationPlan.ExecutionPolicy(1, false, List.of(toolName), List.of(), 30000),
            review()
        );
        InterpretationPlanRuntime.ExecutionRequest request = new InterpretationPlanRuntime.ExecutionRequest(
            plan,
            toolRegistry,
            List.of(toolName),
            "tenant-1",
            "req-web-search-aliases",
            "conv-web-search-aliases",
            "user-1",
            Map.of("originalUserQuery", "总结今天A股行情和两融余额")
        );
        Method method = InterpretationPlanRuntime.class.getDeclaredMethod(
            "resolvedStepInput",
            InterpretationPlan.Step.class,
            InterpretationPlanRuntime.ExecutionRequest.class,
            Map.class
        );
        method.setAccessible(true);

        Map<String, Object> resolved = (Map<String, Object>) method.invoke(runtime, step, request, Map.of());

        assertThat(resolved)
            .containsEntry("query", "总结今天A股行情和两融余额")
            .containsEntry("num_results", 7)
            .doesNotContainKeys("queries", "max_results", "search_type", "freshness");
    }

    @Test
    void acceptsOutputEdgeContractAsWholeSuccessfulStepOutput() {
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.hasTool("web_search")).thenReturn(true);
        when(toolRegistry.getToolMetadata("web_search")).thenReturn(ToolMetadata.builder().riskLevel("low").build());
        ToolRuntimeService toolRuntimeService = mock(ToolRuntimeService.class);
        when(toolRuntimeService.execute(any())).thenReturn(new ToolRuntimeExecution(
            ToolOutput.success(Map.of("results", List.of(Map.of("title", "AI industry update")))),
            ToolMetadata.builder().id("web_search").build(),
            null,
            "success",
            Map.of()
        ));
        InterpretationPlan plan = new InterpretationPlan(
            "1.0",
            new InterpretationPlan.Intent("web_search", "Collect current AI news", "low"),
            context(),
            new InterpretationPlan.Plan(
                List.of(
                    new InterpretationPlan.Step(1, "mcp_tool", "web_search", Map.of("query", "AI news"), List.of(), null, null),
                    new InterpretationPlan.Step(2, "final_answer", "", Map.of("answer", "done"), List.of(1), null, null)
                ),
                List.of(new InterpretationPlan.EdgeContract(1, 2, "output", "any", true))
            ),
            new InterpretationPlan.ExecutionPolicy(2, false, List.of("web_search"), List.of(), 30000),
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
            List.of("web_search"),
            "tenant-1",
            "req-output-edge-contract",
            "conv-output-edge-contract",
            "user-1",
            Map.of()
        ));

        assertThat(result.success())
            .as(result.status() + ": " + result.errorMessage())
            .isTrue();
        assertThat(result.steps()).noneMatch(step -> "edge_contract".equals(step.actionType()));
    }

    @Test
    void acceptsLegacyDataEdgeContractForWebSearchResultEnvelope() {
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.hasTool("web_search")).thenReturn(true);
        when(toolRegistry.getToolMetadata("web_search")).thenReturn(ToolMetadata.builder().riskLevel("low").build());
        ToolRuntimeService toolRuntimeService = mock(ToolRuntimeService.class);
        when(toolRuntimeService.execute(any())).thenReturn(new ToolRuntimeExecution(
            ToolOutput.success(Map.of(
                "results", List.of(Map.of("title", "A股市场更新")),
                "reference_urls", List.of("https://example.com/market")
            )),
            ToolMetadata.builder().id("web_search").build(),
            null,
            "success",
            Map.of()
        ));
        InterpretationPlan plan = new InterpretationPlan(
            "1.0",
            new InterpretationPlan.Intent("web_search", "Collect current market news", "low"),
            context(),
            new InterpretationPlan.Plan(
                List.of(
                    new InterpretationPlan.Step(1, "mcp_tool", "web_search", Map.of("query", "market news"), List.of(), null, null),
                    new InterpretationPlan.Step(2, "final_answer", "", Map.of("answer", "done"), List.of(1), null, null)
                ),
                List.of(new InterpretationPlan.EdgeContract(1, 2, "data", "any", true))
            ),
            new InterpretationPlan.ExecutionPolicy(2, false, List.of("web_search"), List.of(), 30000),
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
            List.of("web_search"),
            "tenant-1",
            "req-data-edge-contract",
            "conv-data-edge-contract",
            "user-1",
            Map.of()
        ));

        assertThat(result.success())
            .as(result.status() + ": " + result.errorMessage())
            .isTrue();
        assertThat(result.steps()).noneMatch(step -> "edge_contract".equals(step.actionType()));
    }

    @Test
    void rejectsFinalAnswerDecisionWhenAnyStepsRemain() throws Exception {
        InterpretationPlan plan = new InterpretationPlan(
            "1.0",
            new InterpretationPlan.Intent("sql_metadata", "Analyze table metadata", "low"),
            context(),
            new InterpretationPlan.Plan(List.of(
                new InterpretationPlan.Step(1, "mcp_tool", "mcp_chatchat_mcp_server_sql_datasource_asset_query", Map.of(), List.of(), null, null),
                new InterpretationPlan.Step(2, "final_answer", "", Map.of("answer", "done"), List.of(1), null, null),
                new InterpretationPlan.Step(3, "mcp_tool", "mcp_chatchat_mcp_server_sql_query_execute", Map.of(), List.of(1), null, null)
            )),
            new InterpretationPlan.ExecutionPolicy(3, false, List.of(
                "mcp_chatchat_mcp_server_sql_datasource_asset_query",
                "mcp_chatchat_mcp_server_sql_query_execute"
            ), List.of(), 30000),
            review()
        );
        Map<Integer, InterpretationPlan.Step> stepsById = new java.util.LinkedHashMap<>();
        plan.steps().forEach(step -> stepsById.put(step.id(), step));
        InterpretationPlanRuntime runtime = new InterpretationPlanRuntime(
            mock(ToolRuntimeService.class),
            new InterpretationPlanValidator(),
            scriptedController(List.of())
        );
        Method method = InterpretationPlanRuntime.class.getDeclaredMethod(
            "validateDecision",
            InterpretationPlanRuntime.DagDecision.class,
            InterpretationPlan.class,
            java.util.Set.class,
            Map.class,
            java.util.Set.class
        );
        method.setAccessible(true);

        Object validation = method.invoke(
            runtime,
            InterpretationPlanRuntime.DagDecision.finalAnswer(2, "done", "scripted final"),
            plan,
            new java.util.LinkedHashSet<>(List.of(2, 3)),
            stepsById,
            new java.util.LinkedHashSet<>(List.of(1))
        );
        Method validMethod = validation.getClass().getDeclaredMethod("valid");
        Method messageMethod = validation.getClass().getDeclaredMethod("message");
        validMethod.setAccessible(true);
        messageMethod.setAccessible(true);

        assertThat((Boolean) validMethod.invoke(validation)).isFalse();
        assertThat((String) messageMethod.invoke(validation))
            .contains("final_answer must be the last executed step")
            .contains("3");
    }

    @Test
    void rejectsExecuteStepDecisionWhenFinalAnswerStepWouldSkipRemainingSteps() throws Exception {
        InterpretationPlan plan = new InterpretationPlan(
            "1.0",
            new InterpretationPlan.Intent("sql_metadata", "Analyze table metadata", "low"),
            context(),
            new InterpretationPlan.Plan(List.of(
                new InterpretationPlan.Step(1, "mcp_tool", "mcp_chatchat_mcp_server_sql_datasource_asset_query", Map.of(), List.of(), null, null),
                new InterpretationPlan.Step(2, "final_answer", "", Map.of("answer", "done"), List.of(1), null, null),
                new InterpretationPlan.Step(3, "mcp_tool", "mcp_chatchat_mcp_server_sql_query_execute", Map.of(), List.of(1), null, null)
            )),
            new InterpretationPlan.ExecutionPolicy(3, false, List.of(
                "mcp_chatchat_mcp_server_sql_datasource_asset_query",
                "mcp_chatchat_mcp_server_sql_query_execute"
            ), List.of(), 30000),
            review()
        );
        Map<Integer, InterpretationPlan.Step> stepsById = new java.util.LinkedHashMap<>();
        plan.steps().forEach(step -> stepsById.put(step.id(), step));
        InterpretationPlanRuntime runtime = new InterpretationPlanRuntime(
            mock(ToolRuntimeService.class),
            new InterpretationPlanValidator(),
            scriptedController(List.of())
        );
        Method method = InterpretationPlanRuntime.class.getDeclaredMethod(
            "validateDecision",
            InterpretationPlanRuntime.DagDecision.class,
            InterpretationPlan.class,
            java.util.Set.class,
            Map.class,
            java.util.Set.class
        );
        method.setAccessible(true);

        Object validation = method.invoke(
            runtime,
            InterpretationPlanRuntime.DagDecision.executeStep(2, "scripted final as execute_step"),
            plan,
            new java.util.LinkedHashSet<>(List.of(2, 3)),
            stepsById,
            new java.util.LinkedHashSet<>(List.of(1))
        );
        Method validMethod = validation.getClass().getDeclaredMethod("valid");
        Method messageMethod = validation.getClass().getDeclaredMethod("message");
        validMethod.setAccessible(true);
        messageMethod.setAccessible(true);

        assertThat((Boolean) validMethod.invoke(validation)).isFalse();
        assertThat((String) messageMethod.invoke(validation))
            .contains("final_answer must be the last executed step")
            .contains("3");
    }

    @Test
    void finalAnswerComesFromFinalStepNotControllerDecisionText() {
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.hasTool("document_search")).thenReturn(true);
        when(toolRegistry.getToolMetadata("document_search")).thenReturn(ToolMetadata.builder().riskLevel("low").build());
        ToolRuntimeService toolRuntimeService = mock(ToolRuntimeService.class);
        when(toolRuntimeService.execute(any())).thenReturn(new ToolRuntimeExecution(
            ToolOutput.success(Map.of("results", List.of("evidence"))),
            ToolMetadata.builder().id("document_search").build(),
            null,
            "success",
            Map.of()
        ));
        InterpretationPlan plan = new InterpretationPlan(
            "1.0",
            new InterpretationPlan.Intent("document_retrieval", "Collect evidence", "low"),
            context(),
            new InterpretationPlan.Plan(List.of(
                new InterpretationPlan.Step(1, "mcp_tool", "document_search", Map.of("query", "internal"), List.of(), null, null),
                new InterpretationPlan.Step(2, "final_answer", "", Map.of("answer", "step-owned final answer"), List.of(1), null, null)
            )),
            new InterpretationPlan.ExecutionPolicy(2, false, List.of("document_search"), List.of(), 30000),
            review()
        );
        InterpretationPlanRuntime runtime = new InterpretationPlanRuntime(
            toolRuntimeService,
            new InterpretationPlanValidator(),
            request -> {
                Integer stepId = request.remainingStepIds().stream().sorted().findFirst().orElse(null);
                if (Integer.valueOf(2).equals(stepId)) {
                    return InterpretationPlanRuntime.DagDecision.finalAnswer(
                        2,
                        "controller-authored answer must be ignored",
                        "select final"
                    );
                }
                return InterpretationPlanRuntime.DagDecision.executeStep(stepId, "select tool");
            }
        );

        InterpretationPlanRuntime.ExecutionResult result = runtime.execute(new InterpretationPlanRuntime.ExecutionRequest(
            plan,
            toolRegistry,
            List.of("document_search"),
            "tenant-1",
            "req-single-writer-final",
            "conv-single-writer-final",
            "user-1",
            Map.of()
        ));

        assertThat(result.success()).isTrue();
        assertThat(result.finalAnswer()).isEqualTo("step-owned final answer");
        assertThat(result.finalAnswer()).isNotEqualTo("controller-authored answer must be ignored");
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
            .containsEntry("executionTraceId", "run-event-dag::interpretation_plan")
            .containsEntry("planExecutionScope", "run-event-dag::attempt:0");
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
                && "run-event-dag::attempt:0".equals(metadata.get("planExecutionScope"))
                && "controller_decision".equals(metadata.get("lifecyclePhase"))
                && metadata.get("decision") instanceof Map<?, ?>
                && metadata.get("guardResult") instanceof Map<?, ?>))
            .isTrue();
    }

    @Test
    void carriesAgentRunIdIntoToolResultReviewRequest() {
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.hasTool("document_search")).thenReturn(true);
        when(toolRegistry.getToolMetadata("document_search"))
            .thenReturn(ToolMetadata.builder().id("document_search").riskLevel("low").build());
        ToolRuntimeService toolRuntimeService = mock(ToolRuntimeService.class);
        when(toolRuntimeService.execute(any())).thenReturn(new ToolRuntimeExecution(
            ToolOutput.success(Map.of("results", List.of("runtime evidence"))),
            ToolMetadata.builder().id("document_search").build(),
            null,
            "success",
            Map.of()
        ));
        AtomicReference<String> reviewedRunId = new AtomicReference<>();
        InterpretationPlanRuntime.StepResultReviewer reviewer = request -> {
            reviewedRunId.set(request.runId());
            return InterpretationPlanRuntime.StepReview.accepted("usable", Map.of());
        };
        InterpretationPlanRuntime runtime = new InterpretationPlanRuntime(
            toolRuntimeService,
            new InterpretationPlanValidator(),
            null,
            reviewer,
            scriptedController(List.of(List.of(1), List.of(2)))
        );

        InterpretationPlanRuntime.ExecutionResult result = runtime.execute(
            new InterpretationPlanRuntime.ExecutionRequest(
                serialPlan(), toolRegistry, List.of("document_search"),
                "tenant-1", "request-review-run-id", "conversation-1", "user-1",
                Map.of("__agentRunId", "run-tool-result-review")
            ));

        assertThat(result.success()).isTrue();
        assertThat(reviewedRunId.get()).isEqualTo("run-tool-result-review");
    }

    @Test
    void doesNotReplayCompletedStepButExecutesNewWorkflowStepAfterRewrite() {
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
        AtomicInteger rewriteReviewCalls = new AtomicInteger();
        InterpretationPlanRuntime.StepResultReviewer reviewer = request -> InterpretationPlanRuntime.StepReview.accepted(
            "sufficient evidence",
            rewriteReviewCalls.getAndIncrement() == 0 ? Map.of(
                "evidenceEvaluation", Map.of(
                    "relevance", 0.95,
                    "answerability", 0.95,
                    "usefulness", "HIGH"
                ),
                "shouldExpandQuery", true,
                "nextActions", List.of(Map.of(
                    "tool", "document_search",
                    "input_changes", Map.of("query", "refined locked evidence"),
                    "scope_basis", Map.of("source", "tool_result", "reference", "$.results[0]"),
                    "capability_basis", Map.of("source", "tool_result", "reference", "$.results[0]"),
                    "expected_evidence_types", List.of("document")
                ))
            ) : Map.of()
        );
        InterpretationPlanRuntime firstRuntime = new InterpretationPlanRuntime(
            toolRuntimeService,
            new InterpretationPlanValidator(),
            runStore,
            reviewer,
            request -> {
                throw new AssertionError("Deterministic repair detection must not call the DAG scheduler model");
            }
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
                if (request.remainingStepIds().contains(3)) {
                    return InterpretationPlanRuntime.DagDecision.executeStep(3, "execute new workflow step after rewrite");
                }
                assertThat(request.remainingStepIds()).containsExactly(2);
                return InterpretationPlanRuntime.DagDecision.finalAnswer(2, "done", "all workflow steps completed");
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
            .containsExactlyInAnyOrder(2, 3);
        assertThat(completed.steps()).extracting(InterpretationPlanRuntime.StepExecution::actionType)
            .contains("mcp_tool", "final_answer");
        List<?> completedPlanStepIds = (List<?>) completed.metadata().get("completedPlanStepIds");
        assertThat(completedPlanStepIds.containsAll(List.of(1, 2, 3))).isTrue();
        verify(toolRuntimeService, times(2)).execute(any());
    }

    @Test
    void doesNotTreatPreviousPlanAttemptStepAsCompletedInCurrentDag() {
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.hasTool("document_search")).thenReturn(true);
        when(toolRegistry.getToolMetadata("document_search"))
            .thenReturn(ToolMetadata.builder().riskLevel("low").build());
        ToolRuntimeService toolRuntimeService = mock(ToolRuntimeService.class);
        when(toolRuntimeService.execute(any())).thenReturn(new ToolRuntimeExecution(
            ToolOutput.success(Map.of("results", List.of("attempt evidence"))),
            ToolMetadata.builder().id("document_search").build(),
            null,
            "success",
            Map.of()
        ));
        InMemoryAgentRunStore runStore = new InMemoryAgentRunStore();
        AtomicInteger attemptReviewCalls = new AtomicInteger();
        InterpretationPlanRuntime.StepResultReviewer reviewer = request ->
            InterpretationPlanRuntime.StepReview.accepted("usable", attemptReviewCalls.getAndIncrement() == 0 ? Map.of(
                "shouldExpandQuery", true,
                "nextActions", List.of(Map.of(
                    "tool", "document_search",
                    "input_changes", Map.of("query", "refined attempt evidence"),
                    "scope_basis", Map.of("source", "tool_result", "reference", "$.results[0]"),
                    "capability_basis", Map.of("source", "tool_result", "reference", "$.results[0]"),
                    "expected_evidence_types", List.of("document")
                ))
            ) : Map.of());
        InterpretationPlanRuntime firstRuntime = new InterpretationPlanRuntime(
            toolRuntimeService,
            new InterpretationPlanValidator(),
            runStore,
            reviewer,
            request -> {
                throw new AssertionError("Deterministic repair detection must not call the DAG scheduler model");
            }
        );

        InterpretationPlanRuntime.ExecutionResult first = firstRuntime.execute(
            new InterpretationPlanRuntime.ExecutionRequest(
                serialPlan(), toolRegistry, List.of("document_search"),
                "tenant-1", "req-attempt-1", "conv-attempt", "user-1",
                Map.of("__agentRunId", "run-attempt-scope", "workflowExecutionAttempt", 0)
            ));

        assertThat(first.status()).isEqualTo("DAG_REWRITE_REQUESTED");
        InterpretationPlanRuntime secondRuntime = new InterpretationPlanRuntime(
            toolRuntimeService,
            new InterpretationPlanValidator(),
            runStore,
            reviewer,
            request -> InterpretationPlanRuntime.DagDecision.finalAnswer(
                2, "done", "current DAG completed")
        );

        InterpretationPlanRuntime.ExecutionResult second = secondRuntime.execute(
            new InterpretationPlanRuntime.ExecutionRequest(
                serialPlan(), toolRegistry, List.of("document_search"),
                "tenant-1", "req-attempt-2", "conv-attempt", "user-1",
                Map.of("__agentRunId", "run-attempt-scope", "workflowExecutionAttempt", 1)
            ));

        assertThat(second.success()).isTrue();
        assertThat(second.steps()).extracting(InterpretationPlanRuntime.StepExecution::stepId)
            .containsExactly(1, 2);
        verify(toolRuntimeService, times(2)).execute(any());
    }

    @Test
    void ignoresStaleStepEventWhenAttemptNumberMatchesButPlanScopeDoesNot() {
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.hasTool("document_search")).thenReturn(true);
        when(toolRegistry.getToolMetadata("document_search"))
            .thenReturn(ToolMetadata.builder().riskLevel("low").build());
        ToolRuntimeService toolRuntimeService = mock(ToolRuntimeService.class);
        when(toolRuntimeService.execute(any())).thenReturn(new ToolRuntimeExecution(
            ToolOutput.success(Map.of("results", List.of("fresh evidence"))),
            ToolMetadata.builder().id("document_search").build(),
            null,
            "success",
            Map.of()
        ));
        InMemoryAgentRunStore runStore = new InMemoryAgentRunStore();
        String runId = "run-plan-scope";
        runStore.recordObservation(runId, AgentObservation.builder()
            .type("tool")
            .source("document_search")
            .content("stale observation")
            .metadata(Map.of(
                "interpretationPlanStepId", 1,
                "interpretationPlanActionType", "mcp_tool",
                "toolName", "document_search",
                "success", true,
                "workflowExecutionAttempt", 1,
                "planExecutionScope", runId + "::attempt:0",
                "stepOutput", Map.of("results", List.of("stale evidence"))
            ))
            .build());
        InterpretationPlanRuntime runtime = new InterpretationPlanRuntime(
            toolRuntimeService,
            new InterpretationPlanValidator(),
            runStore,
            request -> InterpretationPlanRuntime.StepReview.accepted("asset matches the requested docker host", Map.of(
                "selectedAssetIds", List.of("asset-17")
            )),
            request -> InterpretationPlanRuntime.DagDecision.finalAnswer(
                2, "done", "current scoped DAG completed")
        );

        InterpretationPlanRuntime.ExecutionResult result = runtime.execute(
            new InterpretationPlanRuntime.ExecutionRequest(
                serialPlan(), toolRegistry, List.of("document_search"),
                "tenant-1", "req-plan-scope", "conv-plan-scope", "user-1",
                Map.of("__agentRunId", runId, "workflowExecutionAttempt", 1)
            ));

        assertThat(result.success()).isTrue();
        assertThat(result.steps()).extracting(InterpretationPlanRuntime.StepExecution::stepId)
            .containsExactly(1, 2);
        verify(toolRuntimeService).execute(any());
    }

    @Test
    void prioritizesMaterialEvidenceRecoveryBeforeUnrelatedDownstreamStep() {
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.hasTool("metadata_search")).thenReturn(true);
        when(toolRegistry.hasTool("standards_search")).thenReturn(true);
        when(toolRegistry.getToolMetadata(any())).thenReturn(
            ToolMetadata.builder()
                .riskLevel("low")
                .description("Search metadata candidates")
                .build());
        ToolRuntimeService toolRuntimeService = mock(ToolRuntimeService.class);
        when(toolRuntimeService.execute(any())).thenReturn(new ToolRuntimeExecution(
            ToolOutput.success("opaque partial-evidence reference"),
            ToolMetadata.builder().id("metadata_search").build(),
            null,
            "success",
            Map.of()
        ));
        InterpretationPlanRuntime.StepResultReviewer reviewer = request ->
            InterpretationPlanRuntime.StepReview.rejected(
                "exact lookup returned no candidates",
                Map.of(
                    "partialEvidence", true,
                    "evidenceEvaluation", Map.of("shouldExpandQuery", true),
                    "nextActions", List.of(Map.of(
                        "tool", "metadata_search",
                        "input_changes", Map.of("query", "broadened tokens"),
                        "scope_basis", Map.of(
                            "source", "user_query",
                            "reference", "find comparable evidence"),
                        "capability_basis", Map.of(
                            "source", "tool_metadata",
                            "reference", "Search metadata candidates"),
                        "expected_evidence_types", List.of("metadata candidates")
                    ))
                )
            );
        InterpretationPlanRuntime.DagExecutionController controller =
            mock(InterpretationPlanRuntime.DagExecutionController.class);
        InterpretationPlan plan = new InterpretationPlan(
            "1.0",
            new InterpretationPlan.Intent("evidence_review", "find comparable evidence", "low"),
            context(),
            new InterpretationPlan.Plan(List.of(
                new InterpretationPlan.Step(
                    1, "mcp_tool", "metadata_search", Map.of("query", "exact"),
                    List.of(), null, null),
                new InterpretationPlan.Step(
                    2, "mcp_tool", "standards_search", Map.of("query", "standards"),
                    List.of(1), null, null),
                new InterpretationPlan.Step(
                    3, "final_answer", "", Map.of("answer", "bounded"),
                    List.of(2), null, null)
            )),
            new InterpretationPlan.ExecutionPolicy(
                3, false, List.of("metadata_search", "standards_search"), List.of(),
                30000, 1, "partial_result", Map.of(), null, null, null),
            review()
        );
        InterpretationPlanRuntime runtime = new InterpretationPlanRuntime(
            toolRuntimeService,
            new InterpretationPlanValidator(),
            null,
            reviewer,
            controller
        );

        InterpretationPlanRuntime.ExecutionResult result = runtime.execute(
            new InterpretationPlanRuntime.ExecutionRequest(
                plan, toolRegistry, List.of("metadata_search", "standards_search"),
                "tenant-1", "req-recovery-first", "conv-recovery-first", "user-1",
                Map.of("originalUserQuery", "find comparable evidence")
            ));

        assertThat(result.status()).isEqualTo("DAG_REWRITE_REQUESTED");
        assertThat(result.steps()).extracting(InterpretationPlanRuntime.StepExecution::stepId)
            .containsExactly(1);
        Map<?, ?> controllerDecision = (Map<?, ?>) result.metadata().get("controllerDecision");
        assertThat(controllerDecision.get("action")).isEqualTo("rewrite_plan");
        verify(toolRuntimeService, times(1)).execute(any());
        verify(controller, never()).decide(any());
    }

    @Test
    void ignoresUngroundedEvidenceExpansionWithoutRecoveryContract() {
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.hasTool("metadata_search")).thenReturn(true);
        when(toolRegistry.hasTool("standards_search")).thenReturn(true);
        when(toolRegistry.getToolMetadata(any())).thenReturn(
            ToolMetadata.builder().riskLevel("low").build());
        ToolRuntimeService toolRuntimeService = mock(ToolRuntimeService.class);
        when(toolRuntimeService.execute(any())).thenAnswer(invocation -> {
            String toolName = invocation.<ToolRuntimeRequest>getArgument(0)
                .getToolName();
            return new ToolRuntimeExecution(
                ToolOutput.success(Map.of("results", List.of(toolName + " evidence"))),
                ToolMetadata.builder().id(toolName).build(),
                null,
                "success",
                Map.of()
            );
        });
        InterpretationPlanRuntime.StepResultReviewer reviewer = request -> {
            if (!"metadata_search".equals(request.execution().toolName())) {
                return InterpretationPlanRuntime.StepReview.accepted("usable", Map.of());
            }
            return InterpretationPlanRuntime.StepReview.accepted(
                "returned evidence is useful",
                Map.of(
                    "evidenceEvaluation", Map.of("shouldExpandQuery", true),
                    "nextActions", List.of(Map.of(
                        "tool", "metadata_search",
                        "input_changes", Map.of(
                            "query", "invented conventional design checklist")
                    ))
                )
            );
        };
        InterpretationPlan plan = new InterpretationPlan(
            "1.0",
            new InterpretationPlan.Intent("evidence_review", "review returned evidence", "low"),
            context(),
            new InterpretationPlan.Plan(List.of(
                new InterpretationPlan.Step(
                    1, "mcp_tool", "metadata_search", Map.of("query", "exact"),
                    List.of(), null, null),
                new InterpretationPlan.Step(
                    2, "mcp_tool", "standards_search", Map.of("query", "standards"),
                    List.of(1), null, null),
                new InterpretationPlan.Step(
                    3, "final_answer", "", Map.of("answer", "bounded"),
                    List.of(2), null, null)
            )),
            new InterpretationPlan.ExecutionPolicy(
                3, false, List.of("metadata_search", "standards_search"), List.of(),
                30000, 1, "partial_result", Map.of(), null, null, null),
            review()
        );
        InterpretationPlanRuntime runtime = new InterpretationPlanRuntime(
            toolRuntimeService,
            new InterpretationPlanValidator(),
            null,
            reviewer,
            request -> InterpretationPlanRuntime.DagDecision.finalAnswer(
                3, "bounded", "all planned evidence steps completed")
        );

        InterpretationPlanRuntime.ExecutionResult result = runtime.execute(
            new InterpretationPlanRuntime.ExecutionRequest(
                plan, toolRegistry, List.of("metadata_search", "standards_search"),
                "tenant-1", "req-grounded-recovery", "conv-grounded-recovery", "user-1", Map.of()
            ));

        assertThat(result.success()).isTrue();
        assertThat(result.status()).isEqualTo("completed");
        assertThat(result.steps()).extracting(InterpretationPlanRuntime.StepExecution::stepId)
            .containsExactly(1, 2, 3);
        verify(toolRuntimeService, times(2)).execute(any());
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

    private InterpretationPlan minimalPlan(InterpretationPlan.Step step) {
        return new InterpretationPlan(
            "1.0",
            new InterpretationPlan.Intent("sql_metadata", "Analyze table metadata", "low"),
            context(),
            new InterpretationPlan.Plan(List.of(
                step,
                new InterpretationPlan.Step(4, "final_answer", "", Map.of("answer", "done"), List.of(step.id()), null, null)
            )),
            new InterpretationPlan.ExecutionPolicy(2, false, List.of("mcp_chatchat_mcp_server_sql_query_execute"), List.of(), 30000),
            review()
        );
    }

    private InterpretationPlan legacyBusinessQueryPlan() {
        return new InterpretationPlan(
            "1.0",
            new InterpretationPlan.Intent("sql_query", "分析行情数据发生较大波动时异常提醒数据", "medium"),
            context(),
            new InterpretationPlan.Plan(
                List.of(
                    new InterpretationPlan.Step(
                        1,
                        "mcp_tool",
                        "mcp_chatchat_mcp_server_database_query_template_query",
                        Map.of(
                            "finalDecision", "business_database_query",
                            "filters", Map.of(
                                "intent", "分析行情数据发生较大波动时异常提醒数据",
                                "bilingualIntent", List.of("行情波动", "异常提醒", "market data volatility", "alert")
                            ),
                            "limit", 10
                        ),
                        List.of(),
                        null,
                        null
                    ),
                    new InterpretationPlan.Step(
                        2,
                        "mcp_tool",
                        "mcp_chatchat_mcp_server_sql_query_execute",
                        Map.of("templateId", "BOUND_FROM_STEP1", "parameters", Map.of()),
                        List.of(1),
                        null,
                        null
                    ),
                    new InterpretationPlan.Step(
                        3,
                        "final_answer",
                        "",
                        Map.of("answer", "done"),
                        List.of(2),
                        null,
                        null
                    )
                ),
                List.of(
                    new InterpretationPlan.EdgeContract(1, 2, "templates[0].templateId", "string", true),
                    new InterpretationPlan.EdgeContract(1, 2, "assetName", "string", true),
                    new InterpretationPlan.EdgeContract(1, 2, "env", "string", true)
                ),
                List.of(
                    new InterpretationPlan.Binding(1, "$.templates[0].templateId", 2, "templateId", "jsonpath", true),
                    new InterpretationPlan.Binding(1, "$.assetName", 2, "executionContext.assetName", "jsonpath", true),
                    new InterpretationPlan.Binding(1, "$.env", 2, "executionContext.env", "jsonpath", true)
                ),
                null
            ),
            new InterpretationPlan.ExecutionPolicy(
                3,
                false,
                List.of(
                    "mcp_chatchat_mcp_server_database_query_template_query",
                    "mcp_chatchat_mcp_server_sql_query_execute"
                ),
                List.of(),
                30000
            ),
            review()
        );
    }

    private InterpretationPlanRuntime.StepExecution completedSqlAssetStep() {
        Map<String, Object> asset = Map.of(
            "asset", Map.of(
                "id", "datasource-248",
                "name", "test_db_248",
                "environment", "DEV",
                "toolName", "db_query_mysql_248_test_db",
                "databaseType", "mysql"
            ),
            "executionContext", Map.of(
                "assetName", "test_db_248",
                "env", "DEV",
                "databaseType", "mysql"
            )
        );
        return new InterpretationPlanRuntime.StepExecution(
            1,
            "mcp_tool",
            "mcp_chatchat_mcp_server_sql_datasource_asset_query",
            true,
            Map.of("assets", List.of(asset), "selectedAsset", asset),
            null,
            null,
            null,
            10L
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

    private InterpretationPlan sqlQueryPlan(String toolName) {
        return new InterpretationPlan(
            "1.0",
            new InterpretationPlan.Intent("sql_analysis", "Collect SQL analysis evidence", "low"),
            context(),
            new InterpretationPlan.Plan(List.of(
                new InterpretationPlan.Step(1, "mcp_tool", toolName, Map.of(
                    "templateId", "market_kpi",
                    "executionContext", Map.of("assetName", "dm-test", "env", "DEV")
                ), List.of(), null, null),
                new InterpretationPlan.Step(2, "final_answer", "", Map.of("answer", "done"), List.of(1), null, null)
            )),
            new InterpretationPlan.ExecutionPolicy(2, false, List.of(toolName), List.of(), 30000),
            review()
        );
    }

    @Test
    @SuppressWarnings("unchecked")
    void removesProtocolFieldsFromDiscoveryFiltersBeforeToolCall() throws Exception {
        InterpretationPlanRuntime runtime = new InterpretationPlanRuntime(
            mock(ToolRuntimeService.class),
            new InterpretationPlanValidator(),
            mock(InterpretationPlanRuntime.DagExecutionController.class)
        );
        InterpretationPlan.Step step = new InterpretationPlan.Step(
            2,
            "mcp_tool",
            "mcp_chatchat_mcp_server_ssh_template_query",
            Map.of(
                "candidates", List.of(Map.of("targetKind", "database", "confidence", 0.9)),
                "finalDecision", "database",
                "filters", Map.of(
                    "assetName", "TDH scheduler",
                    "env", "DEV",
                    "intent", "list java processes",
                    "trace", Map.of("plannerVersion", "v1.1"),
                    "finalDecision", "host",
                    "filtersSchemaVersion", "target_filters.v1"
                ),
                "trace", Map.of("plannerVersion", "v1.1")
            ),
            List.of(),
            null,
            null
        );
        InterpretationPlan plan = new InterpretationPlan(
            "1.0",
            new InterpretationPlan.Intent("system_operation", "list java processes", "low"),
            context(),
            new InterpretationPlan.Plan(List.of(
                step,
                new InterpretationPlan.Step(3, "final_answer", "", Map.of("answer", "done"), List.of(2), null, null)
            )),
            new InterpretationPlan.ExecutionPolicy(
                2,
                false,
                List.of("mcp_chatchat_mcp_server_ssh_template_query"),
                List.of(),
                30000
            ),
            review()
        );
        InterpretationPlanRuntime.ExecutionRequest request = new InterpretationPlanRuntime.ExecutionRequest(
            plan,
            mock(ToolRegistry.class),
            List.of("mcp_chatchat_mcp_server_ssh_template_query"),
            "tenant-1",
            "req-filter-sanitize",
            "conv-filter-sanitize",
            "user-1",
            Map.of("executionTraceId", "trace-runtime-filter-sanitize")
        );
        Method method = InterpretationPlanRuntime.class.getDeclaredMethod(
            "resolvedStepInput",
            InterpretationPlan.Step.class,
            InterpretationPlanRuntime.ExecutionRequest.class,
            Map.class
        );
        method.setAccessible(true);

        Map<String, Object> resolved = (Map<String, Object>) method.invoke(runtime, step, request, Map.of());

        Map<String, Object> filters = (Map<String, Object>) resolved.get("filters");
        assertThat(filters)
            .containsEntry("assetName", "TDH scheduler")
            .containsEntry("env", "DEV")
            .containsEntry("intent", "list java processes")
            .doesNotContainKeys("trace", "finalDecision", "filtersSchemaVersion");
        assertThat(resolved).containsEntry("finalDecision", "database");
        assertThat(resolved.get("trace").toString()).doesNotContain("routingForcedByTypedDiscoveryTool");
    }

    @Test
    void orderedBatchSkipsPerStepModelReviewer() {
        String toolName = "sql_query_execute";
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.hasTool(toolName)).thenReturn(true);
        when(toolRegistry.getToolMetadata(toolName)).thenReturn(ToolMetadata.builder()
            .id(toolName).riskLevel("low").build());
        ToolCallBatchResult batchResult = new ToolCallBatchResult(
            "oracle-health",
            "SEQUENTIAL",
            "2026-07-26T00:00:00Z",
            "2026-07-26T00:00:01Z",
            "SUCCESS",
            new ToolCallBatchResult.Summary(2, 2, 0, 0, 0, 2),
            List.of(
                new ToolCallResult("one", toolName, "ONE", "asset-a", "SUCCESS", 10, "e-1", Map.of("ok", true), Map.of()),
                new ToolCallResult("two", toolName, "TWO", "asset-a", "SUCCESS", 10, "e-2", Map.of("ok", true), Map.of())
            )
        );
        ToolRuntimeService toolRuntimeService = mock(ToolRuntimeService.class);
        when(toolRuntimeService.execute(any())).thenReturn(new ToolRuntimeExecution(
            ToolOutput.success(batchResult),
            ToolMetadata.builder().id(toolName).build(),
            null,
            "success",
            Map.of("batchExecution", true, "remoteToolInvocationCount", 2)
        ));
        AtomicInteger reviewerCalls = new AtomicInteger();
        InterpretationPlanRuntime runtime = new InterpretationPlanRuntime(
            toolRuntimeService,
            new InterpretationPlanValidator(),
            null,
            request -> {
                reviewerCalls.incrementAndGet();
                return InterpretationPlanRuntime.StepReview.accepted("should not run", Map.of());
            },
            scriptedController(List.of(List.of(1), List.of(2)))
        );
        InterpretationPlan plan = new InterpretationPlan(
            "1.0",
            new InterpretationPlan.Intent("database_health", "execute ordered checks", "low"),
            context(),
            new InterpretationPlan.Plan(
                List.of(
                    new InterpretationPlan.Step(1, "mcp_tool", toolName, Map.of(
                        "executionMode", "SEQUENTIAL",
                        "calls", List.of(
                            Map.of("callId", "one", "toolName", toolName, "arguments", Map.of("templateCode", "ONE")),
                            Map.of("callId", "two", "toolName", toolName, "arguments", Map.of("templateCode", "TWO"))
                        )
                    ), List.of(),
                        new InterpretationPlan.OutputContract("json", "diagnostic_evidence_v1"),
                        new InterpretationPlan.Validation(true, "schema_check", null)),
                    new InterpretationPlan.Step(2, "final_answer", "", Map.of("answer", "done"), List.of(1), null, null)
                ),
                List.of(new InterpretationPlan.EdgeContract(1, 2, "results", "array", true))
            ),
            new InterpretationPlan.ExecutionPolicy(2, false, List.of(toolName), List.of(), 30_000),
            review()
        );

        InterpretationPlanRuntime.ExecutionResult result = runtime.execute(
            new InterpretationPlanRuntime.ExecutionRequest(
                plan, toolRegistry, List.of(toolName), "tenant-1", "req-batch-review",
                "conv-batch-review", "user-1", Map.of()
            )
        );

        assertThat(result.success()).isTrue();
        assertThat(reviewerCalls).hasValue(0);
        assertThat(result.steps().get(0).output()).isInstanceOfSatisfying(Map.class, output -> {
            assertThat(output)
                .containsEntry("contractVersion", "diagnostic_evidence_v1")
                .containsEntry("executionStatus", "SUCCESS")
                .containsEntry("assessmentStatus", "PRELIMINARY_AVAILABLE")
                .containsEntry("evidenceCoverage", 1.0);
            assertThat(output.get("results")).isInstanceOf(List.class);
        });
        assertThat(result.steps().get(0).metadata())
            .containsEntry("toolResultReviewSkipped", true)
            .containsEntry("batchExecution", true)
            .containsEntry("diagnosticEvidenceNormalized", true);
    }

    @Test
    void executesDependentCommandUsingRoutingProjectionFromExternalizedAssetEvidence() {
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        String assetTool = "mcp_chatchat_mcp_server_ssh_asset_query";
        String commandTool = "mcp_chatchat_mcp_server_linux_command_execute";
        when(toolRegistry.hasTool(any())).thenReturn(true);
        when(toolRegistry.getToolMetadata(assetTool)).thenReturn(ToolMetadata.builder()
            .id(assetTool).riskLevel("low").build());
        when(toolRegistry.getToolMetadata(commandTool)).thenReturn(ToolMetadata.builder()
            .id(commandTool).riskLevel("medium").parameters(List.of(
                ToolParameter.builder().name("template").type("string").required(true).build(),
                ToolParameter.builder().name("executionContext").type("object").required(true).build()
            )).build());
        ToolRuntimeService service = mock(ToolRuntimeService.class);
        when(service.execute(any())).thenAnswer(invocation -> {
            ToolRuntimeRequest request = invocation.getArgument(0);
            Object data = request.getToolName().equals(assetTool)
                ? Map.of(
                    "outputTruncated", true,
                    "outputExternal", true,
                    "routingProjection", Map.of(
                        "sourceSchemaVersion", "asset_query_result.v1",
                        "assets", List.of(Map.of("asset", Map.of(
                            "id", "asset-17",
                            "name", "docker-database-simulator",
                            "displayName", "Docker 数据库模拟服务器",
                            "environment", "DEV",
                            "toolName", "ssh_container_test_service"
                        )))
                    ))
                : Map.of("status", "ok");
            return new ToolRuntimeExecution(ToolOutput.success(data),
                ToolMetadata.builder().id(request.getToolName()).build(), null, "success", Map.of());
        });
        InterpretationPlan plan = new InterpretationPlan(
            "1.0",
            new InterpretationPlan.Intent("system_operation", "分析 Docker 数据库模拟服务器磁盘", "medium"),
            context(),
            new InterpretationPlan.Plan(List.of(
                new InterpretationPlan.Step(1, "mcp_tool", assetTool,
                    Map.of("filters", Map.of("intent", "Docker 数据库模拟服务器")), List.of(), null, null),
                new InterpretationPlan.Step(2, "mcp_tool", commandTool,
                    Map.of("template", "CHECK_DISK", "executionContext", Map.of()), List.of(1), null, null),
                new InterpretationPlan.Step(3, "final_answer", "", Map.of("answer", "done"), List.of(2), null, null)
            ), List.of(), List.of(
                new InterpretationPlan.Binding(1, "$.assets[0].asset.name", 2,
                    "executionContext.assetName", "jsonpath", true)
            ), null),
            new InterpretationPlan.ExecutionPolicy(3, false, List.of(assetTool, commandTool), List.of(), 30_000),
            review());
        InterpretationPlanRuntime runtime = new InterpretationPlanRuntime(service,
            new InterpretationPlanValidator(), null,
            request -> InterpretationPlanRuntime.StepReview.accepted("asset matches the requested docker host", Map.of(
                "selectedAssetIds", List.of("asset-17")
            )),
            scriptedController(List.of(List.of(1), List.of(2), List.of(3))));

        InterpretationPlanRuntime.ExecutionResult result = runtime.execute(
            new InterpretationPlanRuntime.ExecutionRequest(plan, toolRegistry,
                List.of(assetTool, commandTool), "tenant-1", "req-routing-projection",
                "conv-1", "user-1", Map.of()));

        assertThat(result.success()).as(result.errorMessage()).isTrue();
        ArgumentCaptor<ToolRuntimeRequest> captor = ArgumentCaptor.forClass(ToolRuntimeRequest.class);
        verify(service, times(2)).execute(captor.capture());
        Map<?, ?> commandInput = captor.getAllValues().get(1).getToolInput().getParameters();
        Map<?, ?> executionContext = (Map<?, ?>) commandInput.get("executionContext");
        assertThat(executionContext.get("assetName")).isEqualTo("docker-database-simulator");
        assertThat(executionContext.get("env")).isEqualTo("DEV");
        assertThat(executionContext.get("assetId")).isEqualTo("asset-17");
    }

    @Test
    @SuppressWarnings("unchecked")
    void upgradesScalarBindingAndExecutesEveryDiscoveredTemplateWithoutModelBatchInstructions() {
        String discoveryTool = "mcp_chatchat_mcp_server_api_template_query";
        String executionTool = "mcp_chatchat_mcp_server_api_template_execute";
        String firstTemplate = "tenant_snapshot_a_" + System.nanoTime();
        String secondTemplate = "tenant_snapshot_b_" + System.nanoTime();
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.hasTool(any())).thenReturn(true);
        when(toolRegistry.getToolMetadata(any())).thenAnswer(invocation ->
            ToolMetadata.builder().id(invocation.getArgument(0)).riskLevel("low").build());
        AtomicReference<Map<String, Object>> executionInput = new AtomicReference<>();
        ToolRuntimeService toolRuntimeService = mock(ToolRuntimeService.class);
        when(toolRuntimeService.execute(any())).thenAnswer(invocation -> {
            ToolRuntimeRequest request = invocation.getArgument(0);
            if (discoveryTool.equals(request.getToolName())) {
                return new ToolRuntimeExecution(ToolOutput.success(Map.of(
                    "schemaVersion", "template_query_result.v1",
                    "returnedCount", 2,
                    "templates", List.of(
                        Map.of(
                            "templateId", firstTemplate,
                            "parameterContract", Map.of("executionTool", executionTool),
                            "parameterSchema", Map.of("type", "object", "properties", Map.of(), "required", List.of())
                        ),
                        Map.of(
                            "templateId", secondTemplate,
                            "parameterContract", Map.of("executionTool", executionTool),
                            "parameterSchema", Map.of("type", "object", "properties", Map.of(), "required", List.of())
                        )
                    )
                )), ToolMetadata.builder().id(discoveryTool).build(), null, "success", Map.of());
            }
            executionInput.set(request.getToolInput().getParameters());
            return new ToolRuntimeExecution(
                ToolOutput.success(Map.of("results", List.of(
                    Map.of("callId", "a", "status", "SUCCESS"),
                    Map.of("callId", "b", "status", "SUCCESS")))),
                ToolMetadata.builder().id(executionTool).build(), null, "success",
                Map.of("batchExecution", true, "remoteToolInvocationCount", 2));
        });
        InterpretationPlan plan = new InterpretationPlan(
            "1.0",
            new InterpretationPlan.Intent("data_query", "run discovered tenant snapshots", "low"),
            context(),
            new InterpretationPlan.Plan(
                List.of(
                    new InterpretationPlan.Step(1, "mcp_tool", discoveryTool,
                        Map.of("filters", Map.of("intent", "tenant snapshots")), List.of(), null, null),
                    new InterpretationPlan.Step(2, "mcp_tool", executionTool,
                        Map.of("parameters", Map.of()),
                        List.of(1), null, null),
                    new InterpretationPlan.Step(3, "final_answer", "", Map.of("answer", "done"), List.of(2), null, null)
                ),
                List.of(),
                List.of(new InterpretationPlan.Binding(1, "$.templates[0].templateId", 2,
                    "$.templateId", "jsonpath", true)),
                null
            ),
            new InterpretationPlan.ExecutionPolicy(3, false,
                List.of(discoveryTool, executionTool), List.of(), 30_000),
            review()
        );
        InterpretationPlanRuntime runtime = new InterpretationPlanRuntime(
            toolRuntimeService,
            new InterpretationPlanValidator(),
            null,
            request -> InterpretationPlanRuntime.StepReview.accepted("both snapshot templates are required", Map.of(
                "selectedTemplateIds", List.of(firstTemplate, secondTemplate)
            )),
            scriptedController(List.of(List.of(1), List.of(2), List.of(3)))
        );

        InterpretationPlanRuntime.ExecutionResult result = runtime.execute(
            new InterpretationPlanRuntime.ExecutionRequest(
                plan, toolRegistry, List.of(discoveryTool, executionTool), "tenant-new",
                "request-new", "conversation-new", "user-new", Map.of("originalUserQuery", "run snapshots"))
        );

        assertThat(result.success())
            .as("status=%s error=%s", result.status(), result.errorMessage())
            .isTrue();
        assertThat(result.steps()).filteredOn(step -> Integer.valueOf(2).equals(step.stepId()))
            .singleElement()
            .satisfies(step -> assertThat(step.metadata())
                .containsEntry("eventKind", "DAG_REPAIR")
                .containsEntry("eventState", "APPLIED")
                .containsKey("runtimeTemplateCompletenessRepair"));
        assertThat(executionInput.get()).doesNotContainKey("runtimeTemplateBinding");
        assertThat(executionInput.get()).containsEntry("stopOnFailure", false);
        assertThat(executionInput.get().get("calls")).isInstanceOfSatisfying(List.class, calls -> {
            assertThat(calls).hasSize(2);
            assertThat(calls.toString()).contains(firstTemplate, secondTemplate)
                .doesNotContain("templates[0]");
        });
        List<Map<String, Object>> calls = (List<Map<String, Object>>) executionInput.get().get("calls");
        assertThat(calls).allSatisfy(call -> assertThat(call.get("arguments"))
            .isInstanceOfSatisfying(Map.class, arguments ->
                assertThat(arguments).containsEntry("parameters", Map.of())));
    }

    @Test
    @SuppressWarnings("unchecked")
    void compilesChineseDiagnosticDimensionsAndRecoversUserParameterForEveryTemplate() {
        String discoveryTool = "mcp_chatchat_mcp_server_api_template_query";
        String executorTool = "mcp_chatchat_mcp_server_api_template_execute";
        List<Map<String, Object>> templates = List.of(
            apiDiagnosticTemplate("orders-template", "委托流水", executorTool),
            apiDiagnosticTemplate("trades-template", "成交明细", executorTool),
            apiDiagnosticTemplate("transit-template", "在途资产", executorTool)
        );
        ToolRegistry registry = mock(ToolRegistry.class);
        when(registry.hasTool(any())).thenReturn(true);
        when(registry.getToolMetadata(any())).thenAnswer(invocation ->
            ToolMetadata.builder().id(invocation.getArgument(0)).riskLevel("low").build());
        AtomicReference<Map<String, Object>> batchInput = new AtomicReference<>();
        ToolRuntimeService service = mock(ToolRuntimeService.class);
        when(service.execute(any())).thenAnswer(invocation -> {
            ToolRuntimeRequest request = invocation.getArgument(0);
            if (discoveryTool.equals(request.getToolName())) {
                return new ToolRuntimeExecution(ToolOutput.success(Map.of(
                    "queryIr", Map.of("asset", Map.of(
                        "scoped", true,
                        "selected", Map.of("id", "asset-live-dev", "name", "live-dev", "environment", "DEV")
                    )),
                    "templates", templates
                )), ToolMetadata.builder().id(discoveryTool).build(), null, "success", Map.of());
            }
            batchInput.set(request.getToolInput().getParameters());
            List<ToolCallResult> results = List.of(
                new ToolCallResult("orders", executorTool, "orders-template", "asset-live-dev", "SUCCESS", 1,
                    "e1", Map.of("records", List.of()), Map.of()),
                new ToolCallResult("trades", executorTool, "trades-template", "asset-live-dev", "SUCCESS", 1,
                    "e2", Map.of("records", List.of()), Map.of()),
                new ToolCallResult("transit", executorTool, "transit-template", "asset-live-dev", "SUCCESS", 1,
                    "e3", Map.of("records", List.of()), Map.of())
            );
            return new ToolRuntimeExecution(ToolOutput.success(new ToolCallBatchResult(
                "diagnostic-step-2", "SEQUENTIAL", "start", "end", "SUCCESS",
                new ToolCallBatchResult.Summary(3, 3, 0, 0, 0, 3), results
            )), ToolMetadata.builder().id(executorTool).build(), null, "success",
                Map.of("batchExecution", true, "remoteToolInvocationCount", 3));
        });
        List<InterpretationPlan.DiagnosticCheck> checks = List.of(
            new InterpretationPlan.DiagnosticCheck("orders", "customer_orders", "委托", true, 1, List.of(2)),
            new InterpretationPlan.DiagnosticCheck("trades", "customer_trades", "成交", true, 2, List.of(2)),
            new InterpretationPlan.DiagnosticCheck("transit", "assets_in_transit", "在途", true, 3, List.of(2))
        );
        InterpretationPlan plan = new InterpretationPlan(
            "1.0",
            new InterpretationPlan.Intent("data_query", "query customer dimensions", "low"),
            context(),
            new InterpretationPlan.Plan(
                List.of(
                    new InterpretationPlan.Step(1, "mcp_tool", discoveryTool,
                        Map.of("filters", Map.of("intent", "customer dimensions")), List.of(), null, null),
                    new InterpretationPlan.Step(2, "mcp_tool", executorTool,
                        Map.of("parameters", Map.of("khh", "100200299999")), List.of(1), null, null),
                    new InterpretationPlan.Step(3, "final_answer", "", Map.of("answer", "done"),
                        List.of(2), null, null)
                ),
                List.of(), List.of(), List.of(), null,
                new InterpretationPlan.DiagnosticProfile("customer_dimensions", "api", checks)
            ),
            new InterpretationPlan.ExecutionPolicy(
                3, false, List.of(discoveryTool, executorTool), List.of(), 30_000),
            review()
        );
        InterpretationPlanRuntime runtime = new InterpretationPlanRuntime(
            service, new InterpretationPlanValidator(),
            scriptedController(List.of(List.of(1), List.of(2), List.of(3)))
        );

        InterpretationPlanRuntime.ExecutionResult result = runtime.execute(
            new InterpretationPlanRuntime.ExecutionRequest(
                plan, registry, List.of(discoveryTool, executorTool), "tenant-1", "req-cn-batch",
                "conv-cn-batch", "user-1",
                Map.of("originalUserQuery", "查询客户 100200299999 的委托、成交和在途资产")
            )
        );

        assertThat(result.success()).as(result.errorMessage()).isTrue();
        assertThat(batchInput.get().get("calls")).isInstanceOfSatisfying(List.class, calls -> {
            assertThat(calls).hasSize(3);
            assertThat(calls.toString()).contains("orders-template", "trades-template", "transit-template");
            for (Object rawCall : calls) {
                Map<String, Object> call = (Map<String, Object>) rawCall;
                Map<String, Object> arguments = (Map<String, Object>) call.get("arguments");
                assertThat((Map<String, Object>) arguments.get("parameters"))
                    .containsEntry("khh", "100200299999");
            }
        });
    }

    @Test
    @SuppressWarnings("unchecked")
    void givesEveryReviewedTemplateATerminalBatchEntryWhenOneCannotCompile() {
        String discoveryTool = "mcp_chatchat_mcp_server_api_template_query";
        String analysisTool = "mcp_chatchat_mcp_server_api_requirement_analyze";
        String executorTool = "mcp_chatchat_mcp_server_api_template_execute";
        List<String> templateIds = List.of("orders-template", "trades-template", "transit-template");
        List<Map<String, Object>> templates = templateIds.stream()
            .map(id -> {
                Map<String, Object> template = new LinkedHashMap<>(apiDiagnosticTemplate(id, id, executorTool));
                template.put("parameterSchema", Map.of(
                    "type", "object",
                    "properties", Map.of(
                        "khh", Map.of("type", "string", "default", "100200000000"),
                        "missingOnlyForTransit", Map.of("type", "string")
                    ),
                    "required", "transit-template".equals(id)
                        ? List.of("khh", "missingOnlyForTransit") : List.of("khh")
                ));
                return template;
            })
            .toList();
        ToolRegistry registry = mock(ToolRegistry.class);
        when(registry.hasTool(any())).thenReturn(true);
        when(registry.getToolMetadata(any())).thenAnswer(invocation ->
            ToolMetadata.builder().id(invocation.getArgument(0)).riskLevel("low").build());
        AtomicReference<Map<String, Object>> batchInput = new AtomicReference<>();
        ToolRuntimeService service = mock(ToolRuntimeService.class);
        when(service.execute(any())).thenAnswer(invocation -> {
            ToolRuntimeRequest request = invocation.getArgument(0);
            if (discoveryTool.equals(request.getToolName())) {
                return new ToolRuntimeExecution(
                    ToolOutput.success(Map.of("templates", templates)),
                    ToolMetadata.builder().id(discoveryTool).build(), null, "success", Map.of());
            }
            if (analysisTool.equals(request.getToolName())) {
                return new ToolRuntimeExecution(
                    ToolOutput.failure("analysis service unavailable"),
                    ToolMetadata.builder().id(analysisTool).build(), null, "failed", Map.of());
            }
            batchInput.set(request.getToolInput().getParameters());
            List<ToolCallResult> results = templateIds.stream()
                .map(id -> new ToolCallResult(id, executorTool, id, null, "SUCCESS", 1,
                    "evidence-" + id, Map.of("records", List.of()), Map.of()))
                .toList();
            return new ToolRuntimeExecution(ToolOutput.success(new ToolCallBatchResult(
                "reviewed-template-step-3", "SEQUENTIAL", "start", "end", "SUCCESS",
                new ToolCallBatchResult.Summary(3, 3, 0, 0, 0, 3), results)),
                ToolMetadata.builder().id(executorTool).build(), null, "success",
                Map.of("batchExecution", true, "remoteToolInvocationCount", 3));
        });
        InterpretationPlan plan = new InterpretationPlan(
            "1.0",
            new InterpretationPlan.Intent("data_query", "query customer dimensions", "low"),
            context(),
            new InterpretationPlan.Plan(
                List.of(
                    new InterpretationPlan.Step(1, "mcp_tool", discoveryTool,
                        Map.of("filters", Map.of("intent", "customer dimensions")), List.of(), null, null),
                    new InterpretationPlan.Step(2, "mcp_tool", analysisTool,
                        Map.of("goal", "analyze customer dimensions"), List.of(1), null, null),
                    new InterpretationPlan.Step(3, "mcp_tool", executorTool,
                        Map.of("batchId", "model-batch", "executionMode", "SEQUENTIAL",
                            "stopOnFailure", false, "calls", "{{ bindings.recommendedTemplates }}"),
                        List.of(2), null, null),
                    new InterpretationPlan.Step(4, "final_answer", "", Map.of("answer", "done"),
                        List.of(3), null, null)
                ),
                List.of(),
                List.of(
                    new InterpretationPlan.DependencyContract(1, 2, true, null, "analyze templates", "stop"),
                    new InterpretationPlan.DependencyContract(2, 3, true, null, "execute recommendations", "stop")
                ),
                List.of(new InterpretationPlan.Binding(2, "$.recommendedTemplates", 3,
                    "calls", "jsonpath", true)),
                null,
                null
            ),
            new InterpretationPlan.ExecutionPolicy(4, false,
                List.of(discoveryTool, analysisTool, executorTool), List.of(), 30_000),
            review()
        );
        InterpretationPlanRuntime runtime = new InterpretationPlanRuntime(
            service,
            new InterpretationPlanValidator(),
            null,
            request -> discoveryTool.equals(request.execution().toolName())
                ? InterpretationPlanRuntime.StepReview.accepted("three templates accepted", Map.of(
                    "selectedTemplateIds", templateIds,
                    "nextActions", List.of(Map.of(
                        "tool", "api_template_execute",
                        "input_changes", Map.of("parameters", Map.of("khh", "100200241779"))
                    ))
                ))
                : InterpretationPlanRuntime.StepReview.accepted("execution accepted", Map.of()),
            scriptedController(List.of(List.of(1), List.of(2), List.of(3), List.of(4)))
        );

        InterpretationPlanRuntime.ExecutionResult result = runtime.execute(
            new InterpretationPlanRuntime.ExecutionRequest(
                plan, registry, List.of(discoveryTool, analysisTool, executorTool), "tenant-1",
                "req-reviewed-batch", "conv-reviewed-batch", "user-1",
                Map.of("originalUserQuery", "查询客户 100200241779 的委托、成交和在途资产")
            )
        );

        assertThat(result.success()).as("status=%s error=%s", result.status(), result.errorMessage()).isTrue();
        assertThat(result.status()).isEqualTo("completed_with_partial_evidence");
        assertThat(result.metadata()).containsEntry("continuedFailureStepIds", List.of(2));
        assertThat(batchInput.get()).containsEntry("stopOnFailure", false);
        List<Map<String, Object>> calls = (List<Map<String, Object>>) batchInput.get().get("calls");
        assertThat(calls).hasSize(3);
        assertThat(calls).extracting(call -> call.get("callId")).containsExactlyElementsOf(templateIds);
        assertThat(calls).allSatisfy(call -> {
            Map<String, Object> arguments = (Map<String, Object>) call.get("arguments");
            assertThat((Map<String, Object>) arguments.get("parameters"))
                .containsEntry("khh", "100200241779");
        });
        assertThat(calls.get(0)).doesNotContainKey("preflightErrorCode");
        assertThat(calls.get(1)).doesNotContainKey("preflightErrorCode");
        assertThat(calls.get(2))
            .containsEntry("preflightErrorCode", "TEMPLATE_REQUIRED_PARAMETERS_MISSING");
        assertThat(calls.get(2).get("preflightMessage")).asString()
            .contains("missingOnlyForTransit");
        InterpretationPlanRuntime.StepExecution executionStep = result.steps().stream()
            .filter(candidate -> candidate.stepId() == 3)
            .findFirst()
            .orElseThrow();
        assertThat(executionStep.metadata())
            .containsEntry("eventKind", "DAG_REPAIR")
            .containsEntry("eventState", "APPLIED");
        assertThat(executionStep.metadata().get("repairEvent")).asString()
            .contains("TEMPLATE_BATCH_TERMINAL_COVERAGE_APPLIED", "transit-template", "BLOCKED");
    }

    private Map<String, Object> apiDiagnosticTemplate(String templateId,
                                                      String description,
                                                      String executorTool) {
        return Map.of(
            "templateId", templateId,
            "name", description,
            "description", description,
            "parameterSchema", Map.of(
                "type", "object",
                "properties", Map.of("khh", Map.of("type", "string", "default", "100200241779")),
                "required", List.of("khh")
            ),
            "executionBinding", Map.of(
                "toolName", executorTool,
                "templateId", templateId,
                "executionContext", Map.of("assetId", "asset-live-dev", "env", "DEV")
            )
        );
    }

    @Test
    @SuppressWarnings("unchecked")
    void compilesSharedDiagnosticExecutorIntoFiveAuditableTemplateCallsWhenReviewFindsAnotherGap() {
        String discoveryTool = "database_ops_template_search";
        String executorTool = "sql_query_execute";
        List<String> templateIds = List.of(
            "ORACLE_INSTANCE_STATUS",
            "ORACLE_SESSION_OVERVIEW",
            "ORACLE_LOCKS",
            "ORACLE_SYSTEM_EVENTS",
            "ORACLE_TABLESPACE_SIZE"
        );
        List<String> discoveryTemplateIds = List.of(
            "ORACLE_INSTANCE_STATUS",
            "ORACLE_SESSION_OVERVIEW",
            "ORACLE_SYSTEM_EVENTS",
            "ORACLE_LOCKS",
            "ORACLE_TABLESPACE_SIZE"
        );
        java.util.ArrayList<Map<String, Object>> templates = new java.util.ArrayList<>(discoveryTemplateIds.stream()
            .map(templateId -> Map.<String, Object>of(
                "templateId", templateId,
                "name", "ORACLE_SYSTEM_EVENTS".equals(templateId)
                    ? "Oracle system wait events"
                    : "ORACLE_LOCKS".equals(templateId) ? "Oracle lock view" : templateId,
                "requiredFields", "ORACLE_TABLESPACE_SIZE".equals(templateId)
                    ? List.of("TABLESPACE_NAME", "USED_PERCENT")
                    : List.of(),
                "purpose", "ORACLE_TABLESPACE_SIZE".equals(templateId)
                    ? "capacity_inventory"
                    : "health",
                "healthCapability", !"ORACLE_TABLESPACE_SIZE".equals(templateId),
                "timeSemantics", "ORACLE_SYSTEM_EVENTS".equals(templateId)
                    ? "SINCE_INSTANCE_START"
                    : "POINT_IN_TIME",
                "requiresContext", "ORACLE_SYSTEM_EVENTS".equals(templateId)
                    ? List.of("INSTANCE_UPTIME")
                    : List.of(),
                "parameterSchema", Map.of("type", "object", "required", List.of()),
                "sqlExecutionBinding", Map.of(
                    "toolName", executorTool,
                    "templateId", templateId
                )
            ))
            .toList());
        templates.add(0, Map.of(
            "templateId", "ORACLE_TABLESPACE_SIZE_OTHER_ASSET",
            "parameterSchema", Map.of("type", "object", "required", List.of()),
            "sqlExecutionBinding", Map.of(
                "toolName", executorTool,
                "templateId", "ORACLE_TABLESPACE_SIZE_OTHER_ASSET",
                "executionContext", Map.of("assetId", "asset-other")
            )
        ));

        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.hasTool(discoveryTool)).thenReturn(true);
        when(toolRegistry.hasTool(executorTool)).thenReturn(true);
        when(toolRegistry.getToolMetadata(discoveryTool))
            .thenReturn(ToolMetadata.builder().id(discoveryTool).riskLevel("low").build());
        when(toolRegistry.getToolMetadata(executorTool))
            .thenReturn(ToolMetadata.builder().id(executorTool).riskLevel("low").build());

        java.util.ArrayList<ToolRuntimeRequest> requests = new java.util.ArrayList<>();
        ToolRuntimeService toolRuntimeService = mock(ToolRuntimeService.class);
        when(toolRuntimeService.execute(any())).thenAnswer(invocation -> {
            ToolRuntimeRequest request = invocation.getArgument(0);
            requests.add(request);
            if (discoveryTool.equals(request.getToolName())) {
                return new ToolRuntimeExecution(
                    ToolOutput.success(Map.of(
                        "queryIr", Map.of(
                            "asset", Map.of(
                                "scoped", true,
                                "selected", Map.of(
                                    "id", "asset-oracle-dev",
                                    "name", "oracle-dev",
                                    "title", "Oracle DEV",
                                    "toolName", "db_query_oracle_dev",
                                    "environment", "DEV"
                                )
                            )
                        ),
                        "templates", templates
                    )),
                    ToolMetadata.builder().id(discoveryTool).build(),
                    null,
                    "success",
                    Map.of("remoteToolInvoked", true)
                );
            }
            List<ToolCallResult> results = templateIds.stream()
                .map(templateId -> new ToolCallResult(
                    templateId.toLowerCase(), executorTool, templateId, "asset-oracle-dev", "SUCCESS",
                    1, "evidence-" + templateId, Map.of("ok", true), Map.of()
                ))
                .toList();
            return new ToolRuntimeExecution(
                ToolOutput.success(new ToolCallBatchResult(
                    "diagnostic-step-2", "SEQUENTIAL", "start", "end", "SUCCESS",
                    new ToolCallBatchResult.Summary(5, 5, 0, 0, 0, 5),
                    results
                )),
                ToolMetadata.builder().id(executorTool).build(),
                null,
                "success",
                Map.of("batchExecution", true, "remoteToolInvocationCount", 5)
            );
        });

        List<InterpretationPlan.DiagnosticCheck> checks = List.of(
            new InterpretationPlan.DiagnosticCheck("instance_status", "instance_status", "availability", true, 1, List.of(3)),
            new InterpretationPlan.DiagnosticCheck("current_sessions", "session_info", "concurrency", true, 2, List.of(3)),
            new InterpretationPlan.DiagnosticCheck("lock_wait", "lock_wait", "concurrency", true, 3, List.of(3)),
            new InterpretationPlan.DiagnosticCheck("system_wait_events", "system_wait_events", "performance", true, 4, List.of(3)),
            new InterpretationPlan.DiagnosticCheck("tablespace_usage", "tablespace_usage", "capacity", true, 5, List.of(3))
        );
        InterpretationPlan plan = new InterpretationPlan(
            "1.0",
            new InterpretationPlan.Intent("database_health", "check oracle health", "low"),
            context(),
            new InterpretationPlan.Plan(
                List.of(
                    new InterpretationPlan.Step(
                        1, "mcp_tool", discoveryTool, Map.of("query", "oracle health"),
                        List.of(), null, null
                    ),
                    new InterpretationPlan.Step(
                        2, "reasoning", "",
                        Map.of("task", "Map the five discovered templates into five output fields"),
                        List.of(1), null, null
                    ),
                    new InterpretationPlan.Step(
                        3, "mcp_tool", executorTool,
                        Map.of(
                            "executionMode", "SEQUENTIAL",
                            "calls", List.of(
                                Map.of("callId", "model-cpu", "toolName", executorTool,
                                    "arguments", Map.of("templateId", "INVENTED_CPU_TEMPLATE")),
                                Map.of("callId", "model-load", "toolName", executorTool,
                                    "arguments", Map.of("templateId", "INVENTED_LOAD_TEMPLATE"))
                            ),
                            "executionContext", Map.of(
                                "assetName", "oracle-dev",
                                "env", "DEV"
                            ),
                            "parameters", Map.of()
                        ),
                        List.of(2), null, null
                    ),
                    new InterpretationPlan.Step(
                        4, "final_answer", "", Map.of("answer", "done"),
                        List.of(3), null, null
                    )
                ),
                List.of(
                    new InterpretationPlan.EdgeContract(2, 3, "instance_status_template", "string", true),
                    new InterpretationPlan.EdgeContract(2, 3, "session_template", "string", true),
                    new InterpretationPlan.EdgeContract(2, 3, "lock_wait_template", "string", true),
                    new InterpretationPlan.EdgeContract(2, 3, "wait_event_template", "string", true),
                    new InterpretationPlan.EdgeContract(2, 3, "tablespace_template", "string", true)
                ),
                List.of(),
                List.of(
                    new InterpretationPlan.Binding(
                        2, "$.instance_status_template", 3,
                        "templateBindings.instance_status_template", "jsonpath", true
                    ),
                    new InterpretationPlan.Binding(
                        2, "$.session_template", 3,
                        "templateBindings.session_template", "jsonpath", true
                    ),
                    new InterpretationPlan.Binding(
                        2, "$.lock_wait_template", 3,
                        "templateBindings.lock_wait_template", "jsonpath", true
                    ),
                    new InterpretationPlan.Binding(
                        2, "$.wait_event_template", 3,
                        "templateBindings.wait_event_template", "jsonpath", true
                    ),
                    new InterpretationPlan.Binding(
                        2, "$.tablespace_template", 3,
                        "templateBindings.tablespace_template", "jsonpath", true
                    )
                ),
                null,
                new InterpretationPlan.DiagnosticProfile("oracle_health", "database", checks)
            ),
            new InterpretationPlan.ExecutionPolicy(
                4, false, List.of(discoveryTool, executorTool), List.of(), 30_000
            ),
            review()
        );
        InterpretationPlanRuntime runtime = new InterpretationPlanRuntime(
            toolRuntimeService,
            new InterpretationPlanValidator(),
            new InterpretationPlanOptimizer(),
            null,
            request -> InterpretationPlanRuntime.StepReview.rejected(
                "available templates cover useful checks but another diagnostic aspect is missing",
                Map.of(
                    "refinedIntent", "find the remaining diagnostic capability",
                    "selectedTemplateIds", templateIds
                )
            ),
            scriptedController(List.of(List.of(1), List.of(2), List.of(3), List.of(4)))
        );

        InterpretationPlanRuntime.ExecutionResult result = runtime.execute(
            new InterpretationPlanRuntime.ExecutionRequest(
                plan, toolRegistry, List.of(discoveryTool, executorTool),
                "tenant", "request-diagnostic-batch", "conversation", "user", Map.of()
            )
        );

        assertThat(result.success())
            .as("status=%s error=%s metadata=%s steps=%s",
                result.status(), result.errorMessage(), result.metadata(), result.steps())
            .isTrue();
        assertThat(result.steps().get(0).metadata())
            .containsEntry("toolResultReviewPartialAccepted", true)
            .containsEntry("partialEvidence", true);
        ToolRuntimeRequest batchRequest = requests.stream()
            .filter(request -> executorTool.equals(request.getToolName()))
            .findFirst()
            .orElseThrow();
        assertThat(batchRequest.getToolInput().getParameters())
            .containsEntry("executionMode", "SEQUENTIAL");
        assertThat((List<?>) batchRequest.getToolInput().getParameters().get("calls")).hasSize(5);
        assertThat((List<Map<String, Object>>) batchRequest.getToolInput().getParameters().get("calls"))
            .extracting(call -> call.get("callId"))
            .containsExactly("instance_status", "current_sessions", "lock_wait", "system_wait_events", "tablespace_usage");
        assertThat((List<Map<String, Object>>) batchRequest.getToolInput().getParameters().get("calls"))
            .extracting(call -> String.valueOf(((Map<?, ?>) call.get("arguments")).get("templateId")))
            .containsExactlyElementsOf(templateIds);
        assertThat(batchRequest.getToolInput().getParameters().toString())
            .doesNotContain("INVENTED_CPU_TEMPLATE", "INVENTED_LOAD_TEMPLATE");
        assertThat(requests.stream()
            .filter(request -> discoveryTool.equals(request.getToolName())))
            .as("Runtime-owned diagnostic execution must reuse the completed discovery result")
            .hasSize(1);
        assertThat((List<String>) ((List<Map<String, Object>>) batchRequest.getToolInput()
            .getParameters().get("calls")).get(4).get("requiredFields"))
            .containsExactly("TABLESPACE_NAME", "USED_PERCENT");
        List<Map<String, Object>> compiledCalls =
            (List<Map<String, Object>>) batchRequest.getToolInput().getParameters().get("calls");
        assertThat(compiledCalls.get(3))
            .containsEntry("purpose", "health")
            .containsEntry("healthCapability", true)
            .containsEntry("timeSemantics", "SINCE_INSTANCE_START")
            .containsEntry("requiresContext", List.of("INSTANCE_UPTIME"));
        assertThat(compiledCalls.get(4))
            .containsEntry("purpose", "capacity_inventory")
            .containsEntry("healthCapability", false)
            .containsEntry("requiredMetrics", List.of("TABLESPACE_NAME", "USED_PERCENT"));
        assertThat((List<Map<String, Object>>) batchRequest.getToolInput().getParameters().get("calls"))
            .allSatisfy(call -> {
                Map<?, ?> arguments = (Map<?, ?>) call.get("arguments");
                Map<String, Object> executionContext =
                    (Map<String, Object>) arguments.get("executionContext");
                assertThat(executionContext)
                    .containsEntry("assetId", "asset-oracle-dev")
                    .containsEntry("assetName", "oracle-dev")
                    .containsEntry("assetDisplayName", "Oracle DEV")
                    .containsEntry("assetToolName", "db_query_oracle_dev")
                    .containsEntry("env", "DEV");
            });
    }

    @Test
    void executesSingleDiscoveredTemplateWhenSeveralDiagnosticChecksShareTheStep() {
        String discoveryTool = "tenant_http_template_query";
        String executorTool = "tenant_http_request_execute";
        String templateId = "tenant_node_metrics_" + System.nanoTime();
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.hasTool(any())).thenReturn(true);
        when(toolRegistry.getToolMetadata(any())).thenAnswer(invocation ->
            ToolMetadata.builder().id(invocation.getArgument(0)).riskLevel("low").build());

        AtomicReference<ToolRuntimeRequest> executionRequest = new AtomicReference<>();
        ToolRuntimeService toolRuntimeService = mock(ToolRuntimeService.class);
        when(toolRuntimeService.execute(any())).thenAnswer(invocation -> {
            ToolRuntimeRequest request = invocation.getArgument(0);
            if (discoveryTool.equals(request.getToolName())) {
                return new ToolRuntimeExecution(
                    ToolOutput.success(Map.of(
                        "queryIr", Map.of("asset", Map.of("selected", Map.of(
                            "id", "asset-node-metrics",
                            "name", "node-metrics",
                            "environment", "PROD"
                        ))),
                        "templates", List.of(Map.of(
                            "templateId", templateId,
                            "capability", "node_resource_metrics",
                            "parameterSchema", Map.of("type", "object", "required", List.of()),
                            "parameterContract", Map.of("executionTool", executorTool),
                            "executionBinding", Map.of(
                                "toolName", executorTool,
                                "templateId", templateId,
                                "executionContext", Map.of(
                                    "assetId", "asset-node-metrics",
                                    "assetName", "node-metrics",
                                    "env", "PROD"
                                )
                            )
                        ))
                    )),
                    ToolMetadata.builder().id(discoveryTool).build(), null, "success", Map.of()
                );
            }
            executionRequest.set(request);
            return new ToolRuntimeExecution(
                ToolOutput.success(Map.of("nodes", List.of())),
                ToolMetadata.builder().id(executorTool).build(), null, "success", Map.of()
            );
        });

        InterpretationPlan plan = new InterpretationPlan(
            "1.0",
            new InterpretationPlan.Intent("tool_chain", "inspect node resources", "low"),
            context(),
            new InterpretationPlan.Plan(
                List.of(
                    new InterpretationPlan.Step(1, "mcp_tool", discoveryTool,
                        Map.of("filters", Map.of("intent", "node resources")), List.of(), null, null),
                    new InterpretationPlan.Step(2, "mcp_tool", executorTool,
                        Map.of(
                            "parameters", Map.of(),
                            "executionContext", Map.of("env", "PROD", "service", "nodes")
                        ), List.of(1), null, null),
                    new InterpretationPlan.Step(3, "final_answer", "",
                        Map.of("answer", "done"), List.of(2), null, null)
                ),
                List.of(),
                List.of(),
                List.of(new InterpretationPlan.Binding(
                    1, "$.templates[0].templateId", 2, "template", "jsonpath", true
                )),
                null,
                new InterpretationPlan.DiagnosticProfile("node_resources", "http", List.of(
                    new InterpretationPlan.DiagnosticCheck(
                        "node_count", "node_resource_metrics", "capacity", true, 1, List.of(2)),
                    new InterpretationPlan.DiagnosticCheck(
                        "node_health", "node_resource_metrics", "availability", true, 2, List.of(2))
                ))
            ),
            new InterpretationPlan.ExecutionPolicy(
                3, false, List.of(discoveryTool, executorTool), List.of(), 30_000
            ),
            review()
        );
        InterpretationPlanRuntime runtime = new InterpretationPlanRuntime(
            toolRuntimeService,
            new InterpretationPlanValidator(),
            scriptedController(List.of(List.of(1), List.of(2), List.of(3)))
        );

        InterpretationPlanRuntime.ExecutionResult result = runtime.execute(
            new InterpretationPlanRuntime.ExecutionRequest(
                plan, toolRegistry, List.of(discoveryTool, executorTool),
                "tenant", "request", "conversation", "user", Map.of()
            )
        );

        assertThat(result.success()).as(result.errorMessage()).isTrue();
        assertThat(executionRequest.get()).isNotNull();
        assertThat(executionRequest.get().getToolInput().getParameters())
            .containsEntry("template", templateId)
            .containsEntry("templateId", templateId)
            .doesNotContainKeys("calls", "executionMode");
        assertThat((Map<String, Object>) executionRequest.get().getToolInput().getParameters().get("executionContext"))
            .containsEntry("assetId", "asset-node-metrics")
            .containsEntry("assetName", "node-metrics")
            .containsEntry("env", "PROD");
    }

    private static Map<String, Object> userQueryParameterProtocol(Integer stepId,
                                                                  String templateId,
                                                                  String evidenceQuote,
                                                                  Map<String, Object> parameters) {
        Map<String, Object> arguments = new LinkedHashMap<>();
        parameters.forEach((name, value) -> arguments.put(name, Map.of(
            "value", value,
            "source", "user_query",
            "evidence", Map.of("quote", evidenceQuote)
        )));
        return Map.of(
            "protocol_version", InterpretationExecutionProtocol.TEMPLATE_PARAMETER_PROTOCOL_VERSION,
            "step_id", stepId,
            "template_id", templateId,
            "arguments", arguments,
            "unresolved_parameters", List.of()
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
            while (waves != null && index.get() < waves.size()) {
                List<Integer> stepIds = waves.get(index.getAndIncrement()).stream()
                    .filter(stepId -> request.remainingStepIds().contains(stepId))
                    .toList();
                if (stepIds.isEmpty()) {
                    continue;
                }
                if (stepIds.size() > 1) {
                    return InterpretationPlanRuntime.DagDecision.executeParallelSteps(stepIds, "scripted parallel decision");
                }
                Integer stepId = stepIds.get(0);
                InterpretationPlan.Step step = request.plan().steps().stream()
                    .filter(candidate -> stepId.equals(candidate.id()))
                    .findFirst()
                    .orElse(null);
                if (step != null && step.finalAnswerAction()) {
                    return InterpretationPlanRuntime.DagDecision.finalAnswer(stepId, String.valueOf(step.input().get("answer")), "scripted final answer");
                }
                return InterpretationPlanRuntime.DagDecision.executeStep(stepId, "scripted decision");
            }
            List<Integer> finalStepIds = request.remainingStepIds().stream()
                .filter(stepId -> request.plan().steps().stream()
                    .anyMatch(step -> stepId.equals(step.id()) && step.finalAnswerAction()))
                .toList();
            if (!finalStepIds.isEmpty()) {
                Integer stepId = finalStepIds.get(0);
                InterpretationPlan.Step step = request.plan().steps().stream()
                    .filter(candidate -> stepId.equals(candidate.id()))
                    .findFirst()
                    .orElse(null);
                Object answer = step == null || step.input() == null ? null : step.input().get("answer");
                return InterpretationPlanRuntime.DagDecision.finalAnswer(stepId, answer == null ? "" : String.valueOf(answer), "scripted final answer");
            }
            return InterpretationPlanRuntime.DagDecision.abort("No scripted DAG decision remains");
        };
    }

    @Test
    void schedulesOrdinaryReadyNodesWithoutDagController() {
        ToolRegistry registry = mock(ToolRegistry.class);
        when(registry.hasTool("document_search")).thenReturn(true);
        when(registry.getToolMetadata("document_search"))
            .thenReturn(ToolMetadata.builder().riskLevel("low").build());
        ToolRuntimeService service = mock(ToolRuntimeService.class);
        when(service.execute(any())).thenReturn(new ToolRuntimeExecution(
            ToolOutput.success(Map.of("results", List.of("evidence"))),
            ToolMetadata.builder().id("document_search").build(), null, "success", Map.of()));
        InterpretationPlanRuntime runtime = new InterpretationPlanRuntime(
            service, new InterpretationPlanValidator(),
            (InterpretationPlanRuntime.DagExecutionController) null);

        InterpretationPlanRuntime.ExecutionResult result = runtime.execute(
            new InterpretationPlanRuntime.ExecutionRequest(
                serialPlan(), registry, List.of("document_search"), "tenant-1",
                "request-java-scheduler", "conversation-java-scheduler", "user-1", Map.of()));

        assertThat(result.success()).isTrue();
        assertThat(result.steps()).extracting(InterpretationPlanRuntime.StepExecution::stepId)
            .containsExactly(1, 2);
        assertThat(result.metadata())
            .containsEntry("llmDagController", false)
            .containsEntry("llmDagDecisionCount", 0);
    }

    @Test
    void asksModelOnlyToArbitrateAmongReadySemanticBranches() {
        ToolRegistry registry = semanticBranchRegistry();
        ToolRuntimeService service = successfulSemanticBranchService();
        InterpretationPlanRuntime runtime = new InterpretationPlanRuntime(
            service, new InterpretationPlanValidator(), request -> {
                assertThat(request.decisionPurpose()).isEqualTo("SEMANTIC_BRANCH_ARBITRATION");
                assertThat(request.readyStepIds()).containsExactly(1, 2);
                assertThat(request.remainingStepIds()).containsExactlyInAnyOrder(1, 2, 3);
                return InterpretationPlanRuntime.DagDecision.executeStep(2, "semantic condition selected branch B");
            });

        InterpretationPlanRuntime.ExecutionResult result = runtime.execute(
            new InterpretationPlanRuntime.ExecutionRequest(
                semanticBranchPlan(), registry, List.of("branch_a", "branch_b"), "tenant-1",
                "request-semantic-branch", "conversation-semantic-branch", "user-1", Map.of()));

        assertThat(result.success()).isTrue();
        assertThat(result.steps()).extracting(InterpretationPlanRuntime.StepExecution::stepId)
            .containsExactly(2, 3);
        assertThat(result.metadata())
            .containsEntry("llmDagDecisionCount", 1)
            .containsEntry("semanticBranchSkippedStepIds", List.of(1));
    }

    @Test
    void rejectsModelSelectionOutsideRuntimeReadySet() {
        ToolRegistry registry = semanticBranchRegistry();
        ToolRuntimeService service = successfulSemanticBranchService();
        InterpretationPlanRuntime runtime = new InterpretationPlanRuntime(
            service, new InterpretationPlanValidator(), request ->
                InterpretationPlanRuntime.DagDecision.finalAnswer(3, "illegal", "skip Ready branches"));

        InterpretationPlanRuntime.ExecutionResult result = runtime.execute(
            new InterpretationPlanRuntime.ExecutionRequest(
                semanticBranchPlan(), registry, List.of("branch_a", "branch_b"), "tenant-1",
                "request-ready-guard", "conversation-ready-guard", "user-1", Map.of()));

        assertThat(result.success()).isFalse();
        assertThat(result.status()).isEqualTo("DAG_DECISION_REJECTED");
        assertThat(result.errorMessage()).contains("outside the Runtime Ready set").contains("[1, 2]");
        verify(service, never()).execute(any());
    }

    private ToolRegistry semanticBranchRegistry() {
        ToolRegistry registry = mock(ToolRegistry.class);
        when(registry.hasTool(any())).thenReturn(true);
        when(registry.getToolMetadata(any()))
            .thenReturn(ToolMetadata.builder().riskLevel("low").build());
        return registry;
    }

    private ToolRuntimeService successfulSemanticBranchService() {
        ToolRuntimeService service = mock(ToolRuntimeService.class);
        when(service.execute(any())).thenAnswer(invocation -> {
            ToolRuntimeRequest request = invocation.getArgument(0);
            return new ToolRuntimeExecution(
                ToolOutput.success(Map.of("branch", request.getToolName())),
                ToolMetadata.builder().id(request.getToolName()).build(), null, "success", Map.of());
        });
        return service;
    }

    private InterpretationPlan semanticBranchPlan() {
        return new InterpretationPlan(
            "1.0",
            new InterpretationPlan.Intent("routing", "choose a legal semantic path", "low"),
            context(),
            new InterpretationPlan.Plan(
                List.of(
                    new InterpretationPlan.Step(1, "mcp_tool", "branch_a", Map.of(), List.of(), null, null),
                    new InterpretationPlan.Step(2, "mcp_tool", "branch_b", Map.of(), List.of(), null, null),
                    new InterpretationPlan.Step(3, "final_answer", "", Map.of("answer", "done"), List.of(), null, null)
                ),
                List.of(),
                List.of(
                    new InterpretationPlan.DependencyContract(1, 3, false, "when source A is authoritative", "route A", "skip"),
                    new InterpretationPlan.DependencyContract(2, 3, false, "when source B is authoritative", "route B", "skip")
                ),
                List.of(),
                null
            ),
            new InterpretationPlan.ExecutionPolicy(3, false, List.of("branch_a", "branch_b"), List.of(), 30_000),
            review()
        );
    }

    private static final class RecordingNodeAttemptStore implements NodeAttemptStore {
        private final AtomicInteger sequence = new AtomicInteger();
        private final Map<String, AttemptSnapshot> attempts = new LinkedHashMap<>();
        private final List<BarrierCommand> barriers = new ArrayList<>();

        @Override
        public synchronized AttemptSnapshot create(AttemptCommand command) {
            String id = "attempt-" + command.nodeId() + "-" + sequence.incrementAndGet();
            AttemptSnapshot snapshot = new AttemptSnapshot(
                id, command.tenantId(), command.runId(), command.nodeId(), 1,
                State.CREATED, 0L, Instant.now(), Instant.now());
            attempts.put(id, snapshot);
            return snapshot;
        }

        @Override
        public synchronized AttemptSnapshot transition(String tenantId, String attemptId,
                                                       State expectedState, State targetState,
                                                       String reason, Map<String, Object> metadata) {
            AttemptSnapshot current = attempts.get(attemptId);
            assertThat(current).isNotNull();
            assertThat(current.tenantId()).isEqualTo(tenantId);
            assertThat(current.state()).isEqualTo(expectedState);
            assertThat(expectedState.mayTransitionTo(targetState)).isTrue();
            AttemptSnapshot updated = new AttemptSnapshot(
                current.attemptId(), current.tenantId(), current.runId(), current.nodeId(),
                current.attemptNumber(), targetState, current.revision() + 1,
                current.createdAt(), Instant.now());
            attempts.put(attemptId, updated);
            return updated;
        }

        @Override
        public synchronized BarrierResult commitBarrier(BarrierCommand command) {
            barriers.add(command);
            List<AttemptSnapshot> committed = command.requiredAttemptIds().stream().map(attemptId -> {
                AttemptSnapshot current = attempts.get(attemptId);
                assertThat(current).isNotNull();
                assertThat(current.tenantId()).isEqualTo(command.tenantId());
                assertThat(current.runId()).isEqualTo(command.runId());
                assertThat(current.state()).isEqualTo(State.PREPARED);
                AttemptSnapshot updated = new AttemptSnapshot(
                    current.attemptId(), current.tenantId(), current.runId(), current.nodeId(),
                    current.attemptNumber(), State.COMMITTED, current.revision() + 1,
                    current.createdAt(), Instant.now());
                attempts.put(attemptId, updated);
                return updated;
            }).toList();
            return new BarrierResult(command.executionEpoch(), true, committed);
        }

        synchronized List<BarrierCommand> barriers() {
            return List.copyOf(barriers);
        }
    }
}
