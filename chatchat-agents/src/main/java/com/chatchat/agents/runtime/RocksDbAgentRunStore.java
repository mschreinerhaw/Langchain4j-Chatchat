package com.chatchat.agents.runtime;

import com.chatchat.agents.runtime.plan.InterpretationPlanRecord;
import com.chatchat.agents.runtime.plan.InterpretationPlan;
import com.chatchat.common.tool.ToolLogSummarizer;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.rocksdb.Options;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.RocksIterator;
import org.rocksdb.WriteBatch;
import org.rocksdb.WriteOptions;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Component
@ConditionalOnProperty(prefix = "chatchat.agent-runtime", name = "store-type", havingValue = "rocksdb", matchIfMissing = true)
public class RocksDbAgentRunStore extends InMemoryAgentRunStore {

    private static final String RUN_KEY_PREFIX = "run:";
    private static final String EVENT_KEY_PREFIX = "event:";
    private static final String STEP_KEY_PREFIX = "step:";
    private static final String OBSERVATION_KEY_PREFIX = "observation:";
    private static final String PLAN_SNAPSHOT_KEY_PREFIX = "plan:snapshot:";
    private static final String PLAN_VERSION_KEY_PREFIX = "plan:version:";
    private static final String PLAN_DAG_KEY_PREFIX = "plan:dag:";
    private static final String PLAN_INDEX_KEY_PREFIX = "plan:index:planId:";
    private static final String AGENT_CANCELLATION_ATTRIBUTE = "__agentCancellation";
    private static final String INTERRUPTED_BY_RESTART = "Agent run interrupted by runtime restart";

    private final AgentRuntimeProperties properties;
    private final ObjectMapper objectMapper;
    private AgentEvidenceStore evidenceStore;
    private Options options;
    private RocksDB db;

    public RocksDbAgentRunStore(AgentRunEventPublisher eventPublisher,
                                AgentRuntimeProperties properties,
                                ObjectMapper objectMapper) {
        super(eventPublisher, properties);
        this.properties = properties == null ? new AgentRuntimeProperties() : properties;
        this.evidenceStore = disabledEvidenceStore();
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper.copy();
        this.objectMapper.getFactory().setStreamReadConstraints(
            this.objectMapper.getFactory().streamReadConstraints().rebuild()
                .maxStringLength(this.properties.getMaxJsonStringLength())
                .build()
        );
    }

    @Autowired(required = false)
    public void setEvidenceStore(AgentEvidenceStore evidenceStore) {
        this.evidenceStore = evidenceStore == null ? disabledEvidenceStore() : evidenceStore;
    }

    @PostConstruct
    public void open() {
        try {
            RocksDB.loadLibrary();
            Path path = Path.of(properties.rocksDbPath()).toAbsolutePath().normalize();
            Files.createDirectories(path);
            options = new Options().setCreateIfMissing(properties.isRocksDbCreateIfMissing());
            db = RocksDB.open(options, path.toString());
            loadRuns();
            log.info("RocksDB agent run store opened at {}. restoredRuns={}", path, runs.size());
        } catch (IOException | RocksDBException ex) {
            throw new IllegalStateException("Failed to open RocksDB agent run store", ex);
        }
    }

    @Override
    public AgentRun submit(AgentRunRequest request) {
        AgentRun run = super.submit(request);
        persistRun(run);
        return run;
    }

    @Override
    public AgentRun start(AgentRunRequest request) {
        AgentRun run = super.start(request);
        persistRun(run);
        return run;
    }

    @Override
    public AgentRun complete(String runId, AgentRunResult result) {
        AgentRun previous = find(runId).orElse(null);
        AgentRun run = super.complete(runId, externalizeResult(runId, result));
        persistTail(run, sizeOfSteps(previous), sizeOfObservations(previous), sizeOfEvents(previous));
        return run;
    }

    @Override
    public AgentRun cancel(String runId, String reason) {
        AgentRun previous = find(runId).orElse(null);
        AgentRun run = super.cancel(runId, reason);
        persistTail(run, sizeOfSteps(previous), sizeOfObservations(previous), sizeOfEvents(previous));
        return run;
    }

    @Override
    public AgentRun fail(String runId, Throwable error) {
        AgentRun previous = find(runId).orElse(null);
        AgentRun run = super.fail(runId, error);
        persistTail(run, sizeOfSteps(previous), sizeOfObservations(previous), sizeOfEvents(previous));
        return run;
    }

    @Override
    public AgentRun recordStep(String runId, AgentRunStep step) {
        int previousEventCount = find(runId).map(run -> run.events().size()).orElse(0);
        AgentRun run = super.recordStep(runId, step);
        int stepIndex = run.steps().indexOf(step);
        if (run.events().size() > previousEventCount && stepIndex >= 0) {
            persistIncrement(run, step, stepIndex, null, -1, previousEventCount);
        }
        return run;
    }

    @Override
    public AgentRun recordObservation(String runId, AgentObservation observation) {
        int previousEventCount = find(runId).map(run -> run.events().size()).orElse(0);
        AgentObservation storedObservation = externalizeObservation(runId, observation);
        AgentRun run = super.recordObservation(runId, storedObservation);
        int observationIndex = run.observations().indexOf(storedObservation);
        if (run.events().size() > previousEventCount && observationIndex >= 0) {
            persistIncrement(run, null, -1, storedObservation, observationIndex, previousEventCount);
        }
        return run;
    }

