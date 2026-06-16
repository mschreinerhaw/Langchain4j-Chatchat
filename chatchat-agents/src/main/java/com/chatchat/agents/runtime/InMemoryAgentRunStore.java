package com.chatchat.agents.runtime;

import com.chatchat.agents.runtime.plan.InterpretationPlan;
import com.chatchat.agents.runtime.plan.InterpretationPlanDagConverter;
import com.chatchat.agents.runtime.plan.InterpretationPlanRecord;
import com.chatchat.agents.runtime.plan.InterpretationPlanStore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
@ConditionalOnProperty(prefix = "chatchat.agent-runtime", name = "store-type", havingValue = "memory")
@Slf4j
public class InMemoryAgentRunStore implements AgentRunStore, InterpretationPlanStore {

    protected final Map<String, AgentRun> runs = new ConcurrentHashMap<>();
    protected final Map<String, InterpretationPlanRecord> planSnapshots = new ConcurrentHashMap<>();
    protected final Map<String, List<InterpretationPlanRecord>> planVersions = new ConcurrentHashMap<>();
    protected final Map<String, String> planDags = new ConcurrentHashMap<>();
    protected final Map<String, String> planIdIndex = new ConcurrentHashMap<>();
    private final AgentRunEventPublisher eventPublisher;
    private final AgentRuntimeProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final InterpretationPlanDagConverter planDagConverter = new InterpretationPlanDagConverter();

    public InMemoryAgentRunStore() {
        this(new NoopAgentRunEventPublisher(), new AgentRuntimeProperties());
    }

    public InMemoryAgentRunStore(AgentRunEventPublisher eventPublisher) {
        this(eventPublisher, new AgentRuntimeProperties());
    }

    @Autowired
    public InMemoryAgentRunStore(AgentRunEventPublisher eventPublisher, AgentRuntimeProperties properties) {
        this.eventPublisher = eventPublisher == null ? new NoopAgentRunEventPublisher() : eventPublisher;
        this.properties = properties == null ? new AgentRuntimeProperties() : properties;
    }

