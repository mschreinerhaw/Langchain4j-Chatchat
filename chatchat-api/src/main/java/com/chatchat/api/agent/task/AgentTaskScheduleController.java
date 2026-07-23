package com.chatchat.api.agent.task;

import com.chatchat.api.security.ApiAuthenticationFilter;
import com.chatchat.chat.task.AgentScheduledTaskService;
import com.chatchat.chat.task.ScheduledAgentTaskRequest;
import com.chatchat.chat.task.ScheduledTaskRunResponse;
import com.chatchat.chat.task.ScheduledTaskResponse;
import com.chatchat.chat.task.ScheduledTaskPageResponse;
import com.chatchat.chat.task.ScheduledTaskRunAuditPageResponse;
import com.chatchat.chat.task.ScheduledNotificationHistoryPageResponse;
import com.chatchat.chat.task.TenantNotificationRecipientService;
import com.chatchat.chat.skills.SkillCatalogService;
import com.chatchat.common.constants.AppConstants;
import com.chatchat.common.response.ApiResponse;
import com.chatchat.enterprise.service.EnterpriseAdminService;
import com.chatchat.integration.mcp.service.McpNotificationClient;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@RestController
@RequiredArgsConstructor
@RequestMapping(AppConstants.API_V1 + "/agent/tasks/runtime/schedules")
@Tag(name = "Agent Runtime Task Scheduler", description = "Lightweight Agent Runtime scheduled tasks")
public class AgentTaskScheduleController {

    private final AgentScheduledTaskService scheduledTaskService;
    private final EnterpriseAdminService enterpriseAdminService;
    private final McpNotificationClient notificationClient;
    private final TenantNotificationRecipientService recipientService;
    private final SkillCatalogService skillCatalogService;

    @GetMapping("/notification-channels")
    @Operation(summary = "List enabled MCP notification channels for scheduler selection")
    public ApiResponse<List<NotificationChannelBindingView>> notificationChannels(HttpServletRequest servletRequest) {
        try {
            String tenantId = scopedTenantId(servletRequest, null);
            Map<String, TenantNotificationRecipientService.RecipientView> recipients = recipientService.list(tenantId).stream()
                .collect(Collectors.toMap(TenantNotificationRecipientService.RecipientView::channelType, Function.identity()));
            return ApiResponse.success(notificationClient.listEnabled().stream().map(option -> {
                TenantNotificationRecipientService.RecipientView recipient = recipients.get(option.channel());
                return new NotificationChannelBindingView(option.id(), option.channel(), option.toolName(), option.title(),
                    option.description(), option.deliveryMode(), option.recipientAware(),
                    recipient == null ? null : recipient.receiver(), recipient != null);
            }).toList());
        } catch (Exception e) {
            return ApiResponse.internalError("MCP通知类型加载失败: " + e.getMessage());
        }
    }

