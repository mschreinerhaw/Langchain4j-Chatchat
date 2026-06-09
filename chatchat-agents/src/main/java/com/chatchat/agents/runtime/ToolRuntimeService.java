package com.chatchat.agents.runtime;

import com.chatchat.agents.tool.ToolRegistry;
import com.chatchat.common.interaction.InteractionToolTrace;
import com.chatchat.common.tool.ToolInput;
import com.chatchat.common.tool.ToolMetadata;
import com.chatchat.common.tool.ToolOutput;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Service
@RequiredArgsConstructor
public class ToolRuntimeService {

    private final ToolRegistry toolRegistry;
    private final ObjectMapper objectMapper;
    private final ToolRuntimeProperties properties;
    private final List<ToolRuntimePolicyProvider> policyProviders;
    private final List<ToolRuntimeAuditSink> auditSinks;

    private final Map<String, Deque<Long>> rateWindows = new ConcurrentHashMap<>();
    private final Map<String, CircuitState> circuitStates = new ConcurrentHashMap<>();
    private final Map<String, ToolCounters> counters = new ConcurrentHashMap<>();

    public ToolRuntimeExecution execute(ToolRuntimeRequest request) {
        String toolName = normalizeText(request == null ? null : request.getToolName());
        if (toolName == null) {
            return deniedExecution("unknown", request, null, "Tool name is required", "INVALID_REQUEST");
        }

        ToolMetadata metadata = toolRegistry.getToolMetadata(toolName);
        ToolInput toolInput = request.getToolInput() == null ? new ToolInput() : request.getToolInput();
        ToolRuntimePolicy policy = resolvePolicy(request, metadata);

        if (isDeniedByPolicy(toolName, request)) {
            return deniedExecution(toolName, request, metadata,
                "Tool is not allowed in the current runtime policy: " + toolName,
                "TOOL_PERMISSION_DENIED");
        }
        if (isDeniedByResolvedPolicy(policy)) {
            return deniedExecution(toolName, request, metadata,
                firstText(policy.reason(), "Tool denied by tenant runtime policy: " + toolName),
                "TOOL_TENANT_POLICY_DENIED");
        }
        if (requiresAuthentication(metadata, policy) && normalizeText(toolInput.getUserId()) == null) {
            return deniedExecution(toolName, request, metadata,
                "Tool requires an authenticated user: " + toolName,
                "TOOL_AUTH_REQUIRED");
        }
        if (isCircuitOpen(toolName, policy)) {
            return rejectedExecution(toolName, request, metadata,
                "Tool circuit is open: " + toolName,
                "TOOL_CIRCUIT_OPEN",
                "circuit_open");
        }
        if (isRateLimited(toolName, metadata, toolInput.getUserId(), policy)) {
            return rejectedExecution(toolName, request, metadata,
                "Tool rate limit exceeded: " + toolName,
                "TOOL_RATE_LIMITED",
                "rate_limited");
        }

        ToolCounters toolCounters = counters.computeIfAbsent(toolName, ignored -> new ToolCounters());
        toolCounters.totalCalls.incrementAndGet();
        toolCounters.activeCalls.incrementAndGet();

        long startedAt = System.currentTimeMillis();
        try {
            ToolOutput output = toolRegistry.executeEnhancedTool(toolName, toolInput);
            if (output.getMetadata() == null) {
                output.setMetadata(new LinkedHashMap<>());
            }
            long finishedAt = System.currentTimeMillis();
            long durationMs = output.getExecutionTimeMs() == null
                ? Math.max(0L, finishedAt - startedAt)
                : output.getExecutionTimeMs();
            output.setExecutionTimeMs(durationMs);
            output.getMetadata().put("runtimeOutcome", output.isSuccess() ? "success" : "failed");
            output.getMetadata().put("runtimeMode", normalizeMode(request));

            if (output.isSuccess()) {
                resetCircuit(toolName);
                toolCounters.successCalls.incrementAndGet();
            } else {
                updateCircuitOnFailure(toolName, policy);
                toolCounters.failedCalls.incrementAndGet();
            }
            toolCounters.totalDurationMs.addAndGet(durationMs);
            toolCounters.lastDurationMs.set(durationMs);

            Map<String, Object> runtimeMetadata = runtimeMetadata(
                request,
                metadata,
                output.isSuccess() ? "success" : "failed",
                null
            );
            InteractionToolTrace trace = buildTrace(toolName, metadata, toolInput, output, startedAt, finishedAt, runtimeMetadata);
            logAudit(toolName, request, output.isSuccess() ? "success" : "failed", durationMs, output.getErrorMessage());
            publishAuditRecord(request, metadata, output, trace, output.isSuccess() ? "success" : "failed", null, durationMs, runtimeMetadata);
            return new ToolRuntimeExecution(output, metadata, trace, output.isSuccess() ? "success" : "failed", runtimeMetadata);
        } finally {
            toolCounters.activeCalls.decrementAndGet();
        }
    }