    @Override
    public Optional<Object> evidence(String documentId) {
        if (documentId == null || documentId.isBlank() || !evidenceStore.isEnabled()) {
            return Optional.empty();
        }
        return evidenceStore.get(documentId).map(json -> {
            try {
                return objectMapper.readValue(json, Object.class);
            } catch (IOException ex) {
                throw new IllegalStateException("Failed to deserialize agent evidence " + documentId, ex);
            }
        });
    }

    @Override
    public List<AgentRunEvent> events(String runId) {
        List<AgentRunEvent> indexedEvents = indexedEvents(runId, 0, Integer.MAX_VALUE);
        return indexedEvents.isEmpty() ? super.events(runId) : indexedEvents;
    }

    @Override
    public List<AgentRunEvent> events(String runId, long afterCreatedAt, int limit) {
        List<AgentRunEvent> indexedEvents = indexedEvents(runId, afterCreatedAt, recordLimit(limit));
        return indexedEvents.isEmpty() ? super.events(runId, afterCreatedAt, limit) : indexedEvents;
    }

    @Override
    public List<AgentRunStep> steps(String runId) {
        List<AgentRunStep> indexedSteps = indexedSteps(runId, Integer.MIN_VALUE, Integer.MAX_VALUE);
        return indexedSteps.isEmpty() ? super.steps(runId) : indexedSteps;
    }

    @Override
    public List<AgentRunStep> steps(String runId, int afterStep, int limit) {
        List<AgentRunStep> indexedSteps = indexedSteps(runId, afterStep, recordLimit(limit));
        return indexedSteps.isEmpty() ? super.steps(runId, afterStep, limit) : indexedSteps;
    }

    @Override
    public List<AgentObservation> observations(String runId) {
        List<AgentObservation> indexedObservations = indexedObservations(runId, 0, Integer.MAX_VALUE);
        return indexedObservations.isEmpty() ? super.observations(runId) : indexedObservations;
    }

    @Override
    public List<AgentObservation> observations(String runId, int offset, int limit) {
        List<AgentObservation> indexedObservations = indexedObservations(runId, offset, recordLimit(limit));
        return indexedObservations.isEmpty() ? super.observations(runId, offset, limit) : indexedObservations;
    }

    @Override
    public void saveSnapshot(InterpretationPlanRecord record) {
        super.saveSnapshot(record);
        if (record == null || record.tenantId() == null || record.taskId() == null) {
            return;
        }
        ensureOpen();
        try {
            db.put(bytes(planSnapshotKey(record.tenantId(), record.taskId())), objectMapper.writeValueAsBytes(record));
        } catch (JsonProcessingException | RocksDBException ex) {
            throw new IllegalStateException("Failed to persist interpretation plan snapshot " + record.taskId(), ex);
        }
    }

    @Override
    public void saveVersion(InterpretationPlanRecord record) {
        super.saveVersion(record);
        if (record == null || record.tenantId() == null || record.taskId() == null) {
            return;
        }
        ensureOpen();
        try {
            db.put(bytes(planVersionKey(record)), objectMapper.writeValueAsBytes(record));
        } catch (JsonProcessingException | RocksDBException ex) {
            throw new IllegalStateException("Failed to persist interpretation plan version " + record.taskId(), ex);
        }
    }

    @Override
    public Optional<InterpretationPlanRecord> getSnapshot(String tenantId, String taskId) {
        Optional<InterpretationPlanRecord> cached = super.getSnapshot(tenantId, taskId);
        if (cached.isPresent() || db == null || taskId == null || taskId.isBlank()) {
            return cached;
        }
        String normalizedTenant = firstText(tenantId, "default");
        try {
            byte[] value = db.get(bytes(planSnapshotKey(normalizedTenant, taskId)));
            if (value == null) {
                return Optional.empty();
            }
            InterpretationPlanRecord record = objectMapper.readValue(value, InterpretationPlanRecord.class);
            super.saveSnapshot(record);
            return Optional.of(record);
        } catch (IOException | RocksDBException ex) {
            throw new IllegalStateException("Failed to read interpretation plan snapshot " + taskId, ex);
        }
    }

    @Override
    public Optional<String> getDagJson(String tenantId, String taskId) {
        Optional<String> cached = super.getDagJson(tenantId, taskId);
        if (cached.isPresent() || db == null || taskId == null || taskId.isBlank()) {
            return cached;
        }
        String normalizedTenant = firstText(tenantId, "default");
        try {
            byte[] value = db.get(bytes(planDagKey(normalizedTenant, taskId)));
            return value == null ? Optional.empty() : Optional.of(new String(value, StandardCharsets.UTF_8));
        } catch (RocksDBException ex) {
            throw new IllegalStateException("Failed to read interpretation plan dag " + taskId, ex);
        }
    }

