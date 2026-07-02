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
import java.util.List;
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
            case RUN_COMPLETED -> "SUCCESS";
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
            Object code = event.payload() == null ? null : event.payload().get("errorCode");
            return code == null || String.valueOf(code).isBlank()
                ? "AGENT_RUNTIME_FAILED"
                : String.valueOf(code);
        }
        if (event.type() == AgentRunEventType.RUN_CANCELLED) {
            return "AGENT_RUNTIME_CANCELLED";
        }
        return null;
    }

    private String writePayload(AgentRunEvent event) {
        Map<String, Object> runtimePayload = event.payload() == null
            ? Map.of()
            : new LinkedHashMap<>(event.payload());
        String status = taskStatus(event.type());
        String answer = terminalAnswer(event, runtimePayload);
        Map<String, Object> uiResponse = terminalUiResponse(status, answer, runtimePayload);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("message", displayMessage(event, answer));
        payload.put("runId", event.runId());
        payload.put("runtimeEventId", event.eventId());
        payload.put("runtimeEventType", event.type() == null ? null : event.type().name());
        payload.put("createdAt", event.createdAt());
        payload.put("payload", runtimePayload);
        if (answer != null && !answer.isBlank()) {
            payload.put("contractVersion", "ui_response_v1");
            payload.put("status", status);
            payload.put("answer", answer);
            payload.put("uiResponse", uiResponse);
            payload.put("executionResult", executionResult(status, answer, uiResponse, runtimePayload));
        }
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize agent runtime task event payload", ex);
        }
    }

    private String displayMessage(AgentRunEvent event, String answer) {
        if (event != null && event.type() == AgentRunEventType.RUN_FAILED && answer != null && !answer.isBlank()) {
            return answer;
        }
        return event == null ? "" : event.message();
    }

    private String terminalAnswer(AgentRunEvent event, Map<String, Object> payload) {
        if (event == null || (event.type() != AgentRunEventType.RUN_FAILED && event.type() != AgentRunEventType.RUN_CANCELLED)) {
            return null;
        }
        Map<String, Object> uiResponse = asMap(payload.get("uiResponse"));
        String direct = firstText(
            stringValue(uiResponse.get("answer")),
            stringValue(payload.get("answer")),
            stringValue(payload.get("errorMessage")),
            stringValue(payload.get("message"))
        );
        if (direct != null && !direct.isBlank() && !"Agent run failed".equalsIgnoreCase(direct)) {
            return direct;
        }
        String code = stringValue(payload.get("errorCode"));
        String fallback = firstText(stringValue(event.message()), "运行时未返回具体失败原因");
        return "本次执行失败，运行时已记录失败信息。"
            + "\n\n失败类型：" + firstText(code, "AGENT_RUNTIME_FAILED")
            + "\n失败原因：" + fallback
            + "\n\n该失败会作为观察结果返回给用户端，请根据工具执行日志、权限或参数配置继续排查。";
    }

    private Map<String, Object> terminalUiResponse(String status, String answer, Map<String, Object> payload) {
        Map<String, Object> existing = asMap(payload.get("uiResponse"));
        if (!existing.isEmpty()) {
            return existing;
        }
        if (answer == null || answer.isBlank()) {
            return Map.of();
        }
        Map<String, Object> uiResponse = new LinkedHashMap<>();
        uiResponse.put("contractVersion", "ui_response_v1");
        uiResponse.put("status", firstText(status, "FAILED"));
        uiResponse.put("answer", answer);
        uiResponse.put("citations", List.of());
        uiResponse.put("evidencePremises", List.of());
        uiResponse.put("confidence", null);
        uiResponse.put("evidenceSummary", "");
        uiResponse.put("visualization", Map.of("type", "none"));
        return uiResponse;
    }

    private Map<String, Object> executionResult(String status,
                                                String answer,
                                                Map<String, Object> uiResponse,
                                                Map<String, Object> payload) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", firstText(status, "FAILED"));
        result.put("uiResponse", uiResponse);
        result.put("semanticFlags", Map.of(
            "hasAnswer", answer != null && !answer.isBlank(),
            "hasInsight", false,
            "hasToolOutput", booleanValue(payload.get("toolTraceCount")) || !listValue(payload.get("observations")).isEmpty(),
            "hasSources", false,
            "hasArtifact", true
        ));
        result.put("message", answer);
        result.put("traceSummary", Map.of(
            "sourceCount", 0,
            "toolTraceCount", intValue(payload.get("toolTraceCount"))
        ));
        result.put("debug", Map.of(
            "errorCode", firstText(stringValue(payload.get("errorCode")), ""),
            "errorMessage", firstText(stringValue(payload.get("errorMessage")), "")
        ));
        return result;
    }

    private Map<String, Object> asMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, item) -> {
            if (key != null) {
                result.put(String.valueOf(key), item);
            }
        });
        return result;
    }

    private List<?> listValue(Object value) {
        return value instanceof List<?> list ? list : List.of();
    }

    private boolean booleanValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue() > 0;
        }
        return value instanceof Boolean bool && bool;
    }

    private int intValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null) {
            return 0;
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private String firstText(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
