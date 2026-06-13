package com.chatchat.agents.runtime;

import com.chatchat.agents.orchestration.AgentOrchestrator;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DefaultAgentRuntimeTest {

    @Test
    void submitQueuesRunAndCompletesThroughExecutor() {
        AgentRunRequest request = AgentRunRequest.builder()
            .runId("runtime-submit-1")
            .query("hello async")
            .requestId("req-runtime-submit-1")
            .build();
        InMemoryAgentRunStore runStore = new InMemoryAgentRunStore();
        AgentOrchestrator orchestrator = mock(AgentOrchestrator.class);
        when(orchestrator.execute(request)).thenAnswer(invocation -> {
            runStore.start(request);
            AgentRunResult result = AgentRunResult.builder()
                .runId("runtime-submit-1")
                .status(AgentRunStatus.COMPLETED)
                .answer("async done")
                .stopReason("final_answer")
                .build();
            AgentRun completed = runStore.complete("runtime-submit-1", result);
            return result.withStatusAndEvents(completed.status(), completed.events());
        });
        DefaultAgentRuntime runtime = new DefaultAgentRuntime(orchestrator, runStore, Runnable::run);

        AgentRunHandle handle = runtime.submit(request);
        AgentRunResult result = handle.completion().join();

        assertThat(handle.runId()).isEqualTo("runtime-submit-1");
        assertThat(result.answer()).isEqualTo("async done");
        assertThat(runtime.find("runtime-submit-1").orElseThrow().status()).isEqualTo(AgentRunStatus.COMPLETED);
        assertThat(runtime.events("runtime-submit-1"))
            .extracting(event -> event.type().name())
            .containsSubsequence("RUN_SUBMITTED", "RUN_STARTED", "RUN_COMPLETED");
    }

    @Test
    void cancelQueuedSubmittedRunBeforeExecution() {
        AgentRunRequest request = AgentRunRequest.builder()
            .runId("runtime-cancel-queued-1")
            .query("cancel queued")
            .requestId("req-runtime-cancel-queued-1")
            .build();
        InMemoryAgentRunStore runStore = new InMemoryAgentRunStore();
        AgentOrchestrator orchestrator = mock(AgentOrchestrator.class);
        QueuedExecutor executor = new QueuedExecutor();
        DefaultAgentRuntime runtime = new DefaultAgentRuntime(orchestrator, runStore, executor);

        AgentRunHandle handle = runtime.submit(request);
        AgentRun cancelled = runtime.cancel(handle.runId());
        executor.runNext();
        AgentRunResult result = handle.completion().join();

        assertThat(cancelled.status()).isEqualTo(AgentRunStatus.CANCELLED);
        assertThat(result.status()).isEqualTo(AgentRunStatus.CANCELLED);
        assertThat(runtime.find("runtime-cancel-queued-1").orElseThrow().status()).isEqualTo(AgentRunStatus.CANCELLED);
        assertThat(runtime.events("runtime-cancel-queued-1"))
            .extracting(event -> event.type().name())
            .containsSubsequence("RUN_SUBMITTED", "RUN_CANCELLED");
        verifyNoInteractions(orchestrator);
    }

    @Test
    void submitReturnsFailedHandleWhenExecutorRejectsRun() {
        AgentRunRequest request = AgentRunRequest.builder()
            .runId("runtime-rejected-1")
            .query("reject")
            .requestId("req-runtime-rejected-1")
            .build();
        InMemoryAgentRunStore runStore = new InMemoryAgentRunStore();
        AgentOrchestrator orchestrator = mock(AgentOrchestrator.class);
        Executor rejectingExecutor = command -> {
            throw new RejectedExecutionException("queue full");
        };
        DefaultAgentRuntime runtime = new DefaultAgentRuntime(orchestrator, runStore, rejectingExecutor);

        AgentRunHandle handle = runtime.submit(request);
        AgentRunResult result = handle.completion().join();

        assertThat(handle.runId()).isEqualTo("runtime-rejected-1");
        assertThat(result.status()).isEqualTo(AgentRunStatus.FAILED);
        assertThat(result.errorMessage()).isEqualTo("Agent runtime executor rejected run");
        assertThat(runtime.find("runtime-rejected-1").orElseThrow().status()).isEqualTo(AgentRunStatus.FAILED);
        assertThat(runtime.events("runtime-rejected-1"))
            .extracting(event -> event.type().name())
            .containsSubsequence("RUN_SUBMITTED", "RUN_FAILED");
        verifyNoInteractions(orchestrator);
    }

    @Test
    void delegatesRunAndExposesStoredRunEvents() {
        AgentRunRequest request = AgentRunRequest.builder()
            .runId("runtime-facade-1")
            .query("hello")
            .requestId("req-runtime-facade-1")
            .build();
        InMemoryAgentRunStore runStore = new InMemoryAgentRunStore();
        runStore.start(request);
        runStore.complete("runtime-facade-1", AgentRunResult.builder()
            .runId("runtime-facade-1")
            .status(AgentRunStatus.COMPLETED)
            .answer("done")
            .stopReason("final_answer")
            .build());
        AgentOrchestrator orchestrator = mock(AgentOrchestrator.class);
        when(orchestrator.execute(request)).thenReturn(AgentRunResult.builder()
            .runId("runtime-facade-1")
            .status(AgentRunStatus.COMPLETED)
            .answer("done")
            .events(runStore.events("runtime-facade-1"))
            .build());

        DefaultAgentRuntime runtime = new DefaultAgentRuntime(orchestrator, runStore);

        AgentRunResult result = runtime.run(request);

        assertThat(result.answer()).isEqualTo("done");
        assertThat(runtime.find("runtime-facade-1")).isPresent();
        assertThat(runtime.events("runtime-facade-1"))
            .extracting(event -> event.type().name())
            .contains("RUN_STARTED", "RUN_COMPLETED");
    }

    @Test
    void returnsEmptyStateForUnknownRun() {
        DefaultAgentRuntime runtime = new DefaultAgentRuntime(
            mock(AgentOrchestrator.class),
            new InMemoryAgentRunStore()
        );

        assertThat(runtime.find("missing-run")).isEmpty();
        assertThat(runtime.events("missing-run")).isEqualTo(List.of());
    }

    @Test
    void snapshotIncludesQueuedRunsAndActiveCancellationSignals() {
        AgentRunRequest request = AgentRunRequest.builder()
            .runId("runtime-snapshot-1")
            .query("snapshot")
            .requestId("req-runtime-snapshot-1")
            .build();
        InMemoryAgentRunStore runStore = new InMemoryAgentRunStore();
        AgentOrchestrator orchestrator = mock(AgentOrchestrator.class);
        QueuedExecutor executor = new QueuedExecutor();
        DefaultAgentRuntime runtime = new DefaultAgentRuntime(orchestrator, runStore, executor);
        when(orchestrator.execute(request)).thenAnswer(invocation -> {
            runStore.start(request);
            AgentRunResult result = AgentRunResult.builder()
                .runId("runtime-snapshot-1")
                .status(AgentRunStatus.COMPLETED)
                .answer("done")
                .build();
            AgentRun completed = runStore.complete("runtime-snapshot-1", result);
            return result.withStatusAndEvents(completed.status(), completed.events());
        });

        AgentRunHandle handle = runtime.submit(request);

        AgentRuntimeSnapshot queued = runtime.snapshot();
        assertThat(queued.totalRuns()).isEqualTo(1);
        assertThat(queued.pendingRuns()).isEqualTo(1);
        assertThat(queued.activeRuns()).isEqualTo(1);
        assertThat(queued.activeCancellationSignals()).isEqualTo(1);

        executor.runNext();
        handle.completion().join();

        AgentRuntimeSnapshot completed = runtime.snapshot();
        assertThat(completed.completedRuns()).isEqualTo(1);
        assertThat(completed.terminalRuns()).isEqualTo(1);
        assertThat(completed.activeCancellationSignals()).isZero();
    }

    @Test
    void listsRunsThroughRuntimeFacade() {
        AgentRunRequest request = AgentRunRequest.builder()
            .runId("runtime-list-1")
            .query("list")
            .tenantId("tenant-list")
            .userId("user-list")
            .requestId("req-runtime-list-1")
            .build();
        InMemoryAgentRunStore runStore = new InMemoryAgentRunStore();
        runStore.start(request);
        runStore.complete("runtime-list-1", AgentRunResult.builder()
            .runId("runtime-list-1")
            .status(AgentRunStatus.COMPLETED)
            .answer("done")
            .build());
        DefaultAgentRuntime runtime = new DefaultAgentRuntime(
            mock(AgentOrchestrator.class),
            runStore
        );

        List<AgentRun> runs = runtime.list(new AgentRunQuery(
            AgentRunStatus.COMPLETED,
            "tenant-list",
            "user-list",
            null,
            10,
            0
        ));

        assertThat(runs)
            .extracting(AgentRun::runId)
            .containsExactly("runtime-list-1");
    }

    @Test
    void readsIncrementalEventsThroughRuntimeFacade() throws InterruptedException {
        AgentRunRequest request = AgentRunRequest.builder()
            .runId("runtime-events-1")
            .requestId("req-runtime-events-1")
            .build();
        InMemoryAgentRunStore runStore = new InMemoryAgentRunStore();
        runStore.start(request);
        long afterStart = runStore.events("runtime-events-1").get(0).createdAt();
        Thread.sleep(2);
        runStore.complete("runtime-events-1", AgentRunResult.builder()
            .runId("runtime-events-1")
            .status(AgentRunStatus.COMPLETED)
            .answer("done")
            .build());
        DefaultAgentRuntime runtime = new DefaultAgentRuntime(
            mock(AgentOrchestrator.class),
            runStore
        );

        assertThat(runtime.events("runtime-events-1", afterStart, 10))
            .extracting(event -> event.type().name())
            .containsExactly("RUN_COMPLETED");
    }

    @Test
    void readsStepsAndObservationsThroughRuntimeFacade() {
        AgentRunRequest request = AgentRunRequest.builder()
            .runId("runtime-records-1")
            .requestId("req-runtime-records-1")
            .build();
        InMemoryAgentRunStore runStore = new InMemoryAgentRunStore();
        runStore.start(request);
        runStore.complete("runtime-records-1", AgentRunResult.builder()
            .runId("runtime-records-1")
            .status(AgentRunStatus.COMPLETED)
            .answer("done")
            .steps(List.of(
                AgentRunStep.builder().step(1).action("search").build(),
                AgentRunStep.builder().step(2).action("final").build()
            ))
            .observations(List.of(
                AgentObservation.text("text", "tool-a", "first observation"),
                AgentObservation.text("text", "tool-b", "second observation")
            ))
            .build());
        DefaultAgentRuntime runtime = new DefaultAgentRuntime(
            mock(AgentOrchestrator.class),
            runStore
        );

        assertThat(runtime.steps("runtime-records-1", 1, 10))
            .extracting(AgentRunStep::action)
            .containsExactly("final");
        assertThat(runtime.observations("runtime-records-1", 1, 10))
            .extracting(AgentObservation::source)
            .containsExactly("tool-b");
    }

    private static final class QueuedExecutor implements Executor {
        private final Queue<Runnable> tasks = new ArrayDeque<>();

        @Override
        public void execute(Runnable command) {
            tasks.add(command);
        }

        private void runNext() {
            assertThat(tasks).isNotEmpty();
            tasks.remove().run();
        }
    }
}