    public ToolRuntimeSnapshot snapshot() {
        List<ToolRuntimeSnapshot.ToolMetric> topTools = new ArrayList<>();
        long totalCalls = 0L;
        long successCalls = 0L;
        long failedCalls = 0L;
        long deniedCalls = 0L;
        long rateLimitedCalls = 0L;
        long circuitOpenRejects = 0L;
        long activeCalls = 0L;

        for (Map.Entry<String, ToolCounters> entry : counters.entrySet()) {
            String toolName = entry.getKey();
            ToolCounters value = entry.getValue();
            long toolTotal = value.totalCalls.get();
            long toolSuccess = value.successCalls.get();
            long toolFailed = value.failedCalls.get();
            long toolDenied = value.deniedCalls.get();
            long toolRateLimited = value.rateLimitedCalls.get();
            long toolCircuitOpen = value.circuitOpenRejects.get();
            long toolActive = value.activeCalls.get();
            long averageDuration = toolSuccess + toolFailed == 0
                ? 0L
                : value.totalDurationMs.get() / Math.max(1L, toolSuccess + toolFailed);
            topTools.add(new ToolRuntimeSnapshot.ToolMetric(
                toolName,
                toolTotal,
                toolSuccess,
                toolFailed,
                toolDenied,
                toolRateLimited,
                toolCircuitOpen,
                toolActive,
                averageDuration,
                value.lastDurationMs.get()
            ));
            totalCalls += toolTotal;
            successCalls += toolSuccess;
            failedCalls += toolFailed;
            deniedCalls += toolDenied;
            rateLimitedCalls += toolRateLimited;
            circuitOpenRejects += toolCircuitOpen;
            activeCalls += toolActive;
        }

        topTools.sort(Comparator
            .comparingLong(ToolRuntimeSnapshot.ToolMetric::totalCalls)
            .reversed()
            .thenComparing(ToolRuntimeSnapshot.ToolMetric::toolName));

        long openCircuits = circuitStates.values().stream()
            .filter(state -> state.openedUntilMs.get() > System.currentTimeMillis())
            .count();

        int topLimit = Math.max(1, properties.getTopToolLimit());
        return new ToolRuntimeSnapshot(
            totalCalls,
            successCalls,
            failedCalls,
            deniedCalls,
            rateLimitedCalls,
            circuitOpenRejects,
            activeCalls,
            openCircuits,
            topTools.stream().limit(topLimit).toList()
        );
    }

    public ToolMetadata metadata(String toolName) {
        return toolRegistry.getToolMetadata(toolName);
    }

    private ToolRuntimeExecution deniedExecution(String toolName,
                                                 ToolRuntimeRequest request,
                                                 ToolMetadata metadata,
                                                 String message,
                                                 String errorCode) {
        ToolCounters toolCounters = counters.computeIfAbsent(toolName, ignored -> new ToolCounters());
        toolCounters.totalCalls.incrementAndGet();
        toolCounters.deniedCalls.incrementAndGet();
        Map<String, Object> runtimeMetadata = runtimeMetadata(request, metadata, "denied", errorCode);
        ToolOutput output = ToolOutput.failure(message);
        output.setExceptionType(errorCode);
        output.getMetadata().putAll(runtimeMetadata);
        InteractionToolTrace trace = buildTrace(toolName, metadata, request == null ? new ToolInput() : request.getToolInput(),
            output, System.currentTimeMillis(), System.currentTimeMillis(), runtimeMetadata);
        logAudit(toolName, request, "denied", 0L, message);
        publishAuditRecord(request, metadata, output, trace, "denied", errorCode, 0L, runtimeMetadata);
        return new ToolRuntimeExecution(output, metadata, trace, "denied", runtimeMetadata);
    }

