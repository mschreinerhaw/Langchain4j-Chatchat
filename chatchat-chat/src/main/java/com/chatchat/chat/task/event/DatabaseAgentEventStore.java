package com.chatchat.chat.task.event;

import com.chatchat.chat.task.core.AgentTaskLatestRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.HashSet;
import java.util.Set;
import java.util.ArrayList;

/** Shared, multi-instance-safe authoritative event store for Agent task projections. */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "chatchat.agent.task.event-store", name = "type",
    havingValue = "database", matchIfMissing = true)
public class DatabaseAgentEventStore implements AgentEventStore {

    private final DatabaseAgentEventRepository repository;
    private final AgentTaskLatestRepository taskRepository;

    @Override
    public boolean supportsDeferredAppend() {
        return true;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String append(AgentEvent event) {
        // This proxy entry point owns the transaction. Calling save() below is a
        // self-invocation, so its annotation alone would not open the transaction.
        return save(event);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<String> appendAll(List<AgentEvent> events) {
        if (events == null || events.isEmpty()) return List.of();
        List<AgentEvent> ordered = events.stream().filter(java.util.Objects::nonNull).toList();
        if (ordered.isEmpty()) return List.of();
        AgentEvent first = ordered.get(0);
        for (AgentEvent event : ordered) {
            if (!java.util.Objects.equals(first.getTaskId(), event.getTaskId())
                || !java.util.Objects.equals(first.getTenantId(), event.getTenantId())
                || !java.util.Objects.equals(first.getSessionId(), event.getSessionId())) {
                throw new IllegalArgumentException("Agent event batch must belong to one task stream");
            }
            if (event.getCreateTime() <= 0) event.setCreateTime(System.currentTimeMillis());
        }
        Set<String> existingIds = new HashSet<>();
        repository.findAllById(ordered.stream().map(AgentEvent::getEventId).toList())
            .forEach(entity -> existingIds.add(entity.getEventId()));
        taskRepository.findByTaskIdForUpdate(first.getTaskId());
        long current = repository.findTopByTenantIdAndSessionIdAndTaskIdOrderBySequenceDesc(
                first.getTenantId(), first.getSessionId(), first.getTaskId())
            .map(DatabaseAgentEventEntity::getSequence).orElse(0L);
        List<DatabaseAgentEventEntity> entities = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        for (AgentEvent event : ordered) {
            ids.add(event.getEventId());
            if (existingIds.contains(event.getEventId())) continue;
            if (event.getSequence() == null || event.getSequence() <= current) {
                event.setSequence(++current);
            } else {
                current = event.getSequence();
            }
            entities.add(toEntity(event));
        }
        if (!entities.isEmpty()) repository.saveAllAndFlush(entities);
        return List.copyOf(ids);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String save(AgentEvent event) {
        if (event == null) {
            throw new IllegalArgumentException("Agent event is required");
        }
        if (event.getCreateTime() <= 0) {
            event.setCreateTime(System.currentTimeMillis());
        }
        if (repository.existsById(event.getEventId())) {
            return event.getEventId();
        }
        // The task row is the event stream lock. Always acquire and release it in this short,
        // independent transaction. Runtime-run persistence can perform additional database work
        // after publishing; inheriting that outer transaction would retain this lock and block
        // worker lease heartbeats until MySQL's lock-wait timeout expires.
        taskRepository.findByTaskIdForUpdate(event.getTaskId());
        long current = repository.findTopByTenantIdAndSessionIdAndTaskIdOrderBySequenceDesc(
                event.getTenantId(), event.getSessionId(), event.getTaskId())
            .map(DatabaseAgentEventEntity::getSequence)
            .orElse(0L);
        if (event.getSequence() == null || event.getSequence() <= current) {
            event.setSequence(current + 1L);
        }
        try {
            repository.saveAndFlush(toEntity(event));
        } catch (DataIntegrityViolationException duplicate) {
            if (!repository.existsById(event.getEventId())) {
                throw duplicate;
            }
        }
        return event.getEventId();
    }

    @Override
    @Transactional(readOnly = true)
    public List<AgentEvent> listByTask(String tenantId, String sessionId, String taskId, int limit) {
        if (tenantId == null || tenantId.isBlank() || sessionId == null || sessionId.isBlank()
            || taskId == null || taskId.isBlank()) {
            return List.of();
        }
        int bounded = Math.max(1, Math.min(limit, 100_000));
        return repository.findByTenantIdAndSessionIdAndTaskIdOrderBySequenceAscCreatedAtAsc(
                tenantId.trim(), sessionId.trim(), taskId.trim(), PageRequest.of(0, bounded))
            .stream().map(this::toEvent).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<AgentEvent> listByTaskAfter(String tenantId, String sessionId, String taskId,
                                            long afterSequence, int limit) {
        if (tenantId == null || tenantId.isBlank() || sessionId == null || sessionId.isBlank()
            || taskId == null || taskId.isBlank()) {
            return List.of();
        }
        int bounded = Math.max(1, Math.min(limit, 100_000));
        return repository
            .findByTenantIdAndSessionIdAndTaskIdAndSequenceGreaterThanOrderBySequenceAscCreatedAtAsc(
                tenantId.trim(), sessionId.trim(), taskId.trim(), Math.max(0L, afterSequence),
                PageRequest.of(0, bounded))
            .stream().map(this::toEvent).toList();
    }

    private DatabaseAgentEventEntity toEntity(AgentEvent event) {
        DatabaseAgentEventEntity entity = new DatabaseAgentEventEntity();
        entity.setEventId(event.getEventId());
        entity.setTaskId(event.getTaskId());
        entity.setRunId(event.getRunId());
        entity.setExecutionId(firstText(event.getExecutionId(), event.getTaskId()));
        entity.setAttemptId(event.getAttemptId());
        entity.setEventScope(firstText(event.getEventScope(), "TASK"));
        entity.setTenantId(event.getTenantId());
        entity.setUserId(event.getUserId());
        entity.setAgentId(event.getAgentId());
        entity.setSessionId(event.getSessionId());
        entity.setParentEventId(event.getParentEventId());
        entity.setSequence(event.getSequence());
        entity.setToolName(event.getToolName());
        entity.setType(event.getType());
        entity.setStatus(event.getStatus());
        entity.setPayload(event.getPayload());
        entity.setLatencyMs(event.getLatencyMs());
        entity.setErrorCode(event.getErrorCode());
        entity.setRetryCount(event.getRetryCount());
        entity.setCreatedAt(event.getCreateTime());
        return entity;
    }

    private AgentEvent toEvent(DatabaseAgentEventEntity entity) {
        return AgentEvent.builder()
            .eventId(entity.getEventId())
            .taskId(entity.getTaskId())
            .runId(entity.getRunId())
            .executionId(entity.getExecutionId())
            .attemptId(entity.getAttemptId())
            .eventScope(entity.getEventScope())
            .tenantId(entity.getTenantId())
            .userId(entity.getUserId())
            .agentId(entity.getAgentId())
            .sessionId(entity.getSessionId())
            .parentEventId(entity.getParentEventId())
            .sequence(entity.getSequence())
            .toolName(entity.getToolName())
            .type(entity.getType())
            .status(entity.getStatus())
            .payload(entity.getPayload())
            .latencyMs(entity.getLatencyMs())
            .errorCode(entity.getErrorCode())
            .retryCount(entity.getRetryCount())
            .createTime(entity.getCreatedAt())
            .build();
    }

    private String firstText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
