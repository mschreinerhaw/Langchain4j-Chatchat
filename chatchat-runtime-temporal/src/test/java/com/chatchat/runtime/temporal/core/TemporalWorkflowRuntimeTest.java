package com.chatchat.runtime.temporal.core;

import com.chatchat.agents.runtime.workflow.WorkflowExecutionStatus;
import com.chatchat.agents.runtime.workflow.WorkflowHandle;
import com.chatchat.agents.runtime.workflow.WorkflowStartRequest;
import com.chatchat.agents.runtime.tool.ToolRuntimeExecution;
import com.chatchat.agents.runtime.tool.ToolRuntimeRequest;
import com.chatchat.agents.runtime.tool.ToolRuntimeService;
import com.chatchat.agents.runtime.plan.execution.PlanToolExecutionCommand;
import com.chatchat.agents.runtime.plan.execution.PlanDagControlPort;
import com.chatchat.agents.runtime.plan.execution.PlanExecutionContinuation;
import com.chatchat.agents.runtime.plan.execution.PlanExecutionPhaseHandler;
import com.chatchat.agents.runtime.plan.execution.PlanModelArbitrationCommand;
import com.chatchat.agents.runtime.plan.execution.PlanModelArbitrationResult;
import com.chatchat.agents.runtime.plan.execution.PlanNodePersistenceCommand;
import com.chatchat.agents.runtime.plan.execution.PlanNodePersistenceResult;
import com.chatchat.agents.runtime.plan.execution.PlanStepPreparationCommand;
import com.chatchat.agents.runtime.plan.execution.PlanStepPreparationResult;
import com.chatchat.agents.runtime.plan.execution.PreparedPlanStep;
import com.chatchat.agents.runtime.plan.InterpretationPlan;
import com.chatchat.agents.runtime.plan.InterpretationPlanOptimizer;
import com.chatchat.agents.runtime.plan.InterpretationPlanRuntime;
import com.chatchat.agents.runtime.plan.InterpretationPlanValidator;
import com.chatchat.agents.tool.ToolRegistry;
import com.chatchat.runtime.temporal.adapter.TemporalPlanDagControlPort;
import com.chatchat.runtime.temporal.adapter.TemporalPlanToolExecutionPort;
import com.chatchat.runtime.temporal.activity.RuntimeOsToolActivityImpl;
import com.chatchat.runtime.temporal.config.TemporalWorkflowProperties;
import com.chatchat.runtime.temporal.contract.TemporalToolActivityCommand;
import com.chatchat.runtime.temporal.contract.TemporalToolExecutionSnapshot;
import com.chatchat.runtime.temporal.contract.TemporalWorkflowResult;
import com.chatchat.runtime.temporal.contract.TemporalPlanExecutionCommand;
import com.chatchat.runtime.temporal.contract.TemporalPlanExecutionResult;
import com.chatchat.runtime.temporal.workflow.RuntimeOsPlanExecutionWorkflow;
import com.chatchat.runtime.temporal.workflow.RuntimeOsTemporalWorkflowImpl;
import com.chatchat.runtime.temporal.workflow.RuntimeOsToolExecutionWorkflow;
import com.chatchat.common.tool.ToolInput;
import com.chatchat.common.tool.ToolMetadata;
import com.chatchat.common.tool.ToolOutput;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.client.WorkflowStub;
import io.temporal.client.WorkflowOptions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TemporalWorkflowRuntimeTest {

    private TestWorkflowEnvironment environment;
    private TemporalWorkflowRuntime runtime;
    private ToolRuntimeService toolRuntimeService;
    private TemporalWorkflowProperties properties;
    private String taskQueue;
    private PlanExecutionPhaseHandler phaseHandler;

    @BeforeEach
    void setUp() {
        environment = TestWorkflowEnvironment.newInstance();
        properties = new TemporalWorkflowProperties();
        taskQueue = "runtime-os-test-" + System.nanoTime();
        properties.setTaskQueue(taskQueue);
        properties.setActivityStartToCloseSeconds(60);
        properties.setActivityHeartbeatSeconds(1);
        toolRuntimeService = mock(ToolRuntimeService.class);
        phaseHandler = mock(PlanExecutionPhaseHandler.class);
        runtime = new TemporalWorkflowRuntime(
            environment.getWorkflowClient(), environment.getWorkerFactory(),
            new ObjectMapper(), properties, toolRuntimeService, phaseHandler);
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
    void executesRegisteredDefinitionAndExposesTerminalSnapshot() throws Exception {
        runtime.register("echo-v1", EchoInput.class, EchoOutput.class,
            (input, context) -> new EchoOutput(input.value().toUpperCase(), context.attempt()));

        WorkflowHandle<EchoOutput> handle = runtime.start(request("run-success", "echo-v1", "hello"));

        assertThat(handle.newlyStarted()).isTrue();
        assertThat(handle.completion().get(10, TimeUnit.SECONDS))
            .isEqualTo(new EchoOutput("HELLO", 1));
        assertThat(runtime.find("run-success")).get()
            .satisfies(snapshot -> {
                assertThat(snapshot.status()).isEqualTo(WorkflowExecutionStatus.COMPLETED);
                assertThat(snapshot.workflowType()).isEqualTo("echo-v1");
                assertThat(snapshot.tenantId()).isEqualTo("tenant-a");
                assertThat(snapshot.idempotencyKey()).isEqualTo("request-a");
            });
        TemporalWorkflowResult childResult = environment.getWorkflowClient()
            .newUntypedWorkflowStub("run-success" + RuntimeOsTemporalWorkflowImpl.EXECUTION_CHILD_SUFFIX)
            .getResult(TemporalWorkflowResult.class);
        assertThat(childResult.outputJson()).contains("HELLO");
    }

    @Test
    void duplicateWorkflowIdAttachesWithoutExecutingDefinitionTwice() throws Exception {
        AtomicInteger executions = new AtomicInteger();
        runtime.register("count-v1", EchoInput.class, EchoOutput.class, (input, context) -> {
            executions.incrementAndGet();
            return new EchoOutput(input.value(), context.attempt());
        });

        WorkflowHandle<EchoOutput> first = runtime.start(request("run-duplicate", "count-v1", "one"));
        assertThat(first.completion().get(10, TimeUnit.SECONDS)).isEqualTo(new EchoOutput("one", 1));
        WorkflowHandle<EchoOutput> duplicate = runtime.start(request("run-duplicate", "count-v1", "one"));

        assertThat(duplicate.newlyStarted()).isFalse();
        assertThat(duplicate.completion().get(10, TimeUnit.SECONDS)).isEqualTo(new EchoOutput("one", 1));
        assertThat(executions).hasValue(1);
    }

    @Test
    void cancellationInterruptsActivityAndCompletesAsCancellation() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        runtime.register("wait-v1", EchoInput.class, EchoOutput.class, (input, context) -> {
            entered.countDown();
            while (true) {
                context.checkCancellation();
                Thread.sleep(20);
            }
        });
        WorkflowHandle<EchoOutput> handle = runtime.start(request("run-cancel", "wait-v1", "wait"));
        assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();

        assertThat(runtime.cancel("run-cancel", "test cancellation")).isTrue();
        assertThatThrownBy(() -> handle.completion().get(10, TimeUnit.SECONDS))
            .isInstanceOf(CancellationException.class);
        assertThat(runtime.find("run-cancel")).get()
            .extracting(snapshot -> snapshot.status())
            .isEqualTo(WorkflowExecutionStatus.CANCELLED);
    }

    @Test
    void duplicateWorkflowIdWithDifferentBusinessIdentityIsRejected() throws Exception {
        runtime.register("echo-v1", EchoInput.class, EchoOutput.class,
            (input, context) -> new EchoOutput(input.value(), context.attempt()));
        runtime.start(request("run-collision", "echo-v1", "one"))
            .completion().get(10, TimeUnit.SECONDS);

        WorkflowStartRequest<EchoInput> collision = new WorkflowStartRequest<>(
            "run-collision", "echo-v1", "tenant-b", "different-request", new EchoInput("two"));

        assertThatThrownBy(() -> runtime.start(collision).completion().join())
            .hasRootCauseInstanceOf(IllegalStateException.class)
            .hasRootCauseMessage(
                "Workflow id already belongs to a different type, tenant or idempotency key: run-collision");
    }

    @Test
    void failedCoarseGrainedActivityIsNotRetriedByDefault() {
        AtomicInteger executions = new AtomicInteger();
        runtime.register("unsafe-side-effect-v1", EchoInput.class, EchoOutput.class,
            (input, context) -> {
                executions.incrementAndGet();
                throw new IllegalStateException("external operation failed");
            });

        WorkflowHandle<EchoOutput> handle = runtime.start(
            request("run-no-automatic-retry", "unsafe-side-effect-v1", "once"));

        assertThatThrownBy(() -> handle.completion().get(10, TimeUnit.SECONDS))
            .rootCause()
            .hasMessageContaining("external operation failed");
        assertThat(executions).hasValue(1);
        assertThat(runtime.find("run-no-automatic-retry")).get()
            .extracting(snapshot -> snapshot.status())
            .isEqualTo(WorkflowExecutionStatus.FAILED);
    }

    @Test
    void executesToolThroughIndependentActivityWithStableIdempotencyEvidence() throws Exception {
        runtime.register("bootstrap-v1", EchoInput.class, EchoOutput.class,
            (input, context) -> new EchoOutput(input.value(), context.attempt()));
        runtime.start(request("run-tool-bootstrap", "bootstrap-v1", "ready"))
            .completion().get(10, TimeUnit.SECONDS);
        when(toolRuntimeService.execute(any(ToolRuntimeRequest.class))).thenAnswer(invocation -> {
            ToolRuntimeRequest request = invocation.getArgument(0);
            return new ToolRuntimeExecution(
                ToolOutput.success(Map.of(
                    "tool", request.getToolName(),
                    "idempotencyKey", request.getAttributes().get(
                        RuntimeOsToolActivityImpl.IDEMPOTENCY_KEY_ATTRIBUTE))),
                null, null, "success", Map.of());
        });
        RuntimeOsToolExecutionWorkflow workflow = environment.getWorkflowClient().newWorkflowStub(
            RuntimeOsToolExecutionWorkflow.class,
            WorkflowOptions.newBuilder()
                .setWorkflowId("run-tool-activity")
                .setTaskQueue(taskQueue)
                .build());
        ToolRuntimeRequest request = ToolRuntimeRequest.builder()
            .toolName("customer_trade_query")
            .runtimeMode("interpretation_plan")
            .requestId("request-tool")
            .tenantId("tenant-a")
            .allowedTools(java.util.List.of("customer_trade_query"))
            .toolInput(ToolInput.builder().parameters(Map.of("customerId", "070200046604")).build())
            .attributes(Map.of())
            .build();

        ToolRuntimeExecution execution = workflow.execute(new TemporalToolActivityCommand(
            request, "tenant-a:run-tool-activity:step-3", 1, 60,
            false, "tool is not admitted for retry"));

        assertThat(execution.output().getData().toString())
            .contains("customer_trade_query", "tenant-a:run-tool-activity:step-3");
        assertThat(execution.audit())
            .containsEntry("workflowActivityIdempotencyKey",
                "tenant-a:run-tool-activity:step-3")
            .containsEntry("workflowActivityMaximumAttempts", 1)
            .containsEntry("workflowActivityRetrySafe", false);
        assertThat(workflow.status())
            .isEqualTo(new TemporalToolExecutionSnapshot(
                "run-tool-activity", "customer_trade_query",
                "tenant-a:run-tool-activity:step-3", "COMPLETED", 1));
        ArgumentCaptor<ToolRuntimeRequest> captured = ArgumentCaptor.forClass(ToolRuntimeRequest.class);
        verify(toolRuntimeService).execute(captured.capture());
        assertThat(captured.getValue().getAttributes())
            .containsEntry(RuntimeOsToolActivityImpl.IDEMPOTENCY_KEY_ATTRIBUTE,
                "tenant-a:run-tool-activity:step-3")
            .containsEntry("workflowActivityAttempt", 1)
            .containsEntry("toolRetryAttempts", 0);
    }

    @Test
    void rejectsUntrustedToolRetryAdmissionBeforeInvokingTool() throws Exception {
        runtime.register("bootstrap-retry-guard-v1", EchoInput.class, EchoOutput.class,
            (input, context) -> new EchoOutput(input.value(), context.attempt()));
        runtime.start(request("run-tool-retry-guard-bootstrap", "bootstrap-retry-guard-v1", "ready"))
            .completion().get(10, TimeUnit.SECONDS);
        RuntimeOsToolExecutionWorkflow workflow = environment.getWorkflowClient().newWorkflowStub(
            RuntimeOsToolExecutionWorkflow.class,
            WorkflowOptions.newBuilder()
                .setWorkflowId("run-tool-retry-guard")
                .setTaskQueue(taskQueue)
                .build());
        ToolRuntimeRequest request = ToolRuntimeRequest.builder()
            .toolName("write_without_retry_contract")
            .runtimeMode("interpretation_plan")
            .requestId("request-retry-guard")
            .tenantId("tenant-a")
            .allowedTools(java.util.List.of("write_without_retry_contract"))
            .toolInput(ToolInput.builder().parameters(Map.of()).build())
            .attributes(Map.of())
            .build();

        assertThatThrownBy(() -> workflow.execute(new TemporalToolActivityCommand(
            request, "tenant-a:run-tool-retry-guard:step-1", 3, 60,
            true, "caller claimed the tool was retry safe")))
            .rootCause()
            .hasMessageContaining(
                "Tool Activity retry admission no longer matches runtime metadata for "
                    + "write_without_retry_contract")
            .hasMessageContaining("nonRetryable=true");
        verify(toolRuntimeService, never()).execute(any(ToolRuntimeRequest.class));
    }

    @Test
    void realPlanPortReattachesToSamePersistedToolWorkflow() throws Exception {
        runtime.register("bootstrap-plan-port-v1", EchoInput.class, EchoOutput.class,
            (input, context) -> new EchoOutput(input.value(), context.attempt()));
        runtime.start(request("run-plan-port-bootstrap", "bootstrap-plan-port-v1", "ready"))
            .completion().get(10, TimeUnit.SECONDS);
        String toolName = "customer_trade_query";
        when(toolRuntimeService.metadata(toolName)).thenReturn(ToolMetadata.builder()
            .id(toolName)
            .operationType("read")
            .metadata(Map.of("idempotent", true, "workflowActivityMaximumAttempts", 3))
            .build());
        when(toolRuntimeService.execute(any(ToolRuntimeRequest.class))).thenReturn(
            new ToolRuntimeExecution(ToolOutput.success(Map.of("records", java.util.List.of())),
                ToolMetadata.builder().id(toolName).build(), null, "success", Map.of()));
        ToolRuntimeRequest request = ToolRuntimeRequest.builder()
            .toolName(toolName)
            .runtimeMode("interpretation_plan")
            .requestId("request-plan-port")
            .tenantId("tenant-a")
            .allowedTools(java.util.List.of(toolName))
            .toolInput(ToolInput.builder()
                .parameters(Map.of("customerId", "070200046604"))
                .build())
            .attributes(Map.of())
            .build();
        PlanToolExecutionCommand command = new PlanToolExecutionCommand(
            PlanToolExecutionCommand.SCHEMA_VERSION,
            "run-plan-port", "run-plan-port::attempt:0", "0", 3, "PRIMARY",
            "fingerprint-a", "tenant-a:run-plan-port:step-3:primary:fingerprint-a", request);
        TemporalPlanToolExecutionPort port = new TemporalPlanToolExecutionPort(
            environment.getWorkflowClient(), properties, toolRuntimeService);

        ToolRuntimeExecution first = port.execute(command);
        ToolRuntimeExecution attached = port.execute(command);

        assertThat(first.output().isSuccess()).isTrue();
        assertThat(attached.output().isSuccess()).isTrue();
        assertThat(TemporalPlanToolExecutionPort.workflowId(command.idempotencyKey()))
            .startsWith(TemporalPlanToolExecutionPort.WORKFLOW_ID_PREFIX);
        verify(toolRuntimeService, times(1)).execute(any(ToolRuntimeRequest.class));
    }

    @Test
    void agentExecutionDelegatesDagControlAndToolStepToDurableWorkflows() throws Exception {
        String toolName = "customer_asset_query";
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.hasTool(toolName)).thenReturn(true);
        ToolMetadata metadata = ToolMetadata.builder()
            .id(toolName).riskLevel("low").operationType("read")
            .metadata(Map.of("idempotent", true)).build();
        when(toolRegistry.getToolMetadata(toolName)).thenReturn(metadata);
        when(toolRuntimeService.metadata(toolName)).thenReturn(metadata);
        when(toolRuntimeService.execute(any(ToolRuntimeRequest.class))).thenReturn(
            new ToolRuntimeExecution(ToolOutput.success(Map.of("totalAssets", 847174.25)),
                metadata, null, "success", Map.of()));
        TemporalPlanToolExecutionPort port = new TemporalPlanToolExecutionPort(
            environment.getWorkflowClient(), properties, toolRuntimeService);
        TemporalPlanDagControlPort dagControlPort = new TemporalPlanDagControlPort(
            environment.getWorkflowClient(), properties);
        InterpretationPlan plan = new InterpretationPlan(
            "1.0",
            new InterpretationPlan.Intent("analysis", "asset analysis", "low"),
            new InterpretationPlan.Context(List.of(), List.of(), List.of(), List.of()),
            new InterpretationPlan.Plan(java.util.List.of(
                new InterpretationPlan.Step(1, "mcp_tool", toolName,
                    Map.of("customerId", "070200046604"), java.util.List.of(), null, null),
                new InterpretationPlan.Step(2, "final_answer", "",
                    Map.of("answer", "asset analysis complete"), java.util.List.of(1), null, null)
            )),
            new InterpretationPlan.ExecutionPolicy(
                2, false, java.util.List.of(toolName), java.util.List.of(), 60_000),
            new InterpretationPlan.Review(
                new InterpretationPlan.SelfCheck(1.0, 0.0, true, java.util.List.of()),
                java.util.List.of())
        );
        runtime.register("real-plan-v1", EchoInput.class, EchoOutput.class, (input, context) -> {
            InterpretationPlanRuntime planRuntime = new InterpretationPlanRuntime(
                toolRuntimeService, new InterpretationPlanValidator(), new InterpretationPlanOptimizer(),
                null, null, decision -> {
                    Integer stepId = decision.readyStepIds().iterator().next();
                    return stepId == 2
                        ? InterpretationPlanRuntime.DagDecision.finalAnswer(
                            stepId, "asset analysis complete", "evidence collected")
                        : InterpretationPlanRuntime.DagDecision.executeStep(stepId, "collect evidence");
                }, null, port, dagControlPort);
            InterpretationPlanRuntime.ExecutionResult result = planRuntime.execute(
                new InterpretationPlanRuntime.ExecutionRequest(
                    plan, toolRegistry, java.util.List.of(toolName), "tenant-a",
                    "request-real-plan", "conversation", "user", Map.of()));
            return new EchoOutput(result.status(), result.steps().size());
        });

        WorkflowHandle<EchoOutput> handle = runtime.start(
            request("run-real-plan", "real-plan-v1", "execute"));
        EchoOutput result = handle.completion().get(15, TimeUnit.SECONDS);

        assertThat(result.value()).isEqualTo("completed");
        assertThat(result.attempt()).isEqualTo(2);
        verify(toolRuntimeService, times(1)).execute(any(ToolRuntimeRequest.class));
        String dagSessionId = "tenant-a::request-real-plan::attempt:0::dag-control";
        PlanDagControlPort.Snapshot dagResult = environment.getWorkflowClient()
            .newUntypedWorkflowStub(TemporalPlanDagControlPort.workflowId(dagSessionId))
            .getResult(PlanDagControlPort.Snapshot.class);
        assertThat(dagResult.sessionId()).isEqualTo(dagSessionId);
        assertThat(dagResult.status()).isEqualTo("CLOSED");
        assertThat(dagResult.revision()).isGreaterThanOrEqualTo(5L);
        assertThat(dagResult.remainingStepIds()).isEmpty();
    }

    @Test
    void deterministicPlanWorkflowOwnsReadyWaveLoopAndStageActivities() throws Exception {
        runtime.register("bootstrap-plan-loop-v1", EchoInput.class, EchoOutput.class,
            (input, context) -> new EchoOutput(input.value(), context.attempt()));
        runtime.start(request("run-plan-loop-bootstrap", "bootstrap-plan-loop-v1", "ready"))
            .completion().get(10, TimeUnit.SECONDS);
        InterpretationPlan plan = new InterpretationPlan(
            "1.0",
            new InterpretationPlan.Intent("analysis", "ordered plan", "low"),
            new InterpretationPlan.Context(List.of(), List.of(), List.of(), List.of()),
            new InterpretationPlan.Plan(List.of(
                new InterpretationPlan.Step(1, "final_answer", "", Map.of("answer", "first"),
                    List.of(), null, null),
                new InterpretationPlan.Step(2, "final_answer", "", Map.of("answer", "done"),
                    List.of(1), null, null))),
            new InterpretationPlan.ExecutionPolicy(2, false, List.of(), List.of(), 60_000),
            new InterpretationPlan.Review(
                new InterpretationPlan.SelfCheck(1.0, 0.0, true, List.of()), List.of()));
        PlanExecutionContinuation initial = new PlanExecutionContinuation(
            null, "tenant-a::plan-loop", plan, List.of(1, 2), List.of(),
            List.of(), List.of(), 0, Map.of());
        when(phaseHandler.arbitrate(any(PlanModelArbitrationCommand.class)))
            .thenAnswer(invocation -> {
                PlanModelArbitrationCommand command = invocation.getArgument(0);
                return new PlanModelArbitrationResult("execute_wave",
                    command.readyStepIds(), Map.of(), null, "ready nodes admitted");
            });
        when(phaseHandler.prepare(any(PlanStepPreparationCommand.class)))
            .thenAnswer(invocation -> {
                PlanStepPreparationCommand command = invocation.getArgument(0);
                List<PreparedPlanStep> prepared = command.selectedStepIds().stream()
                    .map(stepId -> new PreparedPlanStep(
                        stepId, "final_answer", "", null, 1, 60, false,
                        "immediate_result",
                        new InterpretationPlanRuntime.StepExecution(
                            stepId, "final_answer", "", true, Map.of(), null,
                            null, stepId == 2 ? "done" : null, 0L, Map.of()),
                        Map.of()))
                    .toList();
                return new PlanStepPreparationResult(prepared);
            });
        when(phaseHandler.persist(any(PlanNodePersistenceCommand.class)))
            .thenAnswer(invocation -> {
                PlanNodePersistenceCommand command = invocation.getArgument(0);
                List<Integer> committed = command.waveResults().stream()
                    .filter(InterpretationPlanRuntime.StepExecution::success)
                    .map(InterpretationPlanRuntime.StepExecution::stepId).toList();
                List<Integer> remaining = command.continuation().remainingStepIds().stream()
                    .filter(stepId -> !committed.contains(stepId)).toList();
                List<InterpretationPlanRuntime.StepExecution> completed = new java.util.ArrayList<>(
                    command.continuation().completedSteps());
                completed.addAll(command.waveResults());
                PlanExecutionContinuation next = new PlanExecutionContinuation(
                    null, command.continuation().sessionId(), command.continuation().plan(),
                    remaining, completed, List.of(), List.of(),
                    command.continuation().decisionCount() + 1,
                    command.continuation().context());
                return new PlanNodePersistenceResult(next,
                    remaining.isEmpty() ? "COMPLETED" : "RUNNING");
            });
        RuntimeOsPlanExecutionWorkflow workflow = environment.getWorkflowClient().newWorkflowStub(
            RuntimeOsPlanExecutionWorkflow.class,
            WorkflowOptions.newBuilder().setWorkflowId("plan-loop-workflow")
                .setTaskQueue(taskQueue).build());

        TemporalPlanExecutionResult result = workflow.execute(
            new TemporalPlanExecutionCommand(null, initial, 60, 1, false));

        assertThat(result.status()).isEqualTo("COMPLETED");
        assertThat(result.finalAnswer()).isEqualTo("done");
        assertThat(result.continuation().remainingStepIds()).isEmpty();
        assertThat(result.executions()).extracting(InterpretationPlanRuntime.StepExecution::stepId)
            .containsExactly(1, 2);
        verify(phaseHandler, times(2)).arbitrate(any(PlanModelArbitrationCommand.class));
        verify(phaseHandler, times(2)).prepare(any(PlanStepPreparationCommand.class));
        verify(phaseHandler, times(2)).persist(any(PlanNodePersistenceCommand.class));
    }

    private WorkflowStartRequest<EchoInput> request(String id, String type, String value) {
        return new WorkflowStartRequest<>(id, type, "tenant-a", "request-a", new EchoInput(value));
    }

    record EchoInput(String value) {
    }

    record EchoOutput(String value, int attempt) {
    }
}