    @Override
    public List<InterpretationPlanRecord> listVersions(String tenantId, String taskId) {
        List<InterpretationPlanRecord> cached = super.listVersions(tenantId, taskId);
        if (!cached.isEmpty() || db == null || taskId == null || taskId.isBlank()) {
            return cached;
        }
        String normalizedTenant = firstText(tenantId, "default");
        String prefix = planVersionPrefix(normalizedTenant, taskId);
        List<InterpretationPlanRecord> versions = new ArrayList<>();
        try (RocksIterator iterator = db.newIterator()) {
            seekToPrefix(iterator, prefix);
            while (iterator.isValid() && startsWith(iterator.key(), prefix)) {
                versions.add(objectMapper.readValue(iterator.value(), InterpretationPlanRecord.class));
                iterator.next();
            }
            versions.forEach(super::saveVersion);
            return versions;
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to read interpretation plan versions " + taskId, ex);
        }
    }

    @Override
    protected void saveDag(InterpretationPlanRecord record) {
        super.saveDag(record);
        if (record == null || record.tenantId() == null || record.taskId() == null) {
            return;
        }
        ensureOpen();
        try {
            db.put(bytes(planDagKey(record.tenantId(), record.taskId())), bytes(record.dagJson() == null ? "{}" : record.dagJson()));
        } catch (RocksDBException ex) {
            throw new IllegalStateException("Failed to persist interpretation plan dag " + record.taskId(), ex);
        }
    }

    @Override
    protected void savePlanIndex(InterpretationPlanRecord record) {
        super.savePlanIndex(record);
        if (record == null || record.tenantId() == null || record.planId() == null) {
            return;
        }
        ensureOpen();
        try {
            db.put(bytes(planIndexKey(record.tenantId(), record.planId())), bytes(record.taskId()));
        } catch (RocksDBException ex) {
            throw new IllegalStateException("Failed to persist interpretation plan index " + record.planId(), ex);
        }
    }

    @Override
    protected List<String> pruneRuns() {
        List<String> removedRunIds = super.pruneRuns();
        for (String runId : removedRunIds) {
            deletePersistedRun(runId);
        }
        return removedRunIds;
    }

    @PreDestroy
    public void close() {
        if (db != null) {
            db.close();
        }
        if (options != null) {
            options.close();
        }
    }

    private void loadRuns() {
        ensureOpen();
        try (RocksIterator iterator = db.newIterator()) {
            seekToPrefix(iterator, RUN_KEY_PREFIX);
            while (iterator.isValid() && startsWith(iterator.key(), RUN_KEY_PREFIX)) {
                try {
                    AgentRun persistedRun = objectMapper.readValue(iterator.value(), AgentRun.class);
                    if (persistedRun.runId() != null && !persistedRun.runId().isBlank()) {
                        // Keep only the lightweight run header in memory. Indexed
                        // events, steps and observations are loaded page-by-page
                        // when requested. Rehydrating every historical run here
                        // used to migrate all inline evidence during startup and
                        // could retain gigabytes of object graphs before Tomcat
                        // finished starting.
                        AgentRun summary = serializableRun(persistedRun);
                        AgentRun run = recoverInterruptedRun(summary);
                        runs.put(run.runId(), run);
                        boolean recovered = run != summary;
                        if (hasInlineRecords(persistedRun) || recovered) {
                            persistRestoredSummary(run, recovered);
                        }
                    }
                } catch (IOException ex) {
                    log.warn("Failed to restore persisted agent run. key={} error={}",
                        new String(iterator.key(), StandardCharsets.UTF_8), ex.getMessage());
                }
                iterator.next();
            }
        }
    }

    private void persistRestoredSummary(AgentRun run, boolean recovered) {
        try (WriteBatch batch = new WriteBatch(); WriteOptions writeOptions = new WriteOptions()) {
            batch.put(bytes(runKey(run.runId())), objectMapper.writeValueAsBytes(serializableRun(run)));
            if (recovered && !run.events().isEmpty()) {
                AgentRunEvent recoveryEvent = run.events().get(run.events().size() - 1);
                int eventIndex = countPrefixKeys(eventPrefix(run.runId()));
                batch.put(bytes(eventKey(recoveryEvent, eventIndex)),
                    objectMapper.writeValueAsBytes(recoveryEvent));
            }
            db.write(writeOptions, batch);
        } catch (JsonProcessingException | RocksDBException ex) {
            throw new IllegalStateException("Failed to persist restored agent run summary " + run.runId(), ex);
        }
    }

    private int countPrefixKeys(String prefix) {
        int count = 0;
        try (RocksIterator iterator = db.newIterator()) {
            seekToPrefix(iterator, prefix);
            while (iterator.isValid() && startsWith(iterator.key(), prefix)) {
                count++;
                iterator.next();
            }
        }
        return count;
    }

    private boolean hasInlineRecords(AgentRun run) {
        if (run == null) {
            return false;
        }
        if (!run.steps().isEmpty() || !run.observations().isEmpty() || !run.events().isEmpty()) {
            return true;
        }
        AgentRunResult result = run.result();
        return result != null
            && (!result.steps().isEmpty()
                || !result.observations().isEmpty()
                || !result.events().isEmpty()
                || !result.toolTraces().isEmpty());
    }

    @Override
    public InterpretationPlanRecord savePlan(String tenantId,
                                             String taskId,
                                             String planId,
                                             InterpretationPlan plan,
                                             String status,
                                             Map<String, Object> dagOverride) {
        return super.savePlan(
            tenantId,
            taskId,
            planId,
            plan,
            status,
            externalizeDagEvidence(tenantId, taskId, dagOverride)
        );
    }

