package com.chatchat.chat.dag;

import com.chatchat.agents.runtime.plan.NodeAttemptStore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class DatabaseNodeAttemptStore implements NodeAttemptStore {

    private final NodeAttemptRepository repository;
    private final ObjectMapper objectMapper;

    @Override
    public boolean supportsRecoveryQueries() {
        return true;
    }

    @Override
    @Transactional(readOnly = true)
    public List<AttemptSnapshot> committedAttempts(String tenantId, String runId) {
        return repository.findAllByTenantIdAndRunIdAndStateOrderByCommittedAtAscNodeIdAsc(
                required(tenantId, "tenantId"), required(runId, "runId"), State.COMMITTED.name())
            .stream()
            .map(this::snapshot)
            .toList();
    }

    @Override
    @Transactional
    public AttemptSnapshot create(AttemptCommand command) {
        require(command != null, "Node attempt command is required");
        String tenantId = required(command.tenantId(), "tenantId");
        String runId = required(command.runId(), "runId");
        require(command.nodeId() != null, "nodeId is required");
        int nextAttempt = repository
            .findTopByTenantIdAndRunIdAndNodeIdOrderByAttemptNumberDesc(tenantId, runId, command.nodeId())
            .map(value -> value.getAttemptNumber() + 1)
            .orElse(1);
        NodeAttemptEntity entity = new NodeAttemptEntity();
        entity.setAttemptId(UUID.randomUUID().toString());
        entity.setTenantId(tenantId);
        entity.setRunId(runId);
        entity.setExecutionTraceId(command.executionTraceId());
        entity.setPlanVersion(command.planVersion());
        entity.setNodeId(command.nodeId());
        entity.setAttemptNumber(nextAttempt);
        entity.setState(State.CREATED.name());
        entity.setStateReason("attempt created");
        entity.setNodeDefinitionFingerprint(command.nodeDefinitionFingerprint());
        entity.setInputFingerprint(command.inputFingerprint());
        entity.setMetadataJson(writeMetadata(command.metadata()));
        return snapshot(repository.saveAndFlush(entity));
    }

    @Override
    @Transactional
    public AttemptSnapshot transition(String tenantId,
                                      String attemptId,
                                      State expectedState,
                                      State targetState,
                                      String reason,
                                      Map<String, Object> metadata) {
        NodeAttemptEntity entity = repository.findByTenantIdAndAttemptId(
                required(tenantId, "tenantId"), required(attemptId, "attemptId"))
            .orElseThrow(() -> new IllegalStateException("Node attempt not found: " + attemptId));
        State current = State.valueOf(entity.getState());
        if (current != expectedState) {
            throw new IllegalStateException("Stale node attempt transition: expected " + expectedState
                + " but persisted state is " + current);
        }
        if (!current.mayTransitionTo(targetState)) {
            throw new IllegalStateException("Illegal node attempt transition: " + current + " -> " + targetState);
        }
        entity.setState(targetState.name());
        entity.setStateReason(reason);
        if (targetState == State.PREPARED) {
            entity.setPreparedAt(java.time.Instant.now());
        }
        if (metadata != null && !metadata.isEmpty()) {
            Map<String, Object> merged = readMetadata(entity.getMetadataJson());
            merged.putAll(metadata);
            entity.setMetadataJson(writeMetadata(merged));
        }
        return snapshot(repository.saveAndFlush(entity));
    }

    @Override
    @Transactional
    public BarrierResult commitBarrier(BarrierCommand command) {
        require(command != null, "Commit barrier command is required");
        String tenantId = required(command.tenantId(), "tenantId");
        String runId = required(command.runId(), "runId");
        String epoch = required(command.executionEpoch(), "executionEpoch");
        List<String> requiredIds = command.requiredAttemptIds() == null
            ? List.of()
            : command.requiredAttemptIds().stream().filter(value -> value != null && !value.isBlank()).distinct().toList();
        require(!requiredIds.isEmpty(), "Commit barrier requires at least one Attempt");
        List<NodeAttemptEntity> attempts = repository
            .findAllByTenantIdAndAttemptIdInOrderByAttemptId(tenantId, requiredIds);
        if (attempts.size() != requiredIds.size()
            || !new LinkedHashSet<>(attempts.stream().map(NodeAttemptEntity::getAttemptId).toList())
                .equals(new LinkedHashSet<>(requiredIds))) {
            throw new IllegalStateException("Commit barrier is missing required node Attempts for epoch " + epoch);
        }
        for (NodeAttemptEntity attempt : attempts) {
            if (!runId.equals(attempt.getRunId())) {
                throw new IllegalStateException("Commit barrier Attempt belongs to another run: "
                    + attempt.getAttemptId());
            }
            State state = State.valueOf(attempt.getState());
            if (state != State.PREPARED) {
                throw new IllegalStateException("Commit barrier requires PREPARED but "
                    + attempt.getAttemptId() + " is " + state);
            }
        }
        java.time.Instant committedAt = java.time.Instant.now();
        for (NodeAttemptEntity attempt : attempts) {
            attempt.setState(State.COMMITTED.name());
            attempt.setStateReason("execution epoch commit barrier satisfied");
            attempt.setExecutionEpoch(epoch);
            attempt.setCommittedAt(committedAt);
            Map<String, Object> merged = readMetadata(attempt.getMetadataJson());
            merged.putAll(command.metadata() == null ? Map.of() : command.metadata());
            merged.put("executionEpoch", epoch);
            merged.put("commitBarrier", "SATISFIED");
            attempt.setMetadataJson(writeMetadata(merged));
        }
        List<NodeAttemptEntity> committed = repository.saveAllAndFlush(attempts);
        return new BarrierResult(epoch, true, committed.stream().map(this::snapshot).toList());
    }

    private AttemptSnapshot snapshot(NodeAttemptEntity entity) {
        return new AttemptSnapshot(
            entity.getAttemptId(), entity.getTenantId(), entity.getRunId(), entity.getNodeId(),
            entity.getAttemptNumber(), State.valueOf(entity.getState()),
            entity.getRevision() == null ? 0L : entity.getRevision(), entity.getCreatedAt(), entity.getUpdatedAt()
        );
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readMetadata(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return new LinkedHashMap<>(objectMapper.readValue(json, Map.class));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Invalid persisted node attempt metadata", ex);
        }
    }

    private String writeMetadata(Map<String, Object> metadata) {
        try {
            return objectMapper.writeValueAsString(metadata == null ? Map.of() : metadata);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Cannot serialize node attempt metadata", ex);
        }
    }

    private String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }

    private void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }
}
