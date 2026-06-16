package com.chatchat.api.agent.task;

import com.chatchat.agents.runtime.ToolRuntimeProperties;
import com.chatchat.agents.runtime.ToolRuntimeSnapshot;
import com.chatchat.agents.runtime.plan.InterpretationPlanRecord;
import com.chatchat.agents.runtime.plan.InterpretationPlanStore;
import com.chatchat.agents.tool.ToolRegistry;
import com.chatchat.chat.task.AgentEffectAnalytics;
import com.chatchat.chat.task.AgentEvent;
import com.chatchat.chat.task.AgentExperienceSummary;
import com.chatchat.chat.task.AgentLearningService;
import com.chatchat.chat.task.AgentTaskFeedbackRequest;
import com.chatchat.chat.task.AgentRuntimeSummary;
import com.chatchat.chat.task.AgentTaskResponse;
import com.chatchat.chat.task.AgentTaskService;
import com.chatchat.chat.task.AgentTaskSubmitRequest;
import com.chatchat.chat.task.AgentTodoService;
import com.chatchat.api.security.ApiAuthenticationFilter;
import com.chatchat.common.constants.AppConstants;
import com.chatchat.common.response.ApiResponse;
import com.chatchat.common.tool.ToolMetadata;
import com.chatchat.enterprise.entity.SysAuditLog;
import com.chatchat.enterprise.repository.SysAuditLogRepository;
import com.chatchat.enterprise.service.EnterpriseAdminService;
import com.chatchat.integration.mcp.service.McpToolRegistryBridge;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@RestController
@RequiredArgsConstructor
@RequestMapping(AppConstants.API_V1 + "/agent/tasks")
@Tag(name = "Agent Tasks", description = "Tenant-isolated async Agent task APIs")
public class AgentTaskController {

    private final AgentTaskService taskService;
    private final SysAuditLogRepository auditLogRepository;
    private final ObjectMapper objectMapper;
    private final ToolRegistry toolRegistry;
    private final ToolRuntimeProperties toolRuntimeProperties;
    private final McpToolRegistryBridge mcpToolRegistryBridge;
    private final AgentLearningService learningService;
    private final EnterpriseAdminService enterpriseAdminService;
    private final AgentTodoService todoService;
    private final InterpretationPlanStore interpretationPlanStore;

    /**
     * Performs the submit operation.
     *
     * @param request the request value
     * @return the operation result
     */
    @PostMapping
    @Operation(summary = "Submit one async Agent task")
    public ApiResponse<AgentTaskResponse> submit(@RequestBody AgentTaskSubmitRequest request,
                                                 HttpServletRequest servletRequest) {
        try {
            String requestedAgentId = firstText(request.getSkillId(), request.getAgentId());
            String currentUserId = currentUserId(servletRequest);
            if (requestedAgentId != null
                && currentUserId != null
                && !enterpriseAdminService.canAccessAgent(currentUserId, requestedAgentId)) {
                return ApiResponse.badRequest("Current role is not allowed to use this Agent");
            }
            if ((request.getUserId() == null || request.getUserId().isBlank()) && currentUsername(servletRequest) != null) {
                request.setUserId(currentUsername(servletRequest));
            }
            return ApiResponse.success(taskService.submit(request), "Agent task submitted");
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        } catch (Exception e) {
            return ApiResponse.internalError("Agent task submission failed: " + e.getMessage());
        }
    }

    /**
     * Returns the get.
     *
     * @param tenantId the tenant id value
     * @param taskId the task id value
     * @return the get
     */
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

    /**
     * Lists the list.
     *
     * @param tenantId the tenant id value
     * @param sessionId the session id value
     * @param page the page value
     * @param pageSize the page size value
     * @return the list list
     */
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

    /**
     * Runs the configured startup logic.
     *
     * @param tenantId the tenant id value
     * @param latestLimit the latest limit value
     * @return the operation result
     */
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

