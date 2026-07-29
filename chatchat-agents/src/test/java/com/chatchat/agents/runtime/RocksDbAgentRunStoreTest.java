package com.chatchat.agents.runtime;

import com.chatchat.agents.runtime.plan.InterpretationPlan;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
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
        assertThat(restored.steps()).isEmpty();
        assertThat(restored.observations()).isEmpty();
        assertThat(reopened.steps("rocks-run-1")).hasSize(1);
        assertThat(reopened.observations("rocks-run-1"))
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

    @Test
    void externalizesLargeRawObservationAndKeepsOnlyRocksDbReference() {
        AgentRuntimeProperties properties = properties(tempDir);
        properties.setEvidenceExternalizationThresholdBytes(16_384);
        ObjectMapper objectMapper = new ObjectMapper();
        TestEvidenceStore evidenceStore = new TestEvidenceStore();
        RocksDbAgentRunStore store = new RocksDbAgentRunStore(
            new NoopAgentRunEventPublisher(),
            properties,
            objectMapper
        );
        store.setEvidenceStore(evidenceStore);
        String runId = "rocks-large-observation-1";
        String rawEvidence = "evidence-row-".repeat(150_000);
        AgentObservation observation = AgentObservation.builder()
            .type("tool")
            .source("enterprise_metadata_search")
            .content("Enterprise metadata evidence")
            .metadata(Map.of("stepOutput", rawEvidence, "rowCount", 150_000))
            .build();
        store.open();

        store.start(AgentRunRequest.builder().runId(runId).requestId("req-" + runId).build());
        store.recordObservation(runId, observation);
        store.complete(runId, AgentRunResult.builder()
            .runId(runId)
            .status(AgentRunStatus.COMPLETED)
            .answer("done")
            .observations(List.of(observation))
            .build());

        AgentRunEvent event = store.events(runId).stream()
            .filter(item -> item.type() == AgentRunEventType.OBSERVATION_RECORDED)
            .findFirst()
            .orElseThrow();
        assertThat(objectMapper.valueToTree(event).toString().length()).isLessThan(20_000);
        assertThat(event.payload())
            .containsEntry("eventMetadataCompacted", true)
            .containsEntry("rawObservationLocation", "agent_run_observation_index");
        AgentObservation stored = store.observations(runId).get(0);
        assertThat(stored.metadata())
            .doesNotContainKey("stepOutput")
            .containsEntry("stepOutputExternal", true);
        assertThat(store.find(runId).orElseThrow().result().observations().get(0).metadata())
            .doesNotContainKey("stepOutput");
        assertThat(evidenceStore.values).hasSize(1);
        String documentId = String.valueOf(stored.metadata().get("stepOutputDocumentId"));
        assertThat(store.evidence(documentId)).contains(rawEvidence);
        store.close();

        RocksDbAgentRunStore reopened = new RocksDbAgentRunStore(
            new NoopAgentRunEventPublisher(),
            properties,
            objectMapper
        );
        reopened.setEvidenceStore(evidenceStore);
        reopened.open();

        AgentObservation restored = reopened.observations(runId).get(0);
        assertThat(restored.metadata()).doesNotContainKey("stepOutput");
        assertThat(reopened.evidence(documentId)).contains(rawEvidence);
        AgentRunEvent restoredEvent = reopened.events(runId).stream()
            .filter(item -> item.type() == AgentRunEventType.OBSERVATION_RECORDED)
            .findFirst()
            .orElseThrow();
        assertThat(objectMapper.valueToTree(restoredEvent).toString().length()).isLessThan(20_000);
        reopened.close();
    }

    @Test
    void migratesLegacyInlineEvidenceToExternalStoreOnReopen() {
        AgentRuntimeProperties properties = properties(tempDir);
        properties.setEvidenceExternalizationThresholdBytes(16_384);
        ObjectMapper objectMapper = new ObjectMapper();
        String runId = "rocks-legacy-evidence-1";
        String rawEvidence = "legacy-evidence-".repeat(20_000);
        RocksDbAgentRunStore legacyStore = new RocksDbAgentRunStore(
            new NoopAgentRunEventPublisher(),
            properties,
            objectMapper
        );
        legacyStore.open();
        legacyStore.start(AgentRunRequest.builder().runId(runId).requestId("req-" + runId).build());
        legacyStore.recordObservation(runId, AgentObservation.builder()
            .type("tool")
            .source("enterprise_metadata_search")
            .content("legacy evidence")
            .metadata(Map.of("evidenceId", "legacy-ev-1", "stepOutput", rawEvidence))
            .build());
        legacyStore.close();

        TestEvidenceStore evidenceStore = new TestEvidenceStore();
        RocksDbAgentRunStore migratedStore = new RocksDbAgentRunStore(
            new NoopAgentRunEventPublisher(),
            properties,
            objectMapper
        );
        migratedStore.setEvidenceStore(evidenceStore);
        migratedStore.open();

        assertThat(evidenceStore.values).isEmpty();
        AgentObservation migrated = migratedStore.observations(runId).get(0);
        assertThat(migrated.metadata())
            .doesNotContainKey("stepOutput")
            .containsEntry("stepOutputExternal", true);
        String documentId = String.valueOf(migrated.metadata().get("stepOutputDocumentId"));
        assertThat(migratedStore.evidence(documentId)).contains(rawEvidence);
        migratedStore.close();
    }

    @Test
    void dagSnapshotStoresEvidenceReferenceInsteadOfLargeInlineOutput() throws Exception {
        AgentRuntimeProperties properties = properties(tempDir);
        properties.setEvidenceExternalizationThresholdBytes(16_384);
        ObjectMapper objectMapper = new ObjectMapper();
        TestEvidenceStore evidenceStore = new TestEvidenceStore();
        RocksDbAgentRunStore store = new RocksDbAgentRunStore(
            new NoopAgentRunEventPublisher(),
            properties,
            objectMapper
        );
        store.setEvidenceStore(evidenceStore);
        String runId = "rocks-dag-evidence-1";
        String rawEvidence = "dag-evidence-".repeat(30_000);
        store.open();
        store.start(AgentRunRequest.builder()
            .runId(runId)
            .requestId("req-" + runId)
            .tenantId("tenant-dag")
            .build());
        store.recordObservation(runId, AgentObservation.builder()
            .type("tool")
            .source("enterprise_metadata_search")
            .content("DAG evidence")
            .metadata(Map.of(
                "evidenceId", "dag-ev-1",
                "interpretationPlanStepId", 1,
                "stepOutput", rawEvidence
            ))
            .build());
        InterpretationPlan plan = new InterpretationPlan(
            "1.0",
            null,
            null,
            new InterpretationPlan.Plan(List.of(new InterpretationPlan.Step(
                1, "mcp_tool", "enterprise_metadata_search", Map.of(), List.of(), null, null
            ))),
            null,
            null
        );
        store.savePlan(
            "tenant-dag",
            runId,
            "plan-dag-evidence",
            plan,
            "COMPLETED",
            Map.of("nodes", List.of(Map.of("stepId", 1, "output", rawEvidence)), "edges", List.of())
        );

        var dag = objectMapper.readTree(store.getDagJson("tenant-dag", runId).orElseThrow());
        var node = dag.path("nodes").get(0);
        assertThat(node.has("output")).isFalse();
        assertThat(node.path("outputExternal").asBoolean()).isTrue();
        assertThat(node.path("outputDocumentId").asText()).startsWith("agent-evidence-");
        assertThat(store.evidence(node.path("outputDocumentId").asText())).contains(rawEvidence);
        store.close();
    }

    @Test
    void retentionCleanupDeletesRocksDbIndexesAndExternalEvidence() throws Exception {
        AgentRuntimeProperties properties = properties(tempDir);
        properties.setEvidenceExternalizationThresholdBytes(16_384);
        TestEvidenceStore evidenceStore = new TestEvidenceStore();
        RocksDbAgentRunStore store = new RocksDbAgentRunStore(
            new NoopAgentRunEventPublisher(),
            properties,
            new ObjectMapper()
        );
        store.setEvidenceStore(evidenceStore);
        String runId = "rocks-expired-evidence";
        store.open();
        store.start(AgentRunRequest.builder()
            .runId(runId)
            .requestId("req-" + runId)
            .build());
        store.recordObservation(runId, AgentObservation.builder()
            .type("tool")
            .source("large_tool")
            .content("large evidence")
            .metadata(Map.of(
                "evidenceId", "expired-evidence",
                "stepOutput", "evidence-".repeat(40_000)
            ))
            .build());
        store.complete(runId, AgentRunResult.builder()
            .runId(runId)
            .status(AgentRunStatus.COMPLETED)
            .answer("done")
            .build());
        assertThat(evidenceStore.values).hasSize(1);

        properties.setTerminalRunTtlMs(1);
        Thread.sleep(5);

        assertThat(store.cleanupExpiredRuns()).isEqualTo(1);
        assertThat(store.find(runId)).isEmpty();
        assertThat(store.events(runId)).isEmpty();
        assertThat(store.observations(runId)).isEmpty();
        assertThat(evidenceStore.values).isEmpty();
        store.close();

        RocksDbAgentRunStore reopened = new RocksDbAgentRunStore(
            new NoopAgentRunEventPublisher(),
            properties,
            new ObjectMapper()
        );
        reopened.setEvidenceStore(evidenceStore);
        reopened.open();
        assertThat(reopened.find(runId)).isEmpty();
        reopened.close();
    }

    private static class TestEvidenceStore implements AgentEvidenceStore {

        private final Map<String, String> values = new LinkedHashMap<>();

        @Override
        public boolean isEnabled() {
            return true;
        }

        @Override
        public void put(String documentId,
                        String tenantId,
                        String runId,
                        String evidenceId,
                        String json) {
            values.put(documentId, json);
        }

        @Override
        public Optional<String> get(String documentId) {
            return Optional.ofNullable(values.get(documentId));
        }

        @Override
        public void delete(String documentId) {
            values.remove(documentId);
        }
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
