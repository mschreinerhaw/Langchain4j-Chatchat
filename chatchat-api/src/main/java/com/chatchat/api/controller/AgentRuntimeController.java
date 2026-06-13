package com.chatchat.api.controller;

import com.chatchat.agents.runtime.AgentObservation;
import com.chatchat.agents.runtime.AgentRun;
import com.chatchat.agents.runtime.AgentRunEvent;
import com.chatchat.agents.runtime.AgentRunQuery;
import com.chatchat.agents.runtime.AgentRunStatus;
import com.chatchat.agents.runtime.AgentRunStep;
import com.chatchat.agents.runtime.AgentRuntime;
import com.chatchat.agents.runtime.AgentRuntimeSnapshot;
import com.chatchat.api.runtime.AgentRuntimeEventStreamService;
import com.chatchat.api.security.ApiAuthenticationFilter;
import com.chatchat.common.constants.AppConstants;
import com.chatchat.common.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Optional;

@RestController
@RequiredArgsConstructor
@Slf4j
@RequestMapping(AppConstants.API_V1 + "/agent/runtime")
@Tag(name = "Agent Runtime", description = "Inspect and control generic Agent runtime runs")
public class AgentRuntimeController {

    private final AgentRuntime agentRuntime;
    private final AgentRuntimeEventStreamService eventStreamService;

    @GetMapping("/snapshot")
    @Operation(summary = "Get Agent runtime snapshot")
    public ApiResponse<AgentRuntimeSnapshot> snapshot(HttpServletRequest request) {
        try {
            String currentTenantId = currentTenantId(request);
            if (currentTenantId != null) {
                return ApiResponse.success(AgentRuntimeSnapshot.fromRuns(agentRuntime.list(new AgentRunQuery(
                    null,
                    currentTenantId,
                    null,
                    null,
                    1000,
                    0
                ))));
            }
            return ApiResponse.success(agentRuntime.snapshot());
        } catch (RuntimeException ex) {
            return runtimeUnavailable(ex);
        }
    }

    @GetMapping("/runs")
    @Operation(summary = "List Agent runtime runs")
    public ApiResponse<List<AgentRun>> runs(@RequestParam(value = "status", required = false) String status,
                                            @RequestParam(value = "tenantId", required = false) String tenantId,
                                             @RequestParam(value = "userId", required = false) String userId,
                                             @RequestParam(value = "conversationId", required = false) String conversationId,
                                             @RequestParam(value = "limit", required = false) Integer limit,
                                             @RequestParam(value = "offset", required = false) Integer offset,
                                             HttpServletRequest request) {
        try {
            String scopedTenantId = firstText(currentTenantId(request), tenantId);
            return ApiResponse.success(agentRuntime.list(new AgentRunQuery(
                parseStatus(status),
                scopedTenantId,
                userId,
                conversationId,
                valueOrDefault(limit, 50),
                valueOrDefault(offset, 0)
            )));
        } catch (IllegalArgumentException ex) {
            return ApiResponse.badRequest(ex.getMessage());
        } catch (RuntimeException ex) {
            return runtimeUnavailable(ex);
        }
    }

    @GetMapping("/runs/{runId}")
    @Operation(summary = "Get one Agent runtime run")
    public ApiResponse<AgentRun> run(@PathVariable("runId") String runId, HttpServletRequest request) {
        try {
            return findAuthorized(runId, request)
                .map(ApiResponse::success)
                .orElseGet(() -> ApiResponse.notFound("Agent run not found: " + runId));
        } catch (AccessDeniedException ex) {
            return ApiResponse.error(403, ex.getMessage());
        } catch (RuntimeException ex) {
            return runtimeUnavailable(ex);
        }
    }

    @GetMapping("/runs/{runId}/events")
    @Operation(summary = "Read Agent runtime events incrementally")
    public ApiResponse<List<AgentRunEvent>> events(@PathVariable("runId") String runId,
                                                   @RequestParam(value = "afterCreatedAt", required = false) Long afterCreatedAt,
                                                   @RequestParam(value = "limit", required = false) Integer limit,
                                                   HttpServletRequest request) {
        try {
            if (currentTenantId(request) != null) {
                Optional<AgentRun> run = findAuthorized(runId, request);
                if (run.isEmpty()) {
                    return ApiResponse.notFound("Agent run not found: " + runId);
                }
            }
            return ApiResponse.success(agentRuntime.events(runId, valueOrDefault(afterCreatedAt, 0L), valueOrDefault(limit, 100)));
        } catch (AccessDeniedException ex) {
            return ApiResponse.error(403, ex.getMessage());
        } catch (RuntimeException ex) {
            return runtimeUnavailable(ex);
        }
    }

