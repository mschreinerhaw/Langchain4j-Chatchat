package com.chatchat.agents.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

class RocksDbAgentRunStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void persistsRunLifecycleAndRestoresAfterReopen() {
        AgentRuntimeProperties properties = properties(tempDir);
        ObjectMapper objectMapper = new ObjectMapper();
        RocksDbAgentRunStore store = new RocksDbAgentRunStore(
            new NoopAgentRunEventPublisher(),
            properties,
            objectMapper
        );
        store.open();
        AgentRunRequest request = AgentRunRequest.builder()
            .runId("rocks-run-1")
            .requestId("req-rocks-run-1")
            .tenantId("tenant-rocks")
            .userId("user-rocks")
            .query("persist this run")
            .attributes(Map.of(
                "serializable", "yes",
                "__agentCancellation", (BooleanSupplier) () -> false
            ))
            .build();

        store.start(request);
        store.complete("rocks-run-1", AgentRunResult.builder()
            .runId("rocks-run-1")
            .status(AgentRunStatus.COMPLETED)
            .answer("persisted")
            .stopReason("final_answer")
            .steps(List.of(AgentRunStep.builder()
                .step(1)
                .action("final")
                .build()))
            .observations(List.of(AgentObservation.text("text", "test", "stored observation")))
            .build());
        store.close();

        RocksDbAgentRunStore reopened = new RocksDbAgentRunStore(
            new NoopAgentRunEventPublisher(),
            properties,
            objectMapper
        );
        reopened.open();

