package com.chatchat.api.agent.published;

import com.chatchat.api.security.ApiAuthenticationFilter;
import com.chatchat.chat.skills.SkillCatalogService;
import com.chatchat.chat.skills.SkillDefinition;
import com.chatchat.chat.task.core.AgentExecutionState;
import com.chatchat.chat.task.core.AgentTaskResponse;
import com.chatchat.chat.task.core.AgentTaskService;
import com.chatchat.chat.task.core.AgentTaskSubmitRequest;
import com.chatchat.common.constants.AppConstants;
import com.chatchat.common.response.ApiResponse;
import com.chatchat.enterprise.service.EnterpriseAdminService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Stable external API for invoking published Agents.
 *
 * <p>This controller intentionally owns the public curl contract instead of exposing the
 * internal task controller. Authentication is still handled by the global API filter, while
 * tenant identity, task ownership and role-to-Agent authorization are rechecked here for every
 * operation.</p>
 */
@RestController
@RequiredArgsConstructor
@RequestMapping(AppConstants.API_V1 + "/published-agents")
@Tag(name = "Published Agent API", description = "Authenticated question, status and answer APIs for published Agents")
public class PublishedAgentApiController {

    private static final int MAX_HISTORY_WINDOW = 100;

    private final AgentTaskService taskService;
    private final SkillCatalogService skillCatalogService;
    private final EnterpriseAdminService enterpriseAdminService;
    private final ObjectMapper objectMapper;

