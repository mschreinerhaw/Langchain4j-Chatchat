package com.chatchat.chat.task;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class AgentTodoService {

    private static final List<String> OPEN_STATUSES = List.of("PENDING", "PROCESSING");
    private static final List<String> CLOSED_STATUSES = List.of("DONE", "CANCELLED", "EXPIRED");
    private static final String TOOL_CONFIRMATION = "TOOL_CONFIRMATION";
    private static final String FAILURE_RETRY = "FAILURE_RETRY";
    private static final String FEEDBACK_REQUIRED = "FEEDBACK_REQUIRED";

    private final AgentTaskLatestRepository latestRepository;
    private final TodoTaskRepository todoTaskRepository;
    private final AgentTaskService taskService;
    private final AgentEventStore eventStore;
    private final ObjectMapper objectMapper;
    private final TaskConfirmRepository taskConfirmRepository;

    @Transactional
    public TodoTaskPayload listTodos(String tenantId, String userId, int limit) {
        String normalizedTenant = requireText(tenantId, "Tenant ID cannot be empty");
        String normalizedUser = normalizeText(userId);
        int normalizedLimit = Math.max(1, Math.min(limit <= 0 ? 20 : limit, 50));
        synchronizeFromRuntime(normalizedTenant);
        List<TodoTaskEntity> items = normalizedUser == null
            ? todoTaskRepository.findByTenantIdAndStatusInOrderByPriorityDescCreatedAtAsc(
                normalizedTenant,
                OPEN_STATUSES,
                PageRequest.of(0, normalizedLimit)
            )
            : todoTaskRepository.findByTenantIdAndUserIdAndStatusInOrderByPriorityDescCreatedAtAsc(
                normalizedTenant,
                normalizedUser,
                OPEN_STATUSES,
                PageRequest.of(0, normalizedLimit)
            );
        return new TodoTaskPayload(items.size(), items.stream().map(this::toView).toList());
    }

    @Transactional
    public TodoActionResult executeAction(String todoId, TodoActionRequest request) {
        String normalizedTodoId = requireText(todoId, "Todo ID cannot be empty");
        TodoTaskEntity todo = todoTaskRepository.findById(normalizedTodoId)
            .orElseThrow(() -> new IllegalArgumentException("Todo task not found: " + normalizedTodoId));
        String action = normalizeText(request == null ? null : request.action());
        if (action == null) {
            throw new IllegalArgumentException("Todo action cannot be empty");
        }
        todo.setStatus("PROCESSING");
        todoTaskRepository.save(todo);

        Object result = switch (todo.getTodoType()) {
            case TOOL_CONFIRMATION -> handleToolConfirmation(todo, action, request);
            case FAILURE_RETRY -> handleFailureRetry(todo, action);
            case FEEDBACK_REQUIRED -> handleFeedback(todo, action, request);
            default -> throw new IllegalArgumentException("Unsupported todo type: " + todo.getTodoType());
        };
        TodoTaskEntity refreshed = todoTaskRepository.findById(todo.getId()).orElse(todo);
        return new TodoActionResult(action, toView(refreshed), result);
    }

    private void synchronizeFromRuntime(String tenantId) {
        List<AgentTaskLatestEntity> tasks = latestRepository.findByTenantIdOrderByCreateTimeDesc(
            tenantId,
            PageRequest.of(0, 100)
        );
        for (AgentTaskLatestEntity task : tasks) {
            String status = normalizeStatus(task.getStatus());
            if ("WAIT_CONFIRMATION".equals(status) || "WAITING_CONFIRM".equals(status)) {
                upsertTodo(task, TOOL_CONFIRMATION, "HIGH", confirmationTitle(task), confirmationPayload(task));
            } else {
                closeOpenTodo(task, TOOL_CONFIRMATION, "DONE");
            }
            if ("FAILED".equals(status)) {
                upsertTodo(task, FAILURE_RETRY, "HIGH", failureTitle(task), failurePayload(task));
            }
            if ("SUCCESS".equals(status) && task.getFeedbackTime() == null) {
                upsertTodo(task, FEEDBACK_REQUIRED, "MEDIUM", feedbackTitle(task), feedbackPayload(task));
            } else if (task.getFeedbackTime() != null) {
                closeOpenTodo(task, FEEDBACK_REQUIRED, "DONE");
            }
        }
    }

    private TodoTaskEntity upsertTodo(AgentTaskLatestEntity task,
                                      String todoType,
                                      String priority,
                                      String title,
                                      Map<String, Object> payload) {
        List<TodoTaskEntity> matches = todoTaskRepository
            .findByTenantIdAndTaskIdAndTodoTypeOrderByCreatedAtAsc(task.getTenantId(), task.getTaskId(), todoType);
        Optional<TodoTaskEntity> existing = canonicalTodo(matches);
        if (existing.isPresent()
            && CLOSED_STATUSES.contains(normalizeStatus(existing.get().getStatus()))
            && !TOOL_CONFIRMATION.equals(todoType)) {
            closeDuplicateOpenTodos(matches, existing.get(), "CANCELLED");
            return existing.get();
        }
        TodoTaskEntity todo = existing.orElseGet(TodoTaskEntity::new);
        todo.setTenantId(task.getTenantId());
        todo.setUserId(firstText(task.getUserId(), "anonymous"));
        todo.setTaskId(task.getTaskId());
        todo.setAgentId(task.getAgentId());
        todo.setTitle(truncate(title, 300));
        todo.setTodoType(todoType);
        todo.setStatus("PENDING");
        todo.setPriority(priority);
        todo.setSource(firstText(task.getAgentId(), "Agent Runtime"));
        todo.setPayloadJson(writeJson(payload));
        todo.setExpiredAt(TOOL_CONFIRMATION.equals(todoType)
            ? taskConfirmRepository.findTopByTaskIdOrderByCreatedAtDesc(task.getTaskId())
                .map(TaskConfirmEntity::getExpiredAt)
                .orElse(null)
            : null);
        TodoTaskEntity saved = todoTaskRepository.save(todo);
        closeDuplicateOpenTodos(matches, saved, "CANCELLED");
        return saved;
    }

    private void closeOpenTodo(AgentTaskLatestEntity task, String todoType, String status) {
        List<TodoTaskEntity> matches = todoTaskRepository
            .findByTenantIdAndTaskIdAndTodoTypeOrderByCreatedAtAsc(task.getTenantId(), task.getTaskId(), todoType);
        matches.stream()
            .filter(todo -> OPEN_STATUSES.contains(normalizeStatus(todo.getStatus())))
            .forEach(todo -> {
                todo.setStatus(status);
            });
        if (!matches.isEmpty()) {
            todoTaskRepository.saveAll(matches);
        }
    }

    private Optional<TodoTaskEntity> canonicalTodo(List<TodoTaskEntity> matches) {
        if (matches == null || matches.isEmpty()) {
            return Optional.empty();
        }
        return matches.stream()
            .filter(todo -> OPEN_STATUSES.contains(normalizeStatus(todo.getStatus())))
            .findFirst()
            .or(() -> matches.stream().findFirst());
    }

    private void closeDuplicateOpenTodos(List<TodoTaskEntity> matches, TodoTaskEntity canonical, String status) {
        if (matches == null || matches.size() <= 1 || canonical == null) {
            return;
        }
        List<TodoTaskEntity> duplicates = matches.stream()
            .filter(todo -> !sameTodo(todo, canonical))
            .filter(todo -> OPEN_STATUSES.contains(normalizeStatus(todo.getStatus())))
            .peek(todo -> todo.setStatus(status))
            .toList();
        if (!duplicates.isEmpty()) {
            todoTaskRepository.saveAll(duplicates);
        }
    }

    private boolean sameTodo(TodoTaskEntity left, TodoTaskEntity right) {
        if (left == right) {
            return true;
        }
        if (left == null || right == null || left.getId() == null || right.getId() == null) {
            return false;
        }
        return left.getId().equals(right.getId());
    }

    private Object handleToolConfirmation(TodoTaskEntity todo, String action, TodoActionRequest request) {
        if ("reject".equals(action) || "deny".equals(action)) {
            Object result = taskService.reject(
                todo.getTenantId(),
                todo.getTaskId(),
                firstText(request == null ? null : request.userId(), todo.getUserId())
            );
            todo.setStatus("CANCELLED");
            todoTaskRepository.save(todo);
            return result;
        }
        if (!"approve".equals(action) && !"confirm".equals(action)) {
            throw new IllegalArgumentException("Unsupported tool confirmation action: " + action);
        }
        JsonNode payload = readJson(todo.getPayloadJson());
        String token = textAt(payload, "confirmation", "token");
        if (token == null) {
            throw new IllegalArgumentException("Confirmation token is missing");
        }
        AgentTaskSubmitRequest submitRequest = new AgentTaskSubmitRequest();
        submitRequest.setTenantId(todo.getTenantId());
        submitRequest.setUserId(firstText(request == null ? null : request.userId(), todo.getUserId()));
        submitRequest.setAgentId(todo.getAgentId());
        submitRequest.setSessionId(textAt(payload, "task", "sessionId"));
        submitRequest.setQuery(textAt(payload, "task", "question"));
        submitRequest.setResumeTaskId(todo.getTaskId());
        Map<String, Object> confirmation = new LinkedHashMap<>();
        confirmation.put("token", token);
        confirmation.put("approved", true);
        confirmation.put("decision", "allow_once");
        String remember = normalizeText(request == null ? null : request.remember());
        if (remember != null) {
            confirmation.put("remember", remember);
        }
        submitRequest.setToolInput(Map.of("mcpConfirmation", confirmation));
        Object result = taskService.confirm(todo.getTenantId(), todo.getTaskId(), submitRequest);
        todo.setStatus("DONE");
        todoTaskRepository.save(todo);
        return result;
    }

    private Object handleFailureRetry(TodoTaskEntity todo, String action) {
        if ("terminate".equals(action) || "cancel".equals(action)) {
            todo.setStatus("CANCELLED");
            todoTaskRepository.save(todo);
            return Map.of("status", "TERMINATED");
        }
        if (!"retry".equals(action)) {
            throw new IllegalArgumentException("Unsupported failure todo action: " + action);
        }
        Object result = taskService.retry(todo.getTenantId(), todo.getTaskId());
        todo.setStatus("DONE");
        todoTaskRepository.save(todo);
        return result;
    }

    private Object handleFeedback(TodoTaskEntity todo, String action, TodoActionRequest request) {
        AgentTaskFeedbackRequest feedback = request == null ? null : request.feedback();
        if (feedback == null) {
            feedback = new AgentTaskFeedbackRequest();
        }
        feedback.setUserId(firstText(request == null ? null : request.userId(), todo.getUserId()));
        switch (action) {
            case "useful" -> feedback.setUseful(true);
            case "adopted" -> feedback.setAdopted(true);
            case "resolved" -> feedback.setResolved(true);
            case "unresolved" -> feedback.setResolved(false);
            case "feedback" -> {
                // Use caller-provided payload.
            }
            default -> throw new IllegalArgumentException("Unsupported feedback todo action: " + action);
        }
        Object result = taskService.recordFeedback(todo.getTenantId(), todo.getTaskId(), feedback);
        todo.setStatus("DONE");
        todoTaskRepository.save(todo);
        return result;
    }

    private String confirmationTitle(AgentTaskLatestEntity task) {
        Map<String, Object> payload = confirmationPayload(task);
        Object confirmation = payload.get("confirmation");
        if (confirmation instanceof Map<?, ?> map) {
            String displayName = stringValue(firstPresent(map.get("displayName"), map.get("toolName")));
            if (displayName != null && !displayName.isBlank()) {
                return displayName + "需要确认";
            }
        }
        return "工具执行需要确认";
    }

    private Map<String, Object> confirmationPayload(AgentTaskLatestEntity task) {
        Map<String, Object> payload = basePayload(task);
        taskConfirmRepository.findTopByTaskIdOrderByCreatedAtDesc(task.getTaskId()).ifPresent(confirm -> {
            payload.put("confirmStatus", confirm.getStatus());
            payload.put("expiredAt", confirm.getExpiredAt());
            payload.put("confirmMessage", confirm.getConfirmMessage());
            payload.put("toolName", confirm.getToolName());
        });
        latestConfirmationEvent(task).ifPresent(event -> {
            payload.put("eventId", event.getEventId());
            payload.put("reason", "confirm_required 工具等待用户确认");
            payload.put("response", readJsonObject(event.getPayload()));
            JsonNode root = readJson(event.getPayload());
            JsonNode trace = latestConfirmationTrace(root);
            JsonNode confirmation = trace.path("runtimeMetadata").path("confirmation");
            if (!confirmation.isMissingNode() && !confirmation.isNull()) {
                payload.put("confirmation", objectValue(confirmation));
            }
            if (!trace.isMissingNode() && !trace.isNull()) {
                payload.put("toolTrace", objectValue(trace));
            }
        });
        return payload;
    }

    private String failureTitle(AgentTaskLatestEntity task) {
        String message = normalizeText(task.getErrorMessage());
        if (message == null) {
            return "任务执行失败，是否重试";
        }
        return truncate(message, 34) + "，是否重试";
    }

    private Map<String, Object> failurePayload(AgentTaskLatestEntity task) {
        Map<String, Object> payload = basePayload(task);
        payload.put("reason", "任务失败后等待用户选择重试或终止");
        payload.put("errorMessage", task.getErrorMessage());
        return payload;
    }

    private String feedbackTitle(AgentTaskLatestEntity task) {
        String question = normalizeText(task.getQuestion());
        if (question == null) {
            return "请评价本次 Agent 任务";
        }
        return "请评价\"" + truncate(question, 26) + "\"";
    }

    private Map<String, Object> feedbackPayload(AgentTaskLatestEntity task) {
        Map<String, Object> payload = basePayload(task);
        payload.put("reason", "任务完成后等待用户评价：有用/采纳/解决");
        payload.put("answerSummary", task.getAnswerSummary());
        return payload;
    }

    private Map<String, Object> basePayload(AgentTaskLatestEntity task) {
        Map<String, Object> taskPayload = new LinkedHashMap<>();
        taskPayload.put("taskId", task.getTaskId());
        taskPayload.put("tenantId", task.getTenantId());
        taskPayload.put("userId", task.getUserId());
        taskPayload.put("agentId", task.getAgentId());
        taskPayload.put("sessionId", task.getSessionId());
        taskPayload.put("status", task.getStatus());
        taskPayload.put("question", task.getQuestion());
        taskPayload.put("answerSummary", task.getAnswerSummary());
        taskPayload.put("errorMessage", task.getErrorMessage());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("task", taskPayload);
        return payload;
    }

    private Optional<AgentEvent> latestConfirmationEvent(AgentTaskLatestEntity task) {
        return eventStore.listByTask(task.getTenantId(), task.getSessionId(), task.getTaskId(), 120)
            .stream()
            .filter(event -> "NEEDS_CONFIRMATION".equalsIgnoreCase(event.getType())
                || "WAIT_CONFIRMATION".equalsIgnoreCase(event.getStatus()))
            .reduce((previous, current) -> current);
    }

    private JsonNode latestConfirmationTrace(JsonNode response) {
        JsonNode traces = response == null ? null : response.path("toolTraces");
        if (traces == null || !traces.isArray()) {
            return MissingNode.getInstance();
        }
        JsonNode fallback = MissingNode.getInstance();
        for (JsonNode trace : traces) {
            if (fallback.isMissingNode()) {
                fallback = trace;
            }
            String outcome = trace.path("runtimeMetadata").path("outcome").asText("");
            if ("confirmation_required".equalsIgnoreCase(outcome)) {
                return trace;
            }
        }
        return fallback;
    }

    private TodoTaskView toView(TodoTaskEntity entity) {
        return new TodoTaskView(
            entity.getId(),
            entity.getTenantId(),
            entity.getUserId(),
            entity.getTaskId(),
            entity.getAgentId(),
            entity.getTitle(),
            entity.getTodoType(),
            entity.getStatus(),
            entity.getPriority(),
            entity.getSource(),
            readJsonObject(entity.getPayloadJson()),
            entity.getCreatedAt(),
            entity.getUpdatedAt(),
            entity.getExpiredAt()
        );
    }

    private JsonNode readJson(String value) {
        if (value == null || value.isBlank()) {
            return MissingNode.getInstance();
        }
        try {
            return objectMapper.readTree(value);
        } catch (JsonProcessingException ex) {
            return MissingNode.getInstance();
        }
    }

    private Object readJsonObject(String value) {
        JsonNode node = readJson(value);
        if (node.isMissingNode() || node.isNull()) {
            return Map.of();
        }
        return objectValue(node);
    }

    private Object objectValue(JsonNode node) {
        return objectMapper.convertValue(node, Object.class);
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize todo payload", ex);
        }
    }

    private String textAt(JsonNode node, String parent, String field) {
        if (node == null || parent == null || field == null) {
            return null;
        }
        JsonNode value = node.path(parent).path(field);
        return value.isMissingNode() || value.isNull() || value.asText().isBlank() ? null : value.asText();
    }

    private Object firstPresent(Object first, Object second) {
        return first == null ? second : first;
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String normalizeStatus(String status) {
        return firstText(status, "UNKNOWN").toUpperCase();
    }

    private String normalizeText(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String requireText(String value, String message) {
        String normalized = normalizeText(value);
        if (normalized == null) {
            throw new IllegalArgumentException(message);
        }
        return normalized;
    }

    private String firstText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, Math.max(0, maxLength));
    }

    public record TodoTaskPayload(
        long total,
        List<TodoTaskView> items
    ) {
    }

    public record TodoTaskView(
        String id,
        String tenantId,
        String userId,
        String taskId,
        String agentId,
        String title,
        String todoType,
        String status,
        String priority,
        String source,
        Object payload,
        Instant createdAt,
        Instant updatedAt,
        Instant expiredAt
    ) {
    }

    public record TodoActionRequest(
        String action,
        String userId,
        String remember,
        AgentTaskFeedbackRequest feedback
    ) {
    }

    public record TodoActionResult(
        String action,
        TodoTaskView todo,
        Object result
    ) {
    }
}