        AgentRun restored = reopened.find("rocks-run-1").orElseThrow();
        assertThat(restored.status()).isEqualTo(AgentRunStatus.COMPLETED);
        assertThat(restored.request().getTenantId()).isEqualTo("tenant-rocks");
        assertThat(restored.request().getAttributes())
            .containsEntry("serializable", "yes")
            .doesNotContainKey("__agentCancellation");
        assertThat(restored.steps()).hasSize(1);
        assertThat(restored.observations())
            .extracting(AgentObservation::content)
            .containsExactly("stored observation");
        assertThat(reopened.events("rocks-run-1"))
            .extracting(event -> event.type().name())
            .contains("RUN_STARTED", "STEP_RECORDED", "OBSERVATION_RECORDED", "RUN_COMPLETED");
        assertThat(reopened.snapshot().completedRuns()).isEqualTo(1);
        reopened.close();
    }

    @Test
    void persistedRetentionDeletesPrunedTerminalRuns() {
        AgentRuntimeProperties properties = properties(tempDir);
        properties.setMaxStoredRuns(1);
        ObjectMapper objectMapper = new ObjectMapper();
        RocksDbAgentRunStore store = new RocksDbAgentRunStore(
            new NoopAgentRunEventPublisher(),
            properties,
            objectMapper
        );
        store.open();

        completeRun(store, "rocks-retention-1");
        completeRun(store, "rocks-retention-2");
        store.close();

        RocksDbAgentRunStore reopened = new RocksDbAgentRunStore(
            new NoopAgentRunEventPublisher(),
            properties,
            objectMapper
        );
        reopened.open();

        assertThat(reopened.find("rocks-retention-1")).isEmpty();
        assertThat(reopened.find("rocks-retention-2")).isPresent();
        assertThat(reopened.snapshot().totalRuns()).isEqualTo(1);
        reopened.close();
    }

    @Test
    void startupRecoveryFailsInterruptedActiveRunsAndKeepsConfirmationRuns() {
        AgentRuntimeProperties properties = properties(tempDir);
        ObjectMapper objectMapper = new ObjectMapper();
        RocksDbAgentRunStore store = new RocksDbAgentRunStore(
            new NoopAgentRunEventPublisher(),
            properties,
            objectMapper
        );
        store.open();
        store.submit(AgentRunRequest.builder()
            .runId("rocks-pending-before-restart")
            .requestId("req-rocks-pending-before-restart")
            .build());
        store.start(AgentRunRequest.builder()
            .runId("rocks-running-before-restart")
            .requestId("req-rocks-running-before-restart")
            .build());
        store.start(AgentRunRequest.builder()
            .runId("rocks-waiting-before-restart")
            .requestId("req-rocks-waiting-before-restart")
            .build());
        store.complete("rocks-waiting-before-restart", AgentRunResult.builder()
            .runId("rocks-waiting-before-restart")
            .status(AgentRunStatus.WAITING_CONFIRMATION)
            .confirmationRequired(true)
            .build());
        store.close();

        RocksDbAgentRunStore reopened = new RocksDbAgentRunStore(
            new NoopAgentRunEventPublisher(),
            properties,
            objectMapper
        );
        reopened.open();

        AgentRun pending = reopened.find("rocks-pending-before-restart").orElseThrow();
        AgentRun running = reopened.find("rocks-running-before-restart").orElseThrow();
        AgentRun waiting = reopened.find("rocks-waiting-before-restart").orElseThrow();
        assertThat(pending.status()).isEqualTo(AgentRunStatus.FAILED);
        assertThat(running.status()).isEqualTo(AgentRunStatus.FAILED);
        assertThat(waiting.status()).isEqualTo(AgentRunStatus.WAITING_CONFIRMATION);
        assertThat(pending.errorMessage()).isEqualTo("Agent run interrupted by runtime restart");
        assertThat(running.metadata()).containsEntry("previousStatus", "RUNNING");
        assertThat(reopened.events("rocks-running-before-restart"))
            .extracting(event -> event.type().name())
            .contains("RUN_STARTED", "RUN_FAILED");
        reopened.close();
    }

    @Test
    void persistsAndReadsEventsFromIncrementalIndex() throws InterruptedException {
        AgentRuntimeProperties properties = properties(tempDir);
        ObjectMapper objectMapper = new ObjectMapper();
        RocksDbAgentRunStore store = new RocksDbAgentRunStore(
            new NoopAgentRunEventPublisher(),
            properties,
            objectMapper
        );
        store.open();
        AgentRunRequest request = AgentRunRequest.builder()
            .runId("rocks-events-1")
            .requestId("req-rocks-events-1")
            .build();

        store.start(request);
        long afterStart = store.events("rocks-events-1").get(0).createdAt();
        Thread.sleep(2);
        store.complete("rocks-events-1", AgentRunResult.builder()
            .runId("rocks-events-1")
            .status(AgentRunStatus.COMPLETED)
            .answer("done")
            .steps(List.of(AgentRunStep.builder()
                .step(1)
                .action("final")
                .build()))
            .observations(List.of(AgentObservation.text("text", "test", "indexed observation")))
            .build());
        store.close();

        RocksDbAgentRunStore reopened = new RocksDbAgentRunStore(
            new NoopAgentRunEventPublisher(),
            properties,
            objectMapper
        );
        reopened.open();

        assertThat(reopened.events("rocks-events-1", afterStart, 2))
            .extracting(event -> event.type().name())
            .containsExactly("STEP_RECORDED", "OBSERVATION_RECORDED");
        assertThat(reopened.events("rocks-events-1", afterStart, 10))
            .extracting(event -> event.type().name())
            .containsExactly("STEP_RECORDED", "OBSERVATION_RECORDED", "RUN_COMPLETED");
        reopened.close();
    }

    @Test
    void persistsAndReadsStepsAndObservationsFromIndexes() {
        AgentRuntimeProperties properties = properties(tempDir);
        ObjectMapper objectMapper = new ObjectMapper();
        RocksDbAgentRunStore store = new RocksDbAgentRunStore(
            new NoopAgentRunEventPublisher(),
            properties,
            objectMapper
        );
        store.open();
        AgentRunRequest request = AgentRunRequest.builder()
            .runId("rocks-records-1")
            .requestId("req-rocks-records-1")
            .build();

        store.start(request);
        store.complete("rocks-records-1", AgentRunResult.builder()
            .runId("rocks-records-1")
            .status(AgentRunStatus.COMPLETED)
            .answer("done")
            .steps(List.of(
                AgentRunStep.builder().step(1).action("search").toolName("document_search").build(),
                AgentRunStep.builder().step(2).action("final").build()
            ))
            .observations(List.of(
                AgentObservation.text("text", "document_search", "first persisted observation"),
                AgentObservation.text("text", "final", "second persisted observation")
            ))
            .build());
        store.close();

        RocksDbAgentRunStore reopened = new RocksDbAgentRunStore(
            new NoopAgentRunEventPublisher(),
            properties,
            objectMapper
        );
        reopened.open();

        assertThat(reopened.steps("rocks-records-1", 1, 10))
            .extracting(AgentRunStep::action)
            .containsExactly("final");
        assertThat(reopened.steps("rocks-records-1", 0, 1))
            .extracting(AgentRunStep::toolName)
            .containsExactly("document_search");
        assertThat(reopened.observations("rocks-records-1", 1, 10))
            .extracting(AgentObservation::content)
            .containsExactly("second persisted observation");
        reopened.close();
    }

    @Test
    void persistsLiveStepAndObservationBeforeRunCompletion() {
        AgentRuntimeProperties properties = properties(tempDir);
        ObjectMapper objectMapper = new ObjectMapper();
        RocksDbAgentRunStore store = new RocksDbAgentRunStore(
            new NoopAgentRunEventPublisher(),
            properties,
            objectMapper
        );
        AgentRunStep step = AgentRunStep.builder()
            .step(1)
            .action("tool")
            .toolName("document_search")
            .build();
        AgentObservation observation = AgentObservation.text("tool", "document_search", "live persisted observation");
        store.open();

        store.start(AgentRunRequest.builder()
            .runId("rocks-live-records-1")
            .requestId("req-rocks-live-records-1")
            .build());
        store.recordStep("rocks-live-records-1", step);
        store.recordObservation("rocks-live-records-1", observation);
        store.close();

        RocksDbAgentRunStore reopened = new RocksDbAgentRunStore(
            new NoopAgentRunEventPublisher(),
            properties,
            objectMapper
        );
        reopened.open();

        AgentRun restored = reopened.find("rocks-live-records-1").orElseThrow();
        assertThat(restored.status()).isEqualTo(AgentRunStatus.FAILED);
        assertThat(reopened.steps("rocks-live-records-1")).containsExactly(step);
        assertThat(reopened.observations("rocks-live-records-1")).containsExactly(observation);
        assertThat(reopened.events("rocks-live-records-1"))
            .extracting(event -> event.type().name())
            .containsExactly("RUN_STARTED", "STEP_RECORDED", "OBSERVATION_RECORDED", "RUN_FAILED");
        reopened.close();
    }

    private void completeRun(RocksDbAgentRunStore store, String runId) {
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

    private AgentRuntimeProperties properties(Path path) {
        AgentRuntimeProperties properties = new AgentRuntimeProperties();
        properties.setRocksDbPath(path.resolve("agent-runtime-rocksdb").toString());
        return properties;
    }
}