    @PostMapping("/{agentId}/questions")
    @Operation(summary = "Submit a question to one published Agent")
    public ResponseEntity<ApiResponse<PublishedAgentSubmission>> submit(
        @PathVariable("agentId") String agentId,
        @RequestBody PublishedAgentQuestion request,
        HttpServletRequest servletRequest
    ) {
        RequestIdentity identity = requireIdentity(servletRequest);
        SkillDefinition agent = requirePublishedAndAuthorized(agentId, identity.userId());
        if (request == null || !hasText(request.question())) {
            return badRequest("question is required");
        }

        AgentTaskSubmitRequest taskRequest = new AgentTaskSubmitRequest();
        taskRequest.setTenantId(identity.tenantId());
        taskRequest.setUserId(identity.userId());
        taskRequest.setAgentId(agent.id());
        taskRequest.setSkillId(agent.id());
        taskRequest.setSessionId(hasText(request.sessionId()) ? request.sessionId().trim() : UUID.randomUUID().toString());
        taskRequest.setQuery(request.question().trim());
        taskRequest.setMode("agent_chat");
        taskRequest.setStream(false);
        taskRequest.setHistoryWindow(normalizeHistoryWindow(request.historyWindow()));
        taskRequest.setIdempotencyKey(scopedIdempotencyKey(identity.userId(), agent.id(), request.idempotencyKey()));
        taskRequest.setImageAnalysisIds(cleanTextList(request.imageAnalysisIds()));
        taskRequest.setToolInput(sanitizeParameters(request.parameters()));

        try {
            AgentTaskResponse task = taskService.submit(taskRequest);
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.success(
                new PublishedAgentSubmission(
                    task.taskId(), task.executionId(), task.attemptId(), task.sessionId(), agent.id(),
                    task.status(), task.canonicalState(), statusPath(agent.id(), task.taskId()),
                    answerPath(agent.id(), task.taskId()), task.createTime()
                ),
                "Published Agent question accepted"
            ));
        } catch (IllegalArgumentException ex) {
            return badRequest(ex.getMessage());
        }
    }

    @GetMapping("/{agentId}/questions/{taskId}/status")
    @Operation(summary = "Get the authenticated caller's published Agent run status")
    public ResponseEntity<ApiResponse<PublishedAgentRunStatus>> status(
        @PathVariable("agentId") String agentId,
        @PathVariable("taskId") String taskId,
        HttpServletRequest servletRequest
    ) {
        RequestIdentity identity = requireIdentity(servletRequest);
        AgentTaskResponse task = requireOwnedTask(identity, agentId, taskId);
        boolean terminal = terminal(task);
        return ok(new PublishedAgentRunStatus(
            task.taskId(), task.executionId(), task.attemptId(), task.attemptNumber(), task.sessionId(),
            task.agentId(), task.status(), task.canonicalState(), terminal,
            terminal && hasText(task.answerSummary()), task.errorMessage(), task.createTime(), task.updateTime(),
            answerPath(task.agentId(), task.taskId())
        ));
    }

    @GetMapping("/{agentId}/questions/{taskId}/answer")
    @Operation(summary = "Get the final answer for the authenticated caller's published Agent run")
    public ResponseEntity<ApiResponse<PublishedAgentAnswer>> answer(
        @PathVariable("agentId") String agentId,
        @PathVariable("taskId") String taskId,
        HttpServletRequest servletRequest
    ) {
        RequestIdentity identity = requireIdentity(servletRequest);
        AgentTaskResponse task = requireOwnedTask(identity, agentId, taskId);
        AgentTaskService.AgentNotificationContent content = taskService
            .finalNotificationContent(identity.tenantId(), task.taskId())
            .orElse(null);
        boolean terminal = terminal(task);
        boolean ready = content != null && hasText(content.answer());
        return ok(new PublishedAgentAnswer(
            task.taskId(), task.executionId(), task.attemptId(), task.sessionId(), task.agentId(),
            task.status(), task.canonicalState(), terminal, ready,
            ready ? content.answer() : null,
            ready ? content.references() : List.of(),
            task.errorMessage(), task.updateTime()
        ));
    }

    @GetMapping("/{agentId}/curl-example")
    @Operation(summary = "Generate admin-only curl examples for one published Agent")
    public ResponseEntity<ApiResponse<PublishedAgentCurlExample>> curlExample(
        @PathVariable("agentId") String agentId,
        HttpServletRequest servletRequest
    ) {
        RequestIdentity identity = requireIdentity(servletRequest);
        if (!isPlatformAdmin(identity, servletRequest)) {
            return forbidden("Only the platform administrator can view curl examples");
        }
        SkillDefinition agent = requirePublishedAndAuthorized(agentId, identity.userId());
        String baseUrl = ServletUriComponentsBuilder.fromCurrentContextPath().build().toUriString();
        return ok(buildCurlExample(baseUrl, agent));
    }

    private PublishedAgentCurlExample buildCurlExample(String baseUrl, SkillDefinition agent) {
        String escapedBaseUrl = shellDoubleQuoted(baseUrl);
        String escapedAgentId = shellDoubleQuoted(agent.id());
        String body;
        try {
            body = objectMapper.writeValueAsString(Map.of(
                "sessionId", "curl-session-001",
                "question", "Describe your capabilities and answer an example question"
            ));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Unable to generate curl request body", ex);
        }
        String submit = "curl --request POST \"${CHATCHAT_BASE_URL}/api/v1/published-agents/"
            + escapedAgentId + "/questions\" \\\n"
            + "  --header \"Authorization: Bearer ${CHATCHAT_TOKEN}\" \\\n"
            + "  --header \"Content-Type: application/json\" \\\n"
            + "  --data '" + body.replace("'", "'\\''") + "'";
        String status = "curl --request GET \"${CHATCHAT_BASE_URL}/api/v1/published-agents/"
            + escapedAgentId + "/questions/${TASK_ID}/status\" \\\n"
            + "  --header \"Authorization: Bearer ${CHATCHAT_TOKEN}\"";
        String answer = "curl --request GET \"${CHATCHAT_BASE_URL}/api/v1/published-agents/"
            + escapedAgentId + "/questions/${TASK_ID}/answer\" \\\n"
            + "  --header \"Authorization: Bearer ${CHATCHAT_TOKEN}\"";
        String complete = "export CHATCHAT_BASE_URL=\"" + escapedBaseUrl + "\"\n"
            + "export CHATCHAT_TOKEN=\"<paste-login-token>\"\n"
            + "export TASK_ID=\"<taskId-from-submit-response>\"\n\n"
            + "# 1. Submit a question and save data.taskId from the response\n" + submit + "\n\n"
            + "# 2. Get run status\n" + status + "\n\n"
            + "# 3. Get the final answer; poll status while data.ready is false\n" + answer;
        return new PublishedAgentCurlExample(
            agent.id(), agent.label(), baseUrl, "CHATCHAT_TOKEN", submit, status, answer, complete,
            "Use a Bearer token returned by login. Tenant, user and role-to-Agent authorization are enforced server-side."
        );
    }

    private AgentTaskResponse requireOwnedTask(RequestIdentity identity, String requestedAgentId, String taskId) {
        if (!hasText(taskId)) {
            throw new PublishedAgentApiException(HttpStatus.BAD_REQUEST, "taskId is required");
        }
        String normalizedAgentId = normalizeAgentId(requestedAgentId);
        if (!enterpriseAdminService.canAccessAgent(identity.userId(), normalizedAgentId)) {
            throw new PublishedAgentApiException(HttpStatus.FORBIDDEN, "Current role is not allowed to use this Agent");
        }
        AgentTaskResponse task;
        try {
            task = taskService.get(identity.tenantId(), taskId.trim()).orElse(null);
        } catch (IllegalArgumentException ex) {
            task = null;
        }
        if (task == null || !identity.tenantId().equals(task.tenantId())) {
            throw new PublishedAgentApiException(HttpStatus.NOT_FOUND, "Published Agent task not found");
        }
        if (!normalizedAgentId.equals(normalizeAgentId(task.agentId()))) {
            throw new PublishedAgentApiException(HttpStatus.NOT_FOUND, "Published Agent task not found");
        }
        if (!identity.userId().equals(task.userId())) {
            throw new PublishedAgentApiException(HttpStatus.FORBIDDEN, "Task belongs to another user");
        }
        return task;
    }

    private SkillDefinition requirePublishedAndAuthorized(String agentId, String userId) {
        String normalizedAgentId = normalizeAgentId(agentId);
        if (!skillCatalogService.isPublished(normalizedAgentId)) {
            throw new PublishedAgentApiException(HttpStatus.NOT_FOUND, "Published Agent not found");
        }
        if (!enterpriseAdminService.canAccessAgent(userId, normalizedAgentId)) {
            throw new PublishedAgentApiException(HttpStatus.FORBIDDEN, "Current role is not allowed to use this Agent");
        }
        SkillDefinition agent = skillCatalogService.resolve(normalizedAgentId);
        if (agent == null || !normalizedAgentId.equals(normalizeAgentId(agent.id()))) {
            throw new PublishedAgentApiException(HttpStatus.NOT_FOUND, "Published Agent not found");
        }
        return agent;
    }

    private RequestIdentity requireIdentity(HttpServletRequest request) {
        String tenantId = requestAttribute(request, ApiAuthenticationFilter.CURRENT_TENANT_ID);
        String userId = requestAttribute(request, ApiAuthenticationFilter.CURRENT_USER_ID);
        if (!hasText(tenantId) || !hasText(userId)) {
            throw new PublishedAgentApiException(HttpStatus.UNAUTHORIZED, "Authentication is required");
        }
        return new RequestIdentity(tenantId.trim(), userId.trim());
    }

    private boolean isPlatformAdmin(RequestIdentity identity, HttpServletRequest request) {
        String username = requestAttribute(request, ApiAuthenticationFilter.CURRENT_USERNAME);
        return "admin".equalsIgnoreCase(username) && enterpriseAdminService.hasAllAgentAccess(identity.userId());
    }

    private boolean terminal(AgentTaskResponse task) {
        try {
            return AgentExecutionState.fromWire(task.canonicalState()).terminal();
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private String scopedIdempotencyKey(String userId, String agentId, String value) {
        if (!hasText(value)) {
            return null;
        }
        String material = userId + "\n" + agentId + "\n" + value.trim();
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(material.getBytes(StandardCharsets.UTF_8));
            return "published-api:" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    private Map<String, Object> sanitizeParameters(Map<String, Object> parameters) {
        Map<String, Object> sanitized = new LinkedHashMap<>();
        if (parameters == null) {
            return sanitized;
        }
        parameters.forEach((key, value) -> {
            if (hasText(key) && !key.trim().startsWith("__")) {
                sanitized.put(key.trim(), value);
            }
        });
        return sanitized;
    }

    private List<String> cleanTextList(List<String> values) {
        if (values == null) {
            return new ArrayList<>();
        }
        return values.stream().filter(this::hasText).map(String::trim).distinct().toList();
    }

    private Integer normalizeHistoryWindow(Integer value) {
        return value == null ? null : Math.max(0, Math.min(MAX_HISTORY_WINDOW, value));
    }

    private String normalizeAgentId(String value) {
        if (!hasText(value)) {
            throw new PublishedAgentApiException(HttpStatus.BAD_REQUEST, "agentId is required");
        }
        String normalized = value.trim().toLowerCase();
        if (!normalized.matches("[a-z0-9_-]{2,64}")) {
            throw new PublishedAgentApiException(HttpStatus.BAD_REQUEST, "agentId is invalid");
        }
        return normalized;
    }

    private String requestAttribute(HttpServletRequest request, String name) {
        Object value = request == null ? null : request.getAttribute(name);
        return value == null ? null : String.valueOf(value);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String shellDoubleQuoted(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$");
    }

    private String statusPath(String agentId, String taskId) {
        return AppConstants.API_V1 + "/published-agents/" + agentId + "/questions/" + taskId + "/status";
    }

    private String answerPath(String agentId, String taskId) {
        return AppConstants.API_V1 + "/published-agents/" + agentId + "/questions/" + taskId + "/answer";
    }

    private <T> ResponseEntity<ApiResponse<T>> ok(T data) {
        return ResponseEntity.ok(ApiResponse.success(data));
    }

    private <T> ResponseEntity<ApiResponse<T>> badRequest(String message) {
        return ResponseEntity.badRequest().body(ApiResponse.badRequest(message));
    }

    private <T> ResponseEntity<ApiResponse<T>> forbidden(String message) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResponse.error(403, message));
    }

    @org.springframework.web.bind.annotation.ExceptionHandler(PublishedAgentApiException.class)
    public ResponseEntity<ApiResponse<Void>> handleApiException(PublishedAgentApiException ex) {
        return ResponseEntity.status(ex.status()).body(ApiResponse.error(ex.status().value(), ex.getMessage()));
    }

    public record PublishedAgentQuestion(
        String question,
        String sessionId,
        String idempotencyKey,
        Integer historyWindow,
        List<String> imageAnalysisIds,
        Map<String, Object> parameters
    ) {
    }

    public record PublishedAgentSubmission(
        String taskId,
        String executionId,
        String attemptId,
        String sessionId,
        String agentId,
        String status,
        String canonicalState,
        String statusUrl,
        String answerUrl,
        Instant submittedAt
    ) {
    }

    public record PublishedAgentRunStatus(
        String taskId,
        String executionId,
        String attemptId,
        int attemptNumber,
        String sessionId,
        String agentId,
        String status,
        String canonicalState,
        boolean terminal,
        boolean answerAvailable,
        String error,
        Instant createdAt,
        Instant updatedAt,
        String answerUrl
    ) {
    }

    public record PublishedAgentAnswer(
        String taskId,
        String executionId,
        String attemptId,
        String sessionId,
        String agentId,
        String status,
        String canonicalState,
        boolean terminal,
        boolean ready,
        String answer,
        List<Map<String, Object>> references,
        String error,
        Instant updatedAt
    ) {
    }

    public record PublishedAgentCurlExample(
        String agentId,
        String agentName,
        String baseUrl,
        String tokenEnvironmentVariable,
        String submitCurl,
        String statusCurl,
        String answerCurl,
        String completeExample,
        String securityNotice
    ) {
    }

    private record RequestIdentity(String tenantId, String userId) {
    }

    private static final class PublishedAgentApiException extends RuntimeException {
        private final HttpStatus status;

        private PublishedAgentApiException(HttpStatus status, String message) {
            super(message);
            this.status = status;
        }

        private HttpStatus status() {
            return status;
        }
    }
}