    @GetMapping(value = "/runs/{runId}/events/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "Stream Agent runtime events")
    public SseEmitter streamEvents(@PathVariable("runId") String runId,
                                    @RequestParam(value = "afterCreatedAt", required = false) Long afterCreatedAt,
                                    @RequestParam(value = "limit", required = false) Integer limit,
                                    @RequestParam(value = "pollIntervalMs", required = false) Long pollIntervalMs,
                                    @RequestParam(value = "timeoutMs", required = false) Long timeoutMs,
                                    HttpServletRequest request) {
        try {
            if (currentTenantId(request) != null) {
                Optional<AgentRun> run = findAuthorized(runId, request);
                if (run.isEmpty()) {
                    return immediateStream("error", "Agent run not found: " + runId);
                }
            }
        } catch (AccessDeniedException ex) {
            return immediateStream("error", ex.getMessage());
        }
        return eventStreamService.streamEvents(
            runId,
            valueOrDefault(afterCreatedAt, 0L),
            valueOrDefault(limit, 100),
            valueOrDefault(pollIntervalMs, 1_000L),
            valueOrDefault(timeoutMs, 300_000L)
        );
    }

    @GetMapping("/runs/{runId}/steps")
    @Operation(summary = "Read Agent runtime steps incrementally")
    public ApiResponse<List<AgentRunStep>> steps(@PathVariable("runId") String runId,
                                                  @RequestParam(value = "afterStep", required = false) Integer afterStep,
                                                  @RequestParam(value = "limit", required = false) Integer limit,
                                                  HttpServletRequest request) {
        try {
            if (currentTenantId(request) != null) {
                Optional<AgentRun> run = findAuthorized(runId, request);
                if (run.isEmpty()) {
                    return ApiResponse.notFound("Agent run not found: " + runId);
                }
            }
            return ApiResponse.success(agentRuntime.steps(runId, valueOrDefault(afterStep, 0), valueOrDefault(limit, 100)));
        } catch (AccessDeniedException ex) {
            return ApiResponse.error(403, ex.getMessage());
        } catch (RuntimeException ex) {
            return runtimeUnavailable(ex);
        }
    }

    @GetMapping("/runs/{runId}/observations")
    @Operation(summary = "Read Agent runtime observations by page")
    public ApiResponse<List<AgentObservation>> observations(@PathVariable("runId") String runId,
                                                            @RequestParam(value = "offset", required = false) Integer offset,
                                                            @RequestParam(value = "limit", required = false) Integer limit,
                                                            HttpServletRequest request) {
        try {
            if (currentTenantId(request) != null) {
                Optional<AgentRun> run = findAuthorized(runId, request);
                if (run.isEmpty()) {
                    return ApiResponse.notFound("Agent run not found: " + runId);
                }
            }
            return ApiResponse.success(agentRuntime.observations(runId, valueOrDefault(offset, 0), valueOrDefault(limit, 100)));
        } catch (AccessDeniedException ex) {
            return ApiResponse.error(403, ex.getMessage());
        } catch (RuntimeException ex) {
            return runtimeUnavailable(ex);
        }
    }

    @GetMapping("/runs/{runId}/timeline")
    @Operation(summary = "Read one Agent runtime timeline")
    public ApiResponse<AgentRunTimeline> timeline(@PathVariable("runId") String runId,
                                                  @RequestParam(value = "afterCreatedAt", required = false) Long afterCreatedAt,
                                                  @RequestParam(value = "eventLimit", required = false) Integer eventLimit,
                                                   @RequestParam(value = "afterStep", required = false) Integer afterStep,
                                                   @RequestParam(value = "stepLimit", required = false) Integer stepLimit,
                                                   @RequestParam(value = "observationOffset", required = false) Integer observationOffset,
                                                   @RequestParam(value = "observationLimit", required = false) Integer observationLimit,
                                                   HttpServletRequest request) {
        try {
            return findAuthorized(runId, request)
                .map(run -> ApiResponse.success(new AgentRunTimeline(
                    run,
                    agentRuntime.events(runId, valueOrDefault(afterCreatedAt, 0L), valueOrDefault(eventLimit, 100)),
                    agentRuntime.steps(runId, valueOrDefault(afterStep, 0), valueOrDefault(stepLimit, 100)),
                    agentRuntime.observations(runId, valueOrDefault(observationOffset, 0), valueOrDefault(observationLimit, 100))
                )))
                .orElseGet(() -> ApiResponse.notFound("Agent run not found: " + runId));
        } catch (AccessDeniedException ex) {
            return ApiResponse.error(403, ex.getMessage());
        } catch (RuntimeException ex) {
            return runtimeUnavailable(ex);
        }
    }

    @PostMapping("/runs/{runId}/cancel")
    @Operation(summary = "Cancel one Agent runtime run")
    public ApiResponse<AgentRun> cancel(@PathVariable("runId") String runId, HttpServletRequest request) {
        try {
            if (currentTenantId(request) != null) {
                Optional<AgentRun> run = findAuthorized(runId, request);
                if (run.isEmpty()) {
                    return ApiResponse.notFound("Agent run not found: " + runId);
                }
            }
            return ApiResponse.success(agentRuntime.cancel(runId), "Agent run cancellation requested");
        } catch (IllegalArgumentException ex) {
            return ApiResponse.badRequest(ex.getMessage());
        } catch (AccessDeniedException ex) {
            return ApiResponse.error(403, ex.getMessage());
        } catch (RuntimeException ex) {
            return runtimeUnavailable(ex);
        }
    }

    private AgentRunStatus parseStatus(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return AgentRunStatus.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Unsupported agent run status: " + value);
        }
    }

