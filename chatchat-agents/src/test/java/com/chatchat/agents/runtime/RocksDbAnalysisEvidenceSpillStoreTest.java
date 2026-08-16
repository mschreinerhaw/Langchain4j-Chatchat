package com.chatchat.agents.runtime;

import com.chatchat.agents.protocol.ModelProtocolJson;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RocksDbAnalysisEvidenceSpillStoreTest {

    @TempDir
    Path tempDir;

    private final List<RocksDbAnalysisEvidenceSpillStore> stores = new ArrayList<>();

    @AfterEach
    void closeStores() {
        stores.forEach(RocksDbAnalysisEvidenceSpillStore::close);
    }

    @Test
    void spillsAndReadsMultiMegabytePayloadWithoutLoss() {
        RocksDbAnalysisEvidenceSpillStore store = store("large");
        GovernanceIsolationScope scope = scope("tenant-a", "run-a");
        String json = "[{\"payload\":\"" + "中ABC".repeat(600_000) + "\"}]";
        byte[] payload = json.getBytes(StandardCharsets.UTF_8);
        String hash = ModelProtocolJson.sha256Hex(json);

        AnalysisEvidenceSpillStore.SpillReference reference =
            store.spill(scope, "evidence-large", hash, payload);

        assertThat(reference.byteLength()).isEqualTo(payload.length);
        assertThat(store.read(scope, reference)).isEqualTo(payload);
    }

    @Test
    void checkpointSurvivesCloseAndReopen() {
        AgentRuntimeProperties properties = properties("restart");
        RocksDbAnalysisEvidenceSpillStore first = new RocksDbAnalysisEvidenceSpillStore(
            properties, new ObjectMapper());
        stores.add(first);
        first.open();
        GovernanceIsolationScope scope = scope("tenant-a", "run-restart");
        first.checkpoint(scope, "dataset#chunk-1", "input-hash", "{\"content\":\"完整摘要\"}");
        first.close();

        RocksDbAnalysisEvidenceSpillStore reopened = new RocksDbAnalysisEvidenceSpillStore(
            properties, new ObjectMapper());
        stores.add(reopened);
        reopened.open();

        assertThat(reopened.readCheckpoint(scope, "dataset#chunk-1", "input-hash"))
            .contains("{\"content\":\"完整摘要\"}");
    }

    @Test
    void springContainerSelectsProductionConstructorAndOpensStore() {
        AgentRuntimeProperties properties = properties("spring-context");
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(AgentRuntimeProperties.class, () -> properties);
            context.registerBean(ObjectMapper.class, () -> new ObjectMapper());
            context.register(RocksDbAnalysisEvidenceSpillStore.class);
            context.refresh();

            RocksDbAnalysisEvidenceSpillStore store =
                context.getBean(RocksDbAnalysisEvidenceSpillStore.class);
            GovernanceIsolationScope scope = scope("tenant-spring", "run-spring");
            String payload = "spring-constructor-evidence";
            AnalysisEvidenceSpillStore.SpillReference reference = store.spill(
                scope, "spring-evidence", ModelProtocolJson.sha256Hex(payload),
                payload.getBytes(StandardCharsets.UTF_8));

            assertThat(store.read(scope, reference))
                .isEqualTo(payload.getBytes(StandardCharsets.UTF_8));
        }
    }

    @Test
    void rejectsCrossTenantAndCrossRunReads() {
        RocksDbAnalysisEvidenceSpillStore store = store("isolation");
        GovernanceIsolationScope owner = scope("tenant-a", "run-a");
        byte[] payload = "证据".getBytes(StandardCharsets.UTF_8);
        AnalysisEvidenceSpillStore.SpillReference reference = store.spill(
            owner, "evidence", ModelProtocolJson.sha256Hex("证据"), payload);

        assertThatThrownBy(() -> store.read(scope("tenant-b", "run-a"), reference))
            .isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> store.read(scope("tenant-a", "run-b"), reference))
            .isInstanceOf(SecurityException.class);
        assertThat(store.readCheckpoint(scope("tenant-b", "run-a"), "missing", "hash"))
            .isEmpty();
    }

    @Test
    void concurrentPartitionsRemainLosslessAndIsolated() throws Exception {
        RocksDbAnalysisEvidenceSpillStore store = store("concurrent");
        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            List<CompletableFuture<Void>> futures = new ArrayList<>();
            for (int index = 0; index < 64; index++) {
                int value = index;
                futures.add(CompletableFuture.runAsync(() -> {
                    GovernanceIsolationScope scope = scope("tenant-" + (value % 4), "run-" + value);
                    String raw = "{\"row\":" + value + ",\"value\":\"" + "x".repeat(8_192) + "\"}";
                    byte[] payload = raw.getBytes(StandardCharsets.UTF_8);
                    AnalysisEvidenceSpillStore.SpillReference reference = store.spill(
                        scope, "evidence-" + value, ModelProtocolJson.sha256Hex(raw), payload);
                    assertThat(store.read(scope, reference)).isEqualTo(payload);
                    store.checkpoint(scope, "chunk", reference.contentSha256(), "summary-" + value);
                    assertThat(store.readCheckpoint(scope, "chunk", reference.contentSha256()))
                        .contains("summary-" + value);
                }, executor));
            }
            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void detectsForgedChecksumInsteadOfReturningInaccurateEvidence() {
        RocksDbAnalysisEvidenceSpillStore store = store("integrity");
        GovernanceIsolationScope scope = scope("tenant-a", "run-a");
        byte[] payload = "authoritative-result".getBytes(StandardCharsets.UTF_8);
        AnalysisEvidenceSpillStore.SpillReference reference = store.spill(
            scope, "evidence", ModelProtocolJson.sha256Hex("authoritative-result"), payload);
        AnalysisEvidenceSpillStore.SpillReference forged = new AnalysisEvidenceSpillStore.SpillReference(
            reference.schemaVersion(), reference.resolver(), reference.storageKey(), reference.evidenceId(),
            ModelProtocolJson.sha256Hex("different"), reference.byteLength(), reference.createdAtEpochMs());

        assertThatThrownBy(() -> store.read(scope, forged))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("integrity check failed");
    }

    @Test
    void ttlCleanupDeletesExpiredPayloadButKeepsFreshPayload() {
        AgentRuntimeProperties properties = properties("ttl");
        properties.setAnalysisSpillTtlMs(1_000);
        Clock oldClock = Clock.fixed(Instant.parse("2026-08-16T00:00:00Z"), ZoneOffset.UTC);
        RocksDbAnalysisEvidenceSpillStore oldStore = new RocksDbAnalysisEvidenceSpillStore(
            properties, new ObjectMapper(), oldClock);
        stores.add(oldStore);
        oldStore.open();
        GovernanceIsolationScope scope = scope("tenant-a", "run-a");
        String raw = "old";
        AnalysisEvidenceSpillStore.SpillReference reference = oldStore.spill(
            scope, "old", ModelProtocolJson.sha256Hex(raw), raw.getBytes(StandardCharsets.UTF_8));
        oldStore.checkpoint(scope, "old-chunk", "old-input", "old-summary");
        oldStore.close();

        Clock futureClock = Clock.fixed(Instant.parse("2026-08-16T00:00:02Z"), ZoneOffset.UTC);
        RocksDbAnalysisEvidenceSpillStore futureStore = new RocksDbAnalysisEvidenceSpillStore(
            properties, new ObjectMapper(), futureClock);
        stores.add(futureStore);
        futureStore.open();

        assertThatThrownBy(() -> futureStore.read(scope, reference))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("missing");
        assertThat(futureStore.readCheckpoint(scope, "old-chunk", "old-input")).isEmpty();
    }

    private RocksDbAnalysisEvidenceSpillStore store(String name) {
        RocksDbAnalysisEvidenceSpillStore store = new RocksDbAnalysisEvidenceSpillStore(
            properties(name), new ObjectMapper());
        stores.add(store);
        store.open();
        return store;
    }

    private AgentRuntimeProperties properties(String name) {
        AgentRuntimeProperties properties = new AgentRuntimeProperties();
        properties.setAnalysisSpillEnabled(true);
        properties.setRocksDbPath(tempDir.resolve(name + "-run-store").toString());
        properties.setAnalysisSpillRocksDbPath(tempDir.resolve(name + "-spill").toString());
        return properties;
    }

    private GovernanceIsolationScope scope(String tenant, String run) {
        return GovernanceIsolationScope.runtime(tenant, "user", run, "request", "conversation");
    }
}