    private ToolRuntimeExecution rejectedExecution(String toolName,
                                                   ToolRuntimeRequest request,
                                                   ToolMetadata metadata,
                                                   String message,
                                                   String errorCode,
                                                   String outcome) {
        ToolCounters toolCounters = counters.computeIfAbsent(toolName, ignored -> new ToolCounters());
        toolCounters.totalCalls.incrementAndGet();
        if ("rate_limited".equals(outcome)) {
            toolCounters.rateLimitedCalls.incrementAndGet();
        }
        if ("circuit_open".equals(outcome)) {
            toolCounters.circuitOpenRejects.incrementAndGet();
        }
        Map<String, Object> runtimeMetadata = runtimeMetadata(request, metadata, outcome, errorCode);
        ToolOutput output = ToolOutput.failure(message);
        output.setExceptionType(errorCode);
        output.getMetadata().putAll(runtimeMetadata);
        InteractionToolTrace trace = buildTrace(toolName, metadata, request == null ? new ToolInput() : request.getToolInput(),
            output, System.currentTimeMillis(), System.currentTimeMillis(), runtimeMetadata);
        logAudit(toolName, request, outcome, 0L, message);
        publishAuditRecord(request, metadata, output, trace, outcome, errorCode, 0L, runtimeMetadata);
        return new ToolRuntimeExecution(output, metadata, trace, outcome, runtimeMetadata);
    }

    private boolean isDeniedByPolicy(String toolName, ToolRuntimeRequest request) {
        if (!properties.isEnforceAllowedTools() || request == null || request.getAllowedTools() == null || request.getAllowedTools().isEmpty()) {
            return false;
        }
        return request.getAllowedTools().stream()
            .filter(value -> value != null && !value.isBlank())
            .map(String::trim)
            .noneMatch(toolName::equals);
    }

    private boolean requiresAuthentication(ToolMetadata metadata, ToolRuntimePolicy policy) {
        boolean requiresAuth = metadata != null && metadata.isRequiresAuth();
        if (policy != null && policy.requiresAuthentication() != null) {
            requiresAuth = policy.requiresAuthentication();
        }
        return properties.isEnforceAuthentication() && requiresAuth;
    }

    private boolean isRateLimited(String toolName, ToolMetadata metadata, String userId, ToolRuntimePolicy policy) {
        int limit = metadata != null && metadata.isRateLimited() && metadata.getMaxCallsPerMinute() > 0
            ? metadata.getMaxCallsPerMinute()
            : properties.getDefaultMaxCallsPerMinute();
        if (policy != null && policy.maxCallsPerMinute() != null) {
            limit = policy.maxCallsPerMinute();
        }
        if (limit <= 0) {
            return false;
        }
        String actor = normalizeText(userId);
        String key = toolName + "::" + (actor == null ? "anonymous" : actor);
        long now = System.currentTimeMillis();
        long threshold = now - 60_000L;
        Deque<Long> window = rateWindows.computeIfAbsent(key, ignored -> new ArrayDeque<>());
        synchronized (window) {
            while (!window.isEmpty() && window.peekFirst() < threshold) {
                window.pollFirst();
            }
            if (window.size() >= limit) {
                return true;
            }
            window.addLast(now);
            return false;
        }
    }

    private boolean isCircuitOpen(String toolName, ToolRuntimePolicy policy) {
        CircuitState state = circuitStates.computeIfAbsent(toolName, ignored -> new CircuitState());
        if (state.openedUntilMs.get() <= System.currentTimeMillis()) {
            return false;
        }
        return threshold(policy) > 0;
    }

