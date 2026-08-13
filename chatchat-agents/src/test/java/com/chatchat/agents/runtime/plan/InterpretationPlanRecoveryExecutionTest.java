package com.chatchat.agents.runtime.plan;

import com.chatchat.agents.runtime.InMemoryAgentRunStore;
import com.chatchat.agents.runtime.ToolRuntimeExecution;
import com.chatchat.agents.runtime.ToolRuntimeRequest;
import com.chatchat.agents.runtime.ToolRuntimeService;
import com.chatchat.agents.tool.ToolRegistry;
import com.chatchat.common.tool.ToolMetadata;
import com.chatchat.common.tool.ToolOutput;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class InterpretationPlanRecoveryExecutionTest {

    @Test
    void resumesFromLatestCommittedBoundaryAfterDownstreamFailure() {
        SerialScenario scenario = serialScenario();
        InterpretationPlanRuntime.ExecutionResult failed = scenario.runtime().execute(
            scenario.request(Map.of()));

        assertThat(failed.success()).isFalse();
        assertThat(failed.metadata().get("resumeToken")).asString().startsWith("resume.v1.");
        assertThat(failed.metadata()).containsEntry("recoveryStatus", "AVAILABLE");

        InterpretationPlanRuntime.ExecutionResult resumed = scenario.runtime().execute(
            scenario.request(Map.of("resumeToken", failed.metadata().get("resumeToken"))));

        assertThat(resumed.success()).isTrue();
        assertThat(resumed.metadata()).containsEntry("recoveryStatus", "RESUMED");
        assertThat(resumed.metadata()).containsEntry("resumedPlanStepIds", List.of(1));
        assertThat(resumed.steps()).extracting(InterpretationPlanRuntime.StepExecution::stepId)
            .containsExactly(2, 3);
        assertThat(scenario.firstToolCalls()).hasValue(1);
        assertThat(scenario.secondToolCalls()).hasValue(2);
    }

    @Test
    void rejectsTamperedResumeTokenAndSafelyRecomputes() {
        SerialScenario scenario = serialScenario();
        assertThat(scenario.runtime().execute(scenario.request(Map.of())).success()).isFalse();

        InterpretationPlanRuntime.ExecutionResult recomputed = scenario.runtime().execute(
            scenario.request(Map.of("resumeToken", "resume.v1.tampered")));

        assertThat(recomputed.success()).isTrue();
        assertThat(recomputed.metadata()).containsEntry("recoveryStatus", "REJECTED");
        assertThat(recomputed.metadata()).containsEntry("recoveryRejectedReason", "RESUME_TOKEN_MISMATCH");
        assertThat(recomputed.steps()).extracting(InterpretationPlanRuntime.StepExecution::stepId)
            .containsExactly(1, 2, 3);
        assertThat(scenario.firstToolCalls()).hasValue(2);
    }

    @Test
    void missingCommittedAttemptPreventsCheckpointRecovery() {
        SerialScenario scenario = serialScenario();
        InterpretationPlanRuntime.ExecutionResult failed = scenario.runtime().execute(scenario.request(Map.of()));
        scenario.attempts().removeCommittedForNode(1);

        InterpretationPlanRuntime.ExecutionResult recomputed = scenario.runtime().execute(
            scenario.request(Map.of("resumeToken", failed.metadata().get("resumeToken"))));

        assertThat(recomputed.success()).isTrue();
        assertThat(recomputed.metadata()).containsEntry("recoveryStatus", "REJECTED");
        assertThat(recomputed.metadata()).containsEntry(
            "recoveryRejectedReason", "NO_CONSISTENT_COMMITTED_BOUNDARY");
        assertThat(recomputed.steps()).extracting(InterpretationPlanRuntime.StepExecution::stepId)
            .containsExactly(1, 2, 3);
        assertThat(scenario.firstToolCalls()).hasValue(2);
    }

    @Test
    void resumesWholeCommittedParallelEpochWithoutRepeatingTools() {
        ToolRegistry registry = registry(FIRST_TOOL, SECOND_TOOL);
        ToolRuntimeService tools = mock(ToolRuntimeService.class);
        AtomicInteger calls = new AtomicInteger();
        when(tools.execute(any())).thenAnswer(invocation -> {
            ToolRuntimeRequest request = invocation.getArgument(0);
            calls.incrementAndGet();
            return success(request.getToolName());
        });
        InMemoryAgentRunStore store = new InMemoryAgentRunStore();
        RecoverableAttemptStore attempts = new RecoverableAttemptStore();
        AtomicBoolean abort = new AtomicBoolean(true);
        InterpretationPlanRuntime runtime = new InterpretationPlanRuntime(
            tools, new InterpretationPlanValidator(), store,
            request -> abort.getAndSet(false)
                ? InterpretationPlanRuntime.DagDecision.abort("simulated crash after committed wave")
                : InterpretationPlanRuntime.DagDecision.finalAnswer(3, "done", "resume"));
        runtime.setNodeAttemptStore(attempts);
        InterpretationPlan plan = parallelPlan();

        InterpretationPlanRuntime.ExecutionResult failed = runtime.execute(request(plan, registry, Map.of()));
        assertThat(failed.success()).isFalse();
        assertThat(calls).hasValue(2);

        InterpretationPlanRuntime.ExecutionResult resumed = runtime.execute(request(
            plan, registry, Map.of("resumeToken", failed.metadata().get("resumeToken"))));

        assertThat(resumed.success()).isTrue();
        assertThat(resumed.metadata()).containsEntry("resumedPlanStepIds", List.of(1, 2));
        assertThat(resumed.steps()).extracting(InterpretationPlanRuntime.StepExecution::stepId)
            .containsExactly(3);
        assertThat(calls).hasValue(2);
    }

    private SerialScenario serialScenario() {
        ToolRegistry registry = registry(FIRST_TOOL, SECOND_TOOL);
        ToolRuntimeService tools = mock(ToolRuntimeService.class);
        AtomicInteger firstCalls = new AtomicInteger();
        AtomicInteger secondCalls = new AtomicInteger();
        when(tools.execute(any())).thenAnswer(invocation -> {
            ToolRuntimeRequest request = invocation.getArgument(0);
            if (FIRST_TOOL.equals(request.getToolName())) {
                firstCalls.incrementAndGet();
                return success(FIRST_TOOL);
            }
            int attempt = secondCalls.incrementAndGet();
            if (attempt == 1) {
                return new ToolRuntimeExecution(ToolOutput.failure("temporary downstream failure"),
                    metadata(SECOND_TOOL), null, "failed", Map.of());
            }
            return success(SECOND_TOOL);
        });
        InMemoryAgentRunStore store = new InMemoryAgentRunStore();
        RecoverableAttemptStore attempts = new RecoverableAttemptStore();
        InterpretationPlanRuntime runtime = new InterpretationPlanRuntime(
            tools, new InterpretationPlanValidator(), store,
            request -> InterpretationPlanRuntime.DagDecision.finalAnswer(3, "done", "complete"));
        runtime.setNodeAttemptStore(attempts);
        return new SerialScenario(runtime, registry, attempts, firstCalls, secondCalls);
    }

    private ToolRegistry registry(String... names) {
        ToolRegistry registry = mock(ToolRegistry.class);
        for (String name : names) {
            when(registry.hasTool(name)).thenReturn(true);
            when(registry.getToolMetadata(name)).thenReturn(metadata(name));
        }
        return registry;
    }

    private ToolRuntimeExecution success(String tool) {
        return new ToolRuntimeExecution(ToolOutput.success(Map.of("tool", tool, "status", "ok")),
            metadata(tool), null, "success", Map.of());
    }

    private ToolMetadata metadata(String tool) {
        return ToolMetadata.builder().id(tool).version("1.0.0").riskLevel("low").build();
    }

    private InterpretationPlanRuntime.ExecutionRequest request(InterpretationPlan plan,
                                                               ToolRegistry registry,
                                                               Map<String, Object> additions) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("__agentRunId", RUN_ID);
        attributes.put("workflowExecutionAttempt", 0);
        attributes.put("checkpointModelConfig", Map.of("model", "stable"));
        attributes.put("checkpointExecutionEnvironment", Map.of("env", "DEV"));
        attributes.putAll(additions);
        return new InterpretationPlanRuntime.ExecutionRequest(
            plan, registry, List.of(FIRST_TOOL, SECOND_TOOL), "tenant", "request",
            "conversation", "user", Map.copyOf(attributes));
    }

    private InterpretationPlan serialPlan() {
        return plan(false,
            new InterpretationPlan.Step(1, "mcp_tool", FIRST_TOOL, Map.of(), List.of(), null, null),
            new InterpretationPlan.Step(2, "mcp_tool", SECOND_TOOL, Map.of(), List.of(1), null, null),
            new InterpretationPlan.Step(3, "final_answer", "", Map.of("answer", "done"), List.of(2), null, null));
    }

    private InterpretationPlan parallelPlan() {
        return plan(true,
            new InterpretationPlan.Step(1, "mcp_tool", FIRST_TOOL, Map.of(), List.of(), null, null),
            new InterpretationPlan.Step(2, "mcp_tool", SECOND_TOOL, Map.of(), List.of(), null, null),
            new InterpretationPlan.Step(3, "final_answer", "", Map.of("answer", "done"), List.of(1, 2), null, null));
    }

    private InterpretationPlan plan(boolean parallel, InterpretationPlan.Step... steps) {
        return new InterpretationPlan(
            "1.0", new InterpretationPlan.Intent("generic", "recover", "low"),
            new InterpretationPlan.Context(List.of(), List.of(), List.of(), List.of()),
            new InterpretationPlan.Plan(List.of(steps)),
            new InterpretationPlan.ExecutionPolicy(4, parallel,
                List.of(FIRST_TOOL, SECOND_TOOL), List.of(), 30000),
            new InterpretationPlan.Review(
                new InterpretationPlan.SelfCheck(0.8, 0.1, true, List.of()), List.of()));
    }

    private record SerialScenario(InterpretationPlanRuntime runtime,
                                  ToolRegistry registry,
                                  RecoverableAttemptStore attempts,
                                  AtomicInteger firstToolCalls,
                                  AtomicInteger secondToolCalls) {
        InterpretationPlanRuntime.ExecutionRequest request(Map<String, Object> additions) {
            InterpretationPlanRecoveryExecutionTest owner = new InterpretationPlanRecoveryExecutionTest();
            return owner.request(owner.serialPlan(), registry, additions);
        }
    }

    private static final class RecoverableAttemptStore implements NodeAttemptStore {
        private final Map<String, AttemptSnapshot> snapshots = new LinkedHashMap<>();
        private final Map<Integer, Integer> attemptNumbers = new LinkedHashMap<>();
        private int sequence;

        @Override
        public synchronized AttemptSnapshot create(AttemptCommand command) {
            int number = attemptNumbers.merge(command.nodeId(), 1, Integer::sum);
            Instant now = Instant.now();
            AttemptSnapshot snapshot = new AttemptSnapshot("attempt-" + (++sequence), command.tenantId(),
                command.runId(), command.nodeId(), number, State.CREATED, 0, now, now);
            snapshots.put(snapshot.attemptId(), snapshot);
            return snapshot;
        }

        @Override
        public synchronized AttemptSnapshot transition(String tenantId, String attemptId, State expectedState,
                                                       State targetState, String reason, Map<String, Object> metadata) {
            AttemptSnapshot current = snapshots.get(attemptId);
            if (current == null || current.state() != expectedState || !expectedState.mayTransitionTo(targetState)) {
                throw new IllegalStateException("invalid attempt transition");
            }
            AttemptSnapshot updated = new AttemptSnapshot(current.attemptId(), current.tenantId(), current.runId(),
                current.nodeId(), current.attemptNumber(), targetState, current.revision() + 1,
                current.createdAt(), Instant.now());
            snapshots.put(attemptId, updated);
            return updated;
        }

        @Override
        public synchronized BarrierResult commitBarrier(BarrierCommand command) {
            List<AttemptSnapshot> committed = new ArrayList<>();
            for (String attemptId : command.requiredAttemptIds()) {
                AttemptSnapshot current = snapshots.get(attemptId);
                if (current == null || current.state() != State.PREPARED) {
                    throw new IllegalStateException("attempt is not prepared");
                }
                AttemptSnapshot updated = new AttemptSnapshot(current.attemptId(), current.tenantId(), current.runId(),
                    current.nodeId(), current.attemptNumber(), State.COMMITTED, current.revision() + 1,
                    current.createdAt(), Instant.now());
                snapshots.put(attemptId, updated);
                committed.add(updated);
            }
            return new BarrierResult(command.executionEpoch(), true, List.copyOf(committed));
        }

        @Override
        public boolean supportsRecoveryQueries() {
            return true;
        }

        @Override
        public synchronized List<AttemptSnapshot> committedAttempts(String tenantId, String runId) {
            return snapshots.values().stream()
                .filter(value -> tenantId.equals(value.tenantId()) && runId.equals(value.runId()))
                .filter(value -> value.state() == State.COMMITTED)
                .toList();
        }

        synchronized void removeCommittedForNode(int nodeId) {
            Set<String> ids = new LinkedHashSet<>(snapshots.values().stream()
                .filter(value -> value.nodeId() == nodeId && value.state() == State.COMMITTED)
                .map(AttemptSnapshot::attemptId)
                .toList());
            ids.forEach(snapshots::remove);
        }
    }

    private static final String RUN_ID = "recoverable-execution-run";
    private static final String FIRST_TOOL = "first_query";
    private static final String SECOND_TOOL = "second_query";
}