    @GetMapping("/runtime/todos")
    @Operation(summary = "List Runtime todos that need human handling")
    public ApiResponse<AgentTodoService.TodoTaskPayload> todos(@RequestParam("tenantId") String tenantId,
                                                               @RequestParam(value = "userId", required = false) String userId,
                                                               @RequestParam(value = "limit", defaultValue = "20") int limit) {
        try {
            return ApiResponse.success(todoService.listTodos(tenantId, userId, limit));
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }

    @PostMapping("/runtime/todos/{todoId}/actions")
    @Operation(summary = "Resolve one Runtime todo")
    public ApiResponse<AgentTodoService.TodoActionResult> todoAction(@PathVariable("todoId") String todoId,
                                                                     @RequestBody AgentTodoService.TodoActionRequest request) {
        try {
            return ApiResponse.success(todoService.executeAction(todoId, request), "Runtime todo handled");
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        } catch (Exception e) {
            return ApiResponse.internalError("Runtime todo action failed: " + e.getMessage());
        }
    }

    /**
     * Converts the value to ol audits.
     *
     * @param tenantId the tenant id value
     * @param outcome the outcome value
     * @param limit the limit value
     * @return the converted ol audits
     */
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

    /**
     * Performs the events operation.
     *
     * @param tenantId the tenant id value
     * @param taskId the task id value
     * @param limit the limit value
     * @return the operation result
     */
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

    @GetMapping("/{taskId}/plan")
    @Operation(summary = "Get latest InterpretationPlan snapshot for one task")
    public ApiResponse<InterpretationPlanRecord> plan(@RequestParam("tenantId") String tenantId,
                                                      @PathVariable("taskId") String taskId) {
        try {
            return ApiResponse.success(interpretationPlanStore.getSnapshot(requireTenant(tenantId), taskId).orElse(null));
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }

    @GetMapping("/{taskId}/plan-dag")
    @Operation(summary = "Get latest InterpretationPlan DAG for one task")
    public ApiResponse<Map<String, Object>> planDag(@RequestParam("tenantId") String tenantId,
                                                    @PathVariable("taskId") String taskId) {
        try {
            return ApiResponse.success(interpretationPlanStore
                .getSnapshot(requireTenant(tenantId), taskId)
                .map(this::toPlanDagPayload)
                .orElse(null));
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }

    @GetMapping("/{taskId}/plan/versions")
    @Operation(summary = "List InterpretationPlan versions for one task")
    public ApiResponse<List<InterpretationPlanRecord>> planVersions(@RequestParam("tenantId") String tenantId,
                                                                    @PathVariable("taskId") String taskId) {
        try {
            return ApiResponse.success(interpretationPlanStore.listVersions(requireTenant(tenantId), taskId));
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }

    /**
     * Performs the poll result operation.
     *
     * @param tenantId the tenant id value
     * @param taskId the task id value
     * @param timeoutMs the timeout ms value
     * @return the operation result
     */
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

    /**
     * Returns whether cancel.
     *
     * @param tenantId the tenant id value
     * @param taskId the task id value
     * @return whether the condition is satisfied
     */
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

    @PostMapping("/runtime/tasks/{taskId}/confirm")
    @Operation(summary = "Confirm one waiting Runtime task")
    public ApiResponse<AgentTaskResponse> confirm(@RequestParam("tenantId") String tenantId,
                                                  @PathVariable("taskId") String taskId,
                                                  @RequestBody(required = false) AgentTaskSubmitRequest request) {
        try {
            return ApiResponse.success(taskService.confirm(tenantId, taskId, request), "Runtime task confirmed");
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ApiResponse.badRequest(e.getMessage());
        } catch (Exception e) {
            return ApiResponse.internalError("Runtime task confirmation failed: " + e.getMessage());
        }
    }

    @PostMapping("/runtime/tasks/{taskId}/reject")
    @Operation(summary = "Reject one waiting Runtime task")
    public ApiResponse<AgentTaskResponse> reject(@RequestParam("tenantId") String tenantId,
                                                 @PathVariable("taskId") String taskId,
                                                 @RequestBody(required = false) Map<String, Object> request,
                                                 HttpServletRequest servletRequest) {
        try {
            String userId = firstText(
                request == null ? null : stringValue(request.get("userId")),
                currentUsername(servletRequest),
                currentUserId(servletRequest)
            );
            return ApiResponse.success(taskService.reject(tenantId, taskId, userId), "Runtime task rejected");
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ApiResponse.badRequest(e.getMessage());
        } catch (Exception e) {
            return ApiResponse.internalError("Runtime task rejection failed: " + e.getMessage());
        }
    }

    @PostMapping("/runtime/tasks/{taskId}/kill")
    @Operation(summary = "Kill one active Runtime task")
    public ApiResponse<AgentTaskResponse> kill(@RequestParam("tenantId") String tenantId,
                                               @PathVariable("taskId") String taskId) {
        try {
            return ApiResponse.success(taskService.kill(tenantId, taskId), "Runtime task killed");
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        } catch (Exception e) {
            return ApiResponse.internalError("Runtime task kill failed: " + e.getMessage());
        }
    }

    /**
     * Performs the retry operation.
     *
     * @param tenantId the tenant id value
     * @param taskId the task id value
     * @return the operation result
     */
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

    private String currentUserId(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        Object value = request.getAttribute(ApiAuthenticationFilter.CURRENT_USER_ID);
        return value == null ? null : String.valueOf(value);
    }

    private String currentUsername(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        Object value = request.getAttribute(ApiAuthenticationFilter.CURRENT_USERNAME);
        return value == null ? null : String.valueOf(value);
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

    /**
     * Performs the effect analytics operation.
     *
     * @param tenantId the tenant id value
     * @param lowScoreLimit the low score limit value
     * @return the operation result
     */
    @GetMapping("/runtime/effects")
    @Operation(summary = "Get Agent effect analytics from user feedback")
    public ApiResponse<AgentEffectAnalytics> effects(@RequestParam("tenantId") String tenantId,
                                                     @RequestParam(value = "lowScoreLimit", defaultValue = "10") int lowScoreLimit) {
        try {
            return ApiResponse.success(taskService.summarizeEffectAnalytics(tenantId, lowScoreLimit));
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }

    /**
     * Performs the experiences operation.
     *
     * @param tenantId the tenant id value
     * @param limit the limit value
     * @return the operation result
     */
    @GetMapping("/runtime/experiences")
    @Operation(summary = "Get Agent Experience Store entries and scenario metrics")
    public ApiResponse<AgentExperienceSummary> experiences(@RequestParam("tenantId") String tenantId,
                                                           @RequestParam(value = "limit", defaultValue = "20") int limit) {
        try {
            return ApiResponse.success(learningService.summarize(tenantId, limit));
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }

    /**
     * Performs the tool governance operation.
     *
     * @param tenantId the tenant id value
     * @return the operation result
     */
    @GetMapping("/runtime/tool-governance")
    @Operation(summary = "List tool governance levels and default runtime actions")
    public ApiResponse<ToolGovernanceSummary> toolGovernance(@RequestParam("tenantId") String tenantId) {
        try {
            String normalizedTenant = requireTenant(tenantId);
            Map<String, McpToolRegistryBridge.RegisteredMcpTool> mcpToolsByName = mcpToolRegistryBridge
                .listRegisteredTools()
                .stream()
                .collect(Collectors.toMap(
                    McpToolRegistryBridge.RegisteredMcpTool::localToolName,
                    Function.identity(),
                    (left, right) -> left,
                    LinkedHashMap::new
                ));
            Map<String, ToolRuntimeSnapshot.ToolMetric> runtimeMetrics = taskService
                .summarizeRuntime(normalizedTenant, 1)
                .toolRuntime()
                .topTools()
                .stream()
                .collect(Collectors.toMap(
                    ToolRuntimeSnapshot.ToolMetric::toolName,
                    Function.identity(),
                    (left, right) -> left,
                    LinkedHashMap::new
                ));
            Set<String> names = new LinkedHashSet<>(toolRegistry.getAllToolNames());
            names.addAll(mcpToolsByName.keySet());
            List<ToolGovernanceView> tools = names.stream()
                .filter(name -> name != null && !name.isBlank())
                .map(name -> toToolGovernanceView(name, mcpToolsByName.get(name), runtimeMetrics.get(name)))
                .sorted(Comparator.comparing(ToolGovernanceView::runtimeLevel).thenComparing(ToolGovernanceView::toolName))
                .toList();
            Map<String, Long> levelCounts = tools.stream()
                .collect(Collectors.groupingBy(ToolGovernanceView::runtimeLevel, LinkedHashMap::new, Collectors.counting()));
            return ApiResponse.success(new ToolGovernanceSummary(normalizedTenant, tools.size(), levelCounts, tools));
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }

    @PostMapping("/{taskId}/feedback")
    @Operation(summary = "Record product discovery feedback for one completed Agent task")
    public ApiResponse<AgentTaskResponse> feedback(@RequestParam("tenantId") String tenantId,
                                                   @PathVariable("taskId") String taskId,
                                                   @RequestBody AgentTaskFeedbackRequest request) {
        try {
            return ApiResponse.success(taskService.recordFeedback(tenantId, taskId, request), "Agent task feedback recorded");
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        } catch (Exception e) {
            return ApiResponse.internalError("Agent task feedback failed: " + e.getMessage());
        }
    }

    /**
     * Converts the value to tool audit view.
     *
     * @param log the log value
     * @return the converted tool audit view
     */
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

    private Map<String, Object> toPlanDagPayload(InterpretationPlanRecord record) {
        Map<String, Object> dag = record.dag() == null || record.dag().isEmpty()
            ? readJsonMap(record.dagJson())
            : new LinkedHashMap<>(record.dag());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("tenantId", record.tenantId());
        payload.put("taskId", record.taskId());
        payload.put("planId", record.planId());
        payload.put("version", record.version());
        payload.put("status", record.status());
        payload.put("createdAt", record.createdAt());
        payload.put("updatedAt", record.updatedAt());
        payload.put("nodes", dag.getOrDefault("nodes", List.of()));
        payload.put("edges", dag.getOrDefault("edges", List.of()));
        payload.put("summary", dag.getOrDefault("summary", Map.of()));
        return payload;
    }

    private Map<String, Object> readJsonMap(String value) {
        if (value == null || value.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(value, new TypeReference<>() {
            });
        } catch (Exception ex) {
            return Map.of("raw", value);
        }
    }

    private ToolGovernanceView toToolGovernanceView(String toolName,
                                                    McpToolRegistryBridge.RegisteredMcpTool mcpTool,
                                                    ToolRuntimeSnapshot.ToolMetric metric) {
        ToolMetadata metadata = toolRegistry.getToolMetadata(toolName);
        ToolRegistry.Tool simpleTool = toolRegistry.getTool(toolName);
        String runtimeLevel = normalizeRuntimeLevel(firstText(
            configuredRuntimeLevel(toolName),
            metadata == null ? null : metadata.getRuntimeLevel(),
            toolRuntimeProperties.getDefaultRuntimeLevel()
        ));
        String sourceType = mcpTool == null ? "backend" : "mcp";
        return new ToolGovernanceView(
            toolName,
            firstText(metadata == null ? null : metadata.getTitle(), simpleTool == null ? null : simpleTool.getName(), toolName),
            sourceType,
            mcpTool == null ? null : mcpTool.serviceId(),
            mcpTool == null ? null : mcpTool.serviceName(),
            runtimeLevel,
            actionForRuntimeLevel(runtimeLevel),
            "confirm_required".equals(runtimeLevel),
            "forbidden".equals(runtimeLevel),
            mcpTool != null,
            metadata == null ? null : metadata.getRiskLevel(),
            metadata == null ? null : metadata.getOperationType(),
            metadata != null && metadata.isRequiresAuth(),
            metadata != null && metadata.isRateLimited(),
            metric == null ? 0L : metric.totalCalls(),
            metric == null ? 0L : metric.successCalls(),
            metric == null ? 0L : metric.failedCalls(),
            metric == null ? 0L : metric.deniedCalls(),
            metric == null ? 0L : metric.rateLimitedCalls(),
            metric == null ? 0L : metric.circuitOpenRejects()
        );
    }

    /**
     * Reads the json.
     *
     * @param value the value value
     * @return the operation result
     */
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

    /**
     * Performs the long value operation.
     *
     * @param node the node value
     * @param field the field value
     * @return the operation result
     */
    private Long longValue(JsonNode node, String field) {
        if (node == null || field == null || field.isBlank()) {
            return null;
        }
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asLong();
    }

    /**
     * Performs the text value operation.
     *
     * @param node the node value
     * @param field the field value
     * @return the operation result
     */
    private String textValue(JsonNode node, String field) {
        if (node == null || field == null || field.isBlank()) {
            return null;
        }
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : normalizeText(value.asText());
    }

    /**
     * Performs the require tenant operation.
     *
     * @param tenantId the tenant id value
     * @return the operation result
     */
    private String requireTenant(String tenantId) {
        String value = normalizeText(tenantId);
        if (value == null) {
            throw new IllegalArgumentException("Tenant ID cannot be empty");
        }
        return value;
    }

    /**
     * Normalizes the text.
     *
     * @param value the value value
     * @return the operation result
     */
    private String normalizeText(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String configuredRuntimeLevel(String toolName) {
        if (toolRuntimeProperties.getLevelPolicy() == null || toolName == null) {
            return null;
        }
        return toolRuntimeProperties.getLevelPolicy().get(toolName);
    }

    private String normalizeRuntimeLevel(String value) {
        String normalized = normalizeText(value);
        if (normalized == null) {
            return "readonly";
        }
        normalized = normalized.toLowerCase().replace('-', '_');
        return switch (normalized) {
            case "suggestion", "confirm_required", "forbidden" -> normalized;
            default -> "readonly";
        };
    }

    private String actionForRuntimeLevel(String runtimeLevel) {
        return switch (normalizeRuntimeLevel(runtimeLevel)) {
            case "forbidden" -> "deny";
            case "confirm_required" -> "ask_before_execute";
            default -> "auto_execute";
        };
    }

    /**
     * Performs the first text operation.
     *
     * @param primary the primary value
     * @param fallback the fallback value
     * @return the operation result
     */
    private String firstText(String primary, String fallback) {
        return primary == null || primary.isBlank() ? fallback : primary;
    }

    private String firstText(String first, String second, String third) {
        return firstText(firstText(first, second), third);
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

    public record ToolGovernanceSummary(
        String tenantId,
        int totalTools,
        Map<String, Long> levelCounts,
        List<ToolGovernanceView> tools
    ) {
    }

    public record ToolGovernanceView(
        String toolName,
        String displayName,
        String sourceType,
        String serviceId,
        String serviceName,
        String runtimeLevel,
        String defaultAction,
        boolean confirmationRequired,
        boolean disabled,
        boolean mcpSynchronized,
        String riskLevel,
        String operationType,
        boolean requiresAuth,
        boolean rateLimited,
        long totalCalls,
        long successCalls,
        long failedCalls,
        long deniedCalls,
        long rateLimitedCalls,
        long circuitOpenRejects
    ) {
    }
}