    private void updateCircuitOnFailure(String toolName, ToolRuntimePolicy policy) {
        CircuitState state = circuitStates.computeIfAbsent(toolName, ignored -> new CircuitState());
        int failures = state.consecutiveFailures.incrementAndGet();
        if (failures >= threshold(policy)) {
            long until = System.currentTimeMillis() + Math.max(1, openSeconds(policy)) * 1000L;
            state.openedUntilMs.set(until);
        }
    }

    private void resetCircuit(String toolName) {
        CircuitState state = circuitStates.computeIfAbsent(toolName, ignored -> new CircuitState());
        state.consecutiveFailures.set(0);
        state.openedUntilMs.set(0L);
    }

    private InteractionToolTrace buildTrace(String toolName,
                                            ToolMetadata metadata,
                                            ToolInput input,
                                            ToolOutput output,
                                            long startedAt,
                                            long finishedAt,
                                            Map<String, Object> runtimeMetadata) {
        String outputText = stringify(output == null ? null : output.getData());
        return InteractionToolTrace.builder()
            .toolName(toolName)
            .displayName(resolveDisplayName(toolName, metadata))
            .serviceId(resolveServiceId(metadata))
            .serviceName(resolveServiceName(metadata))
            .success(output != null && output.isSuccess())
            .input(input == null ? Map.of() : input.getParameters())
            .output(outputText)
            .errorMessage(output == null ? null : output.getErrorMessage())
            .durationMs(output == null || output.getExecutionTimeMs() == null ? Math.max(0L, finishedAt - startedAt) : output.getExecutionTimeMs())
            .startedAt(startedAt)
            .finishedAt(finishedAt)
            .runtimeMetadata(runtimeMetadata)
            .build();
    }