    @PutMapping("/notification-recipients/{channelType}")
    @Operation(summary = "Bind one notification recipient for the current tenant")
    public ApiResponse<TenantNotificationRecipientService.RecipientView> saveNotificationRecipient(
        @PathVariable("channelType") String channelType,
        @RequestBody NotificationRecipientRequest request,
        HttpServletRequest servletRequest
    ) {
        try {
            return ApiResponse.success(recipientService.save(
                scopedTenantId(servletRequest, null), channelType, request == null ? null : request.receiver()
            ), "租户通知接收人已保存");
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }

    @DeleteMapping("/notification-recipients/{channelType}")
    @Operation(summary = "Remove one notification recipient binding for the current tenant")
    public ApiResponse<Void> deleteNotificationRecipient(@PathVariable("channelType") String channelType,
                                                         HttpServletRequest servletRequest) {
        try {
            recipientService.delete(scopedTenantId(servletRequest, null), channelType);
            return ApiResponse.success(null, "租户通知接收人绑定已删除");
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }

    @PostMapping
    @Operation(summary = "Create one lightweight Agent Runtime scheduled task")
    public ApiResponse<ScheduledTaskResponse> create(@RequestBody ScheduledAgentTaskRequest request,
                                                     HttpServletRequest servletRequest) {
        try {
            String currentUserId = currentUserId(servletRequest);
            String currentTenantId = currentTenantId(servletRequest);
            if (request != null && currentTenantId != null) {
                request.setTenantId(currentTenantId);
                if (request.getPayload() != null) {
                    request.getPayload().setTenantId(currentTenantId);
                }
            }
            if (request != null && currentUserId != null) {
                request.setUserId(currentUserId);
                if (request.getPayload() != null) {
                    request.getPayload().setUserId(currentUserId);
                }
            }
            String requestedAgentId = requestedAgentId(request);
            if (requestedAgentId == null || !skillCatalogService.isPublished(requestedAgentId)) {
                return ApiResponse.badRequest("Agent未发布，不能创建调度");
            }
            if (requestedAgentId != null
                && currentUserId != null
                && !enterpriseAdminService.canAccessAgent(currentUserId, requestedAgentId)) {
                return ApiResponse.badRequest("Current role is not allowed to schedule this Agent");
            }
            if (currentUserId == null && request != null
                && (request.getUserId() == null || request.getUserId().isBlank())
                && currentUsername(servletRequest) != null) {
                request.setUserId(currentUsername(servletRequest));
            }
            return ApiResponse.success(scheduledTaskService.create(request), "Agent Runtime scheduled task created");
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        } catch (Exception e) {
            return ApiResponse.internalError("Agent Runtime scheduled task creation failed: " + e.getMessage());
        }
    }

    @PutMapping("/{scheduledTaskId}")
    @Operation(summary = "Update one lightweight Agent Runtime scheduled task")
    public ApiResponse<ScheduledTaskResponse> update(@PathVariable("scheduledTaskId") String scheduledTaskId,
                                                     @RequestBody ScheduledAgentTaskRequest request,
                                                     HttpServletRequest servletRequest) {
        try {
            String currentUserId = currentUserId(servletRequest);
            String currentTenantId = scopedTenantId(servletRequest, request == null ? null : request.getTenantId());
            if (request != null) {
                request.setTenantId(currentTenantId);
                if (request.getPayload() != null) {
                    request.getPayload().setTenantId(currentTenantId);
                }
                if (currentUserId != null) {
                    request.setUserId(currentUserId);
                    if (request.getPayload() != null) {
                        request.getPayload().setUserId(currentUserId);
                    }
                }
            }
            String requestedAgentId = requestedAgentId(request);
            if (requestedAgentId == null || !skillCatalogService.isPublished(requestedAgentId)) {
                return ApiResponse.badRequest("Agent未发布，不能保存调度");
            }
            if (currentUserId != null
                && !enterpriseAdminService.canAccessAgent(currentUserId, requestedAgentId)) {
                return ApiResponse.badRequest("Current role is not allowed to schedule this Agent");
            }
            scheduledTaskService.requireAccess(currentTenantId, scheduledTaskId, visibleUserId(servletRequest));
            return ApiResponse.success(
                scheduledTaskService.update(currentTenantId, scheduledTaskId, request),
                "Agent Runtime scheduled task updated"
            );
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        } catch (Exception e) {
            return ApiResponse.internalError("Agent Runtime scheduled task update failed: " + e.getMessage());
        }
    }

    @GetMapping
    @Operation(summary = "List lightweight Agent Runtime scheduled tasks")
    public ApiResponse<ScheduledTaskPageResponse> list(@RequestParam("tenantId") String tenantId,
                                                       @RequestParam(value = "agentId", defaultValue = "") String agentId,
                                                       @RequestParam(value = "status", defaultValue = "") String status,
                                                       @RequestParam(value = "keyword", defaultValue = "") String keyword,
                                                       @RequestParam(value = "keywordAgentIds", defaultValue = "") String keywordAgentIds,
                                                       @RequestParam(value = "page", defaultValue = "1") int page,
                                                       @RequestParam(value = "pageSize", defaultValue = "20") int pageSize,
                                                       HttpServletRequest servletRequest) {
        try {
            return ApiResponse.success(scheduledTaskService.search(
                scopedTenantId(servletRequest, tenantId), visibleUserId(servletRequest), agentId, status, keyword,
                splitRecipients(keywordAgentIds), page, pageSize
            ));
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }

    @GetMapping("/audit")
    @Operation(summary = "Search paged scheduled Agent execution audit records")
    public ApiResponse<ScheduledTaskRunAuditPageResponse> audit(
        @RequestParam("tenantId") String tenantId,
        @RequestParam(value = "agentId", defaultValue = "") String agentId,
        @RequestParam(value = "status", defaultValue = "") String status,
        @RequestParam(value = "keyword", defaultValue = "") String keyword,
        @RequestParam(value = "keywordAgentIds", defaultValue = "") String keywordAgentIds,
        @RequestParam(value = "page", defaultValue = "1") int page,
        @RequestParam(value = "pageSize", defaultValue = "20") int pageSize,
        HttpServletRequest servletRequest
    ) {
        try {
            return ApiResponse.success(scheduledTaskService.audit(
                scopedTenantId(servletRequest, tenantId), visibleUserId(servletRequest), agentId, status, keyword,
                splitRecipients(keywordAgentIds), page, pageSize
            ));
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }

    @PostMapping("/notifications/send")
    @Operation(summary = "Send a notification to maintained contacts with administrator recipient selection")
    public ApiResponse<Map<String, Object>> sendNotification(@RequestBody AdminNotificationRequest request,
        HttpServletRequest servletRequest) {
        try {
            if (request == null) {
                throw new IllegalArgumentException("通知内容不能为空");
            }
            McpNotificationClient.NotificationChannelOption option = notificationClient.requireEnabled(request.channelId());
            String tenantId = scopedTenantId(servletRequest, null);
            Set<String> maintained = new LinkedHashSet<>(recipientService.receiver(tenantId, option.channel())
                .map(this::splitRecipients).orElseGet(List::of));
            if (maintained.isEmpty()) {
                throw new IllegalArgumentException("当前通知方式尚未维护联系人");
            }
            boolean admin = isAdmin(servletRequest);
            List<String> requestedReceivers = splitRecipients(request.receivers());
            List<String> selected = admin ? requestedReceivers : List.copyOf(maintained);
            if (admin && selected.isEmpty()) {
                throw new IllegalArgumentException("请选择至少一个接收人");
            }
            if (admin && !maintained.containsAll(selected)) {
                throw new IllegalArgumentException("只能向当前通知方式中已维护的联系人发送消息");
            }
            String title = requireText(request.title(), "通知标题不能为空");
            String content = requireText(request.content(), "通知内容不能为空");
            String level = firstText(request.level(), "INFO").toUpperCase(Locale.ROOT);
            if (!Set.of("INFO", "WARNING", "CRITICAL").contains(level)) {
                throw new IllegalArgumentException("通知级别不正确");
            }
            int sent = 0;
            for (String receiver : selected) {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("receiver", receiver);
                payload.put("title", title);
                payload.put("content", content);
                payload.put("level", level);
                payload.put("sourceTaskId", "admin-manual-notification");
                notificationClient.dispatch(option.id(), payload);
                sent++;
            }
            return ApiResponse.success(Map.of("sent", sent, "total", selected.size()), "通知发送成功");
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        } catch (Exception e) {
            return ApiResponse.internalError("通知发送失败: " + e.getMessage());
        }
    }

    @GetMapping("/{scheduledTaskId}")
    @Operation(summary = "Get one lightweight Agent Runtime scheduled task")
    public ApiResponse<ScheduledTaskResponse> get(@RequestParam("tenantId") String tenantId,
                                                  @PathVariable("scheduledTaskId") String scheduledTaskId,
                                                  HttpServletRequest servletRequest) {
        try {
            String scopedTenantId = scopedTenantId(servletRequest, tenantId);
            scheduledTaskService.requireAccess(scopedTenantId, scheduledTaskId, visibleUserId(servletRequest));
            return scheduledTaskService.get(scopedTenantId, scheduledTaskId)
                .map(ApiResponse::success)
                .orElseGet(() -> ApiResponse.notFound("Scheduled task not found: " + scheduledTaskId));
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }

    @PostMapping("/{scheduledTaskId}/pause")
    @Operation(summary = "Pause future triggers for one scheduled Agent Runtime task")
    public ApiResponse<ScheduledTaskResponse> pause(@RequestParam("tenantId") String tenantId,
                                                    @PathVariable("scheduledTaskId") String scheduledTaskId,
                                                    HttpServletRequest servletRequest) {
        try {
            String scopedTenantId = scopedTenantId(servletRequest, tenantId);
            scheduledTaskService.requireAccess(scopedTenantId, scheduledTaskId, visibleUserId(servletRequest));
            return ApiResponse.success(scheduledTaskService.pause(scopedTenantId, scheduledTaskId), "Scheduled task paused");
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }

    @PostMapping("/{scheduledTaskId}/resume")
    @Operation(summary = "Resume one paused scheduled Agent Runtime task")
    public ApiResponse<ScheduledTaskResponse> resume(@RequestParam("tenantId") String tenantId,
                                                     @PathVariable("scheduledTaskId") String scheduledTaskId,
                                                     HttpServletRequest servletRequest) {
        try {
            String scopedTenantId = scopedTenantId(servletRequest, tenantId);
            scheduledTaskService.requireAccess(scopedTenantId, scheduledTaskId, visibleUserId(servletRequest));
            return ApiResponse.success(scheduledTaskService.resume(scopedTenantId, scheduledTaskId), "Scheduled task resumed");
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }

    @PostMapping("/{scheduledTaskId}/cancel")
    @Operation(summary = "Cancel one scheduled Agent Runtime task")
    public ApiResponse<ScheduledTaskResponse> cancel(@RequestParam("tenantId") String tenantId,
                                                     @PathVariable("scheduledTaskId") String scheduledTaskId,
                                                     HttpServletRequest servletRequest) {
        try {
            String scopedTenantId = scopedTenantId(servletRequest, tenantId);
            scheduledTaskService.requireAccess(scopedTenantId, scheduledTaskId, visibleUserId(servletRequest));
            return ApiResponse.success(scheduledTaskService.cancel(scopedTenantId, scheduledTaskId), "Scheduled task cancelled");
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }

    @PostMapping("/{scheduledTaskId}/rerun")
    @Operation(summary = "Run one scheduled Agent Runtime task immediately")
    public ApiResponse<ScheduledTaskRunResponse> rerun(@RequestParam("tenantId") String tenantId,
                                                       @PathVariable("scheduledTaskId") String scheduledTaskId,
                                                       HttpServletRequest servletRequest) {
        try {
            String scopedTenantId = scopedTenantId(servletRequest, tenantId);
            scheduledTaskService.requireAccess(scopedTenantId, scheduledTaskId, visibleUserId(servletRequest));
            return ApiResponse.success(scheduledTaskService.rerun(scopedTenantId, scheduledTaskId), "Scheduled task rerun submitted");
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        } catch (Exception e) {
            return ApiResponse.internalError("Scheduled task rerun failed: " + e.getMessage());
        }
    }

    @GetMapping("/{scheduledTaskId}/history")
    @Operation(summary = "List execution history for one scheduled Agent Runtime task")
    public ApiResponse<List<ScheduledTaskRunResponse>> history(@RequestParam("tenantId") String tenantId,
                                                               @PathVariable("scheduledTaskId") String scheduledTaskId,
                                                               @RequestParam(value = "page", defaultValue = "1") int page,
                                                               @RequestParam(value = "pageSize", defaultValue = "20") int pageSize,
                                                               HttpServletRequest servletRequest) {
        try {
            String scopedTenantId = scopedTenantId(servletRequest, tenantId);
            scheduledTaskService.requireAccess(scopedTenantId, scheduledTaskId, visibleUserId(servletRequest));
            return ApiResponse.success(scheduledTaskService.history(scopedTenantId, scheduledTaskId, page, pageSize));
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }

    @GetMapping("/{scheduledTaskId}/notification-history")
    @Operation(summary = "Search paged notification history for one scheduled Agent task")
    public ApiResponse<ScheduledNotificationHistoryPageResponse> notificationHistory(
        @RequestParam("tenantId") String tenantId,
        @PathVariable("scheduledTaskId") String scheduledTaskId,
        @RequestParam(value = "keyword", defaultValue = "") String keyword,
        @RequestParam(value = "page", defaultValue = "1") int page,
        @RequestParam(value = "pageSize", defaultValue = "10") int pageSize,
        HttpServletRequest servletRequest
    ) {
        try {
            String scopedTenantId = scopedTenantId(servletRequest, tenantId);
            scheduledTaskService.requireAccess(scopedTenantId, scheduledTaskId, visibleUserId(servletRequest));
            return ApiResponse.success(scheduledTaskService.notificationHistory(
                scopedTenantId, scheduledTaskId, keyword, page, pageSize
            ));
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }

    @GetMapping("/history")
    @Operation(summary = "List scheduled Agent Runtime execution history by Agent")
    public ApiResponse<List<ScheduledTaskRunResponse>> historyByAgent(@RequestParam("tenantId") String tenantId,
                                                                      @RequestParam("agentId") String agentId,
                                                                      @RequestParam(value = "page", defaultValue = "1") int page,
                                                                      @RequestParam(value = "pageSize", defaultValue = "20") int pageSize,
                                                                      HttpServletRequest servletRequest) {
        try {
            return ApiResponse.success(scheduledTaskService.historyByAgent(
                scopedTenantId(servletRequest, tenantId), visibleUserId(servletRequest), agentId, page, pageSize));
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }

    @DeleteMapping("/{scheduledTaskId}")
    @Operation(summary = "Delete one scheduled Agent Runtime task")
    public ApiResponse<Void> delete(@RequestParam("tenantId") String tenantId,
                                    @PathVariable("scheduledTaskId") String scheduledTaskId,
                                    HttpServletRequest servletRequest) {
        try {
            String scopedTenantId = scopedTenantId(servletRequest, tenantId);
            scheduledTaskService.requireAccess(scopedTenantId, scheduledTaskId, visibleUserId(servletRequest));
            scheduledTaskService.delete(scopedTenantId, scheduledTaskId);
            return ApiResponse.success(null, "Scheduled task deleted");
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }

    private String requestedAgentId(ScheduledAgentTaskRequest request) {
        if (request == null) {
            return null;
        }
        if (request.getPayload() != null) {
            return firstText(request.getPayload().getSkillId(), request.getPayload().getAgentId(), request.getAgentId());
        }
        return firstText(request.getAgentId());
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

    private String currentTenantId(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        Object value = request.getAttribute(ApiAuthenticationFilter.CURRENT_TENANT_ID);
        return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value).trim();
    }

    private String scopedTenantId(HttpServletRequest request, String requestedTenantId) {
        return firstText(currentTenantId(request), requestedTenantId, currentUserId(request), "default");
    }

    public record NotificationRecipientRequest(String receiver) {
    }

    public record AdminNotificationRequest(String channelId, String receivers, String title, String content, String level) {
    }

    public record NotificationChannelBindingView(
        String id,
        String channel,
        String toolName,
        String title,
        String description,
        String deliveryMode,
        boolean recipientAware,
        String receiver,
        boolean bound
    ) {
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

    private List<String> splitRecipients(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(value.split("[,，;；\\n]+"))
            .map(String::trim).filter(item -> !item.isBlank()).distinct().toList();
    }

    private String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }

    private boolean isAdmin(HttpServletRequest request) {
        return "admin".equalsIgnoreCase(currentUsername(request));
    }

    private String visibleUserId(HttpServletRequest request) {
        return isAdmin(request) ? "" : firstText(currentUserId(request), "__anonymous__");
    }
}
