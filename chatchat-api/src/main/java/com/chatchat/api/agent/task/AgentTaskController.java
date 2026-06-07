package com.chatchat.api.agent.task;

import com.chatchat.common.constants.AppConstants;
import com.chatchat.common.response.ApiResponse;
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

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping(AppConstants.API_V1 + "/agent/tasks")
@Tag(name = "Agent Tasks", description = "Tenant-isolated async Agent task APIs")
public class AgentTaskController {

    private final AgentTaskService taskService;

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
    public ApiResponse<AgentTaskResponse> get(@PathVariable("taskId") String taskId) {
        return taskService.get(taskId)
            .map(ApiResponse::success)
            .orElseGet(() -> ApiResponse.notFound("Task not found: " + taskId));
    }

    @GetMapping
    @Operation(summary = "List latest Agent tasks")
    public ApiResponse<List<AgentTaskResponse>> list(@RequestParam(value = "tenantId", required = false) String tenantId,
                                                     @RequestParam(value = "sessionId", required = false) String sessionId,
                                                     @RequestParam(value = "page", defaultValue = "1") int page,
                                                     @RequestParam(value = "pageSize", defaultValue = "20") int pageSize) {
        return ApiResponse.success(taskService.list(tenantId, sessionId, page, pageSize));
    }

    @GetMapping("/{taskId}/events")
    @Operation(summary = "List RocksDB Agent event history for one task")
    public ApiResponse<List<AgentEvent>> events(@PathVariable("taskId") String taskId,
                                                @RequestParam(value = "limit", defaultValue = "50") int limit) {
        try {
            return ApiResponse.success(taskService.listEvents(taskId, limit));
        } catch (IllegalArgumentException e) {
            return ApiResponse.notFound(e.getMessage());
        }
    }

    @GetMapping("/{taskId}/result")
    @Operation(summary = "Poll Agent task result queue")
    public ApiResponse<AgentEvent> pollResult(@PathVariable("taskId") String taskId,
                                              @RequestParam(value = "timeoutMs", defaultValue = "1200") long timeoutMs) {
        try {
            return ApiResponse.success(taskService.pollResult(taskId, timeoutMs).orElse(null));
        } catch (IllegalArgumentException e) {
            return ApiResponse.notFound(e.getMessage());
        }
    }
}
