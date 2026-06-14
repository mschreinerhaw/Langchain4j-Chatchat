package com.chatchat.chat.task;

import com.chatchat.agents.runtime.AgentRunEvent;
import com.chatchat.agents.runtime.AgentRunEventPublisher;
import com.chatchat.agents.runtime.AgentRunEventType;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Primary
@Component
@RequiredArgsConstructor
public class AgentRuntimeTaskEventPublisher implements AgentRunEventPublisher {

    private final AgentTaskLatestRepository latestRepository;
    private final AgentEventStore eventStore;
    private final AgentEventBus eventBus;
    private final ObjectMapper objectMapper;

    @Override
    public void publish(AgentRunEvent event) {
        if (event == null || event.runId() == null || event.runId().isBlank()) {
            return;
        }
        Optional<AgentTaskLatestEntity> task = latestRepository.findById(event.runId());
        if (task.isEmpty()) {
            log.debug("Agent runtime event has no matching async task. runId={} eventType={}",
                event.runId(), event.type());
            return;
        }
        AgentTaskLatestEntity latest = task.get();
        AgentEvent taskEvent = AgentEvent.builder()
            .taskId(latest.getTaskId())
            .tenantId(latest.getTenantId())
            .userId(latest.getUserId())
            .agentId(latest.getAgentId())
            .sessionId(latest.getSessionId())
            .parentEventId(parentQuestionEventId(latest))
            .sequence(eventStore.nextSequence(latest.getTenantId(), latest.getSessionId(), latest.getTaskId()))
            .toolName(toolName(event))
            .type(taskEventType(event.type()))
            .status(taskStatus(event.type()))
            .payload(writePayload(event))
            .errorCode(errorCode(event))
            .createTime(event.createdAt())
            .build();
        eventStore.save(taskEvent);
        eventBus.publishResult(taskEvent);
        log.info("Agent runtime event bridged to task flow. taskId={} runId={} runtimeEventType={} taskEventType={} status={} message={}",
            latest.getTaskId(),
            event.runId(),
            event.type(),
            taskEvent.getType(),
            taskEvent.getStatus(),
            event.message());
    }

    private String parentQuestionEventId(AgentTaskLatestEntity latest) {
        return eventStore.findFirstByTaskAndType(
                latest.getTenantId(),
                latest.getSessionId(),
                latest.getTaskId(),
                "QUESTION"
            )
            .map(AgentEvent::getEventId)
            .orElse(null);
    }

    private String taskEventType(AgentRunEventType type) {
        if (type == null) {
            return "RUNTIME_EVENT";
        }
        return switch (type) {
            case RUN_SUBMITTED -> "RUNTIME_SUBMITTED";
            case RUN_STARTED -> "RUNTIME_STARTED";
            case STEP_RECORDED -> "RUNTIME_STEP";
            case OBSERVATION_RECORDED -> "RUNTIME_OBSERVATION";
            case CONFIRMATION_REQUIRED -> "RUNTIME_CONFIRMATION";
            case RUN_COMPLETED -> "RUNTIME_COMPLETED";
            case RUN_CANCELLED -> "RUNTIME_CANCELLED";
            case RUN_FAILED -> "RUNTIME_FAILED";
        };
    }

    private String taskStatus(AgentRunEventType type) {
        if (type == null) {
            return "RUNNING";
        }
        return switch (type) {
            case RUN_SUBMITTED -> "PENDING";
            case CONFIRMATION_REQUIRED -> "WAIT_CONFIRMATION";
            case RUN_CANCELLED -> "CANCELLED";
            case RUN_FAILED -> "FAILED";
            default -> "RUNNING";
        };
    }

    private String toolName(AgentRunEvent event) {
        Object direct = event.payload().get("toolName");
        if (direct == null) {
            direct = event.payload().get("resolvedToolName");
        }
        if (direct == null) {
            direct = event.payload().get("source");
        }
        return direct == null || String.valueOf(direct).isBlank() ? null : String.valueOf(direct).trim();
    }

    private String errorCode(AgentRunEvent event) {
        if (event.type() == AgentRunEventType.RUN_FAILED) {
            return "AGENT_RUNTIME_FAILED";
        }
        if (event.type() == AgentRunEventType.RUN_CANCELLED) {
            return "AGENT_RUNTIME_CANCELLED";
        }
        return null;
    }

    private String writePayload(AgentRunEvent event) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("message", event.message());
        payload.put("runId", event.runId());
        payload.put("runtimeEventId", event.eventId());
        payload.put("runtimeEventType", event.type() == null ? null : event.type().name());
        payload.put("createdAt", event.createdAt());
        payload.put("payload", event.payload());
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize agent runtime task event payload", ex);
        }
    }
}
