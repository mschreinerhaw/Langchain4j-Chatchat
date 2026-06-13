package com.chatchat.agents.runtime;

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
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@ConditionalOnProperty(prefix = "chatchat.agent-runtime", name = "store-type", havingValue = "rocksdb", matchIfMissing = true)
public class RocksDbAgentRunStore extends InMemoryAgentRunStore {

    private static final String RUN_KEY_PREFIX = "run:";
    private static final String EVENT_KEY_PREFIX = "event:";
    private static final String STEP_KEY_PREFIX = "step:";
    private static final String OBSERVATION_KEY_PREFIX = "observation:";
    private static final String AGENT_CANCELLATION_ATTRIBUTE = "__agentCancellation";
    private static final String INTERRUPTED_BY_RESTART = "Agent run interrupted by runtime restart";

    private final AgentRuntimeProperties properties;
    private final ObjectMapper objectMapper;
    private Options options;
    private RocksDB db;

    public RocksDbAgentRunStore(AgentRunEventPublisher eventPublisher,
                                AgentRuntimeProperties properties,
                                ObjectMapper objectMapper) {
        super(eventPublisher, properties);
        this.properties = properties == null ? new AgentRuntimeProperties() : properties;
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
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
            pruneRuns();
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
        AgentRun run = super.complete(runId, result);
        persistRun(run);
        return run;
    }

    @Override
    public AgentRun cancel(String runId, String reason) {
        AgentRun run = super.cancel(runId, reason);
        persistRun(run);
        return run;
    }

    @Override
    public AgentRun fail(String runId, Throwable error) {
        AgentRun run = super.fail(runId, error);
        persistRun(run);
        return run;
    }

    @Override
    public AgentRun recordStep(String runId, AgentRunStep step) {
        AgentRun run = super.recordStep(runId, step);
        persistRun(run);
        return run;
    }

    @Override
    public AgentRun recordObservation(String runId, AgentObservation observation) {
        AgentRun run = super.recordObservation(runId, observation);
        persistRun(run);
        return run;
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
            iterator.seek(bytes(RUN_KEY_PREFIX));
            while (iterator.isValid() && startsWith(iterator.key(), RUN_KEY_PREFIX)) {
                try {
                    AgentRun run = objectMapper.readValue(iterator.value(), AgentRun.class);
                    if (run.runId() != null && !run.runId().isBlank()) {
                        run = recoverInterruptedRun(run);
                        runs.put(run.runId(), run);
                        if (AgentRunStatus.FAILED == run.status()
                            && INTERRUPTED_BY_RESTART.equals(run.errorMessage())) {
                            persistRun(run);
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
            List<byte[]> existingIndexKeys = persistedIndexKeys(run.runId());
            try (WriteBatch batch = new WriteBatch(); WriteOptions writeOptions = new WriteOptions()) {
                batch.put(bytes(runKey(run.runId())), objectMapper.writeValueAsBytes(serializableRun));
                for (byte[] key : existingIndexKeys) {
                    batch.delete(key);
                }
                persistEvents(batch, serializableRun);
                persistSteps(batch, serializableRun);
                persistObservations(batch, serializableRun);
                db.write(writeOptions, batch);
            }
        } catch (JsonProcessingException | RocksDBException ex) {
            throw new IllegalStateException("Failed to persist agent run " + run.runId(), ex);
        }
    }

    private void deletePersistedRun(String runId) {
        if (db == null || runId == null || runId.isBlank()) {
            return;
        }
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
            iterator.seek(bytes(prefix));
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
            iterator.seek(bytes(prefix));
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
            iterator.seek(bytes(prefix));
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
            iterator.seek(bytes(prefix));
            while (iterator.isValid() && startsWith(iterator.key(), prefix) && observations.size() < limit) {
                if (skipped++ >= safeOffset) {
                    observations.add(objectMapper.readValue(iterator.value(), AgentObservation.class));
                }
                iterator.next();
            }
            return observations;
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to read persisted agent run observations " + runId, ex);
        }
    }

    private AgentRun serializableRun(AgentRun run) {
        return AgentRun.builder()
            .runId(run.runId())
            .status(run.status())
            .request(serializableRequest(run.request()))
            .result(run.result())
            .steps(run.steps())
            .observations(run.observations())
            .events(run.events())
            .metadata(safeMap(run.metadata()))
            .startedAt(run.startedAt())
            .finishedAt(run.finishedAt())
            .errorMessage(run.errorMessage())
            .build();
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
            .attributes(safeMap(request.getAttributes()))
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

    private byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
