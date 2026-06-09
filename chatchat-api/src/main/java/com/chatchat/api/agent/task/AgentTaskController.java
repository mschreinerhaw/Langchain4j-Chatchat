package com.chatchat.api.agent.task;

import com.chatchat.chat.task.AgentEvent;
import com.chatchat.chat.task.AgentRuntimeSummary;
import com.chatchat.chat.task.AgentTaskResponse;
import com.chatchat.chat.task.AgentTaskService;
import com.chatchat.chat.task.AgentTaskSubmitRequest;
import com.chatchat.common.constants.AppConstants;
import com.chatchat.common.response.ApiResponse;
import com.chatchat.enterprise.entity.SysAuditLog;
import com.chatchat.enterprise.repository.SysAuditLogRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping(AppConstants.API_V1 + "/agent/tasks")
@Tag(name = "Agent Tasks", description = "Tenant-isolated async Agent task APIs")
public class AgentTaskController {

    private final AgentTaskService taskService;
    private final SysAuditLogRepository auditLogRepository;
    private final ObjectMapper objectMapper;

    @PostMapping
    @Operation(summary = "Submit one async Agent task")
    public ApiResponse<AgentTaskResponse> submit(@RequestBody AgentTaskSubmitRequest request) {
        try {
            return ApiResponse.success(taskService.submit(request), "Agent task submitted");
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        } catch (Exception e) {
            return ApiResponse.internalError("Agent task submission failed: " + e.getMessage());
        }
    }

    @GetMapping("/{taskId}")
    @Operation(summary = "Get latest Agent task status")
    public ApiResponse<AgentTaskResponse> get(@RequestParam("tenantId") String tenantId,
                                              @PathVariable("taskId") String taskId) {
        try {
            return taskService.get(tenantId, taskId)
                .map(ApiResponse::success)
                .orElseGet(() -> ApiResponse.notFound("Task not found: " + taskId));
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }

    @GetMapping
    @Operation(summary = "List latest Agent tasks")
    public ApiResponse<List<AgentTaskResponse>> list(@RequestParam("tenantId") String tenantId,
                                                     @RequestParam(value = "sessionId", required = false) String sessionId,
                                                     @RequestParam(value = "page", defaultValue = "1") int page,
                                                     @RequestParam(value = "pageSize", defaultValue = "20") int pageSize) {
        try {
            return ApiResponse.success(taskService.list(tenantId, sessionId, page, pageSize));
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }

    @GetMapping("/runtime")
    @Operation(summary = "Get Agent Runtime dashboard summary")
    public ApiResponse<AgentRuntimeSummary> runtime(@RequestParam("tenantId") String tenantId,
                                                    @RequestParam(value = "latestLimit", defaultValue = "10") int latestLimit) {
        try {
            return ApiResponse.success(taskService.summarizeRuntime(tenantId, latestLimit));
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }

    @GetMapping("/runtime/tool-audits")
    @Operation(summary = "List recent tenant tool runtime audit logs")
    public ApiResponse<List<ToolAuditView>> toolAudits(@RequestParam("tenantId") String tenantId,
                                                       @RequestParam(value = "outcome", required = false) String outcome,
                                                       @RequestParam(value = "limit", defaultValue = "20") int limit) {
        try {
            String normalizedTenant = requireTenant(tenantId);
            String normalizedOutcome = normalizeText(outcome);
            int normalizedLimit = Math.max(1, Math.min(limit, 50));
            List<ToolAuditView> data = auditLogRepository
                .findTop100ByTenantIdAndModuleNameOrderByCreatedAtDesc(normalizedTenant, "tool_runtime")
                .stream()
                .filter(log -> normalizedOutcome == null || normalizedOutcome.equalsIgnoreCase(log.getResult()))
                .limit(normalizedLimit)
                .map(this::toToolAuditView)
                .toList();
            return ApiResponse.success(data);
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }

    @GetMapping("/{taskId}/events")
    @Operation(summary = "List RocksDB Agent event history for one task")
    public ApiResponse<List<AgentEvent>> events(@RequestParam("tenantId") String tenantId,
                                                @PathVariable("taskId") String taskId,
                                                @RequestParam(value = "limit", defaultValue = "50") int limit) {
        try {
            return ApiResponse.success(taskService.listEvents(tenantId, taskId, limit));
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }

    @GetMapping("/{taskId}/result")
    @Operation(summary = "Poll Agent task result queue")
    public ApiResponse<AgentEvent> pollResult(@RequestParam("tenantId") String tenantId,
                                              @PathVariable("taskId") String taskId,
                                              @RequestParam(value = "timeoutMs", defaultValue = "1200") long timeoutMs) {
        try {
            return ApiResponse.success(taskService.pollResult(tenantId, taskId, timeoutMs).orElse(null));
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }

    @PostMapping("/{taskId}/cancel")
    @Operation(summary = "Cancel one active Agent task")
    public ApiResponse<AgentTaskResponse> cancel(@RequestParam("tenantId") String tenantId,
                                                 @PathVariable("taskId") String taskId) {
        try {
            return ApiResponse.success(taskService.cancel(tenantId, taskId), "Agent task cancelled");
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        } catch (Exception e) {
            return ApiResponse.internalError("Agent task cancellation failed: " + e.getMessage());
        }
    }

    @PostMapping("/{taskId}/retry")
    @Operation(summary = "Retry one failed or cancelled Agent task")
    public ApiResponse<AgentTaskResponse> retry(@RequestParam("tenantId") String tenantId,
                                                @PathVariable("taskId") String taskId) {
        try {
            return ApiResponse.success(taskService.retry(tenantId, taskId), "Agent task retried");
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        } catch (Exception e) {
            return ApiResponse.internalError("Agent task retry failed: " + e.getMessage());
        }
    }

    private ToolAuditView toToolAuditView(SysAuditLog log) {
        JsonNode detail = readJson(log.getDetail());
        return new ToolAuditView(
            log.getId(),
            log.getCreatedAt(),
            log.getTenantId(),
            firstText(textValue(detail, "toolName"), log.getResourceId()),
            firstText(textValue(detail, "outcome"), log.getResult()),
            log.getResult(),
            firstText(textValue(detail, "userId"), log.getActorId()),
            textValue(detail, "mode"),
            textValue(detail, "serviceId"),
            textValue(detail, "requestId"),
            textValue(detail, "conversationId"),
            longValue(detail, "durationMs"),
            textValue(detail, "errorCode"),
            textValue(detail, "errorMessage")
        );
    }

    private JsonNode readJson(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(value);
        } catch (Exception ignored) {
            return null;
        }
    }

    private Long longValue(JsonNode node, String field) {
        if (node == null || field == null || field.isBlank()) {
            return null;
        }
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asLong();
    }

    private String textValue(JsonNode node, String field) {
        if (node == null || field == null || field.isBlank()) {
            return null;
        }
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : normalizeText(value.asText());
    }

    private String requireTenant(String tenantId) {
        String value = normalizeText(tenantId);
        if (value == null) {
            throw new IllegalArgumentException("Tenant ID cannot be empty");
        }
        return value;
    }

    private String normalizeText(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String firstText(String primary, String fallback) {
        return primary == null || primary.isBlank() ? fallback : primary;
    }

    public record ToolAuditView(
        String id,
        Instant createdAt,
        String tenantId,
        String toolName,
        String outcome,
        String result,
        String userId,
        String mode,
        String serviceId,
        String requestId,
        String conversationId,
        Long durationMs,
        String errorCode,
        String errorMessage
    ) {
    }
}