    private AgentRun recoverInterruptedRun(AgentRun run) {
        if (!properties.isFailInterruptedRunsOnStartup() || run == null || !isInterruptedStatus(run.status())) {
            return run;
        }
        long finishedAt = System.currentTimeMillis();
        List<AgentRunEvent> events = new ArrayList<>(run.events());
        events.add(AgentRunEvent.of(run.runId(), AgentRunEventType.RUN_FAILED, INTERRUPTED_BY_RESTART,
            Map.of("reason", "runtime_restart", "previousStatus", run.status().name())));
        Map<String, Object> metadata = new LinkedHashMap<>(run.metadata());
        metadata.put("stopReason", "interrupted");
        metadata.put("errorMessage", INTERRUPTED_BY_RESTART);
        metadata.put("previousStatus", run.status().name());
        return AgentRun.builder()
            .runId(run.runId())
            .status(AgentRunStatus.FAILED)
            .request(run.request())
            .result(run.result())
            .steps(run.steps())
            .observations(run.observations())
            .events(events)
            .metadata(metadata)
            .startedAt(run.startedAt())
            .finishedAt(finishedAt)
            .errorMessage(INTERRUPTED_BY_RESTART)
            .build();
    }

    private boolean isInterruptedStatus(AgentRunStatus status) {
        return status == AgentRunStatus.PENDING || status == AgentRunStatus.RUNNING;
    }

    private void persistRun(AgentRun run) {
        if (run == null || run.runId() == null || run.runId().isBlank()) {
            return;
        }
        ensureOpen();
        try {
            AgentRun serializableRun = serializableRun(run);
            try (WriteBatch batch = new WriteBatch(); WriteOptions writeOptions = new WriteOptions()) {
                batch.put(bytes(runKey(run.runId())), objectMapper.writeValueAsBytes(serializableRun));
                persistEvents(batch, run);
                persistSteps(batch, run);
                persistObservations(batch, run);
                db.write(writeOptions, batch);
            }
        } catch (JsonProcessingException | RocksDBException ex) {
            throw new IllegalStateException("Failed to persist agent run " + run.runId(), ex);
        }
    }

    private void persistIncrement(AgentRun run,
                                  AgentRunStep step,
                                  int stepIndex,
                                  AgentObservation observation,
                                  int observationIndex,
                                  int firstEventIndex) {
        if (run == null || run.runId() == null || run.runId().isBlank()) {
            return;
        }
        ensureOpen();
        try (WriteBatch batch = new WriteBatch(); WriteOptions writeOptions = new WriteOptions()) {
            batch.put(bytes(runKey(run.runId())), objectMapper.writeValueAsBytes(serializableRun(run)));
            if (step != null && stepIndex >= 0) {
                batch.put(bytes(stepKey(run.runId(), step, stepIndex)), objectMapper.writeValueAsBytes(step));
            }
            if (observation != null && observationIndex >= 0) {
                batch.put(bytes(observationKey(run.runId(), observationIndex)),
                    objectMapper.writeValueAsBytes(observation));
            }
            for (int i = Math.max(0, firstEventIndex); i < run.events().size(); i++) {
                AgentRunEvent event = run.events().get(i);
                batch.put(bytes(eventKey(event, i)), objectMapper.writeValueAsBytes(event));
            }
            db.write(writeOptions, batch);
        } catch (JsonProcessingException | RocksDBException ex) {
            throw new IllegalStateException("Failed to incrementally persist agent run " + run.runId(), ex);
        }
    }

    private void persistTail(AgentRun run,
                             int firstStepIndex,
                             int firstObservationIndex,
                             int firstEventIndex) {
        if (run == null || run.runId() == null || run.runId().isBlank()) {
            return;
        }
        ensureOpen();
        try (WriteBatch batch = new WriteBatch(); WriteOptions writeOptions = new WriteOptions()) {
            batch.put(bytes(runKey(run.runId())), objectMapper.writeValueAsBytes(serializableRun(run)));
            for (int i = Math.max(0, firstStepIndex); i < run.steps().size(); i++) {
                AgentRunStep step = run.steps().get(i);
                batch.put(bytes(stepKey(run.runId(), step, i)), objectMapper.writeValueAsBytes(step));
            }
            for (int i = Math.max(0, firstObservationIndex); i < run.observations().size(); i++) {
                batch.put(bytes(observationKey(run.runId(), i)),
                    objectMapper.writeValueAsBytes(run.observations().get(i)));
            }
            for (int i = Math.max(0, firstEventIndex); i < run.events().size(); i++) {
                AgentRunEvent event = run.events().get(i);
                batch.put(bytes(eventKey(event, i)), objectMapper.writeValueAsBytes(event));
            }
            db.write(writeOptions, batch);
        } catch (JsonProcessingException | RocksDBException ex) {
            throw new IllegalStateException("Failed to persist agent run tail " + run.runId(), ex);
        }
    }

    private int sizeOfSteps(AgentRun run) {
        return run == null ? 0 : run.steps().size();
    }

    private int sizeOfObservations(AgentRun run) {
        return run == null ? 0 : run.observations().size();
    }

    private int sizeOfEvents(AgentRun run) {
        return run == null ? 0 : run.events().size();
    }

