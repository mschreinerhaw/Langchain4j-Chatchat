package com.chatchat.api.runtime;

import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.runtime.analysis.evidence.*;
import com.chatchat.common.runtime.analysis.execution.AnalysisExecutionOutcome;
import com.chatchat.common.runtime.analysis.execution.AdaptiveAnalysisController;
import com.chatchat.common.runtime.analysis.execution.AdaptiveAnalysisController.Decision;
import com.chatchat.common.runtime.analysis.model.AnalysisContext;
import com.chatchat.common.runtime.analysis.recovery.EvidenceGap;
import com.chatchat.common.runtime.analysis.spi.AnalysisProgressPort;
import com.chatchat.common.runtime.evidence.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.Supplier;

/** Immutable facts plus a durable revision-driven controller journal. All mutations serialize on the owner partition. */
@Component
public class JdbcRuntimeEvidenceStore implements EvidenceStorePort, AnalysisProgressPort {
    private static final int MAX_BYTES = 2 * 1024 * 1024;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final TransactionTemplate tx;

    public JdbcRuntimeEvidenceStore(JdbcTemplate jdbc, ObjectMapper mapper, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.mapper = mapper.copy().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
        this.tx = new TransactionTemplate(manager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.setTimeout(30);
    }

    public String contentHash(Map<String, Object> payload) { return hash(json(payload)); }

    @Override public EvidenceRegistration register(EvidenceRecord record, EvidenceLineage lineage) {
        validate(record, lineage);
        return locked(record.scope(), () -> registerLocked(record, lineage));
    }

    private EvidenceRegistration registerLocked(EvidenceRecord record, EvidenceLineage lineage) {
        String partition = partition(record.scope());
        String id = entryId(partition, "EVIDENCE", record.evidenceId());
        var existing = entry(id);
        if (!existing.isEmpty()) {
            verify(existing);
            EvidenceRecord stored = decode(existing.get("record_json"), EvidenceRecord.class);
            EvidenceLineage oldLineage = decode(existing.get("lineage_json"), EvidenceLineage.class);
            if (!stored.contentSha256().equals(record.contentSha256())
                || !stored.evidenceType().equals(record.evidenceType())
                || !stored.sourceNode().equals(record.sourceNode())
                || !stored.metadata().equals(record.metadata())
                || !oldLineage.equals(lineage))
                throw new IllegalArgumentException("Evidence identity is immutable; use a new version ID");
            return receipt(record, number(existing, "revision"), number(existing, "stored_at"));
        }
        for (String parent : lineage.parentEvidenceIds()) {
            if (parent.equals(record.evidenceId()) || find(record.scope(), parent).isEmpty())
                throw new IllegalArgumentException("Evidence parent must already exist in the same owner partition");
        }
        long revision = advance(partition);
        long now = System.currentTimeMillis();
        String body = json(record);
        String lineageBody = json(lineage);
        jdbc.update("insert into runtime_evidence_entry (id,partition_id,entry_kind,entry_id,revision,stored_at,occurred_at,evidence_type,source_node,sha256,record_json,lineage_json) values (?,?,?,?,?,?,?,?,?,?,?,?)",
            id, partition, "EVIDENCE", record.evidenceId(), revision, now, record.occurredAtEpochMs(),
            record.evidenceType(), record.sourceNode(), hash(body + "\u0000" + lineageBody), body, lineageBody);
        return receipt(record, revision, now);
    }

    private void validate(EvidenceRecord record, EvidenceLineage lineage) {
        partition(record.scope());
        if (lineage == null || !record.evidenceId().equals(lineage.evidenceId())
            || !partition(record.scope()).equals(partition(lineage.scope())))
            throw new IllegalArgumentException("Evidence and lineage must share an identity and owner partition");
        if (record.evidenceId().length() > 256 || !record.contentSha256().equals(contentHash(record.payload())))
            throw new IllegalArgumentException("Evidence content digest is invalid");
        if (record.payloadReference() != null)
            throw new IllegalArgumentException("External payload references require a governed payload adapter");
        json(record);
    }

    @Override public Optional<EvidenceRecord> find(KernelDataScope scope, String evidenceId) {
        return read(scope, "EVIDENCE", evidenceId, EvidenceRecord.class);
    }

    @Override public Optional<EvidenceLineage> lineage(KernelDataScope scope, String evidenceId) {
        var row = entry(entryId(partition(scope), "EVIDENCE", evidenceId));
        if (row.isEmpty()) return Optional.empty();
        verify(row);
        return Optional.of(decode(row.get("lineage_json"), EvidenceLineage.class));
    }

    @Override public List<EvidenceRecord> query(EvidenceQuery query) {
        // Filter before limiting so a newer unrelated node cannot hide matching evidence.
        List<Object> args = new ArrayList<>(List.of(partition(query.scope()), query.occurredAfterEpochMs(), query.occurredBeforeEpochMs()));
        String sql = "select * from runtime_evidence_entry where partition_id=? and entry_kind='EVIDENCE' and occurred_at>=? and occurred_at<=?";
        if (!query.evidenceTypes().isEmpty()) {
            sql += " and evidence_type in (" + String.join(",", Collections.nCopies(query.evidenceTypes().size(), "?")) + ")";
            args.addAll(query.evidenceTypes());
        }
        if (!query.sourceNodes().isEmpty()) {
            sql += " and source_node in (" + String.join(",", Collections.nCopies(query.sourceNodes().size(), "?")) + ")";
            args.addAll(query.sourceNodes());
        }
        sql += " order by revision desc limit ?";
        args.add(query.limit());
        return jdbc.queryForList(sql, args.toArray()).stream().map(row -> {
            verify(row);
            return decode(row.get("record_json"), EvidenceRecord.class);
        }).filter(record -> query.evidenceTypes().isEmpty() || query.evidenceTypes().contains(record.evidenceType()))
            .filter(record -> query.sourceNodes().isEmpty() || query.sourceNodes().contains(record.sourceNode()))
            .filter(record -> record.occurredAtEpochMs() >= query.occurredAfterEpochMs()
                && record.occurredAtEpochMs() <= query.occurredBeforeEpochMs())
            .limit(query.limit()).toList();
    }

    @Override public EvidenceSnapshot createSnapshot(KernelDataScope scope, String snapshotId,
            List<String> evidenceIds, Map<String, Object> metadata) {
        return locked(scope, () -> snapshotLocked(scope, snapshotId, evidenceIds, metadata));
    }

    private EvidenceSnapshot snapshotLocked(KernelDataScope scope, String snapshotId,
            List<String> evidenceIds, Map<String, Object> metadata) {
            var old = snapshot(scope, snapshotId);
            List<String> ids = evidenceIds == null ? List.of() : evidenceIds.stream().distinct().toList();
            if (old.isPresent()) {
                if (!old.get().evidenceIds().equals(ids) || !old.get().metadata().equals(metadata == null ? Map.of() : metadata))
                    throw new IllegalArgumentException("Snapshot identity is immutable");
                return old.get();
            }
            if (snapshotId == null || snapshotId.length() > 256 || ids.size() > 10_000)
                throw new IllegalArgumentException("Snapshot exceeds limits");
            ids.forEach(id -> find(scope, id).orElseThrow(() -> new IllegalArgumentException("Snapshot evidence not found")));
            long revision = advance(partition(scope));
            var snapshot = new EvidenceSnapshot(null, snapshotId, scope, revision, ids, System.currentTimeMillis(), metadata);
            String body = json(snapshot);
            jdbc.update("insert into runtime_evidence_entry (id,partition_id,entry_kind,entry_id,revision,stored_at,occurred_at,sha256,record_json) values (?,?,?,?,?,?,?,?,?)",
                entryId(partition(scope), "SNAPSHOT", snapshotId), partition(scope), "SNAPSHOT", snapshotId,
                revision, snapshot.createdAtEpochMs(), snapshot.createdAtEpochMs(), hash(body + "\u0000"), body);
            return snapshot;
    }

    @Override public Optional<EvidenceSnapshot> snapshot(KernelDataScope scope, String snapshotId) {
        return read(scope, "SNAPSHOT", snapshotId, EvidenceSnapshot.class);
    }

    @Override public boolean delete(KernelDataScope scope, String evidenceId) {
        return locked(scope, () -> {
            for (var row : jdbc.queryForList("select * from runtime_evidence_entry where partition_id=?", partition(scope))) {
                verify(row);
                boolean referenced = "SNAPSHOT".equals(row.get("entry_kind"))
                    ? decode(row.get("record_json"), EvidenceSnapshot.class).evidenceIds().contains(evidenceId)
                    : decode(row.get("lineage_json"), EvidenceLineage.class).parentEvidenceIds().contains(evidenceId);
                if (referenced) throw new IllegalStateException("Referenced evidence cannot be deleted");
            }
            int changed = jdbc.update("delete from runtime_evidence_entry where id=?", entryId(partition(scope), "EVIDENCE", evidenceId));
            if (changed > 0) advance(partition(scope));
            return changed > 0;
        });
    }

    @Override public State start(KernelDataScope scope, int maxRounds) {
        return locked(scope, () -> {
            var current = state(scope).orElseThrow();
            if (current.rounds() > 0) return new State(false, current.rounds(), current.maxRounds(),
                current.revision(), current.consumedRevision(), current.action(), "RUN_ALREADY_STARTED");
            jdbc.update("update runtime_evidence_partition set analysis_rounds=1,max_rounds=?,analysis_action='EXECUTE',analysis_reason='PRIMARY_ANALYSIS' where id=?",
                Math.max(1, AdaptiveAnalysisController.boundedRounds(maxRounds)), partition(scope));
            return admitted(scope);
        });
    }

    @Override public State observe(AnalysisContext context, AnalysisExecutionOutcome outcome,
            List<EvidenceGap> gaps, int round, Decision decision) {
        return locked(context.kernelScope(), () -> {
            KernelDataScope scope = context.kernelScope();
            var current = state(scope).orElseThrow();
            if (current.rounds() != round || !"EXECUTE".equals(current.action()))
                throw new IllegalStateException("Observation requires its reserved round");
            Map<String, List<String>> ids = new LinkedHashMap<>();
            List<String> fingerprints = new ArrayList<>();
            List<String> snapshotIds = new ArrayList<>();
            for (AnalysisEvidence evidence : outcome.evidenceBundle().evidence()) {
                Map<String, Object> payload = Map.of("content", evidence.content(), "attributes", evidence.attributes());
                String versionId = versionId(evidence, outcome);
                ids.computeIfAbsent(evidence.evidenceId(), ignored -> new ArrayList<>()).add(versionId);
                snapshotIds.add(versionId);
                fingerprints.add(effectiveObservationIdentity(evidence, outcome));
            }
            // Facts remain immutable. Computations are inserted after their source facts.
            List<AnalysisEvidence> ordered = new ArrayList<>(outcome.evidenceBundle().evidence());
            ordered.sort(Comparator.comparing(e -> e instanceof ComputationEvidence ? 1 : 0));
            for (AnalysisEvidence evidence : ordered) {
                Map<String, Object> payload = Map.of("content", evidence.content(), "attributes", evidence.attributes());
                String digest = contentHash(payload);
                String id = versionId(evidence, outcome);
                Map<String, Object> metadata = new LinkedHashMap<>();
                metadata.put("originalEvidenceId", evidence.evidenceId());
                metadata.put("skillId", evidence.attributes().getOrDefault("skillId", context.skillId()));
                metadata.put("verified", verified(evidence, outcome));
                if (evidence instanceof DocumentAnalysisEvidence document) metadata.put("documentId", document.documentId());
                if (evidence instanceof ToolAnalysisEvidence tool) metadata.put("toolName", tool.toolName());
                if (evidence instanceof StructuredDataEvidence && evidence.attributes().get("sourceTool") instanceof String tool)
                    metadata.put("toolName", tool);
                String node = evidence.capability().name();
                var record = new EvidenceRecord(null, id, scope, evidence.capability().name(), node,
                    digest, payload, null, System.currentTimeMillis(), metadata);
                List<String> parents = evidence instanceof ComputationEvidence computation
                    ? computation.inputEvidenceIds().stream().flatMap(parent -> {
                        if (!ids.containsKey(parent)) throw new IllegalArgumentException("Computation source evidence is missing");
                        return ids.get(parent).stream();
                    }).distinct().toList() : List.of();
                var lineage = new EvidenceLineage(null, id, scope, parents, run(scope), node,
                    evidence instanceof ToolAnalysisEvidence tool ? tool.invocationId() : null, Map.of());
                validate(record, lineage);
                registerLocked(record, lineage);
            }
            Collections.sort(fingerprints);
            String fingerprint = hash(json(Map.of("evidence", fingerprints.stream().distinct().toList(),
                "gaps", gaps.stream().map(this::json).distinct().sorted().toList())));
            String previous = (String) jdbc.queryForMap("select observation_hash from runtime_evidence_partition where id=?", partition(scope)).get("observation_hash");
            boolean changed = !fingerprint.equals(previous);
            String action = decision.action().name();
            String reason = decision.reason();
            if (!changed && "RECOVER".equals(action)) { action = "STOP"; reason = "NO_NEW_EVIDENCE"; }
            if ("RECOVER".equals(action) && round >= current.maxRounds()) { action = "STOP"; reason = "BUDGET_EXHAUSTED"; }
            snapshotLocked(scope, "analysis-round-" + round, snapshotIds.stream().distinct().sorted().toList(),
                Map.of("round", round, "action", action, "reason", reason,
                    "planId", outcome.plan() == null ? "" : outcome.plan().planId(),
                    "workflowType", outcome.workflowType().name(),
                    "gapReasons", gaps.stream().map(gap -> gap.reason().name()).toList()));
            jdbc.update("update runtime_evidence_partition set observation_hash=?,observation_revision=observation_revision+?,analysis_action=?,analysis_reason=?,terminal=? where id=?",
                fingerprint, changed ? 1 : 0, action, reason, !"RECOVER".equals(action), partition(scope));
            return admitted(scope);
        });
    }

    @Override public State reserveRecovery(KernelDataScope scope, long expectedRevision) {
        return locked(scope, () -> {
            State state = state(scope).orElseThrow();
            if (!"RECOVER".equals(state.action()) || state.revision() != expectedRevision
                || state.revision() <= state.consumedRevision() || state.rounds() >= state.maxRounds())
                return new State(false, state.rounds(), state.maxRounds(), state.revision(),
                    state.consumedRevision(), "STOP", "WAKE_NOT_ADMITTED");
            jdbc.update("update runtime_evidence_partition set consumed_revision=?,analysis_rounds=analysis_rounds+1,analysis_action='EXECUTE',analysis_reason='EVIDENCE_REVISION' where id=?",
                expectedRevision, partition(scope));
            return admitted(scope);
        });
    }

    @Override public void stop(KernelDataScope scope, String reason) {
        locked(scope, () -> jdbc.update("update runtime_evidence_partition set terminal=true,analysis_action='STOP',analysis_reason=? where id=?",
            reason, partition(scope)));
    }

    @Override public Optional<State> state(KernelDataScope scope) {
        return jdbc.queryForList("select * from runtime_evidence_partition where id=?", partition(scope)).stream()
            .findFirst().map(row -> new State(false, (int) number(row, "analysis_rounds"), (int) number(row, "max_rounds"),
                number(row, "observation_revision"), number(row, "consumed_revision"),
                (String) row.get("analysis_action"), (String) row.get("analysis_reason")));
    }

    private State admitted(KernelDataScope scope) {
        State s = state(scope).orElseThrow();
        return new State(true, s.rounds(), s.maxRounds(), s.revision(), s.consumedRevision(), s.action(), s.reason());
    }

    private <T> T locked(KernelDataScope scope, Supplier<T> work) {
        String id = partition(scope);
        // Creation is its own transaction: a competing insert cannot poison the subsequent lock transaction.
        try {
            tx.execute(status -> {
                if (jdbc.queryForObject("select count(*) from runtime_evidence_partition where id=?", Integer.class, id) == 0)
                    jdbc.update("insert into runtime_evidence_partition (id,tenant_id,user_id,run_id,revision,analysis_rounds,max_rounds,observation_revision,consumed_revision,terminal) values (?,?,?,?,0,0,3,0,0,false)",
                        id, scope.tenantId(), scope.userId(), run(scope));
                return null;
            });
        } catch (DuplicateKeyException concurrentCreate) { /* winner created the same immutable partition */ }
        return tx.execute(status -> {
            jdbc.queryForObject("select id from runtime_evidence_partition where id=? for update", String.class, id);
            return work.get();
        });
    }

    private long advance(String id) {
        jdbc.update("update runtime_evidence_partition set revision=revision+1 where id=?", id);
        return jdbc.queryForObject("select revision from runtime_evidence_partition where id=?", Long.class, id);
    }
    private EvidenceRegistration receipt(EvidenceRecord r, long revision, long timestamp) {
        return new EvidenceRegistration(null, r.evidenceId(), r.partitionKey(), "runtime-evidence:" + r.evidenceId(), revision, timestamp, Map.of());
    }
    private <T> Optional<T> read(KernelDataScope scope, String kind, String id, Class<T> type) {
        var row = entry(entryId(partition(scope), kind, id));
        if (row.isEmpty()) return Optional.empty();
        verify(row);
        return Optional.of(decode(row.get("record_json"), type));
    }
    private Map<String, Object> entry(String id) {
        return jdbc.queryForList("select * from runtime_evidence_entry where id=?", id).stream().findFirst().orElse(Map.of());
    }
    private void verify(Map<String, Object> row) {
        String lineage = row.get("lineage_json") == null ? "" : (String) row.get("lineage_json");
        if (!hash(row.get("record_json") + "\u0000" + lineage).equals(row.get("sha256")))
            throw new IllegalStateException("Runtime evidence integrity check failed");
    }
    private <T> T decode(Object json, Class<T> type) {
        try { return mapper.readValue((String) json, type); }
        catch (Exception failure) { throw new IllegalStateException("Runtime evidence cannot be decoded", failure); }
    }
    private String json(Object value) {
        try {
            String json = mapper.writeValueAsString(value);
            if (json.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) throw new IllegalArgumentException("Runtime evidence exceeds size limit");
            return json;
        } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
            throw new IllegalArgumentException("Runtime evidence cannot be encoded", failure);
        }
    }
    private static String entryId(String partition, String kind, String id) {
        if (id == null || id.isBlank() || id.length() > 256) throw new IllegalArgumentException("Bounded evidence identity required");
        return hash(partition + "\u0000" + kind + "\u0000" + id);
    }
    private boolean verified(AnalysisEvidence evidence, AnalysisExecutionOutcome outcome) {
        return outcome.verification() != null && outcome.verification().accepted()
            && outcome.verification().acceptedEvidence().contains(evidence);
    }
    private String versionId(AnalysisEvidence evidence, AnalysisExecutionOutcome outcome) {
        return hash(evidence.evidenceId() + "\u0000" + evidence.capability() + "\u0000"
            + contentHash(Map.of("content", evidence.content(), "attributes", evidence.attributes()))
            + "\u0000" + verified(evidence, outcome));
    }
    private String effectiveObservationIdentity(AnalysisEvidence evidence, AnalysisExecutionOutcome outcome) {
        Map<String, Object> attributes = new LinkedHashMap<>(evidence.attributes());
        // Invocation identifiers and process-local counters describe execution, not a change in source facts.
        for (String key : List.of("toolRevision", "requestId", "traceId", "invocationId", "executionId")) attributes.remove(key);
        String origin = evidence instanceof ToolAnalysisEvidence tool ? tool.toolName()
            : evidence instanceof DocumentAnalysisEvidence document ? document.documentId() + ":" + document.chunkId()
            : evidence instanceof StructuredDataEvidence data ? data.dataset() + ":" + data.query() + ":" + data.asOf()
            : evidence instanceof ComputationEvidence computation ? computation.formula()
            : evidence.capability().name();
        return hash(json(Map.of("capability", evidence.capability().name(), "origin", origin,
            "content", evidence.content(), "attributes", attributes, "verified", verified(evidence, outcome))));
    }
    private static String partition(KernelDataScope scope) {
        if (scope == null || scope.tenantId() == null || scope.userId() == null || run(scope) == null
            || scope.tenantId().length() > 128 || scope.userId().length() > 128 || run(scope).length() > 128)
            throw new IllegalArgumentException("Authenticated tenant, owner and bounded run scope required");
        return hash(scope.tenantId() + "\u0000" + scope.userId() + "\u0000" + run(scope));
    }
    private static String run(KernelDataScope scope) { return scope.runId() == null ? scope.requestId() : scope.runId(); }
    private static long number(Map<String, Object> row, String key) { return ((Number) row.get(key)).longValue(); }
    private static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