    @Override
    public AgentRun submit(AgentRunRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Agent run request is required");
        }
        String runId = firstText(request.getRunId(), firstText(request.getRequestId(), UUID.randomUUID().toString()));
        request.setRunId(runId);
        long submittedAt = System.currentTimeMillis();
        AgentRun run = runs.compute(runId, (key, current) -> {
            if (current != null) {
                return current;
            }
            AgentRunEvent submitted = AgentRunEvent.of(runId, AgentRunEventType.RUN_SUBMITTED,
                "Agent run submitted", Map.of("requestId", firstText(request.getRequestId(), runId)));
            publishEvent(submitted);
            return AgentRun.builder()
                .runId(runId)
                .status(AgentRunStatus.PENDING)
                .request(request)
                .events(List.of(submitted))
                .metadata(Map.of("requestId", firstText(request.getRequestId(), runId)))
                .startedAt(submittedAt)
                .build();
        });
        pruneRuns();
        return run;
    }

    @Override
    public AgentRun start(AgentRunRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Agent run request is required");
        }
        String runId = firstText(request.getRunId(), firstText(request.getRequestId(), UUID.randomUUID().toString()));
        request.setRunId(runId);
        long startedAt = System.currentTimeMillis();
        AgentRun run = runs.compute(runId, (key, current) -> {
            AgentRun base = current == null ? missingRun(key) : current;
            if (isTerminal(base.status())) {
                return base;
            }
            List<AgentRunEvent> events = new ArrayList<>(base.events());
            AgentRunEvent started = AgentRunEvent.of(runId, AgentRunEventType.RUN_STARTED,
                "Agent run started", Map.of("requestId", firstText(request.getRequestId(), runId)));
            events.add(started);
            publishEvent(started);
            Map<String, Object> metadata = new LinkedHashMap<>(base.metadata());
            metadata.put("requestId", firstText(request.getRequestId(), runId));
            return AgentRun.builder()
                .runId(runId)
                .status(AgentRunStatus.RUNNING)
                .request(request)
                .result(base.result())
                .steps(base.steps())
                .observations(base.observations())
                .events(events)
                .metadata(metadata)
                .startedAt(base.startedAt() <= 0 ? startedAt : base.startedAt())
                .build();
        });
        pruneRuns();
        return run;
    }

    @Override
    public AgentRun complete(String runId, AgentRunResult result) {
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("Agent run id is required");
        }
        AgentRun run = runs.compute(runId, (key, current) -> {
            AgentRun base = current == null ? missingRun(key) : current;
            if (isTerminal(base.status())) {
                return base;
            }
            List<AgentRunEvent> events = new ArrayList<>(base.events());
            List<AgentRunStep> resultSteps = result == null ? List.of() : result.steps();
            List<AgentObservation> resultObservations = result == null ? List.of() : result.observations();
            List<AgentRunStep> newSteps = newSteps(base.steps(), resultSteps);
            List<AgentObservation> newObservations = newObservations(base.observations(), resultObservations);
            List<AgentRunStep> steps = mergeSteps(base.steps(), resultSteps);
            List<AgentObservation> observations = mergeObservations(base.observations(), resultObservations);
            for (AgentRunStep step : newSteps) {
                AgentRunEvent stepRecorded = AgentRunEvent.of(key, AgentRunEventType.STEP_RECORDED,
                    "Agent step recorded", stepPayload(step));
                events.add(stepRecorded);
                publishEvent(stepRecorded);
            }
            for (AgentObservation observation : newObservations) {
                AgentRunEvent observationRecorded = AgentRunEvent.of(key, AgentRunEventType.OBSERVATION_RECORDED,
                    "Agent observation recorded", observationPayload(observation));
                events.add(observationRecorded);
                publishEvent(observationRecorded);
            }
            AgentRunStatus status = result != null && result.confirmationRequired()
                ? AgentRunStatus.WAITING_CONFIRMATION
                : AgentRunStatus.COMPLETED;
            AgentRunEvent finished = AgentRunEvent.of(key,
                status == AgentRunStatus.WAITING_CONFIRMATION
                    ? AgentRunEventType.CONFIRMATION_REQUIRED
                    : AgentRunEventType.RUN_COMPLETED,
                status == AgentRunStatus.WAITING_CONFIRMATION ? "Agent run is waiting for confirmation" : "Agent run completed",
                Map.of("stopReason", result == null ? "" : firstText(result.stopReason(), "")));
            events.add(finished);
            publishEvent(finished);
            return AgentRun.builder()
                .runId(key)
                .status(status)
                .request(base.request())
                .result(result)
                .steps(steps)
                .observations(observations)
                .events(events)
                .metadata(result == null ? base.metadata() : result.metadata())
                .startedAt(base.startedAt())
                .finishedAt(System.currentTimeMillis())
                .build();
        });
        pruneRuns();
        return run;
    }

    @Override
    public AgentRun recordStep(String runId, AgentRunStep step) {
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("Agent run id is required");
        }
        if (step == null) {
            throw new IllegalArgumentException("Agent run step is required");
        }
        AgentRun run = runs.compute(runId, (key, current) -> {
            AgentRun base = current == null ? missingRun(key) : current;
            if (isTerminal(base.status()) || containsStep(base.steps(), step)) {
                return base;
            }
            List<AgentRunStep> steps = new ArrayList<>(base.steps());
            steps.add(step);
            List<AgentRunEvent> events = new ArrayList<>(base.events());
            AgentRunEvent recorded = AgentRunEvent.of(key, AgentRunEventType.STEP_RECORDED,
                "Agent step recorded", stepPayload(step));
            events.add(recorded);
            publishEvent(recorded);
            return AgentRun.builder()
                .runId(key)
                .status(base.status())
                .request(base.request())
                .result(base.result())
                .steps(steps)
                .observations(base.observations())
                .events(events)
                .metadata(base.metadata())
                .startedAt(base.startedAt())
                .finishedAt(base.finishedAt())
                .errorMessage(base.errorMessage())
                .build();
        });
        pruneRuns();
        return run;
    }

    @Override
    public AgentRun recordObservation(String runId, AgentObservation observation) {
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("Agent run id is required");
        }
        if (observation == null) {
            throw new IllegalArgumentException("Agent observation is required");
        }
        AgentRun run = runs.compute(runId, (key, current) -> {
            AgentRun base = current == null ? missingRun(key) : current;
            if (isTerminal(base.status()) || base.observations().contains(observation)) {
                return base;
            }
            List<AgentObservation> observations = new ArrayList<>(base.observations());
            observations.add(observation);
            List<AgentRunEvent> events = new ArrayList<>(base.events());
            AgentRunEvent recorded = AgentRunEvent.of(key, AgentRunEventType.OBSERVATION_RECORDED,
                "Agent observation recorded", observationPayload(observation));
            events.add(recorded);
            publishEvent(recorded);
            return AgentRun.builder()
                .runId(key)
                .status(base.status())
                .request(base.request())
                .result(base.result())
                .steps(base.steps())
                .observations(observations)
                .events(events)
                .metadata(base.metadata())
                .startedAt(base.startedAt())
                .finishedAt(base.finishedAt())
                .errorMessage(base.errorMessage())
                .build();
        });
        pruneRuns();
        return run;
    }

    @Override
    public AgentRun cancel(String runId, String reason) {
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("Agent run id is required");
        }
        AgentRun run = runs.compute(runId, (key, current) -> {
            AgentRun base = current == null ? missingRun(key) : current;
            if (isTerminal(base.status())) {
                return base;
            }
            String message = firstText(reason, "Agent run cancelled");
            List<AgentRunEvent> events = new ArrayList<>(base.events());
            AgentRunEvent cancelled = AgentRunEvent.of(key, AgentRunEventType.RUN_CANCELLED, message, Map.of("reason", message));
            events.add(cancelled);
            publishEvent(cancelled);
            Map<String, Object> metadata = new LinkedHashMap<>(base.metadata());
            metadata.put("stopReason", "cancelled");
            metadata.put("cancellationReason", message);
            return AgentRun.builder()
                .runId(key)
                .status(AgentRunStatus.CANCELLED)
                .request(base.request())
                .result(base.result())
                .steps(base.steps())
                .observations(base.observations())
                .events(events)
                .metadata(metadata)
                .startedAt(base.startedAt())
                .finishedAt(System.currentTimeMillis())
                .errorMessage(message)
                .build();
        });
        pruneRuns();
        return run;
    }

    @Override
    public AgentRun fail(String runId, Throwable error) {
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("Agent run id is required");
        }
        AgentRun run = runs.compute(runId, (key, current) -> {
            AgentRun base = current == null ? missingRun(key) : current;
            if (isTerminal(base.status())) {
                return base;
            }
            String message = error == null ? "Agent run failed" : firstText(error.getMessage(), error.getClass().getSimpleName());
            List<AgentRunEvent> events = new ArrayList<>(base.events());
            AgentRunEvent failed = AgentRunEvent.of(key, AgentRunEventType.RUN_FAILED, message, Map.of("errorMessage", message));
            events.add(failed);
            publishEvent(failed);
            Map<String, Object> metadata = new LinkedHashMap<>(base.metadata());
            metadata.put("errorMessage", message);
            return AgentRun.builder()
                .runId(key)
                .status(AgentRunStatus.FAILED)
                .request(base.request())
                .result(base.result())
                .steps(base.steps())
                .observations(base.observations())
                .events(events)
                .metadata(metadata)
                .startedAt(base.startedAt())
                .finishedAt(System.currentTimeMillis())
                .errorMessage(message)
                .build();
        });
        pruneRuns();
        return run;
    }

    @Override
    public Optional<AgentRun> find(String runId) {
        if (runId == null || runId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(runs.get(runId));
    }

    @Override
    public List<AgentRun> list(AgentRunQuery query) {
        AgentRunQuery criteria = query == null ? AgentRunQuery.recent(50) : query;
        return runs.values().stream()
            .filter(run -> matches(criteria, run))
            .sorted(Comparator.comparingLong(this::updatedAt).reversed())
            .skip(criteria.offset())
            .limit(criteria.limit())
            .toList();
    }

    @Override
    public List<AgentRunEvent> events(String runId) {
        return find(runId)
            .map(AgentRun::events)
            .orElseGet(List::of);
    }

    @Override
    public List<AgentRunEvent> events(String runId, long afterCreatedAt, int limit) {
        int safeLimit = recordLimit(limit);
        return events(runId).stream()
            .filter(event -> event.createdAt() > afterCreatedAt)
            .limit(safeLimit)
            .toList();
    }

    @Override
    public List<AgentRunStep> steps(String runId) {
        return find(runId)
            .map(AgentRun::steps)
            .orElseGet(List::of);
    }

    @Override
    public List<AgentRunStep> steps(String runId, int afterStep, int limit) {
        int safeLimit = recordLimit(limit);
        return steps(runId).stream()
            .filter(step -> step.step() > afterStep)
            .limit(safeLimit)
            .toList();
    }

    @Override
    public List<AgentObservation> observations(String runId) {
        return find(runId)
            .map(AgentRun::observations)
            .orElseGet(List::of);
    }

    @Override
    public List<AgentObservation> observations(String runId, int offset, int limit) {
        int safeOffset = Math.max(offset, 0);
        int safeLimit = recordLimit(limit);
        return observations(runId).stream()
            .skip(safeOffset)
            .limit(safeLimit)
            .toList();
    }

    @Override
    public AgentRuntimeSnapshot snapshot() {
        return AgentRuntimeSnapshot.fromRuns(runs.values());
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
    public InterpretationPlanRecord savePlan(String tenantId,
                                             String taskId,
                                             String planId,
                                             InterpretationPlan plan,
                                             String status,
                                             Map<String, Object> dagOverride) {
        String normalizedTenant = firstText(tenantId, "default");
        String normalizedTaskId = firstText(taskId, "unknown-task");
        int version = nextPlanVersion(normalizedTenant, normalizedTaskId);
        String normalizedPlanId = firstText(planId, "plan-" + normalizedTaskId + "-v" + version);
        long now = System.currentTimeMillis();
        Map<String, Object> dag = dagOverride == null || dagOverride.isEmpty() ? planDagConverter.convert(plan) : dagOverride;
        try {
            InterpretationPlanRecord existing = getSnapshot(normalizedTenant, normalizedTaskId).orElse(null);
            InterpretationPlanRecord record = new InterpretationPlanRecord(
                normalizedTenant,
                normalizedTaskId,
                normalizedPlanId,
                version,
                objectMapper.writeValueAsString(plan),
                objectMapper.writeValueAsString(dag),
                dag,
                firstText(status, "GENERATED"),
                existing == null || existing.createdAt() == null ? now : existing.createdAt(),
                now
            );
            saveSnapshot(record);
            saveVersion(record);
            saveDag(record);
            savePlanIndex(record);
            return record;
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize interpretation plan", ex);
        }
    }

    @Override
    public void saveSnapshot(InterpretationPlanRecord record) {
        if (record == null || record.tenantId() == null || record.taskId() == null) {
            return;
        }
        planSnapshots.put(planTaskKey(record.tenantId(), record.taskId()), record);
    }

    @Override
    public void saveVersion(InterpretationPlanRecord record) {
        if (record == null || record.tenantId() == null || record.taskId() == null) {
            return;
        }
        planVersions.compute(planTaskKey(record.tenantId(), record.taskId()), (key, current) -> {
            List<InterpretationPlanRecord> versions = current == null ? new ArrayList<>() : new ArrayList<>(current);
            versions.add(record);
            versions.sort(Comparator.comparing(item -> Optional.ofNullable(item.version()).orElse(0)));
            return versions;
        });
    }

    @Override
    public Optional<InterpretationPlanRecord> getSnapshot(String tenantId, String taskId) {
        return Optional.ofNullable(planSnapshots.get(planTaskKey(firstText(tenantId, "default"), taskId)));
    }

    @Override
    public Optional<String> getDagJson(String tenantId, String taskId) {
        return Optional.ofNullable(planDags.get(planTaskKey(firstText(tenantId, "default"), taskId)));
    }

    @Override
    public List<InterpretationPlanRecord> listVersions(String tenantId, String taskId) {
        return planVersions.getOrDefault(planTaskKey(firstText(tenantId, "default"), taskId), List.of());
    }

    protected void saveDag(InterpretationPlanRecord record) {
        if (record == null || record.tenantId() == null || record.taskId() == null) {
            return;
        }
        planDags.put(planTaskKey(record.tenantId(), record.taskId()), record.dagJson());
    }

    protected void savePlanIndex(InterpretationPlanRecord record) {
        if (record == null || record.tenantId() == null || record.planId() == null) {
            return;
        }
        planIdIndex.put(record.tenantId() + ":" + record.planId(), record.taskId());
    }

    private int nextPlanVersion(String tenantId, String taskId) {
        return listVersions(tenantId, taskId).stream()
            .map(InterpretationPlanRecord::version)
            .filter(Objects::nonNull)
            .max(Integer::compareTo)
            .orElse(0) + 1;
    }

    private String planTaskKey(String tenantId, String taskId) {
        return firstText(tenantId, "default") + ":" + firstText(taskId, "unknown-task");
    }

    private AgentRun missingRun(String runId) {
        return AgentRun.builder()
            .runId(runId)
            .status(AgentRunStatus.PENDING)
            .events(List.of())
            .metadata(Map.of())
            .startedAt(System.currentTimeMillis())
            .build();
    }

    private boolean isTerminal(AgentRunStatus status) {
        return status == AgentRunStatus.COMPLETED
            || status == AgentRunStatus.FAILED
            || status == AgentRunStatus.CANCELLED;
    }

    protected List<String> pruneRuns() {
        List<String> removedRunIds = new ArrayList<>();
        long ttlMs = properties.terminalRunTtlMs();
        long now = System.currentTimeMillis();
        if (ttlMs > 0) {
            runs.values().stream()
                .filter(run -> isTerminal(run.status()))
                .filter(run -> run.finishedAt() != null && now - run.finishedAt() >= ttlMs)
                .forEach(run -> {
                    if (runs.remove(run.runId(), run)) {
                        removedRunIds.add(run.runId());
                    }
                });
        }
        int maxRuns = properties.maxStoredRuns();
        if (runs.size() <= maxRuns) {
            return removedRunIds;
        }
        runs.values().stream()
            .filter(run -> isTerminal(run.status()))
            .sorted(Comparator
                .comparingLong(this::updatedAt)
                .thenComparing(AgentRun::runId))
            .forEach(run -> {
                if (runs.size() > maxRuns && runs.remove(run.runId(), run)) {
                    removedRunIds.add(run.runId());
                }
            });
        return removedRunIds;
    }

    private boolean matches(AgentRunQuery query, AgentRun run) {
        if (run == null) {
            return false;
        }
        AgentRunRequest request = run.request();
        return (query.status() == null || query.status() == run.status())
            && matchesText(query.tenantId(), request == null ? null : request.getTenantId())
            && matchesText(query.userId(), request == null ? null : request.getUserId())
            && matchesText(query.conversationId(), request == null ? null : request.getConversationId());
    }

    private boolean matchesText(String expected, String actual) {
        return expected == null || expected.equals(actual);
    }

    private long updatedAt(AgentRun run) {
        if (run.finishedAt() != null) {
            return run.finishedAt();
        }
        if (!run.events().isEmpty()) {
            return run.events().get(run.events().size() - 1).createdAt();
        }
        return run.startedAt();
    }

    private void publishEvent(AgentRunEvent event) {
        try {
            eventPublisher.publish(event);
        } catch (RuntimeException ex) {
            log.warn("Agent run event publisher failed. runId={} eventType={} error={}",
                event.runId(), event.type(), ex.getMessage());
        }
    }

    private Map<String, Object> stepPayload(AgentRunStep step) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("step", step.step());
        payload.put("action", firstText(step.action(), ""));
        if (step.toolName() != null && !step.toolName().isBlank()) {
            payload.put("toolName", step.toolName());
        }
        if (step.resolvedToolName() != null && !step.resolvedToolName().isBlank()) {
            payload.put("resolvedToolName", step.resolvedToolName());
        }
        if (step.reason() != null && !step.reason().isBlank()) {
            payload.put("reason", step.reason());
        }
        if (step.answerPreview() != null && !step.answerPreview().isBlank()) {
            payload.put("answerPreview", step.answerPreview());
        }
        if (step.plannedAt() > 0) {
            payload.put("plannedAt", step.plannedAt());
        }
        payload.put("observationCount", step.observationCount());
        if (step.executionPlan() != null && !step.executionPlan().isEmpty()) {
            payload.put("executionPlan", step.executionPlan());
        }
        return payload;
    }

    private Map<String, Object> observationPayload(AgentObservation observation) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", firstText(observation.type(), "text"));
        if (observation.source() != null && !observation.source().isBlank()) {
            payload.put("source", observation.source());
        }
        String preview = preview(observation.content(), 500);
        if (preview != null) {
            payload.put("contentPreview", preview);
        }
        if (observation.metadata() != null && !observation.metadata().isEmpty()) {
            payload.put("metadata", observation.metadata());
        }
        return payload;
    }

    private String preview(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.replaceAll("\\s+", " ").trim();
        int limit = Math.max(80, maxLength);
        return normalized.length() <= limit ? normalized : normalized.substring(0, limit);
    }

    private String firstText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    protected int recordLimit(int limit) {
        if (limit <= 0) {
            return 100;
        }
        return Math.min(limit, 1000);
    }

    private List<AgentRunStep> mergeSteps(List<AgentRunStep> existing, List<AgentRunStep> incoming) {
        List<AgentRunStep> merged = new ArrayList<>(existing == null ? List.of() : existing);
        for (AgentRunStep step : incoming == null ? List.<AgentRunStep>of() : incoming) {
            if (!containsStep(merged, step)) {
                merged.add(step);
            }
        }
        return merged;
    }

    private List<AgentRunStep> newSteps(List<AgentRunStep> existing, List<AgentRunStep> incoming) {
        List<AgentRunStep> values = new ArrayList<>();
        for (AgentRunStep step : incoming == null ? List.<AgentRunStep>of() : incoming) {
            if (!containsStep(existing, step)) {
                values.add(step);
            }
        }
        return values;
    }

    private boolean containsStep(List<AgentRunStep> steps, AgentRunStep step) {
        if (step == null) {
            return true;
        }
        return steps != null && steps.stream().anyMatch(existing -> existing != null && existing.step() == step.step());
    }

    private List<AgentObservation> mergeObservations(List<AgentObservation> existing, List<AgentObservation> incoming) {
        List<AgentObservation> merged = new ArrayList<>(existing == null ? List.of() : existing);
        for (AgentObservation observation : incoming == null ? List.<AgentObservation>of() : incoming) {
            if (!merged.contains(observation)) {
                merged.add(observation);
            }
        }
        return merged;
    }

    private List<AgentObservation> newObservations(List<AgentObservation> existing, List<AgentObservation> incoming) {
        List<AgentObservation> values = new ArrayList<>();
        List<AgentObservation> safeExisting = existing == null ? List.of() : existing;
        for (AgentObservation observation : incoming == null ? List.<AgentObservation>of() : incoming) {
            if (!safeExisting.contains(observation)) {
                values.add(observation);
            }
        }
        return values;
    }
}