    private void deletePersistedRun(String runId) {
        if (db == null || runId == null || runId.isBlank()) {
            return;
        }
        deleteExternalEvidence(runId);
        try {
            db.delete(bytes(runKey(runId)));
            deletePersistedIndexes(runId);
        } catch (RocksDBException ex) {
            throw new IllegalStateException("Failed to delete persisted agent run " + runId, ex);
        }
    }

    private void persistEvents(WriteBatch batch, AgentRun run) throws JsonProcessingException, RocksDBException {
        List<AgentRunEvent> events = run.events();
        for (int i = 0; i < events.size(); i++) {
            AgentRunEvent event = events.get(i);
            batch.put(bytes(eventKey(event, i)), objectMapper.writeValueAsBytes(event));
        }
    }

    private void persistSteps(WriteBatch batch, AgentRun run) throws JsonProcessingException, RocksDBException {
        List<AgentRunStep> steps = run.steps();
        for (int i = 0; i < steps.size(); i++) {
            AgentRunStep step = steps.get(i);
            batch.put(bytes(stepKey(run.runId(), step, i)), objectMapper.writeValueAsBytes(step));
        }
    }

    private void persistObservations(WriteBatch batch, AgentRun run) throws JsonProcessingException, RocksDBException {
        List<AgentObservation> observations = run.observations();
        for (int i = 0; i < observations.size(); i++) {
            AgentObservation observation = observations.get(i);
            batch.put(bytes(observationKey(run.runId(), i)), objectMapper.writeValueAsBytes(observation));
        }
    }

    private void deletePersistedIndexes(String runId) throws RocksDBException {
        for (byte[] key : persistedIndexKeys(runId)) {
            db.delete(key);
        }
    }

    private List<byte[]> persistedIndexKeys(String runId) {
        List<byte[]> keys = new ArrayList<>();
        collectPrefixKeys(eventPrefix(runId), keys);
        collectPrefixKeys(stepPrefix(runId), keys);
        collectPrefixKeys(observationPrefix(runId), keys);
        return keys;
    }

    private void collectPrefixKeys(String prefix, List<byte[]> keys) {
        try (RocksIterator iterator = db.newIterator()) {
            seekToPrefix(iterator, prefix);
            while (iterator.isValid() && startsWith(iterator.key(), prefix)) {
                keys.add(iterator.key().clone());
                iterator.next();
            }
        }
    }

    private List<AgentRunEvent> indexedEvents(String runId, long afterCreatedAt, int limit) {
        if (db == null || runId == null || runId.isBlank() || limit <= 0) {
            return List.of();
        }
        String prefix = eventPrefix(runId);
        List<AgentRunEvent> events = new ArrayList<>();
        try (RocksIterator iterator = db.newIterator()) {
            seekToPrefix(iterator, prefix);
            while (iterator.isValid() && startsWith(iterator.key(), prefix) && events.size() < limit) {
                AgentRunEvent event = objectMapper.readValue(iterator.value(), AgentRunEvent.class);
                if (event.createdAt() > afterCreatedAt) {
                    events.add(event);
                }
                iterator.next();
            }
            return events;
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to read persisted agent run events " + runId, ex);
        }
    }

    private List<AgentRunStep> indexedSteps(String runId, int afterStep, int limit) {
        if (db == null || runId == null || runId.isBlank() || limit <= 0) {
            return List.of();
        }
        String prefix = stepPrefix(runId);
        List<AgentRunStep> steps = new ArrayList<>();
        try (RocksIterator iterator = db.newIterator()) {
            seekToPrefix(iterator, prefix);
            while (iterator.isValid() && startsWith(iterator.key(), prefix) && steps.size() < limit) {
                AgentRunStep step = objectMapper.readValue(iterator.value(), AgentRunStep.class);
                if (step.step() > afterStep) {
                    steps.add(step);
                }
                iterator.next();
            }
            return steps;
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to read persisted agent run steps " + runId, ex);
        }
    }

    private List<AgentObservation> indexedObservations(String runId, int offset, int limit) {
        if (db == null || runId == null || runId.isBlank() || limit <= 0) {
            return List.of();
        }
        int safeOffset = Math.max(offset, 0);
        String prefix = observationPrefix(runId);
        List<AgentObservation> observations = new ArrayList<>();
        int skipped = 0;
        try (RocksIterator iterator = db.newIterator()) {
            seekToPrefix(iterator, prefix);
            while (iterator.isValid() && startsWith(iterator.key(), prefix) && observations.size() < limit) {
                AgentObservation original = objectMapper.readValue(iterator.value(), AgentObservation.class);
                AgentObservation observation = externalizeObservation(runId, original);
                if (observation != original) {
                    db.put(iterator.key(), objectMapper.writeValueAsBytes(observation));
                    log.info("Migrated legacy inline agent evidence to external store runId={}", runId);
                }
                if (skipped++ >= safeOffset) {
                    observations.add(observation);
                }
                iterator.next();
            }
        } catch (IOException | RocksDBException ex) {
            throw new IllegalStateException("Failed to read persisted agent run observations " + runId, ex);
        }
        return observations;
    }