    private int valueOrDefault(Integer value, int fallback) {
        return value == null ? fallback : value;
    }

    private long valueOrDefault(Long value, long fallback) {
        return value == null ? fallback : value;
    }

    private Optional<AgentRun> findAuthorized(String runId, HttpServletRequest request) {
        Optional<AgentRun> run = agentRuntime.find(runId);
        if (run == null) {
            return Optional.empty();
        }
        run.ifPresent(value -> enforceTenantAccess(value, request));
        return run;
    }

    private void enforceTenantAccess(AgentRun run, HttpServletRequest request) {
        String currentTenantId = currentTenantId(request);
        if (currentTenantId == null) {
            return;
        }
        String runTenantId = run.request() == null ? null : run.request().getTenantId();
        if (!currentTenantId.equals(runTenantId)) {
            throw new AccessDeniedException("Agent run is outside the current tenant");
        }
    }

    private String currentTenantId(HttpServletRequest request) {
        return requestAttribute(request, ApiAuthenticationFilter.CURRENT_TENANT_ID);
    }

    private String requestAttribute(HttpServletRequest request, String name) {
        if (request == null) {
            return null;
        }
        Object value = request.getAttribute(name);
        if (value == null || String.valueOf(value).isBlank()) {
            return null;
        }
        return String.valueOf(value).trim();
    }

    private String firstText(String first, String second) {
        return first == null || first.isBlank() ? second : first;
    }

    private SseEmitter immediateStream(String eventName, String message) {
        SseEmitter emitter = new SseEmitter(1000L);
        try {
            emitter.send(SseEmitter.event().name(eventName).data(new ErrorEvent(message)));
            emitter.complete();
        } catch (Exception ex) {
            emitter.completeWithError(ex);
        }
        return emitter;
    }

    private <T> ApiResponse<T> runtimeUnavailable(RuntimeException ex) {
        log.warn("Agent runtime store unavailable: {}", ex.getMessage(), ex);
        return ApiResponse.error(503, "Agent runtime store unavailable");
    }

    public record AgentRunTimeline(
        AgentRun run,
        List<AgentRunEvent> events,
        List<AgentRunStep> steps,
        List<AgentObservation> observations
    ) {
    }

    private record ErrorEvent(String message) {
    }

    private static class AccessDeniedException extends RuntimeException {
        private AccessDeniedException(String message) {
            super(message);
        }
    }
}
