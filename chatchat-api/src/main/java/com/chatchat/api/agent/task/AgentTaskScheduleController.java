package com.chatchat.api.agent.task;

import com.chatchat.api.security.ApiAuthenticationFilter;
import com.chatchat.chat.task.AgentScheduledTaskService;
import com.chatchat.chat.task.ScheduledAgentTaskRequest;
import com.chatchat.chat.task.ScheduledTaskRunResponse;
import com.chatchat.chat.task.ScheduledTaskResponse;
import com.chatchat.common.constants.AppConstants;
import com.chatchat.common.response.ApiResponse;
import com.chatchat.enterprise.service.EnterpriseAdminService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping(AppConstants.API_V1 + "/agent/tasks/runtime/schedules")
@Tag(name = "Agent Runtime Task Scheduler", description = "Lightweight Agent Runtime scheduled tasks")
public class AgentTaskScheduleController {

    private final AgentScheduledTaskService scheduledTaskService;
    private final EnterpriseAdminService enterpriseAdminService;

    @PostMapping
    @Operation(summary = "Create one lightweight Agent Runtime scheduled task")
    public ApiResponse<ScheduledTaskResponse> create(@RequestBody ScheduledAgentTaskRequest request,
                                                     HttpServletRequest servletRequest) {
        try {
            String currentUserId = currentUserId(servletRequest);
            String requestedAgentId = requestedAgentId(request);
            if (requestedAgentId != null
                && currentUserId != null
                && !enterpriseAdminService.canAccessAgent(currentUserId, requestedAgentId)) {
                return ApiResponse.badRequest("Current role is not allowed to schedule this Agent");
            }
            if (request != null
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

    @GetMapping
    @Operation(summary = "List lightweight Agent Runtime scheduled tasks")
    public ApiResponse<List<ScheduledTaskResponse>> list(@RequestParam("tenantId") String tenantId,
                                                         @RequestParam(value = "agentId", required = false) String agentId,
                                                         @RequestParam(value = "page", defaultValue = "1") int page,
                                                         @RequestParam(value = "pageSize", defaultValue = "20") int pageSize) {
        try {
            return ApiResponse.success(scheduledTaskService.list(tenantId, agentId, page, pageSize));
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }

    @GetMapping("/{scheduledTaskId}")
    @Operation(summary = "Get one lightweight Agent Runtime scheduled task")
    public ApiResponse<ScheduledTaskResponse> get(@RequestParam("tenantId") String tenantId,
                                                  @PathVariable("scheduledTaskId") String scheduledTaskId) {
        try {
            return scheduledTaskService.get(tenantId, scheduledTaskId)
                .map(ApiResponse::success)
                .orElseGet(() -> ApiResponse.notFound("Scheduled task not found: " + scheduledTaskId));
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }

    @PostMapping("/{scheduledTaskId}/pause")
    @Operation(summary = "Pause future triggers for one scheduled Agent Runtime task")
    public ApiResponse<ScheduledTaskResponse> pause(@RequestParam("tenantId") String tenantId,
                                                    @PathVariable("scheduledTaskId") String scheduledTaskId) {
        try {
            return ApiResponse.success(scheduledTaskService.pause(tenantId, scheduledTaskId), "Scheduled task paused");
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }

    @PostMapping("/{scheduledTaskId}/resume")
    @Operation(summary = "Resume one paused scheduled Agent Runtime task")
    public ApiResponse<ScheduledTaskResponse> resume(@RequestParam("tenantId") String tenantId,
                                                     @PathVariable("scheduledTaskId") String scheduledTaskId) {
        try {
            return ApiResponse.success(scheduledTaskService.resume(tenantId, scheduledTaskId), "Scheduled task resumed");
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }

    @PostMapping("/{scheduledTaskId}/cancel")
    @Operation(summary = "Cancel one scheduled Agent Runtime task")
    public ApiResponse<ScheduledTaskResponse> cancel(@RequestParam("tenantId") String tenantId,
                                                     @PathVariable("scheduledTaskId") String scheduledTaskId) {
        try {
            return ApiResponse.success(scheduledTaskService.cancel(tenantId, scheduledTaskId), "Scheduled task cancelled");
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }

    @PostMapping("/{scheduledTaskId}/rerun")
    @Operation(summary = "Run one scheduled Agent Runtime task immediately")
    public ApiResponse<ScheduledTaskRunResponse> rerun(@RequestParam("tenantId") String tenantId,
                                                       @PathVariable("scheduledTaskId") String scheduledTaskId) {
        try {
            return ApiResponse.success(scheduledTaskService.rerun(tenantId, scheduledTaskId), "Scheduled task rerun submitted");
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
                                                               @RequestParam(value = "pageSize", defaultValue = "20") int pageSize) {
        try {
            return ApiResponse.success(scheduledTaskService.history(tenantId, scheduledTaskId, page, pageSize));
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }

    @GetMapping("/history")
    @Operation(summary = "List scheduled Agent Runtime execution history by Agent")
    public ApiResponse<List<ScheduledTaskRunResponse>> historyByAgent(@RequestParam("tenantId") String tenantId,
                                                                      @RequestParam("agentId") String agentId,
                                                                      @RequestParam(value = "page", defaultValue = "1") int page,
                                                                      @RequestParam(value = "pageSize", defaultValue = "20") int pageSize) {
        try {
            return ApiResponse.success(scheduledTaskService.historyByAgent(tenantId, agentId, page, pageSize));
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }

    @DeleteMapping("/{scheduledTaskId}")
    @Operation(summary = "Delete one scheduled Agent Runtime task")
    public ApiResponse<Void> delete(@RequestParam("tenantId") String tenantId,
                                    @PathVariable("scheduledTaskId") String scheduledTaskId) {
        try {
            scheduledTaskService.delete(tenantId, scheduledTaskId);
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
}