    private AgentRun serializableRun(AgentRun run) {
        return AgentRun.builder()
            .runId(run.runId())
            .status(run.status())
            .request(serializableRequest(run.request()))
            .result(serializableResult(run.result()))
            .steps(List.of())
            .observations(List.of())
            .events(List.of())
            .metadata(compactMap(run.metadata()))
            .startedAt(run.startedAt())
            .finishedAt(run.finishedAt())
            .errorMessage(run.errorMessage())
            .build();
    }

    private AgentRunResult externalizeResult(String runId, AgentRunResult result) {
        if (result == null || result.observations().isEmpty()) {
            return result;
        }
        List<AgentObservation> observations = result.observations().stream()
            .map(observation -> externalizeObservation(runId, observation))
            .toList();
        return AgentRunResult.builder()
            .runId(result.runId())
            .status(result.status())
            .answer(result.answer())
            .stopReason(result.stopReason())
            .confirmationRequired(result.confirmationRequired())
            .errorMessage(result.errorMessage())
            .steps(result.steps())
            .observations(observations)
            .events(result.events())
            .toolTraces(result.toolTraces())
            .metadata(result.metadata())
            .build();
    }

    private AgentObservation externalizeObservation(String runId, AgentObservation observation) {
        if (observation == null
            || !properties.isEvidenceExternalizationEnabled()
            || !evidenceStore.isEnabled()
            || observation.metadata() == null
            || !observation.metadata().containsKey("stepOutput")
            || observation.metadata().containsKey("stepOutputDocumentId")) {
            return observation;
        }
        Object configuredEvidenceId = observation.metadata().get("evidenceId");
        AgentObservation existing = existingExternalObservation(
            runId,
            configuredEvidenceId == null ? null : String.valueOf(configuredEvidenceId)
        );
        if (existing != null) {
            return existing;
        }
        Object stepOutput = observation.metadata().get("stepOutput");
        try {
            String json = objectMapper.writeValueAsString(stepOutput);
            int payloadBytes = json.getBytes(StandardCharsets.UTF_8).length;
            if (payloadBytes <= properties.evidenceExternalizationThresholdBytes()) {
                return observation;
            }
            String evidenceId = firstText(configuredEvidenceId == null ? null : String.valueOf(configuredEvidenceId),
                observation.source() + ":" + documentId(json));
            AgentObservation matchingObservation = existingExternalObservation(runId, evidenceId);
            if (matchingObservation != null) {
                return matchingObservation;
            }
            String documentId = evidenceDocumentId(runId, evidenceId);
            AgentRun run = find(runId).orElse(null);
            String tenantId = run == null || run.request() == null
                ? "default"
                : firstText(run.request().getTenantId(), "default");
            evidenceStore.put(documentId, tenantId, runId, evidenceId, json);
            Map<String, Object> metadata = new LinkedHashMap<>(observation.metadata());
            metadata.remove("stepOutput");
            metadata.put("stepOutputExternal", true);
            metadata.put("stepOutputDocumentId", documentId);
            metadata.put("stepOutputEvidenceId", evidenceId);
            metadata.put("stepOutputEncoding", "json");
            metadata.put("stepOutputBytes", payloadBytes);
            metadata.put("stepOutputPreview", ToolLogSummarizer.summarize(stepOutput, 4_000));
            log.info("Externalized agent evidence runId={} evidenceId={} documentId={} payloadBytes={}",
                runId, evidenceId, documentId, payloadBytes);
            return AgentObservation.builder()
                .type(observation.type())
                .source(observation.source())
                .content(observation.content())
                .metadata(metadata)
                .build();
        } catch (RuntimeException | JsonProcessingException ex) {
            log.warn("Failed to externalize agent evidence; keeping inline payload. runId={} source={} error={}",
                runId, observation.source(), ex.getMessage());
            return observation;
        }
    }

    private AgentObservation existingExternalObservation(String runId, String evidenceId) {
        if (evidenceId == null || evidenceId.isBlank()) {
            return null;
        }
        AgentRun run = find(runId).orElse(null);
        if (run == null) {
            return null;
        }
        return run.observations().stream()
            .filter(item -> evidenceId.equals(String.valueOf(item.metadata().get("stepOutputEvidenceId"))))
            .findFirst()
            .orElse(null);
    }

