package com.chatchat.api.runtime;

import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.runtime.analysis.evidence.*;
import com.chatchat.common.runtime.analysis.execution.*;
import com.chatchat.common.runtime.analysis.model.*;
import com.chatchat.common.runtime.analysis.recovery.*;
import com.chatchat.common.runtime.evidence.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

class JdbcRuntimeEvidenceStoreTest {
    JdbcTemplate jdbc;
    JdbcRuntimeEvidenceStore store;
    DriverManagerDataSource datasource;
    KernelDataScope scope = scope("tenant", "owner", "run");

    @BeforeEach void database() {
        datasource = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE", "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource("runtime-evidence-schema.sql")).execute(datasource);
        jdbc = new JdbcTemplate(datasource);
        store = new JdbcRuntimeEvidenceStore(jdbc, new ObjectMapper(), new DataSourceTransactionManager(datasource));
    }

    @Test void durableImmutableFactsAreScopedAndSnapshotsPinTheirSources() {
        var fact = fact("e1", scope, "payload");
        var lineage = lineage("e1", scope, List.of());
        var first = store.register(fact, lineage);
        assertThat(store.register(fact, lineage).revision()).isEqualTo(first.revision());
        assertThat(store.find(scope("other", "owner", "run"), "e1")).isEmpty();
        assertThat(store.find(scope("tenant", "other", "run"), "e1")).isEmpty();
        assertThat(store.find(scope("tenant", "owner", "other"), "e1")).isEmpty();
        assertThatThrownBy(() -> store.register(fact("e1", scope, "different"), lineage)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.register(fact("e2", scope, "derived"), lineage("e2", scope, List.of("absent"))))
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(store.find(scope, "e2")).isEmpty();
        store.register(fact("e2", scope, "derived"), lineage("e2", scope, List.of("e1")));
        assertThat(store.lineage(scope, "e2").orElseThrow().parentEvidenceIds()).containsExactly("e1");
        store.createSnapshot(scope, "review", List.of("e1", "e2"), Map.of());
        assertThatThrownBy(() -> store.delete(scope, "e1")).isInstanceOf(IllegalStateException.class);
        var restarted = new JdbcRuntimeEvidenceStore(jdbc, new ObjectMapper(), new DataSourceTransactionManager(datasource));
        assertThat(restarted.snapshot(scope, "review").orElseThrow().evidenceIds()).containsExactly("e1", "e2");
    }

    @Test void rejectsTamperedPayloadAndLineageAndInvalidDigests() {
        var original = fact("e1", scope, "payload");
        assertThatThrownBy(() -> store.register(new EvidenceRecord(null, "bad", scope, "TOOL_CALL", "node",
            "invalid", Map.of("content", "payload"), null, 1, Map.of()), lineage("bad", scope, List.of())))
            .isInstanceOf(IllegalArgumentException.class);
        store.register(original, lineage("e1", scope, List.of()));
        jdbc.update("update runtime_evidence_entry set lineage_json='{}' where entry_id='e1'");
        assertThatThrownBy(() -> store.lineage(scope, "e1")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> store.find(scope, "e1")).isInstanceOf(IllegalStateException.class);
    }

    @Test void atomicReservationsDeduplicateConcurrentStartAndWakeAndSurviveAdapterRestart() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(6);
        try {
            var futures = new ArrayList<Future<Boolean>>();
            for (int i = 0; i < 6; i++) futures.add(pool.submit(() -> store.start(scope, 99).admitted()));
            int winners = 0;
            for (var future : futures) if (future.get(10, TimeUnit.SECONDS)) winners++;
            assertThat(winners).isEqualTo(1);
            assertThat(store.state(scope).orElseThrow().maxRounds()).isEqualTo(3);
            var observed = store.observe(context(), outcome("first"), gaps(), 1, recover());
            futures.clear();
            for (int i = 0; i < 6; i++) futures.add(pool.submit(() -> store.reserveRecovery(scope, observed.revision()).admitted()));
            winners = 0;
            for (var future : futures) if (future.get(10, TimeUnit.SECONDS)) winners++;
            assertThat(winners).isEqualTo(1);
            var restarted = new JdbcRuntimeEvidenceStore(jdbc, new ObjectMapper(), new DataSourceTransactionManager(datasource));
            assertThat(restarted.start(scope, 3).admitted()).isFalse();
            assertThat(restarted.state(scope).orElseThrow().rounds()).isEqualTo(2);
            assertThat(restarted.reserveRecovery(scope, observed.revision()).admitted()).isFalse();
        } finally { pool.shutdownNow(); }
    }

    @Test void effectiveChangesWakeOnceAndPersistTotalThreeRoundLimit() {
        store.start(scope, 3);
        var first = store.observe(context(), outcome("first"), gaps(), 1, recover());
        store.reserveRecovery(scope, first.revision());
        var second = store.observe(context(), outcome("second"), gaps(), 2, recover());
        assertThat(second.revision()).isGreaterThan(first.revision());
        store.reserveRecovery(scope, second.revision());
        var third = store.observe(context(), outcome("third"), gaps(), 3, recover());
        assertThat(third.action()).isEqualTo("STOP");
        assertThat(third.reason()).isEqualTo("BUDGET_EXHAUSTED");
        assertThat(store.reserveRecovery(scope, third.revision()).admitted()).isFalse();
        assertThat(store.state(scope).orElseThrow().rounds()).isEqualTo(3);
        assertThat(store.snapshot(scope, "analysis-round-3")).isPresent();
        assertThatThrownBy(() -> jdbc.update("update runtime_evidence_partition set max_rounds=4"))
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test void unchangedEvidenceDoesNotProduceAnotherWakeAndConflictingFactsRemainSeparate() {
        store.start(scope, 3);
        var first = store.observe(context(), outcome("first"), gaps(), 1, recover());
        store.reserveRecovery(scope, first.revision());
        var second = store.observe(context(), outcome("first"), gaps(), 2, recover());
        assertThat(second.action()).isEqualTo("STOP");
        assertThat(second.reason()).isEqualTo("NO_NEW_EVIDENCE");
        assertThat(second.revision()).isEqualTo(first.revision());
        assertThat(store.query(new EvidenceQuery(scope, List.of(), List.of(), 0, 0, 100))).hasSize(1);
    }
    @Test void newInvocationIdsDoNotTurnIdenticalFactsIntoEffectiveEvidenceChanges() {
        store.start(scope, 3);
        var first = store.observe(context(), outcome("same data"), gaps(), 1, recover());
        store.reserveRecovery(scope, first.revision());
        var old = outcome("same data").evidenceBundle().evidence().get(0);
        var reread = new ToolAnalysisEvidence("new-random-id", "tool", "new-call", "same data", Map.of());
        List<AnalysisEvidence> evidence = List.of(old, reread);
        var result = new AnalysisExecutionOutcome(null, AnalysisWorkflowType.TOOL, null,
            new VerificationResult(true, evidence, List.of()), new EvidenceBundle(null, evidence, List.of(), Map.of()), "", Map.of());
        var unchanged = store.observe(context(), result, gaps(), 2, recover());
        assertThat(unchanged.reason()).isEqualTo("NO_NEW_EVIDENCE");
        assertThat(unchanged.revision()).isEqualTo(first.revision());
        assertThat(store.query(new EvidenceQuery(scope, List.of(), List.of(), 0, 0, 100))).hasSize(2);
    }

    @Test void failedObservationRollsBackBothFactsAndWakeCursor() {
        store.start(scope, 3);
        var evidence = new ComputationEvidence("derived", "sum", List.of("missing"), "{}", Map.of());
        var result = new AnalysisExecutionOutcome(null, AnalysisWorkflowType.COMPUTATION, null,
            new VerificationResult(true, List.of(evidence), List.of()),
            new EvidenceBundle(null, List.of(evidence), List.of(), Map.of()), "", Map.of());
        assertThatThrownBy(() -> store.observe(context(), result, gaps(), 1, recover())).isInstanceOf(IllegalArgumentException.class);
        assertThat(store.query(new EvidenceQuery(scope, List.of(), List.of(), 0, 0, 100))).isEmpty();
        assertThat(store.state(scope).orElseThrow().revision()).isZero();
    }

    @Test void conflictingSourceVersionsAnchorDerivedEvidenceWithoutChoosingAWinner() {
        store.start(scope, 3);
        var a = new ToolAnalysisEvidence("source", "tool", "call", "first", Map.of());
        var b = new ToolAnalysisEvidence("source", "tool", "call", "second", Map.of());
        var c = new ComputationEvidence("derived", "compare", List.of("source"), "conflict", Map.of());
        List<AnalysisEvidence> evidence = List.of(a, b, c);
        var result = new AnalysisExecutionOutcome(null, AnalysisWorkflowType.COMPUTATION, null,
            new VerificationResult(true, evidence, List.of()), new EvidenceBundle(null, evidence, List.of(), Map.of()), "", Map.of());
        store.observe(context(), result, List.of(), 1,
            new AdaptiveAnalysisController.Decision(AdaptiveAnalysisController.Action.DELIVER, "VERIFIED"));
        var records = store.query(new EvidenceQuery(scope, List.of(), List.of(), 0, 0, 100));
        assertThat(records).hasSize(3);
        var derived = records.stream().filter(record -> "COMPUTATION".equals(record.evidenceType())).findFirst().orElseThrow();
        assertThat(store.lineage(scope, derived.evidenceId()).orElseThrow().parentEvidenceIds()).hasSize(2);
    }

    @Test void runtimeConsumesCommittedRevisionBeforeRecoveryAndDoesNotReplayPrimary() {
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        com.chatchat.common.runtime.analysis.spi.AnalysisWorkflow workflow = new com.chatchat.common.runtime.analysis.spi.AnalysisWorkflow() {
            @Override public AnalysisWorkflowType type() { return AnalysisWorkflowType.TOOL; }
            @Override public String workflowId() { return "test.generic-analysis"; }
            @Override public boolean supports(AnalysisContext context, AnalysisIntent intent) { return true; }
            @Override public AnalysisExecutionOutcome execute(AnalysisContext context) {
                calls.incrementAndGet();
                return new AnalysisExecutionOutcome(null, type(), null, new VerificationResult(false, List.of(), List.of()),
                    EvidenceBundle.empty("missing"), "", Map.of());
            }
            @Override public AnalysisExecutionOutcome continueAfterRecovery(AnalysisContext context, AnalysisExecutionOutcome primary,
                    EvidenceBundle evidence, Map<String, Object> metadata) {
                return new AnalysisExecutionOutcome(null, type(), null, new VerificationResult(true, evidence.evidence(), List.of()),
                    evidence, "verified", Map.of());
            }
        };
        com.chatchat.common.runtime.analysis.spi.EvidenceRecoveryWorkflow recovery = new com.chatchat.common.runtime.analysis.spi.EvidenceRecoveryWorkflow() {
            @Override public boolean supports(AnalysisContext context, EvidenceGap gap) { return true; }
            @Override public int priority() { return 1; }
            @Override public EvidenceRecoveryResult recover(AnalysisContext context, EvidenceBundle current, EvidenceGap gap, int round) {
                var state = store.state(scope).orElseThrow();
                assertThat(state.rounds()).isEqualTo(2);
                assertThat(state.consumedRevision()).isEqualTo(state.revision());
                assertThat(state.action()).isEqualTo("EXECUTE");
                return new EvidenceRecoveryResult(RecoveryStatus.COMPLETE, outcome("recovered").evidenceBundle(),
                    List.of(), round, null, null, Map.of());
            }
        };
        var beans = new org.springframework.beans.factory.support.StaticListableBeanFactory();
        beans.addBean("progress", store);
        beans.addBean("recovery", recovery);
        var runtime = new com.chatchat.agents.runtime.analysis.workflow.DefaultAnalysisWorkflowRuntime(List.of(workflow),
            beans.getBeanProvider(com.chatchat.common.runtime.workflow.WorkflowRuntime.class),
            beans.getBeanProvider(com.chatchat.common.runtime.analysis.spi.AnalysisEvidenceArchivePort.class),
            beans.getBeanProvider(com.chatchat.common.runtime.analysis.spi.EvidenceRecoveryWorkflow.class));
        runtime.setProgressPort(beans.getBeanProvider(com.chatchat.common.runtime.analysis.spi.AnalysisProgressPort.class));
        var request = new AnalysisContext("analyze", scope, "skill", List.of(), List.of(), List.of(),
            new AnalysisIntent("ANALYZE", List.of(), Set.of(AnalysisCapability.TOOL_CALL), "UNSPECIFIED", true), Map.of());
        var delivered = runtime.analyze(request);
        assertThat(delivered.metadata()).containsEntry("adaptiveAnalysisAction", "DELIVER")
            .containsEntry("runtimeProgressPersistence", "DATABASE").containsEntry("adaptiveAnalysisRounds", 2);
        assertThat(store.state(scope).orElseThrow().action()).isEqualTo("DELIVER");
        assertThat(runtime.analyze(request).metadata()).containsEntry("adaptiveAnalysisReason", "RUN_ALREADY_STARTED");
        assertThat(calls).hasValue(1);
    }

    private EvidenceRecord fact(String id, KernelDataScope scope, String content) {
        Map<String, Object> payload = Map.of("content", content);
        return new EvidenceRecord(null, id, scope, "TOOL_CALL", "node", store.contentHash(payload), payload, null, 10, Map.of());
    }
    private EvidenceLineage lineage(String id, KernelDataScope scope, List<String> parents) {
        return new EvidenceLineage(null, id, scope, parents, "run", "node", null, Map.of());
    }
    private static KernelDataScope scope(String tenant, String user, String run) {
        return new KernelDataScope(tenant, user, "request", null, run, null, Map.of());
    }
    private AnalysisContext context() { return new AnalysisContext("analyze", scope, "skill", List.of(), List.of(), List.of(), null, Map.of()); }
    private AnalysisExecutionOutcome outcome(String content) {
        var evidence = new ToolAnalysisEvidence("e1", "tool", "call", content, Map.of());
        return new AnalysisExecutionOutcome(null, AnalysisWorkflowType.TOOL, null,
            new VerificationResult(true, List.of(evidence), List.of()), new EvidenceBundle(null, List.of(evidence), List.of(), Map.of()), "", Map.of());
    }
    private List<EvidenceGap> gaps() { return List.of(new EvidenceGap(EvidenceGapReason.RETRIEVAL_EMPTY, null, null, null, 0, 0, false, false, List.of())); }
    private AdaptiveAnalysisController.Decision recover() { return new AdaptiveAnalysisController.Decision(AdaptiveAnalysisController.Action.RECOVER, "EVIDENCE_GAPS"); }
}
