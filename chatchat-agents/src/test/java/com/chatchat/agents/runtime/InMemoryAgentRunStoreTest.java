package com.chatchat.agents.runtime;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryAgentRunStoreTest {

    @Test
    void publishesRunLifecycleEvents() {
        RecordingPublisher publisher = new RecordingPublisher();
        InMemoryAgentRunStore store = new InMemoryAgentRunStore(publisher);
        AgentRunRequest request = AgentRunRequest.builder()
            .runId("publish-run-1")
            .requestId("req-publish-run-1")
            .query("publish")
            .build();

        store.submit(request);
        store.start(request);
        store.complete("publish-run-1", AgentRunResult.builder()
            .runId("publish-run-1")
            .status(AgentRunStatus.COMPLETED)
            .answer("done")
            .stopReason("final_answer")
            .steps(List.of(AgentRunStep.builder()
                .step(1)
                .action("final")
                .build()))
            .observations(List.of(AgentObservation.text("text", "test", "observation")))
            .build());

        assertThat(publisher.events())
            .extracting(event -> event.type().name())
            .containsExactly(
                "RUN_SUBMITTED",
                "RUN_STARTED",
                "STEP_RECORDED",
                "OBSERVATION_RECORDED",
                "RUN_COMPLETED"
            );
    }

    @Test
    void failedCompletionPublishesRunFailedLifecycleEvent() {
        RecordingPublisher publisher = new RecordingPublisher();
        InMemoryAgentRunStore store = new InMemoryAgentRunStore(publisher);
        AgentRunRequest request = AgentRunRequest.builder()
            .runId("publish-failed-completion-1")
            .requestId("req-publish-failed-completion-1")
            .query("publish failed")
            .build();

        store.start(request);
        AgentRun run = store.complete("publish-failed-completion-1", AgentRunResult.builder()
            .runId("publish-failed-completion-1")
            .status(AgentRunStatus.FAILED)
            .answer("PLAN_INVALID_REQUIRED_TOOL_NOT_EXECUTED: missing sql_query_execute")
            .stopReason("mandatory_workflow_incomplete")
            .errorMessage("PLAN_INVALID_REQUIRED_TOOL_NOT_EXECUTED")
            .metadata(Map.of("errorCode", "PLAN_INVALID_REQUIRED_TOOL_NOT_EXECUTED"))
            .build());

        assertThat(run.status()).isEqualTo(AgentRunStatus.FAILED);
        assertThat(publisher.events())
            .extracting(event -> event.type().name())
            .containsExactly("RUN_STARTED", "RUN_FAILED");
        assertThat(publisher.events().get(1).payload())
            .containsEntry("errorCode", "PLAN_INVALID_REQUIRED_TOOL_NOT_EXECUTED")
            .containsEntry("answer", "PLAN_INVALID_REQUIRED_TOOL_NOT_EXECUTED: missing sql_query_execute")
            .containsEntry("status", "FAILED");
    }

    @Test
    void publisherFailureDoesNotBlockStoreStateTransition() {
        InMemoryAgentRunStore store = new InMemoryAgentRunStore(event -> {
            throw new IllegalStateException("publisher down");
        });
        AgentRunRequest request = AgentRunRequest.builder()
            .runId("publish-failure-1")
            .requestId("req-publish-failure-1")
            .query("publish failure")
            .build();

        AgentRun run = store.submit(request);

        assertThat(run.status()).isEqualTo(AgentRunStatus.PENDING);
        assertThat(store.find("publish-failure-1")).isPresent();
        assertThat(store.events("publish-failure-1"))
            .extracting(event -> event.type().name())
            .containsExactly("RUN_SUBMITTED");
    }

    @Test
    void snapshotAggregatesRunStatuses() {
        InMemoryAgentRunStore store = new InMemoryAgentRunStore();
        AgentRunRequest pending = AgentRunRequest.builder()
            .runId("snapshot-pending")
            .requestId("req-snapshot-pending")
            .build();
        AgentRunRequest running = AgentRunRequest.builder()
            .runId("snapshot-running")
            .requestId("req-snapshot-running")
            .build();
        AgentRunRequest completed = AgentRunRequest.builder()
            .runId("snapshot-completed")
            .requestId("req-snapshot-completed")
            .build();
        AgentRunRequest failed = AgentRunRequest.builder()
            .runId("snapshot-failed")
            .requestId("req-snapshot-failed")
            .build();
        AgentRunRequest cancelled = AgentRunRequest.builder()
            .runId("snapshot-cancelled")
            .requestId("req-snapshot-cancelled")
            .build();

        store.submit(pending);
        store.start(running);
        store.start(completed);
        store.complete("snapshot-completed", AgentRunResult.builder()
            .runId("snapshot-completed")
            .status(AgentRunStatus.COMPLETED)
            .answer("done")
            .build());
        store.start(failed);
        store.fail("snapshot-failed", new IllegalStateException("boom"));
        store.start(cancelled);
        store.cancel("snapshot-cancelled", "stop");

        AgentRuntimeSnapshot snapshot = store.snapshot();

        assertThat(snapshot.totalRuns()).isEqualTo(5);
        assertThat(snapshot.pendingRuns()).isEqualTo(1);
        assertThat(snapshot.runningRuns()).isEqualTo(1);
        assertThat(snapshot.completedRuns()).isEqualTo(1);
        assertThat(snapshot.failedRuns()).isEqualTo(1);
        assertThat(snapshot.cancelledRuns()).isEqualTo(1);
        assertThat(snapshot.activeRuns()).isEqualTo(2);
        assertThat(snapshot.terminalRuns()).isEqualTo(3);
        assertThat(snapshot.lastUpdatedAt()).isPositive();
    }

    @Test
    void listFiltersRunsByStatusAndRequestScope() {
        InMemoryAgentRunStore store = new InMemoryAgentRunStore();
        AgentRunRequest tenantACompleted = AgentRunRequest.builder()
            .runId("list-tenant-a-completed")
            .requestId("req-list-tenant-a-completed")
            .tenantId("tenant-a")
            .userId("user-a")
            .conversationId("conv-a")
            .build();
        AgentRunRequest tenantARunning = AgentRunRequest.builder()
            .runId("list-tenant-a-running")
            .requestId("req-list-tenant-a-running")
            .tenantId("tenant-a")
            .userId("user-a")
            .conversationId("conv-a")
            .build();
        AgentRunRequest tenantBCompleted = AgentRunRequest.builder()
            .runId("list-tenant-b-completed")
            .requestId("req-list-tenant-b-completed")
            .tenantId("tenant-b")
            .userId("user-b")
            .conversationId("conv-b")
            .build();

        store.start(tenantACompleted);
        store.complete("list-tenant-a-completed", AgentRunResult.builder()
            .runId("list-tenant-a-completed")
            .status(AgentRunStatus.COMPLETED)
            .answer("done")
            .build());
        store.start(tenantARunning);
        store.start(tenantBCompleted);
        store.complete("list-tenant-b-completed", AgentRunResult.builder()
            .runId("list-tenant-b-completed")
            .status(AgentRunStatus.COMPLETED)
            .answer("done")
            .build());

        List<AgentRun> tenantACompletedRuns = store.list(new AgentRunQuery(
            AgentRunStatus.COMPLETED,
            "tenant-a",
            "user-a",
            "conv-a",
            10,
            0
        ));

        assertThat(tenantACompletedRuns)
            .extracting(AgentRun::runId)
            .containsExactly("list-tenant-a-completed");
        assertThat(store.list(AgentRunQuery.byStatus(AgentRunStatus.COMPLETED, 1))).hasSize(1);
        assertThat(store.list(new AgentRunQuery(null, "tenant-a", null, null, 10, 0)))
            .extracting(AgentRun::runId)
            .containsExactlyInAnyOrder("list-tenant-a-completed", "list-tenant-a-running");
    }

    @Test
    void eventsCanBeReadIncrementally() throws InterruptedException {
        InMemoryAgentRunStore store = new InMemoryAgentRunStore();
        AgentRunRequest request = AgentRunRequest.builder()
            .runId("incremental-events-1")
            .requestId("req-incremental-events-1")
            .build();

        store.start(request);
        long afterStart = store.events("incremental-events-1").get(0).createdAt();
        Thread.sleep(2);
        store.complete("incremental-events-1", AgentRunResult.builder()
            .runId("incremental-events-1")
            .status(AgentRunStatus.COMPLETED)
            .answer("done")
            .steps(List.of(AgentRunStep.builder()
                .step(1)
                .action("final")
                .build()))
            .build());

        assertThat(store.events("incremental-events-1", afterStart, 10))
            .extracting(event -> event.type().name())
            .containsExactly("STEP_RECORDED", "RUN_COMPLETED");
        assertThat(store.events("incremental-events-1", 0, 1)).hasSize(1);
    }

    @Test
    void stepsAndObservationsCanBeReadIncrementally() {
        InMemoryAgentRunStore store = new InMemoryAgentRunStore();
        AgentRunRequest request = AgentRunRequest.builder()
            .runId("incremental-records-1")
            .requestId("req-incremental-records-1")
            .build();

        store.start(request);
        store.complete("incremental-records-1", AgentRunResult.builder()
            .runId("incremental-records-1")
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

        assertThat(store.steps("incremental-records-1", 1, 10))
            .extracting(AgentRunStep::action)
            .containsExactly("final");
        assertThat(store.steps("incremental-records-1", 0, 1))
            .extracting(AgentRunStep::action)
            .containsExactly("search");
        assertThat(store.observations("incremental-records-1", 1, 10))
            .extracting(AgentObservation::content)
            .containsExactly("second observation");
    }

    @Test
    void liveRecordedStepsAndObservationsAreMergedOnCompletion() {
        InMemoryAgentRunStore store = new InMemoryAgentRunStore();
        AgentRunRequest request = AgentRunRequest.builder()
            .runId("live-records-1")
            .requestId("req-live-records-1")
            .build();
        AgentRunStep step = AgentRunStep.builder()
            .step(1)
            .action("tool")
            .toolName("document_search")
            .build();
        AgentObservation observation = AgentObservation.text("tool", "document_search", "live observation");

        store.start(request);
        store.recordStep("live-records-1", step);
        store.recordObservation("live-records-1", observation);
        store.complete("live-records-1", AgentRunResult.builder()
            .runId("live-records-1")
            .status(AgentRunStatus.COMPLETED)
            .answer("done")
            .steps(List.of(step))
            .observations(List.of(observation))
            .build());

        AgentRun completed = store.find("live-records-1").orElseThrow();
        assertThat(completed.steps()).containsExactly(step);
        assertThat(completed.observations()).containsExactly(observation);
        assertThat(completed.events())
            .extracting(event -> event.type().name())
            .containsExactly("RUN_STARTED", "STEP_RECORDED", "OBSERVATION_RECORDED", "RUN_COMPLETED");
    }

    @Test
    void prunesOldTerminalRunsWhenStoreLimitIsExceeded() {
        AgentRuntimeProperties properties = new AgentRuntimeProperties();
        properties.setMaxStoredRuns(2);
        InMemoryAgentRunStore store = new InMemoryAgentRunStore(new NoopAgentRunEventPublisher(), properties);

        completeRun(store, "retention-1");
        completeRun(store, "retention-2");
        completeRun(store, "retention-3");

        assertThat(store.find("retention-1")).isEmpty();
        assertThat(store.list(AgentRunQuery.recent(10)))
            .extracting(AgentRun::runId)
            .containsExactlyInAnyOrder("retention-2", "retention-3");
        assertThat(store.snapshot().totalRuns()).isEqualTo(2);
    }

    @Test
    void retentionKeepsActiveRunsEvenWhenStoreLimitIsExceeded() {
        AgentRuntimeProperties properties = new AgentRuntimeProperties();
        properties.setMaxStoredRuns(1);
        InMemoryAgentRunStore store = new InMemoryAgentRunStore(new NoopAgentRunEventPublisher(), properties);
        completeRun(store, "active-retention-terminal");
        AgentRunRequest active = AgentRunRequest.builder()
            .runId("active-retention-running")
            .requestId("req-active-retention-running")
            .build();

        store.start(active);

        assertThat(store.find("active-retention-running")).isPresent();
        assertThat(store.snapshot().runningRuns()).isEqualTo(1);
    }

    private void completeRun(InMemoryAgentRunStore store, String runId) {
        AgentRunRequest request = AgentRunRequest.builder()
            .runId(runId)
            .requestId("req-" + runId)
            .build();
        store.start(request);
        store.complete(runId, AgentRunResult.builder()
            .runId(runId)
            .status(AgentRunStatus.COMPLETED)
            .answer("done")
            .build());
    }

    private static final class RecordingPublisher implements AgentRunEventPublisher {
        private final List<AgentRunEvent> events = new ArrayList<>();

        @Override
        public void publish(AgentRunEvent event) {
            events.add(event);
        }

        private List<AgentRunEvent> events() {
            return events;
        }
    }
}
