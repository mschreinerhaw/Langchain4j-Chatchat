package com.chatchat.runtime.temporal.core;

import com.chatchat.agents.runtime.AgentRunRequest;
import com.chatchat.agents.runtime.AgentRunResult;
import com.chatchat.agents.runtime.plan.InterpretationPlan;
import com.chatchat.agents.runtime.plan.InterpretationPlanRuntime;
import com.chatchat.agents.runtime.plan.execution.*;
import com.chatchat.agents.runtime.run.AgentRunStatus;
import com.chatchat.agents.runtime.tool.ToolRuntimeService;
import com.chatchat.agents.runtime.workflow.WorkflowStartRequest;
import com.chatchat.agents.runtime.workflow.WorkflowHandle;
import com.chatchat.runtime.temporal.config.TemporalWorkflowProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.temporal.testing.TestWorkflowEnvironment;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AgentSuspendResumeWorkflowTest {

    @Test
    void agentRunExecutesPlanChildAndResumesWithoutCallingCoarseDefinition() throws Exception {
        try (TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance()) {
            TemporalWorkflowProperties properties = new TemporalWorkflowProperties();
            properties.setTaskQueue("agent-resume-" + System.nanoTime());
            properties.setActivityHeartbeatSeconds(1);
            PlanExecutionPhaseHandler phases = mock(PlanExecutionPhaseHandler.class,
                withSettings().extraInterfaces(ResumableAgentRunExecutor.class));
            ResumableAgentRunExecutor executor = (ResumableAgentRunExecutor) phases;
            TemporalWorkflowRuntime runtime = new TemporalWorkflowRuntime(
                environment.getWorkflowClient(), environment.getWorkerFactory(),
                new ObjectMapper(), properties, mock(ToolRuntimeService.class), phases);
            try {
                runtime.register("agent-run-v1", AgentRunRequest.class, AgentRunResult.class,
                    (input, context) -> { throw new AssertionError("coarse definition invoked"); });
                AgentRunRequest request = AgentRunRequest.builder().runId("run-agent-resume")
                    .tenantId("tenant-a").requestId("request-a").query("analyze").build();
                InterpretationPlan plan = new InterpretationPlan("1.0", null, null,
                    new InterpretationPlan.Plan(List.of(new InterpretationPlan.Step(
                        1, "final_answer", "", Map.of("answer", "done"),
                        List.of(), null, null))), null, null);
                PlanExecutionContinuation planState = new PlanExecutionContinuation(
                    null, "tenant-a::run-agent-resume::plan-attempt:1", plan,
                    List.of(1), List.of(), List.of(), List.of(), 0, Map.of());
                AgentPlanPipelineContinuation suspended = new AgentPlanPipelineContinuation(
                    null, planState.sessionId(), request, plan, plan, planState,
                    1, 0, 0, List.of(), List.of(), List.of(), List.of(), Map.of(), Map.of());
                when(executor.executeUntilPlanSuspension(any(), any()))
                    .thenReturn(AgentRunExecutionSlice.suspended(suspended));
                AgentRunResult completed = AgentRunResult.builder().runId("run-agent-resume")
                    .status(AgentRunStatus.COMPLETED).answer("done").build();
                when(executor.resumeAfterPlanExecution(any(), any(), any()))
                    .thenReturn(AgentRunExecutionSlice.completed(completed));
                when(phases.arbitrate(any())).thenReturn(new PlanModelArbitrationResult(
                    "execute_step", List.of(1), Map.of(), null, "ready"));
                InterpretationPlanRuntime.StepExecution step =
                    new InterpretationPlanRuntime.StepExecution(
                        1, "final_answer", "", true, Map.of(), null,
                        null, "done", 0L, Map.of());
                when(phases.prepare(any())).thenReturn(new PlanStepPreparationResult(List.of(
                    new PreparedPlanStep(1, "final_answer", "", null,
                        1, 1, false, "immediate", step, Map.of()))));
                when(phases.persist(any())).thenAnswer(invocation -> {
                    PlanNodePersistenceCommand command = invocation.getArgument(0);
                    List<InterpretationPlanRuntime.StepExecution> results = new ArrayList<>(
                        command.continuation().completedSteps());
                    results.addAll(command.waveResults());
                    return new PlanNodePersistenceResult(new PlanExecutionContinuation(
                        null, command.continuation().sessionId(), plan, List.of(), results,
                        List.of(), List.of(), 1, Map.of()), "COMPLETED");
                });

                WorkflowHandle<AgentRunResult> handle = runtime.start(new WorkflowStartRequest<>(
                    "run-agent-resume", "agent-run-v1", "tenant-a", "request-a", request));
                AgentRunResult result = handle.completion().get(15, TimeUnit.SECONDS);

                assertThat(result.answer()).isEqualTo("done");
                verify(executor).executeUntilPlanSuspension(any(), any());
                verify(executor).resumeAfterPlanExecution(any(), any(), any());
                verify(phases).prepare(any());
            } finally {
                runtime.close();
            }
        }
    }
}
