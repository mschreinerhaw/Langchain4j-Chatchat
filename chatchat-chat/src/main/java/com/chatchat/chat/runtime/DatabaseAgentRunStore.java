package com.chatchat.chat.runtime;

import com.chatchat.agents.runtime.AgentRunRequest;
import com.chatchat.agents.runtime.AgentRunResult;
import com.chatchat.agents.runtime.AgentRuntimeSnapshot;
import com.chatchat.agents.runtime.config.AgentRuntimeProperties;
import com.chatchat.agents.runtime.event.AgentRunEvent;
import com.chatchat.agents.runtime.event.AgentRunEventPublisher;
import com.chatchat.agents.runtime.observation.AgentObservation;
import com.chatchat.agents.runtime.plan.InterpretationPlan;
import com.chatchat.agents.runtime.plan.InterpretationPlanDagConverter;
import com.chatchat.agents.runtime.plan.persistence.InterpretationPlanRecord;
import com.chatchat.agents.runtime.plan.persistence.PlanStepCheckpoint;
import com.chatchat.agents.runtime.run.AgentRun;
import com.chatchat.agents.runtime.run.AgentRunQuery;
import com.chatchat.agents.runtime.run.AgentRunStep;
import com.chatchat.agents.runtime.store.InMemoryAgentRunStore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Shared relational persistence for runtime runs, plans and checkpoints.
 *
 * <p>The in-memory parent remains the state-transition implementation. Every mutation first
 * hydrates the latest database revision and then persists the resulting aggregate, which keeps
 * the existing runtime contract while making reads and restarts multi-instance safe.</p>
 */
@Component
@ConditionalOnProperty(prefix = "chatchat.agent-runtime", name = "store-type",
    havingValue = "database", matchIfMissing = true)
public class DatabaseAgentRunStore extends InMemoryAgentRunStore {

    private static final String CANCELLATION_ATTRIBUTE = "__agentCancellation";

    private final AgentRuntimeRunRepository runRepository;
    private final AgentRuntimePlanRepository planRepository;
    private final AgentRuntimeCheckpointRepository checkpointRepository;
    private final ObjectMapper objectMapper;
    private final AgentRuntimeProperties properties;
    private final InterpretationPlanDagConverter planDagConverter = new InterpretationPlanDagConverter();

    public DatabaseAgentRunStore(AgentRunEventPublisher eventPublisher,
                                 AgentRuntimeProperties properties,
                                 AgentRuntimeRunRepository runRepository,
                                 AgentRuntimePlanRepository planRepository,
                                 AgentRuntimeCheckpointRepository checkpointRepository,
                                 ObjectMapper objectMapper) {
        super(eventPublisher, properties);
        this.runRepository = runRepository;
        this.planRepository = planRepository;
        this.checkpointRepository = checkpointRepository;
        this.objectMapper = objectMapper.copy();
        this.properties = properties;
    }

    @Override
    @Transactional
    public AgentRun submit(AgentRunRequest request) {
        hydrateForUpdate(request == null ? null : firstText(request.getRunId(), request.getRequestId()));
        return persist(super.submit(request));
    }

    @Override
    @Transactional
    public AgentRun start(AgentRunRequest request) {
        hydrateForUpdate(request == null ? null : firstText(request.getRunId(), request.getRequestId()));
        return persist(super.start(request));
    }

    @Override
    @Transactional
    public AgentRun complete(String runId, AgentRunResult result) {
        hydrateForUpdate(runId);
        return persist(super.complete(runId, result));
    }

    @Override
    @Transactional
    public AgentRun cancel(String runId, String reason) {
        hydrateForUpdate(runId);
        return persist(super.cancel(runId, reason));
    }

    @Override
    @Transactional
    public AgentRun fail(String runId, Throwable error) {
        hydrateForUpdate(runId);
        return persist(super.fail(runId, error));
    }

    @Override
    @Transactional
    public AgentRun recordStep(String runId, AgentRunStep step) {
        hydrateForUpdate(runId);
        return persist(super.recordStep(runId, step));
    }

    @Override
    @Transactional
    public AgentRun recordObservation(String runId, AgentObservation observation) {
        hydrateForUpdate(runId);
        return persist(super.recordObservation(runId, observation));
    }

