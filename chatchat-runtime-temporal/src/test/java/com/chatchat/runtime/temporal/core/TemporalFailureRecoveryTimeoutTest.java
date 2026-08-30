package com.chatchat.runtime.temporal.core;

import com.chatchat.agents.runtime.plan.InterpretationPlan;
import com.chatchat.agents.runtime.plan.InterpretationPlanRuntime;
import com.chatchat.agents.runtime.plan.execution.PlanDagControlPort;
import com.chatchat.agents.runtime.plan.execution.PlanExecutionContinuation;
import com.chatchat.agents.runtime.plan.execution.PlanExecutionPhaseHandler;
import com.chatchat.agents.runtime.plan.execution.PlanModelArbitrationResult;
import com.chatchat.agents.runtime.plan.execution.PlanNodePersistenceCommand;
import com.chatchat.agents.runtime.plan.execution.PlanNodePersistenceResult;
import com.chatchat.agents.runtime.plan.execution.PlanToolExecutionCommand;
import com.chatchat.agents.runtime.tool.ToolRuntimeExecution;
import com.chatchat.agents.runtime.tool.ToolRuntimeRequest;
import com.chatchat.agents.runtime.tool.ToolRuntimeService;
import com.chatchat.agents.runtime.workflow.WorkflowStartRequest;
import com.chatchat.common.tool.ToolInput;
import com.chatchat.common.tool.ToolMetadata;
import com.chatchat.common.tool.ToolOutput;
import com.chatchat.runtime.temporal.adapter.TemporalPlanDagControlPort;
import com.chatchat.runtime.temporal.adapter.TemporalPlanToolExecutionPort;
import com.chatchat.runtime.temporal.config.TemporalWorkflowProperties;
import com.chatchat.runtime.temporal.contract.TemporalPlanExecutionCommand;
import com.chatchat.runtime.temporal.contract.TemporalPlanExecutionResult;
import com.chatchat.runtime.temporal.contract.TemporalToolActivityCommand;
import com.chatchat.runtime.temporal.contract.TemporalToolExecutionSnapshot;
import com.chatchat.runtime.temporal.workflow.RuntimeOsPlanExecutionWorkflow;
import com.chatchat.runtime.temporal.workflow.RuntimeOsToolExecutionWorkflow;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.temporal.client.WorkflowOptions;
import io.temporal.failure.TimeoutFailure;
import io.temporal.testing.TestWorkflowEnvironment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TemporalFailureRecoveryTimeoutTest {

    private TestWorkflowEnvironment environment;
    private TemporalWorkflowRuntime runtime;
    private TemporalWorkflowProperties properties;
    private ToolRuntimeService toolRuntimeService;
    private PlanExecutionPhaseHandler phaseHandler;
    private String taskQueue;

    @BeforeEach
    void setUp() throws Exception {
        environment = TestWorkflowEnvironment.newInstance();
        properties = new TemporalWorkflowProperties();
        taskQueue = "temporal-resilience-" + System.nanoTime();
        properties.setTaskQueue(taskQueue);
        properties.setActivityStartToCloseSeconds(60);
        properties.setActivityHeartbeatSeconds(1);
        toolRuntimeService = mock(ToolRuntimeService.class);
        phaseHandler = mock(PlanExecutionPhaseHandler.class);
        runtime = new TemporalWorkflowRuntime(
            environment.getWorkflowClient(), environment.getWorkerFactory(),
            new ObjectMapper(), properties, toolRuntimeService, phaseHandler);
        runtime.register("resilience-bootstrap-v1", EchoInput.class, EchoOutput.class,
            (input, context) -> new EchoOutput(input.value(), context.attempt()));
        runtime.start(new WorkflowStartRequest<>(
                "resilience-bootstrap-" + System.nanoTime(), "resilience-bootstrap-v1",
                "tenant-a", "bootstrap-request", new EchoInput("ready")))
            .completion().get(10, TimeUnit.SECONDS);
    }

    @AfterEach
    void tearDown() {
        if (runtime != null) {
            runtime.close();
        }
        if (environment != null) {
            environment.close();
        }
    }

    @Test
    void planAbortClosesWithoutPreparingOrPersistingNodes() {
        PlanExecutionContinuation initial = continuation("plan-abort", List.of(1), 0);
        when(phaseHandler.arbitrate(any())).thenReturn(new PlanModelArbitrationResult(
            "abort", List.of(), Map.of(), "safe partial answer", "external evidence unavailable"));

        TemporalPlanExecutionResult result = planWorkflow("plan-abort-workflow").execute(
            new TemporalPlanExecutionCommand(null, initial, 60, 1, false));

        assertThat(result.status()).isEqualTo("ABORT");
        assertThat(result.finalAnswer()).isEqualTo("safe partial answer");
        assertThat(result.reason()).isEqualTo("external evidence unavailable");
        assertThat(result.continuation()).isEqualTo(initial);
        verify(phaseHandler, never()).prepare(any());
        verify(phaseHandler, never()).persist(any());
    }

    @Test
    void planWithNoReadyNodeReturnsAuditableNoProgressTerminal() {
        InterpretationPlan blockedPlan = new InterpretationPlan(
            "1.0", new InterpretationPlan.Intent("analysis", "blocked", "low"),
            new InterpretationPlan.Context(List.of(), List.of(), List.of(), List.of()),
            new InterpretationPlan.Plan(List.of(
                new InterpretationPlan.Step(1, "final_answer", "", Map.of("answer", "first"),
                    List.of(), null, null),
                new InterpretationPlan.Step(2, "final_answer", "", Map.of("answer", "second"),
                    List.of(1), null, null))),
            new InterpretationPlan.ExecutionPolicy(2, false, List.of(), List.of(), 60_000),
            new InterpretationPlan.Review(
                new InterpretationPlan.SelfCheck(1.0, 0.0, true, List.of()), List.of()));
        PlanExecutionContinuation blocked = new PlanExecutionContinuation(
            null, "plan-no-progress", blockedPlan, List.of(2), List.of(),
            List.of(), List.of(), 0, Map.of());

        TemporalPlanExecutionResult result = planWorkflow("plan-no-progress-workflow").execute(
            new TemporalPlanExecutionCommand(null, blocked, 60, 1, false));

        assertThat(result.status()).isEqualTo("DAG_NO_PROGRESS");
        assertThat(result.reason()).isEqualTo("Unfinished DAG contains no Ready nodes");
        verify(phaseHandler, never()).arbitrate(any());
    }

    @Test
    void planRewriteRequestReturnsWithoutExecutingAdmittedNodes() {
        PlanExecutionContinuation initial = continuation("plan-rewrite", List.of(1), 0);
        when(phaseHandler.arbitrate(any())).thenReturn(new PlanModelArbitrationResult(
            "rewrite_plan", List.of(), Map.of(), null, "evidence contract changed"));

        TemporalPlanExecutionResult result = planWorkflow("plan-rewrite-workflow").execute(
            new TemporalPlanExecutionCommand(null, initial, 60, 1, false));

        assertThat(result.status()).isEqualTo("REWRITE_PLAN");
        assertThat(result.finalAnswer()).isNull();
        assertThat(result.reason()).isEqualTo("evidence contract changed");
        verify(phaseHandler, never()).prepare(any());
    }

    @Test
    void planPersistenceThatDoesNotAdvanceFailsClosed() {
        PlanExecutionContinuation initial = continuation("plan-stalled", List.of(1), 0);
        when(phaseHandler.arbitrate(any())).thenReturn(new PlanModelArbitrationResult(
            "execute_wave", List.of(1), Map.of(), null, "ready"));
        InterpretationPlanRuntime.StepExecution step = new InterpretationPlanRuntime.StepExecution(
            1, "final_answer", "", true, Map.of(), null, null, "done", 0L, Map.of());
        when(phaseHandler.prepare(any())).thenReturn(
            new com.chatchat.agents.runtime.plan.execution.PlanStepPreparationResult(List.of(
                new com.chatchat.agents.runtime.plan.execution.PreparedPlanStep(
                    1, "final_answer", "", null, 1, 60, false,
                    "immediate", step, Map.of()))));
        when(phaseHandler.persist(any(PlanNodePersistenceCommand.class))).thenReturn(
            new PlanNodePersistenceResult(initial, "RUNNING"));

        assertThatThrownBy(() -> planWorkflow("plan-stalled-workflow").execute(
            new TemporalPlanExecutionCommand(null, initial, 60, 1, false)))
            .hasStackTraceContaining("Node persistence returned a non-progressing continuation");
        verify(phaseHandler, times(1)).persist(any());
    }

    @Test
    void planPersistenceRejectionReturnsFailedTerminalWithCommittedContinuation() {
        PlanExecutionContinuation initial = continuation("plan-persist-rejected", List.of(1), 0);
        InterpretationPlanRuntime.StepExecution step = immediateStep();
        when(phaseHandler.arbitrate(any())).thenReturn(new PlanModelArbitrationResult(
            "execute_wave", List.of(1), Map.of(), null, "ready"));
        when(phaseHandler.prepare(any())).thenReturn(
            new com.chatchat.agents.runtime.plan.execution.PlanStepPreparationResult(List.of(
                new com.chatchat.agents.runtime.plan.execution.PreparedPlanStep(
                    1, "final_answer", "", null, 1, 60, false,
                    "immediate", step, Map.of()))));
        PlanExecutionContinuation rejected = new PlanExecutionContinuation(
            null, initial.sessionId(), initial.plan(), List.of(), List.of(step),
            List.of(), List.of(1), 1, Map.of());
        when(phaseHandler.persist(any())).thenReturn(
            new PlanNodePersistenceResult(rejected, "FAILED"));

        TemporalPlanExecutionResult result = planWorkflow("plan-persist-rejected-workflow").execute(
            new TemporalPlanExecutionCommand(null, initial, 60, 1, false));

        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(result.reason()).isEqualTo("Node persistence rejected the wave");
        assertThat(result.continuation()).isEqualTo(rejected);
    }

    @Test
    void modelSelectionOutsideReadyWaveFailsAsNonRetryableInvariantViolation() {
        PlanExecutionContinuation initial = continuation("plan-invalid-selection", List.of(1), 0);
        when(phaseHandler.arbitrate(any())).thenReturn(new PlanModelArbitrationResult(
            "execute_wave", List.of(99), Map.of(), null, "invalid model output"));

        assertThatThrownBy(() -> planWorkflow("plan-invalid-selection-workflow").execute(
            new TemporalPlanExecutionCommand(null, initial, 60, 1, false)))
            .hasStackTraceContaining("PLAN_EXECUTION_INVARIANT_VIOLATION")
            .hasStackTraceContaining("selected nodes outside the Ready wave");
        verify(phaseHandler, never()).prepare(any());
    }

    @Test
    void toolActivityHardTimeoutIsTerminalAndIsNotRetried() {
        String toolName = "slow_external_adapter";
        ToolRuntimeRequest request = toolRequest(toolName, "timeout-request");
        when(toolRuntimeService.metadata(toolName)).thenReturn(ToolMetadata.builder()
            .id(toolName).operationType("write").metadata(Map.of("idempotent", false)).build());
        when(toolRuntimeService.execute(any())).thenAnswer(invocation -> {
            Thread.sleep(5_000L);
            return successfulExecution(toolName);
        });
        RuntimeOsToolExecutionWorkflow workflow = toolWorkflow("tool-hard-timeout");

        assertThatThrownBy(() -> workflow.execute(new TemporalToolActivityCommand(
            request, "timeout-key", 5, 1, false, "external adapter is not retry safe")))
            .hasRootCauseInstanceOf(TimeoutFailure.class);
        assertThat(workflow.status()).isEqualTo(new TemporalToolExecutionSnapshot(
            "tool-hard-timeout", toolName, "timeout-key", "FAILED", 1));
        verify(toolRuntimeService, times(1)).execute(any());
    }

    @Test
    void failedExternalAdapterWorkflowIsReattachedWithoutRepeatingSideEffect() {
        String toolName = "failing_external_adapter";
        when(toolRuntimeService.metadata(toolName)).thenReturn(ToolMetadata.builder()
            .id(toolName).operationType("write").metadata(Map.of("idempotent", false)).build());
        when(toolRuntimeService.execute(any()))
            .thenThrow(new IllegalStateException("external adapter unavailable"));
        TemporalPlanToolExecutionPort port = new TemporalPlanToolExecutionPort(
            environment.getWorkflowClient(), properties, toolRuntimeService);
        PlanToolExecutionCommand command = toolCommand(toolName, "stable-failure-key");

        assertThatThrownBy(() -> port.execute(command))
            .hasStackTraceContaining("external adapter unavailable");
        assertThatThrownBy(() -> port.execute(command))
            .hasStackTraceContaining("external adapter unavailable");
        verify(toolRuntimeService, times(1)).execute(any());
    }

    @Test
    void externalAdapterWaitSurvivesHeartbeatTimeoutUntilDurableResultArrives() throws Exception {
        String toolName = "slow_but_healthy_adapter";
        properties.setActivityHeartbeatSeconds(3);
        when(toolRuntimeService.metadata(toolName)).thenReturn(ToolMetadata.builder()
            .id(toolName).operationType("read")
            .metadata(Map.of("idempotent", true, "workflowActivityMaximumAttempts", 2)).build());
        when(toolRuntimeService.execute(any())).thenAnswer(invocation -> {
            Thread.sleep(1_250L);
            return successfulExecution(toolName);
        });
        TemporalPlanToolExecutionPort port = new TemporalPlanToolExecutionPort(
            environment.getWorkflowClient(), properties, toolRuntimeService);
        runtime.register("slow-adapter-v1", EchoInput.class, EchoOutput.class,
            (input, context) -> {
                ToolRuntimeExecution execution = port.execute(toolCommand(toolName, "slow-stable-key"));
                return new EchoOutput(String.valueOf(execution.output().getData()), context.attempt());
            });

        EchoOutput output = runtime.<EchoInput, EchoOutput>start(new WorkflowStartRequest<>(
                "slow-adapter-parent", "slow-adapter-v1", "tenant-a", "slow-request",
                new EchoInput("execute")))
            .completion().get(10, TimeUnit.SECONDS);

        assertThat(output.value()).contains(toolName);
        verify(toolRuntimeService, times(1)).execute(any());
    }

    @Test
    void dagAdapterReattachesToOpenSessionAndRejectsUseAfterClose() {
        TemporalPlanDagControlPort port = new TemporalPlanDagControlPort(
            environment.getWorkflowClient(), properties);
        InterpretationPlan plan = plan();
        PlanDagControlPort.SessionCommand command = new PlanDagControlPort.SessionCommand(
            null, "dag-recovery-session", new com.chatchat.agents.runtime.plan.execution
                .DeterministicPlanDagStateMachine().compile(plan));

        PlanDagControlPort.Session original = port.open(command);
        PlanDagControlPort.Session recovered = port.open(command);
        PlanDagControlPort.Snapshot snapshot = recovered.synchronize(
            new PlanDagControlPort.StateCommand(List.of(1), List.of(), List.of(), List.of()));

        assertThat(snapshot.sessionId()).isEqualTo("dag-recovery-session");
        assertThat(snapshot.readyStepIds()).containsExactly(1);
        recovered.close();
        recovered.close();
        assertThatThrownBy(() -> recovered.synchronize(
            new PlanDagControlPort.StateCommand(List.of(1), List.of(), List.of(), List.of())))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("Plan DAG Temporal session is closed");
        assertThatThrownBy(() -> original.decideBarrier(List.of(), false))
            .isInstanceOf(io.temporal.client.WorkflowNotFoundException.class);
    }

    private RuntimeOsPlanExecutionWorkflow planWorkflow(String workflowId) {
        return environment.getWorkflowClient().newWorkflowStub(
            RuntimeOsPlanExecutionWorkflow.class,
            WorkflowOptions.newBuilder().setWorkflowId(workflowId).setTaskQueue(taskQueue).build());
    }

    private RuntimeOsToolExecutionWorkflow toolWorkflow(String workflowId) {
        return environment.getWorkflowClient().newWorkflowStub(
            RuntimeOsToolExecutionWorkflow.class,
            WorkflowOptions.newBuilder().setWorkflowId(workflowId).setTaskQueue(taskQueue).build());
    }

    private PlanExecutionContinuation continuation(String sessionId, List<Integer> remaining,
                                                   int decisionCount) {
        return new PlanExecutionContinuation(null, sessionId, plan(), remaining, List.of(),
            List.of(), List.of(), decisionCount, Map.of());
    }

    private InterpretationPlan plan() {
        return new InterpretationPlan(
            "1.0", new InterpretationPlan.Intent("analysis", "resilience", "low"),
            new InterpretationPlan.Context(List.of(), List.of(), List.of(), List.of()),
            new InterpretationPlan.Plan(List.of(new InterpretationPlan.Step(
                1, "final_answer", "", Map.of("answer", "done"), List.of(), null, null))),
            new InterpretationPlan.ExecutionPolicy(1, false, List.of(), List.of(), 60_000),
            new InterpretationPlan.Review(
                new InterpretationPlan.SelfCheck(1.0, 0.0, true, List.of()), List.of()));
    }

    private ToolRuntimeRequest toolRequest(String toolName, String requestId) {
        return ToolRuntimeRequest.builder()
            .toolName(toolName).runtimeMode("interpretation_plan")
            .requestId(requestId).tenantId("tenant-a").allowedTools(List.of(toolName))
            .toolInput(ToolInput.builder().parameters(Map.of()).build()).attributes(Map.of()).build();
    }

    private PlanToolExecutionCommand toolCommand(String toolName, String idempotencyKey) {
        return new PlanToolExecutionCommand(
            null, "resilience-run", "resilience-scope", "0", 1, "PRIMARY",
            "fingerprint", idempotencyKey, toolRequest(toolName, idempotencyKey));
    }

    private ToolRuntimeExecution successfulExecution(String toolName) {
        return new ToolRuntimeExecution(ToolOutput.success(Map.of("adapter", toolName)),
            ToolMetadata.builder().id(toolName).build(), null, "success", Map.of());
    }

    private InterpretationPlanRuntime.StepExecution immediateStep() {
        return new InterpretationPlanRuntime.StepExecution(
            1, "final_answer", "", true, Map.of(), null, null, "done", 0L, Map.of());
    }

    record EchoInput(String value) {
    }

    record EchoOutput(String value, int attempt) {
    }
}
