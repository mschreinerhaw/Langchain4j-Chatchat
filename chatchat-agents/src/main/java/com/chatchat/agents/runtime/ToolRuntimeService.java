package com.chatchat.agents.runtime;

import com.chatchat.agents.protocol.ModelProtocolJson;
import com.chatchat.agents.tool.ToolRegistry;
import com.chatchat.common.interaction.InteractionToolTrace;
import com.chatchat.common.tool.ToolInput;
import com.chatchat.common.tool.ToolMetadata;
import com.chatchat.common.tool.ToolOutput;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Service
public class ToolRuntimeService {

    private final ToolRegistry toolRegistry;
    private final ObjectMapper objectMapper;
    private final ToolRuntimeProperties properties;
    private final McpPolicyProperties mcpPolicyProperties;
    private final McpWorkflowProperties mcpWorkflowProperties;
    private final List<ToolRuntimePolicyProvider> policyProviders;
    private final List<ToolRuntimeAuditSink> auditSinks;
    private final ToolRuntimeUserPolicyStore userPolicyStore;
    private final ExecutorService toolExecutionExecutor;

    private final Map<String, Deque<Long>> rateWindows = new ConcurrentHashMap<>();
    private final Map<String, CircuitState> circuitStates = new ConcurrentHashMap<>();
    private final Map<String, ToolCounters> counters = new ConcurrentHashMap<>();
    private final Map<String, WorkflowState> workflowStates = new ConcurrentHashMap<>();

    /**
     * Creates a new ToolRuntimeService instance.
     *
     * @param toolRegistry the tool registry value
     * @param objectMapper the object mapper value
     * @param properties the properties value
     * @param mcpPolicyProperties the mcp policy properties value
     * @param mcpWorkflowProperties the mcp workflow properties value
     * @param policyProviders the policy providers value
     * @param userPolicyStores the user policy stores value
     * @param auditSinks the audit sinks value
     */
    @Autowired
    public ToolRuntimeService(ToolRegistry toolRegistry,
                              ObjectMapper objectMapper,
                              ToolRuntimeProperties properties,
                              McpPolicyProperties mcpPolicyProperties,
                              McpWorkflowProperties mcpWorkflowProperties,
                              List<ToolRuntimePolicyProvider> policyProviders,
                              List<ToolRuntimeUserPolicyStore> userPolicyStores,
                              List<ToolRuntimeAuditSink> auditSinks) {
        this.toolRegistry = toolRegistry;
        this.objectMapper = objectMapper;
        this.properties = properties == null ? new ToolRuntimeProperties() : properties;
        this.mcpPolicyProperties = mcpPolicyProperties == null ? new McpPolicyProperties() : mcpPolicyProperties;
        this.mcpWorkflowProperties = mcpWorkflowProperties == null ? new McpWorkflowProperties() : mcpWorkflowProperties;
        this.policyProviders = policyProviders == null ? List.of() : policyProviders;
        this.userPolicyStore = userPolicyStores == null || userPolicyStores.isEmpty()
            ? new InMemoryToolRuntimeUserPolicyStore()
            : userPolicyStores.get(0);
        this.auditSinks = auditSinks == null ? List.of() : auditSinks;
        this.toolExecutionExecutor = new ThreadPoolExecutor(
            this.properties.safeExecutionCorePoolSize(),
            this.properties.safeExecutionMaxPoolSize(),
            60L,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(this.properties.safeExecutionQueueCapacity()),
            new ToolRuntimeThreadFactory(),
            new ThreadPoolExecutor.AbortPolicy()
        );
    }

    /**
     * Creates a new ToolRuntimeService instance.
     *
     * @param toolRegistry the tool registry value
     * @param objectMapper the object mapper value
     * @param properties the properties value
     * @param mcpPolicyProperties the mcp policy properties value
     * @param mcpWorkflowProperties the mcp workflow properties value
     * @param policyProviders the policy providers value
     * @param auditSinks the audit sinks value
     */
    public ToolRuntimeService(ToolRegistry toolRegistry,
                              ObjectMapper objectMapper,
                              ToolRuntimeProperties properties,
                              McpPolicyProperties mcpPolicyProperties,
                              McpWorkflowProperties mcpWorkflowProperties,
                              List<ToolRuntimePolicyProvider> policyProviders,
                              List<ToolRuntimeAuditSink> auditSinks) {
        this(
            toolRegistry,
            objectMapper,
            properties,
            mcpPolicyProperties,
            mcpWorkflowProperties,
            policyProviders,
            List.of(),
            auditSinks
        );
    }

    /**
     * Creates a new ToolRuntimeService instance.
     *
     * @param toolRegistry the tool registry value
     * @param objectMapper the object mapper value
     * @param properties the properties value
     * @param mcpPolicyProperties the mcp policy properties value
     * @param policyProviders the policy providers value
     * @param auditSinks the audit sinks value
     */
    public ToolRuntimeService(ToolRegistry toolRegistry,
                              ObjectMapper objectMapper,
                              ToolRuntimeProperties properties,
                              McpPolicyProperties mcpPolicyProperties,
                              List<ToolRuntimePolicyProvider> policyProviders,
                              List<ToolRuntimeAuditSink> auditSinks) {
        this(toolRegistry, objectMapper, properties, mcpPolicyProperties, new McpWorkflowProperties(), policyProviders, auditSinks);
    }

    /**
     * Creates a new ToolRuntimeService instance.
     *
     * @param toolRegistry the tool registry value
     * @param objectMapper the object mapper value
     * @param properties the properties value
     * @param policyProviders the policy providers value
     * @param auditSinks the audit sinks value
     */
    public ToolRuntimeService(ToolRegistry toolRegistry,
                              ObjectMapper objectMapper,
                              ToolRuntimeProperties properties,
                              List<ToolRuntimePolicyProvider> policyProviders,
                              List<ToolRuntimeAuditSink> auditSinks) {
        this(toolRegistry, objectMapper, properties, new McpPolicyProperties(), new McpWorkflowProperties(), policyProviders, auditSinks);
    }