    private Map<String, Object> runtimeMetadata(ToolRuntimeRequest request,
                                                ToolMetadata metadata,
                                                String outcome,
                                                String errorCode) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("runtimeMode", normalizeMode(request));
        values.put("requestId", request == null ? null : request.getRequestId());
        values.put("conversationId", request == null ? null : request.getConversationId());
        values.put("userId", request == null ? null : request.getUserId());
        values.put("outcome", outcome);
        values.put("errorCode", errorCode);
        values.put("serviceId", resolveServiceId(metadata));
        values.put("serviceName", resolveServiceName(metadata));
        return values;
    }

    private ToolRuntimePolicy resolvePolicy(ToolRuntimeRequest request, ToolMetadata metadata) {
        ToolRuntimePolicy merged = null;
        for (ToolRuntimePolicyProvider provider : policyProviders) {
            if (provider == null) {
                continue;
            }
            ToolRuntimePolicy candidate = provider.resolve(request, metadata);
            if (candidate == null) {
                continue;
            }
            merged = mergePolicies(merged, candidate);
        }
        return merged;
    }

    private ToolRuntimePolicy mergePolicies(ToolRuntimePolicy base, ToolRuntimePolicy override) {
        if (base == null) {
            return override;
        }
        Map<String, Object> attributes = new LinkedHashMap<>();
        if (base.attributes() != null) {
            attributes.putAll(base.attributes());
        }
        if (override.attributes() != null) {
            attributes.putAll(override.attributes());
        }
        return ToolRuntimePolicy.builder()
            .allowed(override.allowed() != null ? override.allowed() : base.allowed())
            .reason(firstText(override.reason(), base.reason()))
            .maxCallsPerMinute(override.maxCallsPerMinute() != null ? override.maxCallsPerMinute() : base.maxCallsPerMinute())
            .requiresAuthentication(override.requiresAuthentication() != null ? override.requiresAuthentication() : base.requiresAuthentication())
            .circuitBreakerFailureThreshold(override.circuitBreakerFailureThreshold() != null
                ? override.circuitBreakerFailureThreshold()
                : base.circuitBreakerFailureThreshold())
            .circuitBreakerOpenSeconds(override.circuitBreakerOpenSeconds() != null
                ? override.circuitBreakerOpenSeconds()
                : base.circuitBreakerOpenSeconds())
            .attributes(attributes)
            .build();
    }

    private boolean isDeniedByResolvedPolicy(ToolRuntimePolicy policy) {
        return policy != null && Boolean.FALSE.equals(policy.allowed());
    }

    private int threshold(ToolRuntimePolicy policy) {
        if (policy != null && policy.circuitBreakerFailureThreshold() != null) {
            return Math.max(1, policy.circuitBreakerFailureThreshold());
        }
        return Math.max(1, properties.getCircuitBreakerFailureThreshold());
    }

    private int openSeconds(ToolRuntimePolicy policy) {
        if (policy != null && policy.circuitBreakerOpenSeconds() != null) {
            return Math.max(1, policy.circuitBreakerOpenSeconds());
        }
        return Math.max(1, properties.getCircuitBreakerOpenSeconds());
    }

    private void publishAuditRecord(ToolRuntimeRequest request,
                                    ToolMetadata metadata,
                                    ToolOutput output,
                                    InteractionToolTrace trace,
                                    String outcome,
                                    String errorCode,
                                    long durationMs,
                                    Map<String, Object> runtimeMetadata) {
        if (auditSinks == null || auditSinks.isEmpty()) {
            return;
        }
        ToolRuntimeAuditRecord record = new ToolRuntimeAuditRecord(
            request,
            metadata,
            output,
            trace,
            outcome,
            errorCode,
            durationMs,
            runtimeMetadata == null ? Map.of() : new LinkedHashMap<>(runtimeMetadata)
        );
        for (ToolRuntimeAuditSink sink : auditSinks) {
            if (sink == null) {
                continue;
            }
            sink.record(record);
        }
    }

    private void logAudit(String toolName,
                          ToolRuntimeRequest request,
                          String outcome,
                          long durationMs,
                          String errorMessage) {
        log.info("Tool runtime call tool={} outcome={} mode={} requestId={} conversationId={} userId={} durationMs={} error={}",
            toolName,
            outcome,
            normalizeMode(request),
            request == null ? null : request.getRequestId(),
            request == null ? null : request.getConversationId(),
            request == null ? null : request.getUserId(),
            durationMs,
            errorMessage);
    }

    private String stringify(Object data) {
        if (data == null) {
            return null;
        }
        if (data instanceof String text) {
            return text;
        }
        try {
            return objectMapper.writeValueAsString(data);
        } catch (JsonProcessingException ex) {
            return String.valueOf(data);
        }
    }

    private String resolveDisplayName(String toolName, ToolMetadata metadata) {
        if (metadata != null && metadata.getTitle() != null && !metadata.getTitle().isBlank()) {
            return metadata.getTitle().trim();
        }
        return toolName;
    }

    private String resolveServiceId(ToolMetadata metadata) {
        if (metadata == null || metadata.getMetadata() == null) {
            return null;
        }
        Object value = metadata.getMetadata().get("serviceId");
        return value == null ? null : String.valueOf(value);
    }

    private String resolveServiceName(ToolMetadata metadata) {
        if (metadata == null || metadata.getAuthor() == null || metadata.getAuthor().isBlank()) {
            return null;
        }
        String author = metadata.getAuthor().trim();
        if (author.startsWith("MCP:")) {
            return author.substring(4).trim();
        }
        return author;
    }

    private String normalizeMode(ToolRuntimeRequest request) {
        String value = request == null ? null : request.getRuntimeMode();
        return value == null || value.isBlank() ? "tool_runtime" : value.trim();
    }

    private String normalizeText(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String firstText(String first, String second) {
        return first == null || first.isBlank() ? second : first;
    }

    private static final class CircuitState {
        private final AtomicInteger consecutiveFailures = new AtomicInteger();
        private final AtomicLong openedUntilMs = new AtomicLong();
    }

    private static final class ToolCounters {
        private final AtomicLong totalCalls = new AtomicLong();
        private final AtomicLong successCalls = new AtomicLong();
        private final AtomicLong failedCalls = new AtomicLong();
        private final AtomicLong deniedCalls = new AtomicLong();
        private final AtomicLong rateLimitedCalls = new AtomicLong();
        private final AtomicLong circuitOpenRejects = new AtomicLong();
        private final AtomicLong totalDurationMs = new AtomicLong();
        private final AtomicLong lastDurationMs = new AtomicLong();
        private final AtomicLong activeCalls = new AtomicLong();
    }
}