    @Override
    @Transactional
    public AgentRun recordEvent(String runId, AgentRunEvent event) {
        hydrateForUpdate(runId);
        return persist(super.recordEvent(runId, event));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AgentRun> find(String runId) {
        if (runId == null || runId.isBlank()) {
            return Optional.empty();
        }
        return runRepository.findById(runId).map(this::deserializeRun);
    }

    @Override
    @Transactional(readOnly = true)
    public List<AgentRun> list(AgentRunQuery query) {
        AgentRunQuery criteria = query == null ? AgentRunQuery.recent(50) : query;
        int fetchSize = Math.min(1_000, criteria.offset() + criteria.limit());
        return runRepository.search(
                criteria.status() == null ? "" : criteria.status().name(),
                firstText(criteria.tenantId(), ""),
                firstText(criteria.userId(), ""),
                firstText(criteria.conversationId(), ""),
                PageRequest.of(0, Math.max(1, fetchSize)))
            .getContent().stream()
            .skip(criteria.offset())
            .limit(criteria.limit())
            .map(this::deserializeRun)
            .toList();
    }

    @Override
    public List<AgentRunEvent> events(String runId) {
        return find(runId).map(AgentRun::events).orElseGet(List::of);
    }

    @Override
    public List<AgentRunEvent> events(String runId, long afterCreatedAt, int limit) {
        int safeLimit = limit <= 0 ? 100 : Math.min(limit, 500);
        return events(runId).stream()
            .filter(event -> event.createdAt() > afterCreatedAt)
            .limit(safeLimit)
            .toList();
    }

    @Override
    public List<AgentRunStep> steps(String runId) {
        return find(runId).map(AgentRun::steps).orElseGet(List::of);
    }

    @Override
    public List<AgentRunStep> steps(String runId, int afterStep, int limit) {
        int safeLimit = limit <= 0 ? 100 : Math.min(limit, 500);
        return steps(runId).stream().filter(step -> step.step() > afterStep).limit(safeLimit).toList();
    }

    @Override
    public List<AgentObservation> observations(String runId) {
        return find(runId).map(AgentRun::observations).orElseGet(List::of);
    }

    @Override
    public List<AgentObservation> observations(String runId, int offset, int limit) {
        int safeLimit = limit <= 0 ? 100 : Math.min(limit, 500);
        return observations(runId).stream().skip(Math.max(0, offset)).limit(safeLimit).toList();
    }

    @Override
    @Transactional
    public void savePlanStepCheckpoint(PlanStepCheckpoint checkpoint) {
        if (checkpoint == null || checkpoint.runId() == null || checkpoint.runId().isBlank()
            || checkpoint.stepId() == null || checkpoint.materializedResult() == null) {
            return;
        }
        AgentRuntimeCheckpointEntity entity = new AgentRuntimeCheckpointEntity();
        entity.setCheckpointId(checkpoint.runId() + ":" + checkpoint.stepId());
        entity.setRunId(checkpoint.runId());
        entity.setStepId(checkpoint.stepId());
        entity.setCheckpointJson(write(checkpoint));
        entity.setUpdatedAt(System.currentTimeMillis());
        checkpointRepository.save(entity);
    }

    @Override
    @Transactional(readOnly = true)
    public List<PlanStepCheckpoint> planStepCheckpoints(String runId) {
        if (runId == null || runId.isBlank()) {
            return List.of();
        }
        return checkpointRepository.findByRunIdOrderByStepIdAsc(runId).stream()
            .map(entity -> read(entity.getCheckpointJson(), PlanStepCheckpoint.class))
            .toList();
    }

    @Override
    @Transactional
    public void deletePlanStepCheckpoints(String runId) {
        if (runId != null && !runId.isBlank()) {
            checkpointRepository.deleteByRunId(runId);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public AgentRuntimeSnapshot snapshot() {
        List<AgentRun> all = runRepository.findAll().stream().map(this::deserializeRun).toList();
        return AgentRuntimeSnapshot.fromRuns(all);
    }

    @Override
    @Transactional
    public int cleanupExpiredRuns() {
        List<String> terminal = List.of("COMPLETED", "FAILED", "CANCELLED");
        LinkedHashSet<String> removals = new LinkedHashSet<>();
        long ttlMs = properties.terminalRunTtlMs();
        if (ttlMs > 0) {
            runRepository.findByStatusInAndFinishedAtLessThanEqualOrderByFinishedAtAsc(
                    terminal, System.currentTimeMillis() - ttlMs, PageRequest.of(0, properties.maxStoredRuns()))
                .forEach(entity -> removals.add(entity.getRunId()));
        }
        long remaining = Math.max(0L, runRepository.count() - removals.size());
        if (remaining > properties.maxStoredRuns()) {
            int overflow = Math.toIntExact(Math.min(Integer.MAX_VALUE, remaining - properties.maxStoredRuns()));
            runRepository.findByStatusInOrderByUpdatedAtAsc(terminal, PageRequest.of(0, overflow))
                .forEach(entity -> removals.add(entity.getRunId()));
        }
        for (String runId : removals) {
            checkpointRepository.deleteByRunId(runId);
            runRepository.deleteById(runId);
            runs.remove(runId);
        }
        return removals.size();
    }

    @Override
    @Transactional
    public InterpretationPlanRecord savePlan(String tenantId,
                                             String taskId,
                                             String planId,
                                             InterpretationPlan plan,
                                             String status,
                                             Map<String, Object> dagOverride) {
        String normalizedTenant = firstText(tenantId, "default");
        String normalizedTask = firstText(taskId, "unknown-task");
        InterpretationPlanRecord existing = getSnapshot(normalizedTenant, normalizedTask).orElse(null);
        int version = existing == null || existing.version() == null ? 1 : existing.version() + 1;
        long now = System.currentTimeMillis();
        Map<String, Object> dag = dagOverride == null || dagOverride.isEmpty()
            ? planDagConverter.convert(plan) : dagOverride;
        InterpretationPlanRecord record = new InterpretationPlanRecord(
            normalizedTenant,
            normalizedTask,
            firstText(planId, "plan-" + normalizedTask + "-v" + version),
            version,
            write(plan),
            write(dag),
            dag,
            firstText(status, "GENERATED"),
            existing == null || existing.createdAt() == null ? now : existing.createdAt(),
            now);
        saveVersion(record);
        return record;
    }

    @Override
    public InterpretationPlanRecord savePlan(String tenantId,
                                             String taskId,
                                             String planId,
                                             InterpretationPlan plan,
                                             String status) {
        return savePlan(tenantId, taskId, planId, plan, status, null);
    }

    @Override
    @Transactional
    public void saveSnapshot(InterpretationPlanRecord record) {
        saveVersion(record);
    }

    @Override
    @Transactional
    public void saveVersion(InterpretationPlanRecord record) {
        if (record == null || record.tenantId() == null || record.taskId() == null || record.version() == null) {
            return;
        }
        planRepository.save(toEntity(record));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<InterpretationPlanRecord> getSnapshot(String tenantId, String taskId) {
        return planRepository.findTopByTenantIdAndTaskIdOrderByVersionDesc(
            firstText(tenantId, "default"), firstText(taskId, "unknown-task")).map(this::toRecord);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<String> getDagJson(String tenantId, String taskId) {
        return getSnapshot(tenantId, taskId).map(InterpretationPlanRecord::dagJson);
    }

    @Override
    @Transactional(readOnly = true)
    public List<InterpretationPlanRecord> listVersions(String tenantId, String taskId) {
        return planRepository.findByTenantIdAndTaskIdOrderByVersionAsc(
                firstText(tenantId, "default"), firstText(taskId, "unknown-task")).stream()
            .map(this::toRecord)
            .toList();
    }

    private void hydrate(String runId) {
        if (runId == null || runId.isBlank()) {
            return;
        }
        runRepository.findById(runId).map(this::deserializeRun).ifPresent(run -> runs.put(runId, run));
    }

    private void hydrateForUpdate(String runId) {
        if (runId == null || runId.isBlank()) {
            return;
        }
        // Serialize aggregate mutations across local analysis workers and application nodes.
        // Without this lock, two workers can hydrate the same @Version revision and the loser
        // fails at commit with ObjectOptimisticLockingFailureException.
        runRepository.findByIdForUpdate(runId)
            .map(this::deserializeRun)
            .ifPresent(run -> runs.put(runId, run));
    }

    private AgentRun persist(AgentRun run) {
        if (run == null) {
            return null;
        }
        AgentRuntimeRunEntity entity = runRepository.findById(run.runId()).orElseGet(AgentRuntimeRunEntity::new);
        AgentRun serializable = serializableRun(run);
        AgentRunRequest request = serializable.request();
        entity.setRunId(run.runId());
        entity.setExecutionId(requestAttribute(request, "__executionId", run.runId()));
        entity.setAttemptNumber(integerAttribute(request, "__executionAttemptNumber", 1));
        entity.setTenantId(request == null ? null : request.getTenantId());
        entity.setUserId(request == null ? null : request.getUserId());
        entity.setConversationId(request == null ? null : request.getConversationId());
        entity.setStatus(run.status().name());
        entity.setRunJson(write(serializable));
        entity.setStartedAt(run.startedAt());
        entity.setFinishedAt(run.finishedAt());
        entity.setUpdatedAt(updatedAt(run));
        runRepository.save(entity);
        runs.put(run.runId(), run);
        return run;
    }

    private AgentRun deserializeRun(AgentRuntimeRunEntity entity) {
        AgentRun run = read(entity.getRunJson(), AgentRun.class);
        runs.put(run.runId(), run);
        return run;
    }

    private AgentRun serializableRun(AgentRun run) {
        return AgentRun.builder()
            .runId(run.runId())
            .status(run.status())
            .request(serializableRequest(run.request()))
            .result(serializableResult(run.result()))
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
            .metadata(safeMap(result.metadata()))
            .build();
    }

    private Map<String, Object> safeMap(Map<String, Object> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> safe = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            if (key != null && !CANCELLATION_ATTRIBUTE.equals(key)) {
                safe.put(key, safeValue(value));
            }
        });
        return safe;
    }

    private Object safeValue(Object value) {
        if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean) {
            return value;
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> safe = new LinkedHashMap<>();
            map.forEach((key, nested) -> {
                if (key != null) safe.put(String.valueOf(key), safeValue(nested));
            });
            return safe;
        }
        if (value instanceof Iterable<?> iterable) {
            List<Object> safe = new ArrayList<>();
            iterable.forEach(item -> safe.add(safeValue(item)));
            return safe;
        }
        if (value.getClass().isArray()) {
            int length = java.lang.reflect.Array.getLength(value);
            List<Object> safe = new ArrayList<>(length);
            for (int index = 0; index < length; index++) safe.add(safeValue(java.lang.reflect.Array.get(value, index)));
            return safe;
        }
        return String.valueOf(value);
    }

    private AgentRuntimePlanEntity toEntity(InterpretationPlanRecord record) {
        AgentRuntimePlanEntity entity = new AgentRuntimePlanEntity();
        entity.setRecordId(record.tenantId() + ":" + record.taskId() + ":" + record.version());
        entity.setTenantId(record.tenantId());
        entity.setTaskId(record.taskId());
        entity.setPlanId(record.planId());
        entity.setVersion(record.version());
        entity.setPlanJson(record.planJson());
        entity.setDagJson(firstText(record.dagJson(), "{}"));
        entity.setStatus(firstText(record.status(), "GENERATED"));
        entity.setCreatedAt(record.createdAt() == null ? System.currentTimeMillis() : record.createdAt());
        entity.setUpdatedAt(record.updatedAt() == null ? System.currentTimeMillis() : record.updatedAt());
        return entity;
    }

    @SuppressWarnings("unchecked")
    private InterpretationPlanRecord toRecord(AgentRuntimePlanEntity entity) {
        Map<String, Object> dag = read(entity.getDagJson(), Map.class);
        return new InterpretationPlanRecord(entity.getTenantId(), entity.getTaskId(), entity.getPlanId(),
            entity.getVersion(), entity.getPlanJson(), entity.getDagJson(), dag, entity.getStatus(),
            entity.getCreatedAt(), entity.getUpdatedAt());
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Failed to serialize agent runtime state", error);
        }
    }

    private <T> T read(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Failed to deserialize agent runtime state", error);
        }
    }

    private long updatedAt(AgentRun run) {
        if (run.finishedAt() != null) return run.finishedAt();
        if (!run.events().isEmpty()) return run.events().get(run.events().size() - 1).createdAt();
        return run.startedAt();
    }

    private String requestAttribute(AgentRunRequest request, String name, String fallback) {
        if (request == null || request.getAttributes() == null) return fallback;
        Object value = request.getAttributes().get(name);
        return value == null || String.valueOf(value).isBlank() ? fallback : String.valueOf(value);
    }

    private Integer integerAttribute(AgentRunRequest request, String name, int fallback) {
        String value = requestAttribute(request, name, String.valueOf(fallback));
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private String firstText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