    private Map<String, Object> externalizeDagEvidence(String tenantId,
                                                       String runId,
                                                       Map<String, Object> dag) {
        if (dag == null
            || dag.isEmpty()
            || !properties.isEvidenceExternalizationEnabled()
            || !evidenceStore.isEnabled()
            || !(dag.get("nodes") instanceof List<?> nodes)) {
            return dag;
        }
        Map<String, Object> projectedDag = new LinkedHashMap<>(dag);
        List<Object> projectedNodes = new ArrayList<>(nodes.size());
        for (Object item : nodes) {
            if (!(item instanceof Map<?, ?> node) || !node.containsKey("output")) {
                projectedNodes.add(item);
                continue;
            }
            Map<String, Object> projectedNode = stringKeyMap(node);
            Object output = projectedNode.get("output");
            try {
                String json = objectMapper.writeValueAsString(output);
                int payloadBytes = json.getBytes(StandardCharsets.UTF_8).length;
                if (payloadBytes <= properties.evidenceExternalizationThresholdBytes()) {
                    projectedNodes.add(projectedNode);
                    continue;
                }
                String stepId = String.valueOf(projectedNode.getOrDefault("stepId", "unknown"));
                EvidenceReference existing = observationEvidenceReference(runId, stepId);
                String evidenceId = existing == null
                    ? "run:" + runId + ":step:" + stepId
                    : existing.evidenceId();
                String documentId = existing == null
                    ? evidenceDocumentId(runId, evidenceId)
                    : existing.documentId();
                if (existing == null) {
                    evidenceStore.put(documentId, firstText(tenantId, "default"), runId, evidenceId, json);
                }
                projectedNode.remove("output");
                projectedNode.put("outputExternal", true);
                projectedNode.put("outputDocumentId", documentId);
                projectedNode.put("outputEvidenceId", evidenceId);
                projectedNode.put("outputBytes", payloadBytes);
                projectedNode.put("outputPreview", ToolLogSummarizer.summarize(output, 4_000));
                projectedNodes.add(projectedNode);
            } catch (RuntimeException | JsonProcessingException ex) {
                log.warn("Failed to externalize DAG evidence; keeping inline output. runId={} stepId={} error={}",
                    runId, projectedNode.get("stepId"), ex.getMessage());
                projectedNodes.add(projectedNode);
            }
        }
        projectedDag.put("nodes", projectedNodes);
        return projectedDag;
    }

    private EvidenceReference observationEvidenceReference(String runId, String stepId) {
        AgentRun run = find(runId).orElse(null);
        if (run == null) {
            return null;
        }
        for (AgentObservation observation : run.observations()) {
            Map<String, Object> metadata = observation.metadata();
            if (!stepId.equals(String.valueOf(metadata.get("interpretationPlanStepId")))) {
                continue;
            }
            Object documentId = metadata.get("stepOutputDocumentId");
            Object evidenceId = metadata.get("stepOutputEvidenceId");
            if (documentId != null && evidenceId != null) {
                return new EvidenceReference(String.valueOf(documentId), String.valueOf(evidenceId));
            }
        }
        return null;
    }

    private Map<String, Object> stringKeyMap(Map<?, ?> values) {
        Map<String, Object> result = new LinkedHashMap<>();
        values.forEach((key, value) -> {
            if (key != null) {
                result.put(String.valueOf(key), value);
            }
        });
        return result;
    }

    private void deleteExternalEvidence(String runId) {
        if (!evidenceStore.isEnabled()) {
            return;
        }
        String prefix = observationPrefix(runId);
        try (RocksIterator iterator = db.newIterator()) {
            seekToPrefix(iterator, prefix);
            while (iterator.isValid() && startsWith(iterator.key(), prefix)) {
                try {
                    AgentObservation observation = objectMapper.readValue(iterator.value(), AgentObservation.class);
                    Object documentId = observation.metadata().get("stepOutputDocumentId");
                    if (documentId != null && !String.valueOf(documentId).isBlank()) {
                        evidenceStore.delete(String.valueOf(documentId));
                    }
                } catch (IOException | RuntimeException ex) {
                    log.warn("Failed to delete external agent evidence runId={} key={} error={}",
                        runId, new String(iterator.key(), StandardCharsets.UTF_8), ex.getMessage());
                }
                iterator.next();
            }
        }
    }

    private String evidenceDocumentId(String runId, String evidenceId) {
        return "agent-evidence-" + documentId("agent-evidence:" + runId + ":" + evidenceId);
    }