    /**
     * Executes the execute.
     *
     * @param request the request value
     * @return the operation result
     */
    public ToolRuntimeExecution execute(ToolRuntimeRequest request) {
        String toolName = normalizeText(request == null ? null : request.getToolName());
        if (toolName == null) {
            return deniedExecution("unknown", request, null, "Tool name is required", "INVALID_REQUEST", null, null);
        }

        ToolMetadata metadata = toolRegistry.getToolMetadata(toolName);
        ToolInput toolInput = request.getToolInput() == null ? new ToolInput() : request.getToolInput();
        if (isParamBindingDenied(toolInput)) {
            return deniedExecution(toolName, request, metadata,
                firstText(paramBindingError(toolInput), "Tool parameter binding was denied by runtime policy"),
                firstText(paramBindingCode(toolInput), "MCP_PARAM_BINDING_DENIED"),
                null,
                null);
        }
        ToolRuntimePolicy policy = resolvePolicy(request, metadata);
        ToolExecutionPlan executionPlan = buildExecutionPlan(toolName, request, metadata, toolInput);
        ToolPolicyDecision policyDecision = decideMcpPolicy(toolName, request, metadata, toolInput, policy, executionPlan);
        WorkflowDecision workflowDecision = decideWorkflow(toolName, request, toolInput, executionPlan);
        policyDecision = applyWorkflowDecision(policyDecision, workflowDecision);

        if (isDeniedByPolicy(toolName, request)) {
            return deniedExecution(toolName, request, metadata,
                "Tool is not allowed in the current runtime policy: " + toolName,
                "TOOL_PERMISSION_DENIED",
                executionPlan,
                policyDecision);
        }
        if (isDeniedByResolvedPolicy(policy)) {
            return deniedExecution(toolName, request, metadata,
                firstText(policy.reason(), "Tool denied by tenant runtime policy: " + toolName),
                "TOOL_TENANT_POLICY_DENIED",
                executionPlan,
                policyDecision);
        }
        if (policyDecision.action() == ToolRuntimeAction.DENY) {
            return deniedExecution(toolName, request, metadata,
                firstText(policyDecision.reason(), "MCP policy denied tool execution: " + toolName),
                workflowDenied(policyDecision) ? "MCP_WORKFLOW_DENIED" : "MCP_POLICY_DENIED",
                executionPlan,
                policyDecision);
        }
        if (policyDecision.action() == ToolRuntimeAction.ASK_BEFORE_EXECUTE && !isConfirmed(request, policyDecision)) {
            return confirmationRequiredExecution(toolName, request, metadata, executionPlan, policyDecision);
        }
        if (requiresAuthentication(metadata, policy) && normalizeText(toolInput.getUserId()) == null) {
            return deniedExecution(toolName, request, metadata,
                "Tool requires an authenticated user: " + toolName,
                "TOOL_AUTH_REQUIRED",
                executionPlan,
                policyDecision);
        }
        if (isCircuitOpen(toolName, policy)) {
            return rejectedExecution(toolName, request, metadata,
                "Tool circuit is open: " + toolName,
                "TOOL_CIRCUIT_OPEN",
                "circuit_open",
                executionPlan,
                policyDecision);
        }
        if (isRateLimited(toolName, metadata, toolInput.getUserId(), policy)) {
            return rejectedExecution(toolName, request, metadata,
                "Tool rate limit exceeded: " + toolName,
                "TOOL_RATE_LIMITED",
                "rate_limited",
                executionPlan,
                policyDecision);
        }

        ToolCounters toolCounters = counters.computeIfAbsent(toolName, ignored -> new ToolCounters());
        toolCounters.totalCalls.incrementAndGet();
        toolCounters.activeCalls.incrementAndGet();

        long startedAt = System.currentTimeMillis();
        try {
            rememberUserToolPolicy(request, policyDecision);
            ToolOutput output = executeToolWithTimeout(toolName, toolInput, request, policy, metadata);
            if (output.getMetadata() == null) {
                output.setMetadata(new LinkedHashMap<>());
            }
            output.setData(processResultData(output.getData(), metadata));
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
                rememberWorkflowSuccess(toolName, request, executionPlan, workflowDecision);
            } else {
                updateCircuitOnFailure(toolName, policy);
                toolCounters.failedCalls.incrementAndGet();
                rememberWorkflowFailure(toolName, request, executionPlan, workflowDecision);
            }
            toolCounters.totalDurationMs.addAndGet(durationMs);
            toolCounters.lastDurationMs.set(durationMs);

            Map<String, Object> runtimeMetadata = runtimeMetadata(
                request,
                metadata,
                output.isSuccess() ? "success" : "failed",
                null,
                executionPlan,
                policyDecision
            );
            InteractionToolTrace trace = buildTrace(toolName, metadata, toolInput, output, startedAt, finishedAt, runtimeMetadata);
            logAudit(toolName, request, output.isSuccess() ? "success" : "failed", durationMs, output.getErrorMessage());
            publishAuditRecord(request, metadata, output, trace, output.isSuccess() ? "success" : "failed", null, durationMs, runtimeMetadata);
            return new ToolRuntimeExecution(output, metadata, trace, output.isSuccess() ? "success" : "failed", runtimeMetadata);
        } finally {
            toolCounters.activeCalls.decrementAndGet();
        }
    }

    @PreDestroy
    public void shutdown() {
        toolExecutionExecutor.shutdownNow();
    }

    private ToolOutput executeToolWithTimeout(String toolName,
                                              ToolInput toolInput,
                                              ToolRuntimeRequest request,
                                              ToolRuntimePolicy policy,
                                              ToolMetadata metadata) {
        long timeoutMs = resolveToolTimeoutMs(request, policy, metadata);
        CompletableFuture<ToolOutput> future;
        try {
            future = CompletableFuture.supplyAsync(() -> toolRegistry.executeEnhancedTool(toolName, toolInput), toolExecutionExecutor);
        } catch (RejectedExecutionException ex) {
            ToolOutput output = ToolOutput.failure("Tool execution queue is full: " + toolName);
            output.setExceptionType("TOOL_EXECUTION_REJECTED");
            return output;
        }
        try {
            ToolOutput output = timeoutMs <= 0
                ? future.get()
                : future.get(timeoutMs, TimeUnit.MILLISECONDS);
            return output == null ? ToolOutput.failure("Tool returned no output: " + toolName) : output;
        } catch (TimeoutException ex) {
            future.cancel(true);
            ToolOutput output = ToolOutput.failure("Tool execution timed out after " + timeoutMs + " ms: " + toolName);
            output.setExceptionType("TOOL_TIMEOUT");
            return output;
        } catch (InterruptedException ex) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            ToolOutput output = ToolOutput.failure("Tool execution interrupted: " + toolName);
            output.setExceptionType("TOOL_INTERRUPTED");
            return output;
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause() == null ? ex : ex.getCause();
            ToolOutput output = ToolOutput.failure(cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage());
            output.setExceptionType(cause.getClass().getSimpleName());
            return output;
        }
    }

    private long resolveToolTimeoutMs(ToolRuntimeRequest request, ToolRuntimePolicy policy, ToolMetadata metadata) {
        String toolName = request == null ? null : request.getToolName();
        if (isMcpTool(toolName, metadata)) {
            return 0L;
        }
        Object value = firstPresent(
            request == null || request.getAttributes() == null ? null : request.getAttributes().get("toolTimeoutMs"),
            policy == null || policy.attributes() == null ? null : policy.attributes().get("toolTimeoutMs"),
            metadata == null ? null : metadata.getTimeoutMillis()
        );
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value != null) {
            try {
                return Long.parseLong(String.valueOf(value));
            } catch (NumberFormatException ignored) {
                return properties.safeDefaultToolTimeoutMs();
            }
        }
        return properties.safeDefaultToolTimeoutMs();
    }

    private boolean isMcpTool(String toolName, ToolMetadata metadata) {
        String normalized = normalizePolicyKey(firstText(toolName, metadata == null ? null : metadata.getId()));
        if (normalized.startsWith("mcp_")) {
            return true;
        }
        if (metadata != null) {
            if (metadata.getCategories() != null && metadata.getCategories().stream()
                .anyMatch(category -> "mcp".equalsIgnoreCase(String.valueOf(category)))) {
                return true;
            }
            if (metadata.getTags() != null && metadata.getTags().stream()
                .anyMatch(tag -> "mcp".equalsIgnoreCase(String.valueOf(tag)))) {
                return true;
            }
        }
        return normalized.contains("web_search")
            || normalized.contains("document_search")
            || normalized.contains("crawl_url")
            || normalized.contains("finance_site_search")
            || normalized.contains("generic_web_site_search")
            || normalized.contains("retrieve_financial_evidence")
            || normalized.contains("retrieve_evidence")
            || normalized.contains("search_and_extract");
    }

    /**
     * Performs the snapshot operation.
     *
     * @return the operation result
     */
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

    /**
     * Performs the metadata operation.
     *
     * @param toolName the tool name value
     * @return the operation result
     */
    public ToolMetadata metadata(String toolName) {
        return toolRegistry.getToolMetadata(toolName);
    }

    /**
     * Performs the denied execution operation.
     *
     * @param toolName the tool name value
     * @param request the request value
     * @param metadata the metadata value
     * @param message the message value
     * @param errorCode the error code value
     * @param executionPlan the execution plan value
     * @param policyDecision the policy decision value
     * @return the operation result
     */
    private ToolRuntimeExecution deniedExecution(String toolName,
                                                 ToolRuntimeRequest request,
                                                 ToolMetadata metadata,
                                                 String message,
                                                 String errorCode,
                                                 ToolExecutionPlan executionPlan,
                                                 ToolPolicyDecision policyDecision) {
        ToolCounters toolCounters = counters.computeIfAbsent(toolName, ignored -> new ToolCounters());
        toolCounters.totalCalls.incrementAndGet();
        toolCounters.deniedCalls.incrementAndGet();
        Map<String, Object> runtimeMetadata = runtimeMetadata(request, metadata, "denied", errorCode, executionPlan, policyDecision);
        ToolOutput output = ToolOutput.failure(message);
        output.setExceptionType(errorCode);
        output.getMetadata().putAll(runtimeMetadata);
        InteractionToolTrace trace = buildTrace(toolName, metadata, request == null ? new ToolInput() : request.getToolInput(),
            output, System.currentTimeMillis(), System.currentTimeMillis(), runtimeMetadata);
        logAudit(toolName, request, "denied", 0L, message);
        publishAuditRecord(request, metadata, output, trace, "denied", errorCode, 0L, runtimeMetadata);
        return new ToolRuntimeExecution(output, metadata, trace, "denied", runtimeMetadata);
    }

    /**
     * Performs the rejected execution operation.
     *
     * @param toolName the tool name value
     * @param request the request value
     * @param metadata the metadata value
     * @param message the message value
     * @param errorCode the error code value
     * @param outcome the outcome value
     * @param executionPlan the execution plan value
     * @param policyDecision the policy decision value
     * @return the operation result
     */
    private ToolRuntimeExecution rejectedExecution(String toolName,
                                                   ToolRuntimeRequest request,
                                                   ToolMetadata metadata,
                                                   String message,
                                                   String errorCode,
                                                   String outcome,
                                                   ToolExecutionPlan executionPlan,
                                                   ToolPolicyDecision policyDecision) {
        ToolCounters toolCounters = counters.computeIfAbsent(toolName, ignored -> new ToolCounters());
        toolCounters.totalCalls.incrementAndGet();
        if ("rate_limited".equals(outcome)) {
            toolCounters.rateLimitedCalls.incrementAndGet();
        }
        if ("circuit_open".equals(outcome)) {
            toolCounters.circuitOpenRejects.incrementAndGet();
        }
        Map<String, Object> runtimeMetadata = runtimeMetadata(request, metadata, outcome, errorCode, executionPlan, policyDecision);
        ToolOutput output = ToolOutput.failure(message);
        output.setExceptionType(errorCode);
        output.getMetadata().putAll(runtimeMetadata);
        InteractionToolTrace trace = buildTrace(toolName, metadata, request == null ? new ToolInput() : request.getToolInput(),
            output, System.currentTimeMillis(), System.currentTimeMillis(), runtimeMetadata);
        logAudit(toolName, request, outcome, 0L, message);
        publishAuditRecord(request, metadata, output, trace, outcome, errorCode, 0L, runtimeMetadata);
        return new ToolRuntimeExecution(output, metadata, trace, outcome, runtimeMetadata);
    }

    /**
     * Performs the confirmation required execution operation.
     *
     * @param toolName the tool name value
     * @param request the request value
     * @param metadata the metadata value
     * @param executionPlan the execution plan value
     * @param policyDecision the policy decision value
     * @return the operation result
     */
    private ToolRuntimeExecution confirmationRequiredExecution(String toolName,
                                                               ToolRuntimeRequest request,
                                                               ToolMetadata metadata,
                                                               ToolExecutionPlan executionPlan,
                                                               ToolPolicyDecision policyDecision) {
        ToolCounters toolCounters = counters.computeIfAbsent(toolName, ignored -> new ToolCounters());
        toolCounters.totalCalls.incrementAndGet();
        Map<String, Object> runtimeMetadata = runtimeMetadata(
            request,
            metadata,
            "confirmation_required",
            "MCP_CONFIRMATION_REQUIRED",
            executionPlan,
            policyDecision
        );
        ToolOutput output = ToolOutput.failure("MCP tool execution requires user confirmation: " + toolName);
        output.setExceptionType("MCP_CONFIRMATION_REQUIRED");
        output.setData(Map.of("confirmationRequired", runtimeMetadata.get("confirmation")));
        output.getMetadata().putAll(runtimeMetadata);
        long now = System.currentTimeMillis();
        InteractionToolTrace trace = buildTrace(toolName, metadata, request == null ? new ToolInput() : request.getToolInput(),
            output, now, now, runtimeMetadata);
        logAudit(toolName, request, "confirmation_required", 0L, output.getErrorMessage());
        publishAuditRecord(request, metadata, output, trace, "confirmation_required",
            "MCP_CONFIRMATION_REQUIRED", 0L, runtimeMetadata);
        return new ToolRuntimeExecution(output, metadata, trace, "confirmation_required", runtimeMetadata);
    }

    /**
     * Returns whether is denied by policy.
     *
     * @param toolName the tool name value
     * @param request the request value
     * @return whether the condition is satisfied
     */
    private boolean isDeniedByPolicy(String toolName, ToolRuntimeRequest request) {
        if (!properties.isEnforceAllowedTools() || request == null || request.getAllowedTools() == null || request.getAllowedTools().isEmpty()) {
            return false;
        }
        return request.getAllowedTools().stream()
            .filter(value -> value != null && !value.isBlank())
            .map(String::trim)
            .noneMatch(toolName::equals);
    }

    private boolean isParamBindingDenied(ToolInput toolInput) {
        Map<String, Object> parameters = toolInput == null ? null : toolInput.getParameters();
        Object status = parameters == null ? null : parameters.get("__runtimeParamBindingStatus");
        if (status == null) {
            return false;
        }
        String normalized = String.valueOf(status).trim();
        return "DENIED".equalsIgnoreCase(normalized) || "REVIEW_REQUIRED".equalsIgnoreCase(normalized);
    }

    private String paramBindingError(ToolInput toolInput) {
        Map<String, Object> parameters = toolInput == null ? null : toolInput.getParameters();
        Object value = parameters == null ? null : parameters.get("__runtimeParamBindingError");
        return value == null ? null : String.valueOf(value);
    }

    private String paramBindingCode(ToolInput toolInput) {
        Map<String, Object> parameters = toolInput == null ? null : toolInput.getParameters();
        Object value = parameters == null ? null : parameters.get("__runtimeParamBindingCode");
        return value == null ? null : String.valueOf(value);
    }

    /**
     * Returns whether requires authentication.
     *
     * @param metadata the metadata value
     * @param policy the policy value
     * @return whether the condition is satisfied
     */
    private boolean requiresAuthentication(ToolMetadata metadata, ToolRuntimePolicy policy) {
        boolean requiresAuth = metadata != null && metadata.isRequiresAuth();
        if (policy != null && policy.requiresAuthentication() != null) {
            requiresAuth = policy.requiresAuthentication();
        }
        return properties.isEnforceAuthentication() && requiresAuth;
    }

    /**
     * Returns whether is rate limited.
     *
     * @param toolName the tool name value
     * @param metadata the metadata value
     * @param userId the user id value
     * @param policy the policy value
     * @return whether the condition is satisfied
     */
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

    /**
     * Returns whether is circuit open.
     *
     * @param toolName the tool name value
     * @param policy the policy value
     * @return whether the condition is satisfied
     */
    private boolean isCircuitOpen(String toolName, ToolRuntimePolicy policy) {
        CircuitState state = circuitStates.computeIfAbsent(toolName, ignored -> new CircuitState());
        if (state.openedUntilMs.get() <= System.currentTimeMillis()) {
            return false;
        }
        return threshold(policy) > 0;
    }

    /**
     * Updates the circuit on failure.
     *
     * @param toolName the tool name value
     * @param policy the policy value
     */
    private void updateCircuitOnFailure(String toolName, ToolRuntimePolicy policy) {
        CircuitState state = circuitStates.computeIfAbsent(toolName, ignored -> new CircuitState());
        int failures = state.consecutiveFailures.incrementAndGet();
        if (failures >= threshold(policy)) {
            long until = System.currentTimeMillis() + Math.max(1, openSeconds(policy)) * 1000L;
            state.openedUntilMs.set(until);
        }
    }

    /**
     * Performs the reset circuit operation.
     *
     * @param toolName the tool name value
     */
    private void resetCircuit(String toolName) {
        CircuitState state = circuitStates.computeIfAbsent(toolName, ignored -> new CircuitState());
        state.consecutiveFailures.set(0);
        state.openedUntilMs.set(0L);
    }

    /**
     * Builds the trace.
     *
     * @param toolName the tool name value
     * @param metadata the metadata value
     * @param input the input value
     * @param output the output value
     * @param startedAt the started at value
     * @param finishedAt the finished at value
     * @param runtimeMetadata the runtime metadata value
     * @return the built trace
     */
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

    /**
     * Runs the configured startup logic.
     *
     * @param request the request value
     * @param metadata the metadata value
     * @param outcome the outcome value
     * @param errorCode the error code value
     * @param executionPlan the execution plan value
     * @param policyDecision the policy decision value
     * @return the operation result
     */
    private Map<String, Object> runtimeMetadata(ToolRuntimeRequest request,
                                                ToolMetadata metadata,
                                                String outcome,
                                                String errorCode,
                                                ToolExecutionPlan executionPlan,
                                                ToolPolicyDecision policyDecision) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("runtimeMode", normalizeMode(request));
        values.put("requestId", request == null ? null : request.getRequestId());
        values.put("conversationId", request == null ? null : request.getConversationId());
        values.put("userId", request == null ? null : request.getUserId());
        values.put("outcome", outcome);
        values.put("errorCode", errorCode);
        values.put("serviceId", resolveServiceId(metadata));
        values.put("serviceName", resolveServiceName(metadata));
        values.put("executionPlan", executionPlan == null ? null : executionPlan.toMap());
        if (policyDecision != null) {
            values.put("policyResult", policyDecision.action().code());
            values.put("policyReason", policyDecision.reason());
            values.put("runtimeLevel", policyDecision.runtimeLevel());
            values.put("riskLevel", policyDecision.riskLevel());
            values.put("operationType", policyDecision.operationType());
            values.put("dataScope", policyDecision.dataScope());
            values.put("matchedPolicyRules", policyDecision.matchedRules());
            if (policyDecision.action() == ToolRuntimeAction.ASK_BEFORE_EXECUTE) {
                values.put("confirmation", buildConfirmationPayload(request, metadata, executionPlan, policyDecision));
            }
        }
        ToolGovernanceDecision governance = governanceDecision(request, metadata, outcome, executionPlan, policyDecision);
        values.put("governance", governance.toMap());
        values.put("tenantId", governance.tenantId());
        values.put("roles", governance.roles());
        values.put("auditId", governance.auditId());
        values.put("policyDecision", governance.policyDecision());
        values.put("confirmRequired", governance.confirmRequired());
        return values;
    }

    private ToolGovernanceDecision governanceDecision(ToolRuntimeRequest request,
                                                      ToolMetadata metadata,
                                                      String outcome,
                                                      ToolExecutionPlan executionPlan,
                                                      ToolPolicyDecision policyDecision) {
        ToolRuntimeAction action = policyDecision == null ? null : policyDecision.action();
        String runtimeLevel = policyDecision == null
            ? normalizeRuntimeLevel(firstText(metadata == null ? null : metadata.getRuntimeLevel(), properties.getDefaultRuntimeLevel()))
            : policyDecision.runtimeLevel();
        String toolRiskLevel = policyDecision == null
            ? firstText(executionPlan == null ? null : executionPlan.riskLevel(), metadata == null ? "low" : metadata.getRiskLevel())
            : policyDecision.riskLevel();
        String decision = policyDecisionLabel(action, outcome);
        boolean confirmRequired = action == ToolRuntimeAction.ASK_BEFORE_EXECUTE
            || "REQUIRE_CONFIRM".equals(decision)
            || "confirmation_required".equals(outcome);
        boolean confirmed = policyDecision != null
            && action == ToolRuntimeAction.ASK_BEFORE_EXECUTE
            && isConfirmed(request, policyDecision)
            && !"confirmation_required".equals(outcome);
        return new ToolGovernanceDecision(
            ToolGovernanceDecision.CONTRACT_VERSION,
            normalizeText(request == null ? null : request.getTenantId()),
            normalizeText(request == null ? null : request.getUserId()),
            governanceRoles(request),
            runtimeLevel,
            normalizePolicyKey(toolRiskLevel),
            confirmRequired,
            confirmed,
            auditId(request),
            decision,
            action == null ? null : action.code(),
            policyDecision == null ? null : policyDecision.reason(),
            runtimeLevel,
            policyDecision == null
                ? firstText(executionPlan == null ? null : executionPlan.operationType(), metadata == null ? null : metadata.getOperationType())
                : policyDecision.operationType(),
            policyDecision == null ? null : policyDecision.dataScope(),
            policyDecision == null ? List.of() : policyDecision.matchedRules()
        );
    }

    private String policyDecisionLabel(ToolRuntimeAction action, String outcome) {
        if (action == ToolRuntimeAction.DENY || "denied".equals(outcome)) {
            return "BLOCK";
        }
        if (action == ToolRuntimeAction.ASK_BEFORE_EXECUTE || "confirmation_required".equals(outcome)) {
            return "REQUIRE_CONFIRM";
        }
        return "ALLOW";
    }

    private String auditId(ToolRuntimeRequest request) {
        Object value = firstPresent(
            request == null || request.getAttributes() == null ? null : request.getAttributes().get("auditId"),
            request == null || request.getAttributes() == null ? null : request.getAttributes().get("toolAuditId")
        );
        String text = value == null ? null : String.valueOf(value).trim();
        return text == null || text.isBlank() ? UUID.randomUUID().toString() : text;
    }

    private List<String> governanceRoles(ToolRuntimeRequest request) {
        List<String> roles = new ArrayList<>();
        collectRoles(request == null || request.getAttributes() == null ? null : request.getAttributes().get("roles"), roles);
        collectRoles(request == null || request.getAttributes() == null ? null : request.getAttributes().get("role"), roles);
        ToolInput input = request == null ? null : request.getToolInput();
        collectRoles(input == null || input.getContext() == null ? null : input.getContext().get("roles"), roles);
        collectRoles(input == null || input.getContext() == null ? null : input.getContext().get("role"), roles);
        return roles.stream()
            .map(String::trim)
            .filter(value -> !value.isBlank())
            .distinct()
            .toList();
    }

    private void collectRoles(Object value, List<String> roles) {
        if (value == null || roles == null) {
            return;
        }
        if (value instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                collectRoles(item, roles);
            }
            return;
        }
        String text = String.valueOf(value);
        for (String item : text.split("[,;]")) {
            if (!item.isBlank()) {
                roles.add(item.trim());
            }
        }
    }

    /**
     * Resolves the policy.
     *
     * @param request the request value
     * @param metadata the metadata value
     * @return the resolved policy
     */
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

    /**
     * Performs the merge policies operation.
     *
     * @param base the base value
     * @param override the override value
     * @return the operation result
     */
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
            .executionAction(override.executionAction() != null ? override.executionAction() : base.executionAction())
            .runtimeLevel(firstText(override.runtimeLevel(), base.runtimeLevel()))
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

    /**
     * Returns whether is denied by resolved policy.
     *
     * @param policy the policy value
     * @return whether the condition is satisfied
     */
    private boolean isDeniedByResolvedPolicy(ToolRuntimePolicy policy) {
        return policy != null && Boolean.FALSE.equals(policy.allowed());
    }

    /**
     * Builds the execution plan.
     *
     * @param toolName the tool name value
     * @param request the request value
     * @param metadata the metadata value
     * @param toolInput the tool input value
     * @return the built execution plan
     */
    private ToolExecutionPlan buildExecutionPlan(String toolName,
                                                 ToolRuntimeRequest request,
                                                 ToolMetadata metadata,
                                                 ToolInput toolInput) {
        Map<String, Object> plan = asMap(request == null || request.getAttributes() == null
            ? null
            : request.getAttributes().get("executionPlan"));
        Map<String, Object> parameters = toolInput == null || toolInput.getParameters() == null
            ? Map.of()
            : new LinkedHashMap<>(toolInput.getParameters());
        return ToolExecutionPlan.builder()
            .workflow(firstText(
                firstText(stringValue(plan.get("workflow")), stringValue(plan.get("workflow_id"))),
                stringValue(plan.get("workflowId"))
            ))
            .intent(firstText(stringValue(plan.get("intent")), stringValue(plan.get("reason"))))
            .tool(firstText(stringValue(plan.get("tool")), toolName))
            .operationType(firstText(
                firstText(stringValue(plan.get("operation_type")), stringValue(plan.get("operationType"))),
                metadata == null ? "read" : firstText(metadata.getOperationType(), "read")
            ))
            .riskLevel(firstText(
                firstText(stringValue(plan.get("risk_level")), stringValue(plan.get("riskLevel"))),
                metadata == null ? "low" : firstText(metadata.getRiskLevel(), "low")
            ))
            .parameters(parameters)
            .reason(firstText(stringValue(plan.get("reason")), "Runtime planned MCP tool invocation"))
            .build();
    }

    /**
     * Performs the decide mcp policy operation.
     *
     * @param toolName the tool name value
     * @param request the request value
     * @param metadata the metadata value
     * @param toolInput the tool input value
     * @param policy the policy value
     * @param executionPlan the execution plan value
     * @return the operation result
     */
    private ToolPolicyDecision decideMcpPolicy(String toolName,
                                               ToolRuntimeRequest request,
                                               ToolMetadata metadata,
                                               ToolInput toolInput,
                                               ToolRuntimePolicy policy,
                                               ToolExecutionPlan executionPlan) {
        String riskLevel = normalizePolicyKey(firstText(executionPlan.riskLevel(), metadata == null ? "low" : metadata.getRiskLevel()));
        String operationType = firstText(executionPlan.operationType(), metadata == null ? "read" : metadata.getOperationType());
        String runtimeLevel = resolveRuntimeLevel(toolName, request, metadata, policy, executionPlan);
        String dataScope = inferDataScope(toolInput == null ? Map.of() : toolInput.getParameters());
        if (mcpPolicyProperties == null || !mcpPolicyProperties.isEnabled() || !isMcpGovernedTool(toolName, metadata)) {
            ToolRuntimeAction action = actionForRuntimeLevel(runtimeLevel);
            return new ToolPolicyDecision(action, "Runtime level resolved action " + action.code(),
                runtimeLevel, riskLevel, operationType, dataScope,
                List.of("runtime_level." + runtimeLevel + "=" + action.code()),
                confirmationToken(request, executionPlan));
        }

        List<String> matchedRules = new ArrayList<>();
        ToolRuntimeAction riskAction = ToolRuntimeAction.from(
            valueForKey(mcpPolicyProperties.getRiskPolicy(), riskLevel),
            defaultActionForRisk(riskLevel)
        );
        matchedRules.add("risk_policy." + riskLevel + "=" + riskAction.code());

        ToolRuntimeAction action = riskAction;
        Object confirmationDefault = metadata == null || metadata.getConfirmation() == null
            ? null
            : metadata.getConfirmation().get("default");
        ToolRuntimeAction metadataAction = ToolRuntimeAction.from(confirmationDefault, null);
        if (metadataAction != null) {
            action = metadataAction;
            matchedRules.add("tool_metadata.confirmation.default=" + metadataAction.code());
        }
        if (policy != null && policy.executionAction() != null) {
            action = policy.executionAction();
            matchedRules.add("runtime_policy_provider=" + action.code());
        }

        ToolRuntimeAction levelAction = actionForRuntimeLevel(runtimeLevel);
        if (levelAction == ToolRuntimeAction.DENY) {
            action = ToolRuntimeAction.DENY;
        } else if (levelAction == ToolRuntimeAction.ASK_BEFORE_EXECUTE && action != ToolRuntimeAction.DENY) {
            action = ToolRuntimeAction.ASK_BEFORE_EXECUTE;
        }
        matchedRules.add("runtime_level." + runtimeLevel + "=" + levelAction.code());

        ToolRuntimeAction toolAction = actionForTool(toolName, metadata);
        if (toolAction != null) {
            action = toolAction;
            matchedRules.add("tool_policy." + toolName + "=" + toolAction.code());
        }

        ParameterDecision parameterDecision = decideParameterPolicy(toolName, metadata, toolInput);
        matchedRules.addAll(parameterDecision.matchedRules());
        if (parameterDecision.action() == ToolRuntimeAction.DENY) {
            action = ToolRuntimeAction.DENY;
        } else if (parameterDecision.action() != null && action != ToolRuntimeAction.DENY) {
            action = parameterDecision.action();
        }
        if ("forbidden".equals(riskLevel)) {
            action = ToolRuntimeAction.DENY;
        }
        ToolRuntimeAction userOverride = userPolicyStore
            .findAction(
                normalizeText(request == null ? null : request.getTenantId()),
                normalizeText(request == null ? null : request.getUserId()),
                toolName
            )
            .orElse(null);
        if (userOverride != null) {
            if (userOverride == ToolRuntimeAction.DENY || action != ToolRuntimeAction.DENY) {
                action = userOverride;
                matchedRules.add("user_tool_policy=" + userOverride.code());
            } else {
                matchedRules.add("user_tool_policy_ignored_by_deny=" + userOverride.code());
            }
        }

        return new ToolPolicyDecision(action, "MCP policy resolved action " + action.code(),
            /**
             * Performs the confirmation token operation.
             *
             * @param request the request value
             * @param executionPlan the execution plan value
             * @return the operation result
             */
            runtimeLevel, riskLevel, operationType, dataScope, matchedRules, confirmationToken(request, executionPlan));
    }

    private String resolveRuntimeLevel(String toolName,
                                       ToolRuntimeRequest request,
                                       ToolMetadata metadata,
                                       ToolRuntimePolicy policy,
                                       ToolExecutionPlan executionPlan) {
        String value = null;
        value = firstText(properties.getDefaultRuntimeLevel(), value);
        value = firstText(levelFromOperationAndRisk(executionPlan), value);
        value = firstText(metadata == null ? null : metadata.getRuntimeLevel(), value);
        value = firstText(configuredRuntimeLevel(toolName, metadata), value);
        value = firstText(policy == null ? null : policy.runtimeLevel(), value);
        Map<String, Object> requestAttributes = request == null ? null : request.getAttributes();
        if (requestAttributes != null) {
            Map<String, Object> plan = asMap(requestAttributes.get("executionPlan"));
            value = firstText(stringValue(firstPresent(
                requestAttributes.get("runtimeLevel"),
                requestAttributes.get("toolRuntimeLevel"),
                plan.get("runtime_level"),
                plan.get("runtimeLevel")
            )), value);
        }
        return normalizeRuntimeLevel(value);
    }

    private String configuredRuntimeLevel(String toolName, ToolMetadata metadata) {
        if (properties == null || properties.getLevelPolicy() == null || properties.getLevelPolicy().isEmpty()) {
            return null;
        }
        for (String candidate : toolPolicyKeys(toolName, metadata)) {
            String configured = valueForKey(properties.getLevelPolicy(), candidate);
            if (configured != null && !configured.isBlank()) {
                return configured;
            }
        }
        return null;
    }

    private String levelFromOperationAndRisk(ToolExecutionPlan executionPlan) {
        if (executionPlan == null) {
            return null;
        }
        String risk = normalizePolicyKey(executionPlan.riskLevel());
        if ("forbidden".equals(risk)) {
            return "forbidden";
        }
        if ("high".equals(risk) || "medium".equals(risk)) {
            return "confirm_required";
        }
        String operation = normalizePolicyKey(executionPlan.operationType());
        if ("write".equals(operation) || "send".equals(operation) || "delete".equals(operation)
            || "permission_change".equals(operation)) {
            return "confirm_required";
        }
        return "readonly";
    }

    private ToolRuntimeAction actionForRuntimeLevel(String runtimeLevel) {
        return switch (normalizeRuntimeLevel(runtimeLevel)) {
            case "forbidden" -> ToolRuntimeAction.DENY;
            case "confirm_required" -> ToolRuntimeAction.ASK_BEFORE_EXECUTE;
            default -> ToolRuntimeAction.AUTO_EXECUTE;
        };
    }

    private String normalizeRuntimeLevel(String runtimeLevel) {
        String value = normalizePolicyKey(runtimeLevel);
        return switch (value) {
            case "readonly", "read_only", "read" -> "readonly";
            case "suggestion", "suggest", "advice" -> "suggestion";
            case "confirm_required", "confirmation_required", "ask_before_execute", "confirm" -> "confirm_required";
            case "forbidden", "deny", "blocked" -> "forbidden";
            default -> "readonly";
        };
    }

    /**
     * Returns whether is mcp governed tool.
     *
     * @param toolName the tool name value
     * @param metadata the metadata value
     * @return whether the condition is satisfied
     */
    private boolean isMcpGovernedTool(String toolName, ToolMetadata metadata) {
        if (toolName != null && toolName.startsWith("mcp_")) {
            return true;
        }
        if (metadata == null) {
            return false;
        }
        if (metadata.getAuthor() != null && metadata.getAuthor().trim().startsWith("MCP:")) {
            return true;
        }
        if (metadata.getCategories() != null && metadata.getCategories().stream().anyMatch("mcp"::equalsIgnoreCase)) {
            return true;
        }
        if (metadata.getTags() != null && metadata.getTags().stream().anyMatch("mcp"::equalsIgnoreCase)) {
            return true;
        }
        return metadata.getRiskLevel() != null && !"low".equalsIgnoreCase(metadata.getRiskLevel());
    }

    /**
     * Returns whether workflow denied.
     *
     * @param policyDecision the policy decision value
     * @return whether the condition is satisfied
     */
    private boolean workflowDenied(ToolPolicyDecision policyDecision) {
        return policyDecision != null
            && policyDecision.matchedRules() != null
            && policyDecision.matchedRules().stream().anyMatch(rule -> rule != null && rule.startsWith("workflow."));
    }

    /**
     * Performs the action for tool operation.
     *
     * @param toolName the tool name value
     * @param metadata the metadata value
     * @return the operation result
     */
    private ToolRuntimeAction actionForTool(String toolName, ToolMetadata metadata) {
        if (mcpPolicyProperties == null || mcpPolicyProperties.getToolPolicy() == null) {
            return null;
        }
        for (String candidate : toolPolicyKeys(toolName, metadata)) {
            String configured = valueForKey(mcpPolicyProperties.getToolPolicy(), candidate);
            ToolRuntimeAction action = ToolRuntimeAction.from(configured, null);
            if (action != null) {
                return action;
            }
        }
        return null;
    }

    /**
     * Performs the decide parameter policy operation.
     *
     * @param toolName the tool name value
     * @param metadata the metadata value
     * @param toolInput the tool input value
     * @return the operation result
     */
    private ParameterDecision decideParameterPolicy(String toolName, ToolMetadata metadata, ToolInput toolInput) {
        if (mcpPolicyProperties == null || mcpPolicyProperties.getParameterPolicy() == null) {
            return new ParameterDecision(null, List.of());
        }
        Map<String, Object> parameters = toolInput == null || toolInput.getParameters() == null
            ? Map.of()
            : toolInput.getParameters();
        List<String> matched = new ArrayList<>();
        ToolRuntimeAction action = null;
        for (String candidate : toolPolicyKeys(toolName, metadata)) {
            Map<String, String> rules = valueForNestedKey(mcpPolicyProperties.getParameterPolicy(), candidate);
            if (rules == null || rules.isEmpty()) {
                continue;
            }
            for (Map.Entry<String, String> entry : rules.entrySet()) {
                if (!parameterRuleMatches(entry.getKey(), parameters)) {
                    continue;
                }
                ToolRuntimeAction ruleAction = ToolRuntimeAction.from(entry.getValue(), null);
                if (ruleAction == null) {
                    continue;
                }
                matched.add("parameter_policy." + candidate + "." + entry.getKey() + "=" + ruleAction.code());
                if (ruleAction == ToolRuntimeAction.DENY) {
                    return new ParameterDecision(ruleAction, matched);
                }
                action = ruleAction;
            }
        }
        return new ParameterDecision(action, matched);
    }

    /**
     * Returns whether parameter rule matches.
     *
     * @param rule the rule value
     * @param parameters the parameters value
     * @return whether the condition is satisfied
     */
    private boolean parameterRuleMatches(String rule, Map<String, Object> parameters) {
        String normalized = normalizePolicyKey(rule);
        return switch (normalized) {
            case "recipient_count_gt_10" -> recipientCount(parameters) > 10;
            case "external_domain" -> hasExternalDomain(parameters);
            case "contains_delete" -> containsWord(parameters, "delete");
            case "contains_update" -> containsWord(parameters, "update");
            case "contains_drop" -> containsWord(parameters, "drop");
            case "customer_detail" -> isCustomerDetail(parameters);
            case "branch_summary" -> isBranchSummary(parameters);
            default -> truthy(parameters.get(rule)) || truthy(parameters.get(normalized));
        };
    }

    /**
     * Performs the decide workflow operation.
     *
     * @param toolName the tool name value
     * @param request the request value
     * @param toolInput the tool input value
     * @param executionPlan the execution plan value
     * @return the operation result
     */
    private WorkflowDecision decideWorkflow(String toolName,
                                            ToolRuntimeRequest request,
                                            ToolInput toolInput,
                                            ToolExecutionPlan executionPlan) {
        if (mcpWorkflowProperties == null || !mcpWorkflowProperties.isEnabled()) {
            return WorkflowDecision.notApplicable();
        }
        Map<String, Object> agentWorkflowConfig = agentWorkflowConfig(request);
        McpWorkflowProperties.WorkflowSpec agentWorkflow = workflowFromAgentConfig(agentWorkflowConfig, toolName, executionPlan);
        String workflowName = agentWorkflow == null
            ? resolveWorkflowName(toolName, request, executionPlan)
            : firstText(agentWorkflowName(agentWorkflowConfig, executionPlan), "agent_workflow");
        McpWorkflowProperties.WorkflowSpec workflow = agentWorkflow == null && workflowName != null
            ? mcpWorkflowProperties.getWorkflows().get(workflowName)
            : agentWorkflow;
        McpWorkflowProperties.ToolDependencySpec globalDependency = firstDependency(
            dependencyFromAgentConfig(agentWorkflowConfig, toolName),
            dependencyForTool(toolName)
        );
        if (workflow == null && globalDependency == null) {
            return WorkflowDecision.notApplicable();
        }

        String stateKey = workflowStateKey(request, workflowName);
        WorkflowState state = workflowStates.computeIfAbsent(stateKey, ignored -> new WorkflowState());
        Set<String> completed = completedTools(request, state);
        List<String> matchedRules = new ArrayList<>();
        matchedRules.add("workflow." + firstText(workflowName, "global") + ".active");

        McpWorkflowProperties.ExecutionStrategy strategy = workflow == null
            ? new McpWorkflowProperties.ExecutionStrategy()
            : workflow.getExecutionStrategy();
        int maxSteps = strategy == null ? 0 : strategy.getMaxSteps();
        if (maxSteps > 0 && state.attemptedSteps.get() + 1 > maxSteps) {
            return new WorkflowDecision(true, workflowName, stateKey, ToolRuntimeAction.DENY,
                "MCP workflow exceeded max_steps=" + maxSteps, matchedRules);
        }
        if (strategy != null && strategy.isStopOnError() && state.failed.get()) {
            return new WorkflowDecision(true, workflowName, stateKey, ToolRuntimeAction.DENY,
                "MCP workflow is stopped because a previous required step failed", matchedRules);
        }

        List<String> dependencies = new ArrayList<>();
        if (globalDependency != null && globalDependency.getDependsOn() != null) {
            dependencies.addAll(globalDependency.getDependsOn());
            matchedRules.add("tool_dependencies." + toolName + "=" + globalDependency.getDependsOn());
        }

        McpWorkflowProperties.WorkflowStep currentStep = workflow == null ? null : workflowStep(workflow, toolName);
        if (workflow != null) {
            if (currentStep == null) {
                return new WorkflowDecision(true, workflowName, stateKey, ToolRuntimeAction.DENY,
                    "Tool " + toolName + " is not part of MCP workflow " + workflowName, matchedRules);
            }
            if (currentStep.getDependsOn() != null) {
                dependencies.addAll(currentStep.getDependsOn());
            }
            WorkflowDecision sequenceDecision = validateWorkflowSequence(
                workflowName,
                workflow,
                currentStep,
                toolName,
                completed,
                strategy,
                matchedRules,
                stateKey
            );
            if (sequenceDecision.action() == ToolRuntimeAction.DENY) {
                return sequenceDecision;
            }
            if (currentStep.getCondition() != null && !currentStep.getCondition().isBlank()) {
                Map<String, Object> context = workflowContext(request, toolInput);
                if (!conditionMatches(currentStep.getCondition(), context)) {
                    matchedRules.add("workflow." + workflowName + "." + toolName + ".condition=false");
                    return new WorkflowDecision(true, workflowName, stateKey, ToolRuntimeAction.DENY,
                        "MCP workflow condition is not satisfied for " + toolName + ": " + currentStep.getCondition(),
                        matchedRules);
                }
                matchedRules.add("workflow." + workflowName + "." + toolName + ".condition=true");
            }
        }

        List<String> missing = dependencies.stream()
            .filter(value -> value != null && !value.isBlank())
            .filter(dependency -> !containsTool(completed, dependency))
            .distinct()
            .toList();
        if (!missing.isEmpty()) {
            return new WorkflowDecision(true, workflowName, stateKey, ToolRuntimeAction.DENY,
                "MCP workflow dependency not completed before " + toolName + ": " + missing,
                matchedRules);
        }

        ToolRuntimeAction action = currentStep == null
            ? null
            : workflowConfirmationAction(currentStep.getConfirmation(), executionPlan);
        if (action != null) {
            matchedRules.add("workflow." + workflowName + "." + toolName + ".confirmation=" + action.code());
        }
        return new WorkflowDecision(true, workflowName, stateKey, action,
            "MCP workflow resolved action " + (action == null ? "inherit_policy" : action.code()),
            matchedRules);
    }

    private ToolRuntimeAction workflowConfirmationAction(String confirmation, ToolExecutionPlan executionPlan) {
        if (confirmation == null || confirmation.isBlank()) {
            return null;
        }
        String value = normalizePolicyKey(confirmation);
        return switch (value) {
            case "none", "no", "false", "auto", "auto_execute" -> ToolRuntimeAction.AUTO_EXECUTE;
            case "required_always", "required", "always", "ask_before_execute", "confirm_required", "confirmation_required" ->
                ToolRuntimeAction.ASK_BEFORE_EXECUTE;
            case "required_for_write", "write" -> workflowRequiresWriteConfirmation(executionPlan)
                ? ToolRuntimeAction.ASK_BEFORE_EXECUTE
                : null;
            case "required_for_risky_command", "risky_command", "risky" -> workflowRequiresRiskyConfirmation(executionPlan)
                ? ToolRuntimeAction.ASK_BEFORE_EXECUTE
                : null;
            default -> ToolRuntimeAction.from(confirmation, null);
        };
    }

    private boolean workflowRequiresWriteConfirmation(ToolExecutionPlan executionPlan) {
        String operation = normalizePolicyKey(executionPlan == null ? null : executionPlan.operationType());
        return "write".equals(operation)
            || "send".equals(operation)
            || "delete".equals(operation)
            || "update".equals(operation)
            || "execute".equals(operation)
            || "permission_change".equals(operation);
    }

    private boolean workflowRequiresRiskyConfirmation(ToolExecutionPlan executionPlan) {
        String risk = normalizePolicyKey(executionPlan == null ? null : executionPlan.riskLevel());
        String operation = normalizePolicyKey(executionPlan == null ? null : executionPlan.operationType());
        return "medium".equals(risk)
            || "high".equals(risk)
            || "forbidden".equals(risk)
            || (!operation.isBlank() && !"read".equals(operation) && !"readonly".equals(operation) && !"read_only".equals(operation));
    }

    /**
     * Validates the workflow sequence.
     *
     * @param workflowName the workflow name value
     * @param workflow the workflow value
     * @param currentStep the current step value
     * @param toolName the tool name value
     * @param completed the completed value
     * @param strategy the strategy value
     * @param matchedRules the matched rules value
     * @param stateKey the state key value
     * @return the operation result
     */
    private WorkflowDecision validateWorkflowSequence(String workflowName,
                                                      McpWorkflowProperties.WorkflowSpec workflow,
                                                      McpWorkflowProperties.WorkflowStep currentStep,
                                                      String toolName,
                                                      Set<String> completed,
                                                      McpWorkflowProperties.ExecutionStrategy strategy,
                                                      List<String> matchedRules,
                                                      String stateKey) {
        if (workflow == null || workflow.getSteps() == null || currentStep == null) {
            return WorkflowDecision.allowed(workflowName, stateKey, matchedRules);
        }
        String mode = strategy == null ? "sequential" : firstText(strategy.getMode(), "sequential");
        boolean orderedStages = "sequential".equalsIgnoreCase(mode)
            || "hybrid".equalsIgnoreCase(mode)
            || !Boolean.TRUE.equals(strategy == null ? false : strategy.isAllowParallel());
        if (!orderedStages || parallelStep(workflow, toolName)) {
            return WorkflowDecision.allowed(workflowName, stateKey, matchedRules);
        }
        int currentOrder = stepOrder(currentStep);
        List<String> missingRequired = workflow.getSteps().stream()
            .filter(step -> step != null && !stepTools(step).isEmpty())
            .filter(McpWorkflowProperties.WorkflowStep::isRequired)
            .filter(step -> stepOrder(step) < currentOrder)
            .flatMap(step -> stepTools(step).stream())
            .filter(requiredTool -> !containsTool(completed, requiredTool))
            .distinct()
            .toList();
        if (!missingRequired.isEmpty()) {
            matchedRules.add("workflow." + workflowName + ".sequential=true");
            return new WorkflowDecision(true, workflowName, stateKey, ToolRuntimeAction.DENY,
                "MCP workflow required previous steps before " + toolName + ": " + missingRequired,
                matchedRules);
        }
        matchedRules.add("workflow." + workflowName + ".sequential=true");
        return WorkflowDecision.allowed(workflowName, stateKey, matchedRules);
    }

    /**
     * Performs the apply workflow decision operation.
     *
     * @param base the base value
     * @param workflowDecision the workflow decision value
     * @return the operation result
     */
    private ToolPolicyDecision applyWorkflowDecision(ToolPolicyDecision base, WorkflowDecision workflowDecision) {
        if (workflowDecision == null || !workflowDecision.applicable()) {
            return base;
        }
        List<String> matchedRules = new ArrayList<>(base.matchedRules());
        matchedRules.addAll(workflowDecision.matchedRules());
        ToolRuntimeAction action = base.action();
        String reason = base.reason();
        if (workflowDecision.action() == ToolRuntimeAction.DENY) {
            action = ToolRuntimeAction.DENY;
            reason = workflowDecision.reason();
        } else if (workflowDecision.action() == ToolRuntimeAction.ASK_BEFORE_EXECUTE
            && action != ToolRuntimeAction.DENY) {
            action = ToolRuntimeAction.ASK_BEFORE_EXECUTE;
            reason = workflowDecision.reason();
        } else if (workflowDecision.action() == ToolRuntimeAction.AUTO_EXECUTE
            && action != ToolRuntimeAction.DENY) {
            action = ToolRuntimeAction.AUTO_EXECUTE;
            reason = workflowDecision.reason();
        }
        return new ToolPolicyDecision(action, reason, base.runtimeLevel(), base.riskLevel(), base.operationType(),
            base.dataScope(), matchedRules, base.confirmationToken());
    }

    /**
     * Performs the remember workflow success operation.
     *
     * @param toolName the tool name value
     * @param request the request value
     * @param executionPlan the execution plan value
     * @param workflowDecision the workflow decision value
     */
    private void rememberWorkflowSuccess(String toolName,
                                         ToolRuntimeRequest request,
                                         ToolExecutionPlan executionPlan,
                                         WorkflowDecision workflowDecision) {
        rememberWorkflowAttempt(toolName, request, executionPlan, workflowDecision, true);
    }

    /**
     * Performs the remember workflow failure operation.
     *
     * @param toolName the tool name value
     * @param request the request value
     * @param executionPlan the execution plan value
     * @param workflowDecision the workflow decision value
     */
    private void rememberWorkflowFailure(String toolName,
                                         ToolRuntimeRequest request,
                                         ToolExecutionPlan executionPlan,
                                         WorkflowDecision workflowDecision) {
        rememberWorkflowAttempt(toolName, request, executionPlan, workflowDecision, false);
    }

    /**
     * Performs the remember workflow attempt operation.
     *
     * @param toolName the tool name value
     * @param request the request value
     * @param executionPlan the execution plan value
     * @param workflowDecision the workflow decision value
     * @param success the success value
     */
    private void rememberWorkflowAttempt(String toolName,
                                         ToolRuntimeRequest request,
                                         ToolExecutionPlan executionPlan,
                                         WorkflowDecision workflowDecision,
                                         boolean success) {
        if (workflowDecision == null || !workflowDecision.applicable()) {
            return;
        }
        String stateKey = firstText(workflowDecision.stateKey(), workflowStateKey(request,
            firstText(workflowDecision.workflowName(), executionPlan == null ? null : executionPlan.workflow())));
        WorkflowState state = workflowStates.computeIfAbsent(stateKey, ignored -> new WorkflowState());
        state.attemptedSteps.incrementAndGet();
        if (success) {
            state.completedTools.add(toolName);
            state.failed.set(false);
        } else {
            state.failed.set(true);
        }
    }

    /**
     * Returns whether is confirmed.
     *
     * @param request the request value
     * @param policyDecision the policy decision value
     * @return whether the condition is satisfied
     */
    private boolean isConfirmed(ToolRuntimeRequest request, ToolPolicyDecision policyDecision) {
        Map<String, Object> confirmation = confirmationFromRequest(request);
        if (confirmation.isEmpty()) {
            return false;
        }
        String decision = stringValue(firstPresent(confirmation.get("decision"), confirmation.get("action")));
        boolean approved = Boolean.TRUE.equals(confirmation.get("approved"))
            || "allow_once".equalsIgnoreCase(firstText(decision, ""))
            || "confirm_execute".equalsIgnoreCase(firstText(decision, ""))
            || "tool_auto_execute".equalsIgnoreCase(firstText(decision, ""));
        if (!approved) {
            return false;
        }
        String token = stringValue(confirmation.get("token"));
        return policyDecision != null && policyDecision.confirmationToken().equals(token);
    }

    /**
     * Performs the remember user tool policy operation.
     *
     * @param request the request value
     * @param policyDecision the policy decision value
     */
    private void rememberUserToolPolicy(ToolRuntimeRequest request, ToolPolicyDecision policyDecision) {
        Map<String, Object> confirmation = confirmationFromRequest(request);
        String remember = stringValue(confirmation.get("remember"));
        if (remember == null || remember.isBlank() || request == null || policyDecision == null) {
            return;
        }
        ToolRuntimeAction action = switch (remember.trim().toLowerCase(Locale.ROOT)) {
            case "tool_auto_execute", "auto_execute" -> ToolRuntimeAction.AUTO_EXECUTE;
            case "tool_deny", "deny" -> ToolRuntimeAction.DENY;
            case "tool_always_confirm", "ask_before_execute" -> ToolRuntimeAction.ASK_BEFORE_EXECUTE;
            default -> null;
        };
        if (action != null) {
            userPolicyStore.saveAction(
                normalizeText(request.getTenantId()),
                normalizeText(request.getUserId()),
                normalizeText(request.getToolName()),
                action
            );
        }
    }

    /**
     * Builds the confirmation payload.
     *
     * @param request the request value
     * @param metadata the metadata value
     * @param executionPlan the execution plan value
     * @param policyDecision the policy decision value
     * @return the built confirmation payload
     */
    private Map<String, Object> buildConfirmationPayload(ToolRuntimeRequest request,
                                                         ToolMetadata metadata,
                                                         ToolExecutionPlan executionPlan,
                                                         ToolPolicyDecision policyDecision) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("purpose", executionPlan == null ? null : executionPlan.reason());
        payload.put("toolName", request == null ? null : request.getToolName());
        payload.put("displayName", resolveDisplayName(request == null ? null : request.getToolName(), metadata));
        payload.put("runtimeLevel", policyDecision.runtimeLevel());
        payload.put("riskLevel", policyDecision.riskLevel());
        payload.put("parameters", executionPlan == null ? Map.of() : executionPlan.parameters());
        payload.put("dataScope", policyDecision.dataScope());
        payload.put("operationType", policyDecision.operationType());
        payload.put("token", policyDecision.confirmationToken());
        payload.put("choices", List.of(
            "allow_once",
            "similar_auto_execute",
            "tool_auto_execute",
            "tool_always_confirm",
            "tool_deny"
        ));
        return payload;
    }

    /**
     * Performs the process result data operation.
     *
     * @param data the data value
     * @param metadata the metadata value
     * @return the operation result
     */
    private Object processResultData(Object data, ToolMetadata metadata) {
        Set<String> fields = new HashSet<>();
        if (isMcpGovernedTool(metadata == null ? null : metadata.getId(), metadata)) {
            fields.addAll(List.of("phone", "id_card", "account_no"));
        }
        Map<String, Object> outputPolicy = metadata == null ? null : metadata.getOutputPolicy();
        Object configured = outputPolicy == null ? null : firstPresent(outputPolicy.get("mask_fields"), outputPolicy.get("maskFields"));
        if (configured instanceof List<?> list) {
            list.stream()
                .map(this::stringValue)
                .filter(value -> value != null && !value.isBlank())
                .map(value -> value.trim().toLowerCase(Locale.ROOT))
                .forEach(fields::add);
        }
        if (fields.isEmpty()) {
            return data;
        }
        return maskValue(data, fields);
    }

    /**
     * Performs the mask value operation.
     *
     * @param value the value value
     * @param fields the fields value
     * @return the operation result
     */
    @SuppressWarnings("unchecked")
    private Object maskValue(Object value, Set<String> fields) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> masked = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                if (fields.contains(key.toLowerCase(Locale.ROOT))) {
                    masked.put(key, "******");
                } else {
                    masked.put(key, maskValue(entry.getValue(), fields));
                }
            }
            return masked;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(item -> maskValue(item, fields)).toList();
        }
        return value;
    }

    /**
     * Converts the value to ol policy keys.
     *
     * @param toolName the tool name value
     * @param metadata the metadata value
     * @return the converted ol policy keys
     */
    private List<String> toolPolicyKeys(String toolName, ToolMetadata metadata) {
        List<String> keys = new ArrayList<>();
        if (toolName != null && !toolName.isBlank()) {
            keys.add(toolName);
            String semanticKey = normalizeToolSemanticKey(toolName);
            if (!semanticKey.isBlank()) {
                keys.add(semanticKey);
            }
        }
        if (metadata != null && metadata.getId() != null && !metadata.getId().isBlank()) {
            keys.add(metadata.getId());
        }
        if (metadata != null && metadata.getMetadata() != null) {
            Object remoteToolName = metadata.getMetadata().get("remoteToolName");
            if (remoteToolName != null && !String.valueOf(remoteToolName).isBlank()) {
                keys.add(String.valueOf(remoteToolName));
            }
        }
        return keys.stream().distinct().toList();
    }

    /**
     * Resolves the workflow name.
     *
     * @param toolName the tool name value
     * @param request the request value
     * @param executionPlan the execution plan value
     * @return the resolved workflow name
     */
    private String resolveWorkflowName(String toolName, ToolRuntimeRequest request, ToolExecutionPlan executionPlan) {
        String explicit = firstText(
            executionPlan == null ? null : executionPlan.workflow(),
            stringValue(request == null || request.getAttributes() == null ? null : firstPresent(
                request.getAttributes().get("workflow"),
                request.getAttributes().get("workflowId"),
                request.getAttributes().get("workflow_id")
            ))
        );
        if (explicit != null && mcpWorkflowProperties.getWorkflows().containsKey(explicit)) {
            return explicit;
        }
        if (mcpWorkflowProperties.getWorkflows() == null || mcpWorkflowProperties.getWorkflows().isEmpty()) {
            return null;
        }
        for (Map.Entry<String, McpWorkflowProperties.WorkflowSpec> entry : mcpWorkflowProperties.getWorkflows().entrySet()) {
            McpWorkflowProperties.WorkflowSpec workflow = entry.getValue();
            if (workflow == null || workflow.getSteps() == null) {
                continue;
            }
            boolean matched = workflow.getSteps().stream()
                .anyMatch(step -> step != null && sameTool(step.getTool(), toolName));
            if (matched) {
                return entry.getKey();
            }
        }
        return null;
    }

    /**
     * Performs the agent workflow config operation.
     *
     * @param request the request value
     * @return the operation result
     */
    private Map<String, Object> agentWorkflowConfig(ToolRuntimeRequest request) {
        Object rawWorkflow = request == null || request.getAttributes() == null
            ? null
            : request.getAttributes().get("mcpWorkflow");
        Map<String, Object> workflow = workflowConfigMap(rawWorkflow);
        if (workflow.isEmpty()) {
            return Map.of();
        }
        Object enabled = workflow.get("enabled");
        if (enabled instanceof Boolean bool && !bool) {
            return Map.of();
        }
        return workflow;
    }

    private Map<String, Object> workflowConfigMap(Object rawWorkflow) {
        if (rawWorkflow instanceof List<?> list) {
            Map<String, Object> workflow = new LinkedHashMap<>();
            workflow.put("enabled", true);
            workflow.put("steps", list);
            return workflow;
        }
        return asMap(rawWorkflow);
    }

    /**
     * Performs the agent workflow name operation.
     *
     * @param config the config value
     * @param executionPlan the execution plan value
     * @return the operation result
     */
    private String agentWorkflowName(Map<String, Object> config, ToolExecutionPlan executionPlan) {
        return firstText(
            executionPlan == null ? null : executionPlan.workflow(),
            stringValue(firstPresent(
                config.get("workflow"),
                config.get("workflowId"),
                config.get("workflow_id"),
                config.get("id"),
                config.get("name")
            ))
        );
    }

    /**
     * Performs the workflow from agent config operation.
     *
     * @param config the config value
     * @param toolName the tool name value
     * @param executionPlan the execution plan value
     * @return the operation result
     */
    private McpWorkflowProperties.WorkflowSpec workflowFromAgentConfig(Map<String, Object> config,
                                                                       String toolName,
                                                                       ToolExecutionPlan executionPlan) {
        if (config == null || config.isEmpty()) {
            return null;
        }
        McpWorkflowProperties.WorkflowSpec workflow = new McpWorkflowProperties.WorkflowSpec();
        workflow.setExecutionStrategy(executionStrategyFromMap(asMap(firstPresent(
            config.get("executionStrategy"),
            config.get("execution_strategy")
        ))));
        workflow.setParallelSteps(stringList(firstPresent(config.get("parallelSteps"), config.get("parallel_steps"))));
        workflow.setSteps(workflowStepsFromList(config.get("steps")));
        String explicitName = agentWorkflowName(config, executionPlan);
        boolean explicitMatched = explicitName != null && sameTool(explicitName, executionPlan == null ? null : executionPlan.workflow());
        boolean toolMatched = workflow.getSteps() != null
            && workflow.getSteps().stream().anyMatch(step -> stepContainsTool(step, toolName));
        if (!toolMatched && !explicitMatched) {
            return null;
        }
        return workflow;
    }

    /**
     * Performs the execution strategy from map operation.
     *
     * @param values the values value
     * @return the operation result
     */
    private McpWorkflowProperties.ExecutionStrategy executionStrategyFromMap(Map<String, Object> values) {
        McpWorkflowProperties.ExecutionStrategy strategy = new McpWorkflowProperties.ExecutionStrategy();
        if (values == null || values.isEmpty()) {
            return strategy;
        }
        String mode = stringValue(values.get("mode"));
        if (mode != null && !mode.isBlank()) {
            strategy.setMode(mode.trim());
        }
        Boolean stopOnError = booleanValue(firstPresent(values.get("stopOnError"), values.get("stop_on_error")));
        if (stopOnError != null) {
            strategy.setStopOnError(stopOnError);
        }
        Integer maxSteps = integerValue(firstPresent(values.get("maxSteps"), values.get("max_steps")));
        if (maxSteps != null) {
            strategy.setMaxSteps(Math.max(0, maxSteps));
        }
        Boolean allowParallel = booleanValue(firstPresent(values.get("allowParallel"), values.get("allow_parallel")));
        if (allowParallel != null) {
            strategy.setAllowParallel(allowParallel);
        }
        return strategy;
    }

    /**
     * Performs the workflow steps from list operation.
     *
     * @param value the value value
     * @return the operation result
     */
    private List<McpWorkflowProperties.WorkflowStep> workflowStepsFromList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<McpWorkflowProperties.WorkflowStep> steps = new ArrayList<>();
        Map<String, String> stepKeyToTool = new LinkedHashMap<>();
        int index = 1;
        for (Object item : list) {
            Map<String, Object> rawStep = asMap(item);
            String tool = stringValue(firstPresent(rawStep.get("tool"), rawStep.get("toolName")));
            List<String> parallelSteps = stringList(firstPresent(rawStep.get("parallelSteps"), rawStep.get("parallel_steps")));
            if ((tool == null || tool.isBlank()) && parallelSteps.isEmpty()) {
                continue;
            }
            McpWorkflowProperties.WorkflowStep step = new McpWorkflowProperties.WorkflowStep();
            Object stepValue = firstPresent(rawStep.get("step"), rawStep.get("order"));
            String stepText = stringValue(stepValue);
            step.setName(firstText(stringValue(rawStep.get("name")),
                stepText != null && integerValue(stepText) == null ? stepText : null));
            step.setStep(firstInteger(firstPresent(rawStep.get("step"), rawStep.get("order")), index));
            step.setTool(tool == null || tool.isBlank() ? null : tool.trim());
            step.setParallelSteps(parallelSteps);
            Boolean required = booleanValue(rawStep.get("required"));
            step.setRequired(required == null || required);
            step.setCondition(stringValue(rawStep.get("condition")));
            step.setConfirmation(stringValue(rawStep.get("confirmation")));
            step.setDependsOn(stringList(firstPresent(rawStep.get("dependsOn"), rawStep.get("depends_on"))));
            steps.add(step);
            for (String key : workflowStepKeys(step, stepText)) {
                stepKeyToTool.putIfAbsent(normalizePolicyKey(key), step.getTool());
            }
            index++;
        }
        for (McpWorkflowProperties.WorkflowStep step : steps) {
            step.setDependsOn(step.getDependsOn() == null ? List.of() : step.getDependsOn().stream()
                .map(dependency -> firstText(stepKeyToTool.get(normalizePolicyKey(dependency)), dependency))
                .filter(dependency -> dependency != null && !dependency.isBlank())
                .distinct()
                .toList());
        }
        return steps;
    }

    private List<String> workflowStepKeys(McpWorkflowProperties.WorkflowStep step, String rawStepValue) {
        if (step == null) {
            return List.of();
        }
        List<String> keys = new ArrayList<>();
        if (step.getName() != null && !step.getName().isBlank()) {
            keys.add(step.getName());
        }
        if (rawStepValue != null && !rawStepValue.isBlank()) {
            keys.add(rawStepValue);
        }
        if (step.getStep() != null) {
            keys.add(String.valueOf(step.getStep()));
        }
        if (step.getTool() != null && !step.getTool().isBlank()) {
            keys.add(step.getTool());
        }
        return keys;
    }

    /**
     * Performs the dependency from agent config operation.
     *
     * @param config the config value
     * @param toolName the tool name value
     * @return the operation result
     */
    private McpWorkflowProperties.ToolDependencySpec dependencyFromAgentConfig(Map<String, Object> config, String toolName) {
        if (config == null || config.isEmpty() || toolName == null) {
            return null;
        }
        Map<String, Object> dependencies = asMap(firstPresent(config.get("toolDependencies"), config.get("tool_dependencies")));
        if (dependencies.isEmpty()) {
            return null;
        }
        String normalized = normalizePolicyKey(toolName);
        for (Map.Entry<String, Object> entry : dependencies.entrySet()) {
            if (!sameTool(entry.getKey(), toolName) && !normalizePolicyKey(entry.getKey()).equals(normalized)) {
                continue;
            }
            List<String> dependsOn = entry.getValue() instanceof Map<?, ?> dependencyMap
                ? stringList(firstPresent(dependencyMap.get("dependsOn"), dependencyMap.get("depends_on")))
                : stringList(entry.getValue());
            if (dependsOn.isEmpty()) {
                return null;
            }
            McpWorkflowProperties.ToolDependencySpec spec = new McpWorkflowProperties.ToolDependencySpec();
            spec.setDependsOn(dependsOn);
            return spec;
        }
        return null;
    }

    /**
     * Performs the first dependency operation.
     *
     * @param first the first value
     * @param second the second value
     * @return the operation result
     */
    private McpWorkflowProperties.ToolDependencySpec firstDependency(McpWorkflowProperties.ToolDependencySpec first,
                                                                     McpWorkflowProperties.ToolDependencySpec second) {
        if (first != null && first.getDependsOn() != null && !first.getDependsOn().isEmpty()) {
            return first;
        }
        return second;
    }

    /**
     * Performs the dependency for tool operation.
     *
     * @param toolName the tool name value
     * @return the operation result
     */
    private McpWorkflowProperties.ToolDependencySpec dependencyForTool(String toolName) {
        if (mcpWorkflowProperties == null || mcpWorkflowProperties.getToolDependencies() == null || toolName == null) {
            return null;
        }
        for (Map.Entry<String, McpWorkflowProperties.ToolDependencySpec> entry : mcpWorkflowProperties.getToolDependencies().entrySet()) {
            if (sameTool(entry.getKey(), toolName)) {
                return entry.getValue();
            }
        }
        return null;
    }

    /**
     * Performs the workflow step operation.
     *
     * @param workflow the workflow value
     * @param toolName the tool name value
     * @return the operation result
     */
    private McpWorkflowProperties.WorkflowStep workflowStep(McpWorkflowProperties.WorkflowSpec workflow, String toolName) {
        if (workflow == null || workflow.getSteps() == null) {
            return null;
        }
        return workflow.getSteps().stream()
            .filter(step -> stepContainsTool(step, toolName))
            .findFirst()
            .orElse(null);
    }

    /**
     * Returns whether parallel step.
     *
     * @param workflow the workflow value
     * @param toolName the tool name value
     * @return whether the condition is satisfied
     */
    private boolean parallelStep(McpWorkflowProperties.WorkflowSpec workflow, String toolName) {
        return workflow != null
            && workflow.getParallelSteps() != null
            && workflow.getParallelSteps().stream().anyMatch(stepTool -> sameTool(stepTool, toolName));
    }

    /**
     * Performs the step order operation.
     *
     * @param step the step value
     * @return the operation result
     */
    private int stepOrder(McpWorkflowProperties.WorkflowStep step) {
        return step == null || step.getStep() == null ? Integer.MAX_VALUE : step.getStep();
    }

    /**
     * Returns whether step contains tool.
     *
     * @param step the step value
     * @param toolName the tool name value
     * @return whether the condition is satisfied
     */
    private boolean stepContainsTool(McpWorkflowProperties.WorkflowStep step, String toolName) {
        return step != null
            && (sameTool(step.getTool(), toolName)
            || (step.getParallelSteps() != null && step.getParallelSteps().stream().anyMatch(candidate -> sameTool(candidate, toolName))));
    }

    /**
     * Performs the step tools operation.
     *
     * @param step the step value
     * @return the operation result
     */
    private List<String> stepTools(McpWorkflowProperties.WorkflowStep step) {
        if (step == null) {
            return List.of();
        }
        List<String> tools = new ArrayList<>();
        if (step.getTool() != null && !step.getTool().isBlank()) {
            tools.add(step.getTool());
        }
        if (step.getParallelSteps() != null) {
            step.getParallelSteps().stream()
                .filter(tool -> tool != null && !tool.isBlank())
                .forEach(tools::add);
        }
        return tools.stream().distinct().toList();
    }

    /**
     * Returns whether same tool.
     *
     * @param configuredTool the configured tool value
     * @param actualTool the actual tool value
     * @return whether the condition is satisfied
     */
    private boolean sameTool(String configuredTool, String actualTool) {
        return normalizeToolSemanticKey(configuredTool).equals(normalizeToolSemanticKey(actualTool));
    }

    /**
     * Returns whether contains tool.
     *
     * @param tools the tools value
     * @param expectedTool the expected tool value
     * @return whether the condition is satisfied
     */
    private boolean containsTool(Set<String> tools, String expectedTool) {
        if (tools == null || expectedTool == null) {
            return false;
        }
        return tools.stream().anyMatch(tool -> sameTool(tool, expectedTool));
    }

    /**
     * Normalizes the tool semantic key.
     *
     * @param toolName the tool name value
     * @return the operation result
     */
    private String normalizeToolSemanticKey(String toolName) {
        String normalized = normalizePolicyKey(toolName);
        if (normalized.contains("document_search")) {
            return "document_search";
        }
        if (normalized.contains("search_and_extract")) {
            return "search_and_extract";
        }
        if (normalized.contains("web_search")) {
            return "web_search";
        }
        return normalized;
    }

    /**
     * Performs the completed tools operation.
     *
     * @param request the request value
     * @param state the state value
     * @return the operation result
     */
    private Set<String> completedTools(ToolRuntimeRequest request, WorkflowState state) {
        Set<String> completed = new HashSet<>(state == null ? Set.of() : state.completedTools);
        Object configured = request == null || request.getAttributes() == null
            ? null
            : firstPresent(request.getAttributes().get("workflowCompletedTools"), request.getAttributes().get("completedTools"));
        if (configured instanceof List<?> list) {
            list.stream().map(this::stringValue).filter(value -> value != null && !value.isBlank()).forEach(completed::add);
        } else if (configured instanceof String text && !text.isBlank()) {
            for (String item : text.split("[,;]")) {
                if (!item.isBlank()) {
                    completed.add(item.trim());
                }
            }
        }
        return completed;
    }

    /**
     * Performs the workflow state key operation.
     *
     * @param request the request value
     * @param workflowName the workflow name value
     * @return the operation result
     */
    private String workflowStateKey(ToolRuntimeRequest request, String workflowName) {
        String tenant = normalizeText(request == null ? null : request.getTenantId());
        String user = normalizeText(request == null ? null : request.getUserId());
        String conversation = normalizeText(request == null ? null : request.getConversationId());
        String scope = firstText(workflowRunScope(request), firstText(conversation, "adhoc"));
        return firstText(tenant, "default") + "::" + firstText(user, "anonymous")
            + "::" + scope + "::" + firstText(workflowName, "global");
    }

    private String workflowRunScope(ToolRuntimeRequest request) {
        if (request == null) {
            return null;
        }
        Map<String, Object> attributes = request.getAttributes();
        Object attributeScope = firstPresent(
            attributes == null ? null : attributes.get("agentRunId"),
            attributes == null ? null : attributes.get("__agentRunId"),
            attributes == null ? null : attributes.get("taskId"),
            attributes == null ? null : attributes.get("agentTaskId"),
            attributes == null ? null : attributes.get("__agentTaskId"),
            attributes == null ? null : attributes.get("runId"),
            attributes == null ? null : attributes.get("workflowRunId")
        );
        String scoped = normalizeText(stringValue(attributeScope));
        if (scoped != null) {
            return scoped;
        }
        Map<String, Object> parameters = request.getToolInput() == null ? null : request.getToolInput().getParameters();
        Object parameterScope = firstPresent(
            parameters == null ? null : parameters.get("__agentRunId"),
            parameters == null ? null : parameters.get("agentRunId"),
            parameters == null ? null : parameters.get("__agentTaskId"),
            parameters == null ? null : parameters.get("agentTaskId"),
            parameters == null ? null : parameters.get("taskId"),
            parameters == null ? null : parameters.get("runId"),
            parameters == null ? null : parameters.get("workflowRunId")
        );
        scoped = normalizeText(stringValue(parameterScope));
        if (scoped != null) {
            return scoped;
        }
        return normalizeText(request.getRequestId());
    }

    /**
     * Performs the workflow context operation.
     *
     * @param request the request value
     * @param toolInput the tool input value
     * @return the operation result
     */
    private Map<String, Object> workflowContext(ToolRuntimeRequest request, ToolInput toolInput) {
        Map<String, Object> context = new LinkedHashMap<>();
        if (request != null && request.getAttributes() != null) {
            context.putAll(asMap(firstPresent(request.getAttributes().get("workflowContext"), request.getAttributes().get("workflowVariables"))));
        }
        if (toolInput != null && toolInput.getParameters() != null) {
            context.putAll(toolInput.getParameters());
        }
        return context;
    }

    /**
     * Returns whether condition matches.
     *
     * @param condition the condition value
     * @param context the context value
     * @return whether the condition is satisfied
     */
    private boolean conditionMatches(String condition, Map<String, Object> context) {
        if (condition == null || condition.isBlank()) {
            return true;
        }
        String expression = condition.trim();
        String[] operators = {">=", "<=", "==", "!=", ">", "<"};
        for (String operator : operators) {
            int index = expression.indexOf(operator);
            if (index <= 0) {
                continue;
            }
            String left = expression.substring(0, index).trim();
            String right = expression.substring(index + operator.length()).trim();
            Object leftValue = context == null ? null : context.get(left);
            if (leftValue == null) {
                leftValue = context == null ? null : context.get(normalizePolicyKey(left));
            }
            return compareCondition(leftValue, operator, right);
        }
        Object value = context == null ? null : context.get(expression);
        return truthy(value);
    }

    /**
     * Returns whether compare condition.
     *
     * @param leftValue the left value value
     * @param operator the operator value
     * @param rightText the right text value
     * @return whether the condition is satisfied
     */
    private boolean compareCondition(Object leftValue, String operator, String rightText) {
        if (leftValue == null) {
            return false;
        }
        Double leftNumber = numberValue(leftValue);
        Double rightNumber = numberValue(unquote(rightText));
        if (leftNumber != null && rightNumber != null) {
            return switch (operator) {
                case ">=" -> leftNumber >= rightNumber;
                case "<=" -> leftNumber <= rightNumber;
                case ">" -> leftNumber > rightNumber;
                case "<" -> leftNumber < rightNumber;
                case "==" -> Double.compare(leftNumber, rightNumber) == 0;
                case "!=" -> Double.compare(leftNumber, rightNumber) != 0;
                default -> false;
            };
        }
        String left = String.valueOf(leftValue);
        String right = unquote(rightText);
        return switch (operator) {
            case "==" -> left.equalsIgnoreCase(right);
            case "!=" -> !left.equalsIgnoreCase(right);
            default -> false;
        };
    }

    /**
     * Performs the number value operation.
     *
     * @param value the value value
     * @return the operation result
     */
    private Double numberValue(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return value == null ? null : Double.parseDouble(String.valueOf(value).trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    /**
     * Performs the unquote operation.
     *
     * @param value the value value
     * @return the operation result
     */
    private String unquote(String value) {
        if (value == null) {
            return "";
        }
        String text = value.trim();
        if ((text.startsWith("\"") && text.endsWith("\"")) || (text.startsWith("'") && text.endsWith("'"))) {
            return text.substring(1, text.length() - 1);
        }
        return text;
    }

    /**
     * Performs the default action for risk operation.
     *
     * @param riskLevel the risk level value
     * @return the operation result
     */
    private ToolRuntimeAction defaultActionForRisk(String riskLevel) {
        return switch (normalizePolicyKey(riskLevel)) {
            case "forbidden" -> ToolRuntimeAction.DENY;
            case "medium", "high" -> ToolRuntimeAction.ASK_BEFORE_EXECUTE;
            default -> ToolRuntimeAction.AUTO_EXECUTE;
        };
    }

    /**
     * Performs the confirmation from request operation.
     *
     * @param request the request value
     * @return the operation result
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> confirmationFromRequest(ToolRuntimeRequest request) {
        if (request == null) {
            return Map.of();
        }
        Object value = request.getAttributes() == null ? null : request.getAttributes().get("mcpConfirmation");
        if (!(value instanceof Map<?, ?>) && request.getToolInput() != null && request.getToolInput().getContext() != null) {
            value = request.getToolInput().getContext().get("mcpConfirmation");
        }
        if (!(value instanceof Map<?, ?> map)) {
            return Map.of();
        }
        return (Map<String, Object>) map;
    }

    /**
     * Performs the confirmation token operation.
     *
     * @param request the request value
     * @param executionPlan the execution plan value
     * @return the operation result
     */
    private String confirmationToken(ToolRuntimeRequest request, ToolExecutionPlan executionPlan) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("tenantId", request == null ? null : request.getTenantId());
        values.put("userId", request == null ? null : request.getUserId());
        values.put("conversationId", request == null ? null : request.getConversationId());
        values.put("toolName", request == null ? null : request.getToolName());
        values.put("plan", executionPlan == null ? null : executionPlan.toMap());
        return sha256(stringify(values)).substring(0, 32);
    }

    /**
     * Performs the sha256 operation.
     *
     * @param value the value value
     * @return the operation result
     */
    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : bytes) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception ex) {
            return Integer.toHexString(String.valueOf(value).hashCode());
        }
    }

    /**
     * Performs the as map operation.
     *
     * @param value the value value
     * @return the operation result
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return Map.of();
        }
        return (Map<String, Object>) map;
    }

    /**
     * Performs the string list operation.
     *
     * @param value the value value
     * @return the operation result
     */
    private List<String> stringList(Object value) {
        if (value instanceof List<?> list) {
            return list.stream()
                .map(this::stringValue)
                .filter(text -> text != null && !text.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
        }
        if (value instanceof String text && !text.isBlank()) {
            List<String> values = new ArrayList<>();
            for (String item : text.split("[,;\\n]")) {
                if (!item.isBlank()) {
                    values.add(item.trim());
                }
            }
            return values.stream().distinct().toList();
        }
        return List.of();
    }

    /**
     * Returns whether boolean value.
     *
     * @param value the value value
     * @return whether the condition is satisfied
     */
    private Boolean booleanValue(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        if ("true".equalsIgnoreCase(text) || "yes".equalsIgnoreCase(text) || "1".equals(text)) {
            return true;
        }
        if ("false".equalsIgnoreCase(text) || "no".equalsIgnoreCase(text) || "0".equals(text)) {
            return false;
        }
        return null;
    }

    /**
     * Performs the integer value operation.
     *
     * @param value the value value
     * @return the operation result
     */
    private Integer integerValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return value == null ? null : Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    /**
     * Performs the first integer operation.
     *
     * @param value the value value
     * @param fallback the fallback value
     * @return the operation result
     */
    private int firstInteger(Object value, int fallback) {
        Integer parsed = integerValue(value);
        return parsed == null ? fallback : parsed;
    }

    /**
     * Performs the value for key operation.
     *
     * @param values the values value
     * @param key the key value
     * @return the operation result
     */
    private String valueForKey(Map<String, String> values, String key) {
        if (values == null || key == null) {
            return null;
        }
        String normalizedKey = normalizePolicyKey(key);
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (normalizePolicyKey(entry.getKey()).equals(normalizedKey)) {
                return entry.getValue();
            }
        }
        return null;
    }

    /**
     * Performs the value for nested key operation.
     *
     * @param values the values value
     * @param key the key value
     * @return the operation result
     */
    private Map<String, String> valueForNestedKey(Map<String, Map<String, String>> values, String key) {
        if (values == null || key == null) {
            return null;
        }
        String normalizedKey = normalizePolicyKey(key);
        for (Map.Entry<String, Map<String, String>> entry : values.entrySet()) {
            if (normalizePolicyKey(entry.getKey()).equals(normalizedKey)) {
                return entry.getValue();
            }
        }
        return null;
    }

    /**
     * Normalizes the policy key.
     *
     * @param value the value value
     * @return the operation result
     */
    private String normalizePolicyKey(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.trim().toLowerCase(Locale.ROOT).replace('-', '_');
    }

    /**
     * Performs the first present operation.
     *
     * @param values the values value
     * @return the operation result
     */
    private Object firstPresent(Object... values) {
        if (values == null) {
            return null;
        }
        for (Object value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    /**
     * Performs the recipient count operation.
     *
     * @param parameters the parameters value
     * @return the operation result
     */
    private int recipientCount(Map<String, Object> parameters) {
        Object count = firstPresent(parameters.get("recipient_count"), parameters.get("recipientCount"), parameters.get("count"));
        if (count instanceof Number number) {
            return number.intValue();
        }
        Object recipients = firstPresent(parameters.get("recipients"), parameters.get("to"));
        if (recipients instanceof List<?> list) {
            return list.size();
        }
        if (recipients instanceof String text && !text.isBlank()) {
            return text.split("[,;]").length;
        }
        try {
            return count == null ? 0 : Integer.parseInt(String.valueOf(count));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    /**
     * Returns whether has external domain.
     *
     * @param parameters the parameters value
     * @return whether the condition is satisfied
     */
    private boolean hasExternalDomain(Map<String, Object> parameters) {
        List<String> recipients = new ArrayList<>();
        Object value = firstPresent(parameters.get("recipients"), parameters.get("recipient"), parameters.get("to"), parameters.get("email"));
        if (value instanceof List<?> list) {
            list.forEach(item -> recipients.add(String.valueOf(item)));
        } else if (value != null) {
            for (String item : String.valueOf(value).split("[,;]")) {
                recipients.add(item);
            }
        }
        return recipients.stream()
            .map(String::trim)
            .filter(text -> text.contains("@"))
            .map(text -> text.substring(text.indexOf('@') + 1).toLowerCase(Locale.ROOT))
            .anyMatch(domain -> !domain.endsWith(".local") && !domain.endsWith(".internal") && !domain.contains("chatchat"));
    }

    /**
     * Returns whether contains word.
     *
     * @param parameters the parameters value
     * @param word the word value
     * @return whether the condition is satisfied
     */
    private boolean containsWord(Map<String, Object> parameters, String word) {
        String text = String.join(" ",
            stringValue(parameters.get("sql")),
            stringValue(parameters.get("query")),
            stringValue(parameters.get("input")),
            stringValue(parameters.get("statement"))
        ).toLowerCase(Locale.ROOT);
        return text.matches(".*\\b" + word.toLowerCase(Locale.ROOT) + "\\b.*");
    }

    /**
     * Returns whether is customer detail.
     *
     * @param parameters the parameters value
     * @return whether the condition is satisfied
     */
    private boolean isCustomerDetail(Map<String, Object> parameters) {
        if (truthy(parameters.get("customer_detail")) || truthy(parameters.get("customerDetail"))) {
            return true;
        }
        if (parameters.containsKey("customer_id") || parameters.containsKey("customerId")) {
            return true;
        }
        String scope = String.join(" ",
            stringValue(parameters.get("scope")),
            stringValue(parameters.get("data_scope")),
            stringValue(parameters.get("dataScope")),
            stringValue(parameters.get("level"))
        ).toLowerCase(Locale.ROOT);
        return scope.contains("customer") || scope.contains("detail");
    }

    /**
     * Returns whether is branch summary.
     *
     * @param parameters the parameters value
     * @return whether the condition is satisfied
     */
    private boolean isBranchSummary(Map<String, Object> parameters) {
        String scope = String.join(" ",
            stringValue(parameters.get("scope")),
            stringValue(parameters.get("data_scope")),
            stringValue(parameters.get("dataScope")),
            stringValue(parameters.get("level"))
        ).toLowerCase(Locale.ROOT);
        return (scope.contains("branch") || parameters.containsKey("branch_id") || parameters.containsKey("branchId"))
            && !isCustomerDetail(parameters);
    }

    /**
     * Performs the infer data scope operation.
     *
     * @param parameters the parameters value
     * @return the operation result
     */
    private String inferDataScope(Map<String, Object> parameters) {
        if (parameters == null || parameters.isEmpty()) {
            return "unknown";
        }
        if (isCustomerDetail(parameters)) {
            return "customer_detail";
        }
        if (isBranchSummary(parameters)) {
            return "branch_summary";
        }
        String scope = firstText(stringValue(parameters.get("data_scope")), stringValue(parameters.get("scope")));
        return firstText(scope, "unknown");
    }

    /**
     * Returns whether truthy.
     *
     * @param value the value value
     * @return whether the condition is satisfied
     */
    private boolean truthy(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof Number number) {
            return number.intValue() != 0;
        }
        if (value == null) {
            return false;
        }
        String text = String.valueOf(value).trim();
        return "true".equalsIgnoreCase(text) || "yes".equalsIgnoreCase(text) || "1".equals(text);
    }

    /**
     * Performs the threshold operation.
     *
     * @param policy the policy value
     * @return the operation result
     */
    private int threshold(ToolRuntimePolicy policy) {
        if (policy != null && policy.circuitBreakerFailureThreshold() != null) {
            return Math.max(1, policy.circuitBreakerFailureThreshold());
        }
        return Math.max(1, properties.getCircuitBreakerFailureThreshold());
    }

    /**
     * Opens the seconds.
     *
     * @param policy the policy value
     * @return the operation result
     */
    private int openSeconds(ToolRuntimePolicy policy) {
        if (policy != null && policy.circuitBreakerOpenSeconds() != null) {
            return Math.max(1, policy.circuitBreakerOpenSeconds());
        }
        return Math.max(1, properties.getCircuitBreakerOpenSeconds());
    }

    /**
     * Publishes the audit record.
     *
     * @param request the request value
     * @param metadata the metadata value
     * @param output the output value
     * @param trace the trace value
     * @param outcome the outcome value
     * @param errorCode the error code value
     * @param durationMs the duration ms value
     * @param runtimeMetadata the runtime metadata value
     */
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

    /**
     * Performs the log audit operation.
     *
     * @param toolName the tool name value
     * @param request the request value
     * @param outcome the outcome value
     * @param durationMs the duration ms value
     * @param errorMessage the error message value
     */
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

    /**
     * Performs the stringify operation.
     *
     * @param data the data value
     * @return the operation result
     */
    private String stringify(Object data) {
        if (data == null) {
            return null;
        }
        if (data instanceof String text) {
            return text;
        }
        return ModelProtocolJson.compact(data);
    }

    /**
     * Performs the string value operation.
     *
     * @param value the value value
     * @return the operation result
     */
    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    /**
     * Resolves the display name.
     *
     * @param toolName the tool name value
     * @param metadata the metadata value
     * @return the resolved display name
     */
    private String resolveDisplayName(String toolName, ToolMetadata metadata) {
        if (metadata != null && metadata.getTitle() != null && !metadata.getTitle().isBlank()) {
            return metadata.getTitle().trim();
        }
        return toolName;
    }

    /**
     * Resolves the service id.
     *
     * @param metadata the metadata value
     * @return the resolved service id
     */
    private String resolveServiceId(ToolMetadata metadata) {
        if (metadata == null || metadata.getMetadata() == null) {
            return null;
        }
        Object value = metadata.getMetadata().get("serviceId");
        return value == null ? null : String.valueOf(value);
    }

    /**
     * Resolves the service name.
     *
     * @param metadata the metadata value
     * @return the resolved service name
     */
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

    /**
     * Normalizes the mode.
     *
     * @param request the request value
     * @return the operation result
     */
    private String normalizeMode(ToolRuntimeRequest request) {
        String value = request == null ? null : request.getRuntimeMode();
        return value == null || value.isBlank() ? "tool_runtime" : value.trim();
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

    /**
     * Performs the first text operation.
     *
     * @param first the first value
     * @param second the second value
     * @return the operation result
     */
    private String firstText(String first, String second) {
        return first == null || first.isBlank() ? second : first;
    }

    private record ToolPolicyDecision(
        ToolRuntimeAction action,
        String reason,
        String runtimeLevel,
        String riskLevel,
        String operationType,
        String dataScope,
        List<String> matchedRules,
        String confirmationToken
    ) {
    }

    private record ParameterDecision(
        ToolRuntimeAction action,
        List<String> matchedRules
    ) {
    }

    private record WorkflowDecision(
        boolean applicable,
        String workflowName,
        String stateKey,
        ToolRuntimeAction action,
        String reason,
        List<String> matchedRules
    ) {
        /**
         * Performs the not applicable operation.
         *
         * @return the operation result
         */
        private static WorkflowDecision notApplicable() {
            return new WorkflowDecision(false, null, null, null, null, List.of());
        }

        /**
         * Performs the allowed operation.
         *
         * @param workflowName the workflow name value
         * @param stateKey the state key value
         * @param matchedRules the matched rules value
         * @return the operation result
         */
        private static WorkflowDecision allowed(String workflowName, String stateKey, List<String> matchedRules) {
            return new WorkflowDecision(true, workflowName, stateKey, null,
                "MCP workflow allows tool execution", new ArrayList<>(matchedRules));
        }
    }

    private static final class ToolRuntimeThreadFactory implements ThreadFactory {
        private final AtomicInteger sequence = new AtomicInteger(1);

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "tool-runtime-exec-" + sequence.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        }
    }

    private static final class CircuitState {
        private final AtomicInteger consecutiveFailures = new AtomicInteger();
        private final AtomicLong openedUntilMs = new AtomicLong();
    }

    private static final class WorkflowState {
        private final Set<String> completedTools = ConcurrentHashMap.newKeySet();
        private final AtomicInteger attemptedSteps = new AtomicInteger();
        private final AtomicBoolean failed = new AtomicBoolean(false);
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

    private static final class InMemoryToolRuntimeUserPolicyStore implements ToolRuntimeUserPolicyStore {

        private final Map<String, ToolRuntimeAction> actions = new ConcurrentHashMap<>();

        /**
         * Finds the action.
         *
         * @param tenantId the tenant id value
         * @param userId the user id value
         * @param toolName the tool name value
         * @return the matching action
         */
        @Override
        public Optional<ToolRuntimeAction> findAction(String tenantId, String userId, String toolName) {
            return Optional.ofNullable(actions.get(key(tenantId, userId, toolName)));
        }

        /**
         * Saves the action.
         *
         * @param tenantId the tenant id value
         * @param userId the user id value
         * @param toolName the tool name value
         * @param action the action value
         */
        @Override
        public void saveAction(String tenantId, String userId, String toolName, ToolRuntimeAction action) {
            if (action == null) {
                return;
            }
            actions.put(key(tenantId, userId, toolName), action);
        }

        /**
         * Performs the key operation.
         *
         * @param tenantId the tenant id value
         * @param userId the user id value
         * @param toolName the tool name value
         * @return the operation result
         */
        private String key(String tenantId, String userId, String toolName) {
            return first(tenantId, "default") + "::" + first(userId, "anonymous") + "::" + first(toolName, "unknown");
        }

        /**
         * Performs the first operation.
         *
         * @param value the value value
         * @param fallback the fallback value
         * @return the operation result
         */
        private String first(String value, String fallback) {
            return value == null || value.isBlank() ? fallback : value.trim();
        }
    }
}