    private String documentId(String value) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes(value));
            return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    private AgentEvidenceStore disabledEvidenceStore() {
        return new AgentEvidenceStore() {
            @Override
            public boolean isEnabled() {
                return false;
            }

            @Override
            public void put(String documentId, String tenantId, String runId, String evidenceId, String json) {
            }

            @Override
            public Optional<String> get(String documentId) {
                return Optional.empty();
            }

            @Override
            public void delete(String documentId) {
            }
        };
    }

    private record EvidenceReference(String documentId, String evidenceId) {
    }

    private AgentRunResult serializableResult(AgentRunResult result) {
        if (result == null) {
            return null;
        }
        return AgentRunResult.builder()
            .runId(result.runId())
            .status(result.status())
            .answer(result.answer())
            .stopReason(result.stopReason())
            .confirmationRequired(result.confirmationRequired())
            .errorMessage(result.errorMessage())
            .steps(List.of())
            .observations(List.of())
            .events(List.of())
            .toolTraces(List.of())
            .metadata(compactMap(result.metadata()))
            .build();
    }

    private Map<String, Object> compactMap(Map<String, Object> values) {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }
        Object summarized = ToolLogSummarizer.summarize(values, 32_000);
        if (!(summarized instanceof Map<?, ?> map)) {
            return Map.of("summary", safeValue(summarized));
        }
        Map<String, Object> compact = new LinkedHashMap<>();
        map.forEach((key, value) -> {
            if (key != null && !AGENT_CANCELLATION_ATTRIBUTE.equals(String.valueOf(key))) {
                compact.put(String.valueOf(key), safeValue(value));
            }
        });
        return compact;
    }

    private AgentRunRequest serializableRequest(AgentRunRequest request) {
        if (request == null) {
            return null;
        }
        return AgentRunRequest.builder()
            .runId(request.getRunId())
            .query(request.getQuery())
            .tenantId(request.getTenantId())
            .availableTools(request.getAvailableTools())
            .systemPrompt(request.getSystemPrompt())
            .modelName(request.getModelName())
            .boundDocumentIds(request.getBoundDocumentIds())
            .boundDocumentTags(request.getBoundDocumentTags())
            .skillId(request.getSkillId())
            .requestId(request.getRequestId())
            .conversationId(request.getConversationId())
            .userId(request.getUserId())
            .webSearchResultLimit(request.getWebSearchResultLimit())
            .requiredToolNames(request.getRequiredToolNames())
            .requireBoundToolCall(request.isRequireBoundToolCall())
            .maxSteps(request.getMaxSteps())
            .maxToolCalls(request.getMaxToolCalls())
            .timeoutMs(request.getTimeoutMs())
            .attributes(compactMap(request.getAttributes()))
            .build();
    }

    private Map<String, Object> safeMap(Map<String, Object> values) {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> safeValues = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            if (entry.getKey() == null || AGENT_CANCELLATION_ATTRIBUTE.equals(entry.getKey())) {
                continue;
            }
            safeValues.put(entry.getKey(), safeValue(entry.getValue()));
        }
        return safeValues;
    }

    @SuppressWarnings("unchecked")
    private Object safeValue(Object value) {
        if (value == null
            || value instanceof String
            || value instanceof Number
            || value instanceof Boolean) {
            return value;
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> nested = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() != null) {
                    nested.put(String.valueOf(entry.getKey()), safeValue(entry.getValue()));
                }
            }
            return nested;
        }
        if (value instanceof Iterable<?> iterable) {
            List<Object> nested = new ArrayList<>();
            for (Object item : iterable) {
                nested.add(safeValue(item));
            }
            return nested;
        }
        if (value.getClass().isArray()) {
            return objectMapper.convertValue(value, List.class);
        }
        return String.valueOf(value);
    }

    private void ensureOpen() {
        if (db == null) {
            throw new IllegalStateException("RocksDB agent run store is not open");
        }
    }

    /**
     * Positions an iterator without RocksIterator.seek(byte[]).
     *
     * <p>Recent Windows rocksdbjni builds execute an AVX2-only native lower-bound
     * path from seek on pre-Haswell CPUs and terminate the JVM with
     * EXCEPTION_ILLEGAL_INSTRUCTION. Sequential positioning keeps the same ordered
     * prefix semantics and remains compatible with the supported x86_64 baseline.</p>
     */
    private void seekToPrefix(RocksIterator iterator, String prefix) {
        iterator.seekToFirst();
        byte[] expected = bytes(prefix);
        while (iterator.isValid()) {
            byte[] key = iterator.key();
            int comparison = compareUnsigned(key, expected);
            if (comparison >= 0 || startsWith(key, prefix)) {
                return;
            }
            iterator.next();
        }
    }

    private int compareUnsigned(byte[] left, byte[] right) {
        int length = Math.min(left.length, right.length);
        for (int index = 0; index < length; index++) {
            int comparison = Integer.compare(Byte.toUnsignedInt(left[index]), Byte.toUnsignedInt(right[index]));
            if (comparison != 0) {
                return comparison;
            }
        }
        return Integer.compare(left.length, right.length);
    }

    private boolean startsWith(byte[] key, String prefix) {
        return new String(key, StandardCharsets.UTF_8).startsWith(prefix);
    }

    private String runKey(String runId) {
        return RUN_KEY_PREFIX + runId;
    }

    private String eventPrefix(String runId) {
        return EVENT_KEY_PREFIX + runId + ":";
    }

    private String stepPrefix(String runId) {
        return STEP_KEY_PREFIX + runId + ":";
    }

    private String observationPrefix(String runId) {
        return OBSERVATION_KEY_PREFIX + runId + ":";
    }

    private String eventKey(AgentRunEvent event, int index) {
        return eventPrefix(event.runId())
            + String.format("%010d", index)
            + ":"
            + String.format("%020d", event.createdAt())
            + ":"
            + event.eventId();
    }

    private String stepKey(String runId, AgentRunStep step, int index) {
        return stepPrefix(runId)
            + String.format("%010d", step.step())
            + ":"
            + String.format("%010d", index);
    }

    private String observationKey(String runId, int index) {
        return observationPrefix(runId) + String.format("%010d", index);
    }

    private String planSnapshotKey(String tenantId, String taskId) {
        return PLAN_SNAPSHOT_KEY_PREFIX + firstText(tenantId, "default") + ":" + taskId;
    }

    private String planVersionPrefix(String tenantId, String taskId) {
        return PLAN_VERSION_KEY_PREFIX + firstText(tenantId, "default") + ":" + taskId + ":";
    }

    private String planVersionKey(InterpretationPlanRecord record) {
        int version = record.version() == null ? 0 : record.version();
        return planVersionPrefix(record.tenantId(), record.taskId()) + String.format("%010d", version);
    }

    private String planDagKey(String tenantId, String taskId) {
        return PLAN_DAG_KEY_PREFIX + firstText(tenantId, "default") + ":" + taskId;
    }

    private String planIndexKey(String tenantId, String planId) {
        return PLAN_INDEX_KEY_PREFIX + firstText(tenantId, "default") + ":" + planId;
    }

    private String firstText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
