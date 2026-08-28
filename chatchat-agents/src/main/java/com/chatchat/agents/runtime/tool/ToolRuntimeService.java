package com.chatchat.agents.runtime.tool;

import com.chatchat.agents.runtime.config.McpWorkflowProperties;
import com.chatchat.agents.runtime.governance.McpEvidenceGovernanceBridge;
import com.chatchat.agents.runtime.governance.McpEvidenceResult;
import com.chatchat.agents.runtime.governance.McpPolicyProperties;
import com.chatchat.common.runtime.evidence.EvidencePayloadStorePort;

import com.chatchat.agents.runtime.protocol.RuntimeEvidenceProtocol;
import com.chatchat.common.runtime.protocol.RuntimeProtocolRegistry;

import com.chatchat.agents.protocol.ModelProtocolJson;
import com.chatchat.agents.runtime.batch.BatchExecutionMode;
import com.chatchat.agents.runtime.batch.TemplateExecutionLayer;
import com.chatchat.agents.runtime.batch.ToolCallBatch;
import com.chatchat.agents.runtime.batch.ToolCallBatchResult;
import com.chatchat.agents.runtime.batch.ToolCallRequest;
import com.chatchat.agents.runtime.batch.ToolCallResult;
import com.chatchat.agents.runtime.batch.ToolCallBatchSchema;
import com.chatchat.agents.runtime.batch.ToolEvidencePolicy;
import com.chatchat.agents.runtime.plan.DiagnosticRunStateMachine;
import com.chatchat.agents.runtime.toolcall.CanonicalToolInvocation;
import com.chatchat.agents.runtime.toolcall.CompiledToolArguments;
import com.chatchat.agents.runtime.toolcall.ToolArgumentCompiler;
import com.chatchat.agents.runtime.toolcall.ToolInputSchemaResolver;
import com.chatchat.agents.tool.ToolRegistry;
import com.chatchat.common.interaction.InteractionToolTrace;
import com.chatchat.common.mcp.runtime.McpAnalysisPayload;
import com.chatchat.common.mcp.contract.McpTemplateBindingEvidence;
import com.chatchat.common.mcp.runtime.McpRuntimeKernel;
import com.chatchat.common.mcp.service.McpServiceCall;
import com.chatchat.common.mcp.service.McpServiceResult;
import com.chatchat.common.tool.ToolInput;
import com.chatchat.common.tool.ToolLogSummarizer;
import com.chatchat.common.tool.ToolMetadata;
import com.chatchat.common.tool.ToolOutput;
import com.chatchat.common.tool.ToolParameter;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

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
    private final TemplateExecutionLayer templateExecutionLayer = new TemplateExecutionLayer();
    private RuntimeEvidenceProtocol<McpEvidenceResult> evidenceGovernanceBridge =
        new McpEvidenceGovernanceBridge();

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    @SuppressWarnings("unchecked")
    public void setRuntimeProtocolRegistry(RuntimeProtocolRegistry registry) {
        if (registry != null) {
            this.evidenceGovernanceBridge =
                (RuntimeEvidenceProtocol<McpEvidenceResult>) (RuntimeEvidenceProtocol<?>)
                    registry.require(RuntimeEvidenceProtocol.class);
        }
    }
    private final ToolArgumentCompiler toolArgumentCompiler = new ToolArgumentCompiler();
    private final ToolInputSchemaResolver toolInputSchemaResolver = new ToolInputSchemaResolver();
    private final ToolRuntimeUserPolicyStore userPolicyStore;
    private final ExecutorService toolExecutionExecutor;
    private final ExecutorService auditExecutor;
    private volatile EvidencePayloadStorePort evidenceStore;
    private volatile DistributedToolRateLimiter distributedRateLimiter;
    private volatile McpRuntimeKernel mcpRuntimeKernel;

    private final Map<String, Deque<Long>> rateWindows = new ConcurrentHashMap<>();
    private final Map<String, CircuitState> circuitStates = new ConcurrentHashMap<>();
    private final Map<String, ToolCounters> counters = new ConcurrentHashMap<>();
    private final Map<String, WorkflowState> workflowStates = new ConcurrentHashMap<>();
    private static final int REVIEW_CACHE_MAX_ENTRIES = 64;
    private static final long REVIEW_CACHE_MAX_BYTES = 64L * 1024L * 1024L;
    private final Object reviewCacheLock = new Object();
    private final LinkedHashMap<String, String> reviewPayloads = new LinkedHashMap<>(16, 0.75f, true);
    private long reviewPayloadBytes;

    @Autowired(required = false)
    public void setEvidenceStore(EvidencePayloadStorePort evidenceStore) {
        this.evidenceStore = evidenceStore;
    }

    @Autowired(required = false)
    public void setDistributedRateLimiter(DistributedToolRateLimiter distributedRateLimiter) {
        this.distributedRateLimiter = distributedRateLimiter;
    }

    /** Injects the common Runtime OS kernel without coupling Agents to an MCP implementation. */
    public void setMcpRuntimeKernel(McpRuntimeKernel mcpRuntimeKernel) {
        this.mcpRuntimeKernel = mcpRuntimeKernel;
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
     * @param userPolicyStores the user policy stores value
     * @param auditSinks the audit sinks value
     */
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
        this.auditExecutor = new ThreadPoolExecutor(
            1,
            1,
            0L,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(this.properties.safeAuditQueueCapacity()),
            runnable -> {
                Thread thread = new Thread(runnable, "tool-runtime-audit");
                thread.setDaemon(true);
                return thread;
            },
            new ThreadPoolExecutor.AbortPolicy()
        );
    }

    /** Production constructor: a Spring-managed ToolRuntime cannot exist without the Runtime OS kernel. */
    @Autowired
    public ToolRuntimeService(ToolRegistry toolRegistry,
                              ObjectMapper objectMapper,
                              ToolRuntimeProperties properties,
                              McpPolicyProperties mcpPolicyProperties,
                              McpWorkflowProperties mcpWorkflowProperties,
                              List<ToolRuntimePolicyProvider> policyProviders,
                              List<ToolRuntimeUserPolicyStore> userPolicyStores,
                              List<ToolRuntimeAuditSink> auditSinks,
                              McpRuntimeKernel mcpRuntimeKernel) {
        this(toolRegistry, objectMapper, properties, mcpPolicyProperties, mcpWorkflowProperties,
            policyProviders, userPolicyStores, auditSinks);
        this.mcpRuntimeKernel = Objects.requireNonNull(mcpRuntimeKernel, "mcpRuntimeKernel");
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
        ToolRuntimeExecution revisionConflict = registryRevisionConflict(request);
        if (revisionConflict != null) return revisionConflict;
        BatchValidation batchValidation = validateBatchEnvelope(request);
        if (batchValidation.present() && !batchValidation.valid()) {
            String toolName = normalizeText(request == null ? null : request.getToolName());
            return deniedExecution(
                firstText(toolName, "tool_call_batch"),
                request,
                toolRegistry.getToolMetadata(toolName),
                batchValidation.message(),
                batchValidation.errorCode(),
                null,
                null
            );
        }
        ToolCallBatch batch = toolCallBatch(request);
        if (batch != null) {
            return executeBatchRequest(batch, request);
        }
        int retryAttempts = resolveToolRetryAttempts(request);
        int maxCalls = retryAttempts + 2;
        int regularRetriesRemaining = retryAttempts;
        boolean contractRepairAttempted = false;
        Map<String, Object> originalSemanticArguments = request == null
            || request.getToolInput() == null
            || request.getToolInput().getParameters() == null
            ? Map.of()
            : new LinkedHashMap<>(request.getToolInput().getParameters());
        ToolRuntimeExecution lastExecution = null;
        for (int callAttempt = 1; callAttempt <= maxCalls; callAttempt++) {
            int declaredMaxCalls = retryAttempts + 1 + (contractRepairAttempted ? 1 : 0);
            ToolRuntimeRequest attemptRequest = toolRetryRequest(
                request, callAttempt, declaredMaxCalls, retryAttempts);
            lastExecution = executeOnce(attemptRequest);
            if (request != null && attemptRequest != null) {
                request.setCanonicalInvocation(attemptRequest.getCanonicalInvocation());
            }
            enrichRetryMetadata(lastExecution, callAttempt, declaredMaxCalls, retryAttempts);
            if (!contractRepairAttempted
                && inputContractFailure(lastExecution)
                && repairToolArguments(request, originalSemanticArguments, lastExecution)) {
                contractRepairAttempted = true;
                log.info("Retrying tool call after deterministic contract repair tool={} callAttempt={}/{} error={}",
                    request == null ? null : request.getToolName(),
                    callAttempt + 1,
                    retryAttempts + 2,
                    lastExecution == null || lastExecution.output() == null
                        ? null
                        : lastExecution.output().getErrorMessage());
                continue;
            }
            if (regularRetriesRemaining <= 0 || !shouldRetry(lastExecution)) {
                return lastExecution;
            }
            regularRetriesRemaining--;
            log.info("Retrying tool call tool={} callAttempt={}/{} outcome={} error={}",
                request == null ? null : request.getToolName(),
                callAttempt + 1,
                declaredMaxCalls,
                lastExecution == null ? null : lastExecution.outcome(),
                lastExecution == null || lastExecution.output() == null
                    ? null
                    : lastExecution.output().getErrorMessage());
        }
        return lastExecution;
    }

    private ToolRuntimeExecution registryRevisionConflict(ToolRuntimeRequest request) {
        if (request == null || request.getAttributes() == null) return null;
        Object rawExpected = request.getAttributes().get("toolRegistryRevision");
        if (!(rawExpected instanceof Number expected)) return null;
        long actual = toolRegistry.getRevision();
        if (expected.longValue() == actual) return null;
        String toolName = normalizeText(request.getToolName());
        return deniedExecution(
            firstText(toolName, "tool_call"), request, toolRegistry.getToolMetadata(toolName),
            "Tool registry changed during this workflow; retry against one published contract snapshot.",
            "TOOL_REGISTRY_SNAPSHOT_STALE", null, null);
    }

    private boolean repairToolArguments(ToolRuntimeRequest request,
                                        Map<String, Object> originalSemanticArguments,
                                        ToolRuntimeExecution failedExecution) {
        if (request == null || request.getToolInput() == null) {
            return false;
        }
        ToolMetadata metadata = toolRegistry.getToolMetadata(request.getToolName());
        Map<String, Object> schema = toolInputSchemaResolver.resolvePublished(metadata);
        if (schema.isEmpty()) {
            return false;
        }
        ToolArgumentCompiler.CompilationResult compilation = toolArgumentCompiler.compile(
            originalSemanticArguments,
            schema
        );
        if (!compilation.valid()) {
            return false;
        }
        request.getToolInput().setParameters(new LinkedHashMap<>(compilation.parameters()));
        request.getToolInput().getContext().put("runtimeContractRepair", Map.of(
            "category", "INPUT_CONTRACT_ERROR",
            "trigger", contractFailureCode(failedExecution),
            "repairs", compilation.repairs()
        ));
        return true;
    }

    private boolean inputContractFailure(ToolRuntimeExecution execution) {
        String code = contractFailureCode(execution).toUpperCase(Locale.ROOT);
        return code.contains("INPUT_CONTRACT")
            || code.contains("INPUT_REQUIRED")
            || code.contains("INVALID_ARGUMENT")
            || code.contains("INVALID_TOOL_ARGUMENTS")
            || code.contains("PARAMETER_REQUIRED")
            || code.contains("SCHEMA_VALIDATION");
    }

    private String contractFailureCode(ToolRuntimeExecution execution) {
        if (execution == null || execution.output() == null) {
            return "";
        }
        ToolOutput output = execution.output();
        List<Object> candidates = new ArrayList<>();
        candidates.add(output.getExceptionType());
        candidates.add(output.getErrorMessage());
        if (output.getMetadata() != null) {
            candidates.add(output.getMetadata().get("errorCode"));
            candidates.add(output.getMetadata().get("runtimeErrorCode"));
            candidates.add(output.getMetadata().get("category"));
        }
        collectContractFailureSignals(output.getData(), candidates, 0);
        return candidates.stream()
            .filter(Objects::nonNull)
            .map(String::valueOf)
            .filter(value -> !value.isBlank())
            .collect(Collectors.joining(" "));
    }

    private void collectContractFailureSignals(Object value, List<Object> candidates, int depth) {
        if (value == null || depth > 4) {
            return;
        }
        if (value instanceof Map<?, ?> map) {
            for (String key : List.of("errorCode", "code", "category", "error", "message")) {
                if (map.get(key) != null) {
                    candidates.add(map.get(key));
                }
            }
            for (Object nested : map.values()) {
                collectContractFailureSignals(nested, candidates, depth + 1);
            }
        } else if (value instanceof Collection<?> collection) {
            collection.forEach(item -> collectContractFailureSignals(item, candidates, depth + 1));
        }
    }

    public ToolCallBatchResult executeBatch(ToolCallBatch batch, ToolRuntimeRequest context) {
        long startedAt = System.currentTimeMillis();
        String startedAtText = Instant.ofEpochMilli(startedAt).toString();
        String batchId = firstText(batch == null ? null : batch.batchId(), UUID.randomUUID().toString());
        List<ToolCallRequest> calls = batch == null || batch.calls() == null ? List.of() : batch.calls();
        int declaredCheckCount = batchAttributeInt(context, "diagnosticDeclaredCheckCount", calls.size());
        boolean diagnosticBatch = batchAttributeInt(context, "diagnosticDeclaredCheckCount", 0) > 0;
        List<String> missingAuthorizedChecks = batchAttributeStrings(
            context, "diagnosticMissingAuthorizedCheckIds");
        String diagnosticRunId = batchAttributeText(context, "diagnosticRunId");
        BatchValidation validation = validateBatchObject(batch, context);
        if (!validation.valid()) {
            return invalidBatchResult(batchId, startedAtText, validation);
        }
        List<ToolCallResult> results = new ArrayList<>();
        int success = 0;
        int failed = 0;
        int blocked = 0;
        int skipped = 0;
        int remoteToolInvocations = 0;
        int resultCount = 0;
        boolean timeBudgetExhausted = false;
        List<TemplateExecutionLayer.Attempt> attempts = templateExecutionLayer.execute(
            calls,
            (call, index) -> {
                String callId = firstText(call == null ? null : call.callId(), "call-" + (index + 1));
                String toolName = normalizeText(call == null ? null : call.toolName());
                Map<String, Object> arguments = call == null || call.arguments() == null
                    ? Map.of()
                    : call.arguments();
                if (call != null && call.preflightErrorCode() != null
                    && !call.preflightErrorCode().isBlank()) {
                    return TemplateExecutionLayer.Invocation.failed(
                        "BLOCKED",
                        call.preflightErrorCode(),
                        firstText(call.preflightMessage(), "Runtime preflight blocked this admitted template")
                    );
                }
                if (requestRemainingTimeMs(context) == 0L) {
                    return TemplateExecutionLayer.Invocation.terminal(
                        DiagnosticRunStateMachine.FailureCode.TIME_BUDGET_EXHAUSTED.wireValue(),
                        DiagnosticRunStateMachine.FailureCode.TIME_BUDGET_EXHAUSTED.wireValue(),
                        "Diagnostic execution time budget exhausted before invocation",
                        "BATCH_DEADLINE_EXHAUSTED",
                        "Not executed because the Runtime execution deadline was exhausted"
                    );
                }
                if (toolName == null || !batchCapableTool(toolName)) {
                    return TemplateExecutionLayer.Invocation.failed(
                        "FAILED",
                        "BATCH_TOOL_NOT_ALLOWED",
                        "Template batch calls require a registered template batch-capable executor"
                    );
                }
                ToolRuntimeRequest childRequest = batchChildRequest(
                    context, batchId, callId, toolName, arguments, index);
                return TemplateExecutionLayer.Invocation.completed(execute(childRequest));
            }
        );

        for (TemplateExecutionLayer.Attempt attempt : attempts) {
            int index = attempt.index();
            ToolCallRequest call = attempt.call();
            String callId = firstText(call == null ? null : call.callId(), "call-" + (index + 1));
            String toolName = normalizeText(call == null ? null : call.toolName());
            Map<String, Object> arguments = call == null || call.arguments() == null
                ? Map.of()
                : call.arguments();
            if (!attempt.completed()) {
                String attemptStatus = firstText(attempt.status(), "FAILED");
                if ("NOT_EXECUTED".equalsIgnoreCase(attemptStatus)) {
                    skipped++;
                } else if ("BLOCKED".equalsIgnoreCase(attemptStatus)) {
                    blocked++;
                } else {
                    failed++;
                }
                if (DiagnosticRunStateMachine.FailureCode.TIME_BUDGET_EXHAUSTED.wireValue()
                    .equalsIgnoreCase(attemptStatus)) {
                    timeBudgetExhausted = true;
                }
                results.add(skippedBatchResult(
                    context, batchId, index, callId, toolName, arguments,
                    attemptStatus,
                    firstText(attempt.errorCode(), "TEMPLATE_CHILD_FAILED"),
                    firstText(attempt.message(), "Template execution failed")
                ));
                continue;
            }
            ToolRuntimeExecution execution = attempt.execution();
            ToolOutput output = execution == null ? null : execution.output();
            if (output != null) {
                resultCount++;
            }
            Map<String, Object> audit = execution == null || execution.audit() == null
                ? Map.of()
                : execution.audit();
            boolean invoked = Boolean.TRUE.equals(audit.get("remoteToolInvoked"));
            if (invoked) {
                remoteToolInvocations++;
            }
            boolean callBlocked = Boolean.TRUE.equals(audit.get("blockedBeforeInvocation"));
            String exceptionType = output == null ? "TOOL_NO_RESULT" : output.getExceptionType();
            boolean evidenceUsable = output != null && output.isSuccess()
                && (!emptyResult(output.getData())
                    || !diagnosticBatch
                    || Boolean.TRUE.equals(call.emptyResultIsSuccess()));
            String status;
            if (DiagnosticRunStateMachine.FailureCode.TIME_BUDGET_EXHAUSTED.wireValue()
                .equalsIgnoreCase(exceptionType)
                || "time_budget_exhausted".equalsIgnoreCase(execution == null ? null : execution.outcome())) {
                status = DiagnosticRunStateMachine.FailureCode.TIME_BUDGET_EXHAUSTED.wireValue();
                timeBudgetExhausted = true;
                failed++;
            } else if (callBlocked) {
                status = "BLOCKED";
                blocked++;
            } else if (output != null && output.isSuccess() && evidenceUsable) {
                status = "SUCCESS";
                success++;
            } else if (output != null && output.isSuccess()) {
                status = "RESULT_MISSING";
                failed++;
            } else {
                status = "FAILED";
                failed++;
            }
            long durationMs = output == null || output.getExecutionTimeMs() == null
                ? 0L
                : output.getExecutionTimeMs();
            String evidenceId = firstText(stringValue(audit.get("auditId")),
                batchId + ":" + callId + ":" + (index + 1));
            Map<String, Object> error = output != null && output.isSuccess() && !evidenceUsable
                ? errorPayload("EMPTY_RESULT_NOT_ACCEPTED",
                        "The diagnostic result is empty and the template did not authorize empty evidence")
                : output == null || output.isSuccess() ? Map.of()
                : errorPayload(firstText(exceptionType,
                        firstText(stringValue(audit.get("errorCode")), "TOOL_FAILED")),
                    firstText(output.getErrorMessage(), "Tool call failed"));
            results.add(new ToolCallResult(
                diagnosticRunId,
                batchId,
                callId,
                callId,
                toolName,
                normalizeToolSemanticKey(toolName),
                templateId(arguments),
                templateCode(arguments),
                assetId(arguments),
                contextValue(arguments, "assetDisplayName", "asset_display_name", "displayName"),
                contextValue(arguments, "assetToolName", "asset_tool_name"),
                index + 1,
                evidenceUsable,
                status,
                invoked,
                durationMs,
                evidenceId,
                output == null ? null : output.getData(),
                error,
                call.evidencePolicy(),
                null
            ));
        }

        Map<String, Object> assetArguments = calls.isEmpty() || calls.get(0) == null
            ? Map.of()
            : calls.get(0).arguments();
        for (String missingCheckId : missingAuthorizedChecks) {
            skipped++;
            results.add(new ToolCallResult(
                diagnosticRunId,
                batchId,
                missingCheckId,
                missingCheckId,
                null,
                null,
                null,
                null,
                assetId(assetArguments),
                contextValue(assetArguments, "assetDisplayName", "asset_display_name", "displayName"),
                contextValue(assetArguments, "assetToolName", "asset_tool_name"),
                results.size() + 1,
                false,
                "NOT_EXECUTED",
                false,
                0L,
                null,
                null,
                errorPayload("AUTHORIZED_TEMPLATE_NOT_FOUND",
                    "No authorized template returned for this diagnostic check and target asset")
            ));
        }
        boolean resultInconsistent = results.size() != declaredCheckCount;
        String status;
        if (resultInconsistent) {
            status = "BATCH_RESULT_INCONSISTENT";
        } else if (!missingAuthorizedChecks.isEmpty()) {
            status = "BATCH_COMPILATION_INCOMPLETE";
        } else if (timeBudgetExhausted) {
            status = DiagnosticRunStateMachine.FailureCode.TIME_BUDGET_EXHAUSTED.wireValue();
        } else if (success == calls.size() && !calls.isEmpty()) {
            status = DiagnosticRunStateMachine.Outcome.SUCCESS.wireValue();
        } else if (success > 0) {
            status = DiagnosticRunStateMachine.Outcome.PARTIAL_SUCCESS.wireValue();
        } else {
            status = DiagnosticRunStateMachine.State.FAILED.wireValue();
        }
        long completedAt = System.currentTimeMillis();
        return new ToolCallBatchResult(
            batchId,
            BatchExecutionMode.SEQUENTIAL.name(),
            startedAtText,
            Instant.ofEpochMilli(completedAt).toString(),
            status,
            new ToolCallBatchResult.Cardinality(
                declaredCheckCount,
                calls.size(),
                remoteToolInvocations,
                resultCount
            ),
            new ToolCallBatchResult.Summary(
                declaredCheckCount, success, failed, blocked, skipped, remoteToolInvocations),
            List.copyOf(results)
        );
    }

    private ToolRuntimeExecution executeBatchRequest(ToolCallBatch batch, ToolRuntimeRequest request) {
        long startedAt = System.currentTimeMillis();
        ToolCallBatchResult result = executeBatch(batch, request);
        long finishedAt = System.currentTimeMillis();
        boolean summarizeAvailable = summarizeAvailableResults(request);
        boolean successful = DiagnosticRunStateMachine.Outcome.SUCCESS.wireValue().equals(result.status())
            || summarizeAvailable && (
                DiagnosticRunStateMachine.Outcome.PARTIAL_SUCCESS.wireValue().equals(result.status())
                    || "BATCH_COMPILATION_INCOMPLETE".equals(result.status())
                    || "BATCH_RESULT_INCONSISTENT".equals(result.status())
                    || failureIsolatedBatchCompleted(result));
        ToolOutput output = ToolOutput.builder()
            .success(successful)
            .data(result)
            .message("Tool call batch completed with status " + result.status())
            .errorMessage(successful ? null : batchFailureMessage(result))
            .exceptionType(successful ? null : result.status())
            .executionTimeMs(Math.max(0L, finishedAt - startedAt))
            .metadata(new LinkedHashMap<>())
            .build();
        Map<String, Object> runtimeMetadata = new LinkedHashMap<>();
        runtimeMetadata.put("runtimeMode", normalizeMode(request));
        runtimeMetadata.put("requestId", request == null ? null : request.getRequestId());
        runtimeMetadata.put("conversationId", request == null ? null : request.getConversationId());
        runtimeMetadata.put("outcome", result.status().toLowerCase(Locale.ROOT));
        runtimeMetadata.put("executionStatus", result.status());
        runtimeMetadata.put("remoteToolInvoked", false);
        runtimeMetadata.put("remoteToolInvocationCount", result.summary().remoteToolInvocations());
        runtimeMetadata.put("batchExecution", true);
        runtimeMetadata.put("failureIsolatedBatchExecution", true);
        runtimeMetadata.put("templateExecutionLayer", true);
        runtimeMetadata.put("failureIsolation", true);
        runtimeMetadata.put("resultHandlingPolicy", summarizeAvailable
            ? "SUMMARIZE_AVAILABLE" : "STRICT_BATCH_SUCCESS");
        runtimeMetadata.put("requestedStopOnFailureIgnored", batch != null && batch.stopOnFailure());
        runtimeMetadata.put("batchId", result.batchId());
        runtimeMetadata.put("declaredCheckCount", result.cardinality().declaredCheckCount());
        runtimeMetadata.put("compiledCallCount", result.cardinality().compiledCallCount());
        runtimeMetadata.put("executedCallCount", result.cardinality().executedCallCount());
        runtimeMetadata.put("resultCount", result.cardinality().resultCount());
        runtimeMetadata.put("batchResultCountConsistent",
            result.cardinality().compiledCallCount() == result.cardinality().resultCount());
        output.getMetadata().putAll(runtimeMetadata);
        ToolMetadata metadata = toolRegistry.getToolMetadata(request == null ? null : request.getToolName());
        InteractionToolTrace trace = buildTrace(
            request == null ? "tool_call_batch" : request.getToolName(),
            metadata,
            request == null ? null : request.getToolInput(),
            output,
            startedAt,
            finishedAt,
            runtimeMetadata
        );
        return new ToolRuntimeExecution(
            output,
            metadata,
            trace,
            result.status().toLowerCase(Locale.ROOT),
            runtimeMetadata
        );
    }

    private String batchFailureMessage(ToolCallBatchResult result) {
        String summary = "Tool call batch completed with status "
            + (result == null ? "FAILED" : firstText(result.status(), "FAILED"));
        if (result == null || result.results() == null || result.results().isEmpty()) {
            return summary;
        }
        List<String> failures = result.results().stream()
            .filter(Objects::nonNull)
            .filter(child -> !child.evidenceUsable())
            .limit(5)
            .map(child -> {
                Map<String, Object> error = child.error() == null ? Map.of() : child.error();
                String code = firstText(stringValue(error.get("code")),
                    firstText(child.status(), "TOOL_FAILED"));
                String message = firstText(stringValue(error.get("message")), "Tool call failed");
                return firstText(child.callId(), firstText(child.templateId(), "call"))
                    + "[" + compactBatchFailureText(code, 80) + "]: "
                    + compactBatchFailureText(message, 240);
            })
            .toList();
        return failures.isEmpty() ? summary : summary + "; child failures: " + String.join("; ", failures);
    }

    private String compactBatchFailureText(String value, int limit) {
        String normalized = value == null ? "" : value.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= limit) {
            return normalized;
        }
        return normalized.substring(0, Math.max(0, limit - 3)) + "...";
    }

    private boolean failureIsolatedBatchCompleted(ToolCallBatchResult result) {
        if (result == null || result.cardinality() == null || result.summary() == null) {
            return false;
        }
        if (DiagnosticRunStateMachine.FailureCode.TIME_BUDGET_EXHAUSTED.wireValue()
            .equalsIgnoreCase(result.status())) {
            return false;
        }
        return result.cardinality().compiledCallCount() > 0
            && result.summary().success() > 0
            && result.results().size() == result.summary().total();
    }

    private ToolRuntimeExecution executeOnce(ToolRuntimeRequest request) {
        String toolName = normalizeText(request == null ? null : request.getToolName());
        if (toolName == null) {
            return deniedExecution("unknown", request, null, "Tool name is required", "INVALID_REQUEST", null, null);
        }

        ToolMetadata metadata = toolRegistry.getToolMetadata(toolName);
        ToolInput toolInput = request.getToolInput() == null ? new ToolInput() : request.getToolInput();
        enrichToolInputContext(request, toolInput);
        applyRequiredToolParameters(toolName, metadata, request, toolInput);
        CompiledToolArguments argumentCompilation = toolArgumentCompiler.compileCanonical(
            toolInput.getParameters(),
            toolInputSchemaResolver.resolvePublished(metadata)
        );
        if (!argumentCompilation.valid()) {
            return deniedExecution(
                toolName,
                request,
                metadata,
                argumentCompilation.structuredError(toolName, "execute"),
                "INVALID_TOOL_ARGUMENTS",
                null,
                null
            );
        }
        toolInput.setParameters(new LinkedHashMap<>(argumentCompilation.values()));
        if (!argumentCompilation.repairs().isEmpty()) {
            toolInput.getContext().put("runtimeToolArgumentRepairs", argumentCompilation.repairs());
            log.info("Runtime compiled tool arguments tool={} repairs={} compiledKeys={}",
                toolName, argumentCompilation.repairs(), toolInput.getParameters().keySet());
        }
        CanonicalToolInvocation canonicalInvocation = new CanonicalToolInvocation(
            null,
            firstText(toolInput.getRequestId(), request == null ? null : request.getRequestId()),
            stringValue(toolInput.getContext() == null ? null
                : firstPresent(toolInput.getContext().get("stepId"), toolInput.getContext().get("planStepId"))),
            toolName,
            argumentCompilation,
            toolInput.getContext()
        );
        if (request != null) {
            request.setCanonicalInvocation(canonicalInvocation);
        }
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
        if (isCircuitOpen(toolName, policy) && !isToolRetryContinuation(request)) {
            return rejectedExecution(toolName, request, metadata,
                "Tool circuit is open: " + toolName,
                "TOOL_CIRCUIT_OPEN",
                "circuit_open",
                executionPlan,
                policyDecision);
        }
        if (isRateLimited(toolName, metadata, request.getTenantId(), toolInput.getUserId(), policy)) {
            return rejectedExecution(toolName, request, metadata,
                "Tool rate limit exceeded: " + toolName,
                "TOOL_RATE_LIMITED",
                "rate_limited",
                executionPlan,
                policyDecision);
        }
        if (requestRemainingTimeMs(request) == 0L) {
            return rejectedExecution(toolName, request, metadata,
                "Agent execution time budget exhausted before tool invocation: " + toolName,
                DiagnosticRunStateMachine.FailureCode.TIME_BUDGET_EXHAUSTED.wireValue(),
                "time_budget_exhausted",
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
            output.setData(processResultData(output.getData(), metadata, request, output));
            Object evidencePayload = losslessMcpAnalysisPayload(output, metadata, request);
            McpEvidenceResult governedEvidence = evidenceGovernanceBridge.capture(
                request,
                toolName,
                output.isSuccess() ? "success" : "failed",
                evidencePayload
            );
            output.setData(governedEvidence.payload());
            output.getMetadata().put("mcpEvidenceResult", governedEvidence.descriptor());
            output.getMetadata().put("mcpEvidenceResultSchemaVersion", McpEvidenceResult.SCHEMA_VERSION);
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
                if (shouldRecordCircuitFailure(output)) {
                    updateCircuitOnFailure(toolName, policy);
                }
                if (isCircuitOpen(toolName, policy)) {
                    output.getMetadata().put("retryable", false);
                    output.getMetadata().put("circuitOpened", true);
                }
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
            runtimeMetadata.put("mcpEvidenceResult", governedEvidence.descriptor());
            runtimeMetadata.put("mcpEvidenceResultSchemaVersion", McpEvidenceResult.SCHEMA_VERSION);
            InteractionToolTrace trace = buildTrace(toolName, metadata, toolInput, output, startedAt, finishedAt, runtimeMetadata);
            logAudit(toolName, request, output.isSuccess() ? "success" : "failed", durationMs, output.getErrorMessage());
            publishAuditRecord(request, metadata, output, trace, output.isSuccess() ? "success" : "failed", null, durationMs, runtimeMetadata);
            return new ToolRuntimeExecution(output, metadata, trace, output.isSuccess() ? "success" : "failed", runtimeMetadata);
        } finally {
            toolCounters.activeCalls.decrementAndGet();
        }
    }

    private int resolveToolRetryAttempts(ToolRuntimeRequest request) {
        Map<String, Object> attributes = request == null || request.getAttributes() == null
            ? Map.of()
            : request.getAttributes();
        Object configured = firstPresent(
            attributes.get("toolRetryAttempts"),
            attributes.get("toolRetryCount"),
            attributes.get("maxToolRetries")
        );
        if (configured == null) {
            Map<String, Object> workflow = asMap(attributes.get("mcpWorkflow"));
            Map<String, Object> strategy = asMap(firstPresent(
                workflow.get("executionStrategy"),
                workflow.get("execution_strategy")
            ));
            configured = firstPresent(
                strategy.get("toolRetryAttempts"),
                strategy.get("tool_retry_attempts"),
                strategy.get("toolRetryCount"),
                strategy.get("maxToolRetries")
            );
        }
        Integer parsed = integerValue(configured);
        int fallback = properties.safeDefaultRetryAttempts();
        return parsed == null ? fallback : Math.max(0, Math.min(5, parsed));
    }

    private ToolRuntimeRequest toolRetryRequest(ToolRuntimeRequest request,
                                                int callAttempt,
                                                int maxCalls,
                                                int retryAttempts) {
        if (request == null) {
            return null;
        }
        Map<String, Object> attributes = new LinkedHashMap<>(
            request.getAttributes() == null ? Map.of() : request.getAttributes());
        String workflowAttempt = firstText(
            stringValue(firstPresent(
                attributes.get("workflowExecutionAttempt"),
                attributes.get("interpretationPlanAttempt"),
                attributes.get("workflowAttempt")
            )),
            "0"
        );
        attributes.put("toolRetryAttempt", callAttempt - 1);
        attributes.put("toolRetryAttempts", retryAttempts);
        attributes.put("toolCallAttempt", callAttempt);
        attributes.put("toolCallMaxAttempts", maxCalls);
        attributes.put("workflowExecutionAttempt", workflowAttempt + ".tool-call-" + callAttempt);
        return ToolRuntimeRequest.builder()
            .toolName(request.getToolName())
            .runtimeMode(request.getRuntimeMode())
            .requestId(request.getRequestId())
            .conversationId(request.getConversationId())
            .tenantId(request.getTenantId())
            .userId(request.getUserId())
            .allowedTools(request.getAllowedTools())
            .toolInput(request.getToolInput())
            .attributes(attributes)
            .build();
    }

    private void enrichRetryMetadata(ToolRuntimeExecution execution,
                                     int callAttempt,
                                     int maxCalls,
                                     int retryAttempts) {
        if (execution == null || execution.output() == null) {
            return;
        }
        if (execution.output().getMetadata() == null) {
            execution.output().setMetadata(new LinkedHashMap<>());
        }
        execution.output().getMetadata().put("toolRetryAttempt", callAttempt - 1);
        execution.output().getMetadata().put("toolRetryAttempts", retryAttempts);
        execution.output().getMetadata().put("toolCallAttempt", callAttempt);
        execution.output().getMetadata().put("toolCallMaxAttempts", maxCalls);
    }

    private boolean shouldRetry(ToolRuntimeExecution execution) {
        if (execution == null || execution.output() == null) {
            return false;
        }
        if (execution.output().isSuccess() || !"failed".equalsIgnoreCase(execution.outcome())) {
            return false;
        }
        if (execution.output().getMetadata() == null) {
            return true;
        }
        Object retryable = firstPresent(
            execution.output().getMetadata().get("retryable"),
            execution.output().getMetadata().get("mcpRetryable"));
        return !Boolean.FALSE.equals(retryable);
    }

    private boolean isToolRetryContinuation(ToolRuntimeRequest request) {
        Integer callAttempt = integerValue(request == null || request.getAttributes() == null
            ? null
            : request.getAttributes().get("toolCallAttempt"));
        return callAttempt != null && callAttempt > 1;
    }

    private void enrichToolInputContext(ToolRuntimeRequest request, ToolInput toolInput) {
        if (request == null || toolInput == null) {
            return;
        }
        if (normalizeText(toolInput.getRequestId()) == null) {
            toolInput.setRequestId(request.getRequestId());
        }
        if (normalizeText(toolInput.getConversationId()) == null) {
            toolInput.setConversationId(request.getConversationId());
        }
        if (normalizeText(toolInput.getUserId()) == null) {
            toolInput.setUserId(request.getUserId());
        }
        Map<String, Object> context = toolInput.getContext() == null
            ? new LinkedHashMap<>()
            : new LinkedHashMap<>(toolInput.getContext());
        putIfAbsentText(context, "tenantId", request.getTenantId());
        putIfAbsentText(context, "userId", request.getUserId());
        putIfAbsentText(context, "requestId", request.getRequestId());
        putIfAbsentText(context, "conversationId", request.getConversationId());
        copyRuntimeAttribute(context, request.getAttributes(), "mcpExecutionContext");
        copyRuntimeAttribute(context, request.getAttributes(), "defaultDataAsset");
        copyRuntimeAttribute(context, request.getAttributes(), "assetSelectionPolicy");
        copyRuntimeAttribute(context, request.getAttributes(), "mcpWorkflow");
        copyRuntimeAttribute(context, request.getAttributes(), "workflowContext");
        copyRuntimeAttribute(context, request.getAttributes(), "workflowVariables");
        copyRuntimeAttribute(context, request.getAttributes(), "requiredToolParameters");
        toolInput.setContext(context);
    }

    private void applyRequiredToolParameters(String toolName,
                                             ToolMetadata metadata,
                                             ToolRuntimeRequest request,
                                             ToolInput toolInput) {
        if (request == null || toolInput == null || request.getAttributes() == null) {
            return;
        }
        Map<String, Object> configuredByTool = asMap(request.getAttributes().get("requiredToolParameters"));
        if (configuredByTool.isEmpty()) {
            return;
        }
        Map<String, Object> required = new LinkedHashMap<>();
        configuredByTool.forEach((configuredTool, configuredParameters) -> {
            if ("*".equals(configuredTool) || sameTool(configuredTool, toolName)) {
                required.putAll(asMap(configuredParameters));
            }
        });
        if (required.isEmpty()) {
            return;
        }
        Set<String> supportedParameters = metadata == null || metadata.getParameters() == null
            ? new LinkedHashSet<>()
            : metadata.getParameters().stream()
                .filter(Objects::nonNull)
                .map(ToolParameter::getName)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<String, Object> publishedSchema = toolInputSchemaResolver.resolvePublished(metadata);
        if (publishedSchema.get("properties") instanceof Map<?, ?> properties) {
            properties.keySet().stream()
                .filter(Objects::nonNull)
                .map(String::valueOf)
                .forEach(supportedParameters::add);
        }
        Map<String, Object> parameters = toolInput.getParameters() == null
            ? new LinkedHashMap<>()
            : new LinkedHashMap<>(toolInput.getParameters());
        List<String> applied = new ArrayList<>();
        required.forEach((parameterName, value) -> {
            if (supportedParameters.contains(parameterName)) {
                parameters.put(parameterName, value);
                applied.add(parameterName);
            } else {
                log.warn("Required tool parameter ignored because it is absent from Tool schema "
                        + "tool={} parameter={} requestId={}",
                    toolName, parameterName, request.getRequestId());
            }
        });
        if (applied.isEmpty()) {
            return;
        }
        toolInput.setParameters(parameters);

        Map<String, Object> context = toolInput.getContext() == null
            ? new LinkedHashMap<>()
            : new LinkedHashMap<>(toolInput.getContext());
        context.put("runtimeRequiredToolParametersApplied", List.copyOf(applied));
        toolInput.setContext(context);
    }

    private void copyRuntimeAttribute(Map<String, Object> context, Map<String, Object> attributes, String key) {
        if (context == null || attributes == null || key == null || key.isBlank()) {
            return;
        }
        Object value = attributes.get(key);
        if (value != null) {
            context.putIfAbsent(key, value);
        }
    }

    private void putIfAbsentText(Map<String, Object> values, String key, String value) {
        if (values == null || key == null || key.isBlank() || value == null || value.isBlank()) {
            return;
        }
        values.putIfAbsent(key, value.trim());
    }

    @PreDestroy
    public void shutdown() {
        toolExecutionExecutor.shutdownNow();
        auditExecutor.shutdownNow();
    }

    private ToolOutput executeToolWithTimeout(String toolName,
                                              ToolInput toolInput,
                                              ToolRuntimeRequest request,
                                              ToolRuntimePolicy policy,
                                              ToolMetadata metadata) {
        long timeoutMs = resolveToolTimeoutMs(request, policy, metadata);
        if (timeoutMs == 0L && requestRemainingTimeMs(request) == 0L) {
            ToolOutput output = ToolOutput.failure("Agent execution time budget exhausted before tool invocation: " + toolName);
            output.setExceptionType(DiagnosticRunStateMachine.FailureCode.TIME_BUDGET_EXHAUSTED.wireValue());
            return output;
        }
        CompletableFuture<ToolOutput> future;
        try {
            future = CompletableFuture.supplyAsync(
                () -> executeThroughRuntimeKernel(toolName, toolInput, request, metadata),
                toolExecutionExecutor);
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
            output.setExceptionType(requestRemainingTimeMs(request) == 0L
                ? DiagnosticRunStateMachine.FailureCode.TIME_BUDGET_EXHAUSTED.wireValue()
                : "TOOL_TIMEOUT");
            output.setMetadata(new LinkedHashMap<>(Map.of("retryable", false, "terminalReason", "deadline_exceeded")));
            return output;
        } catch (InterruptedException ex) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            ToolOutput output = ToolOutput.failure("Tool execution interrupted: " + toolName);
            output.setExceptionType("TOOL_INTERRUPTED");
            output.setMetadata(new LinkedHashMap<>(Map.of("retryable", false, "terminalReason", "interrupted")));
            return output;
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause() == null ? ex : ex.getCause();
            ToolOutput output = ToolOutput.failure(cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage());
            output.setExceptionType(cause.getClass().getSimpleName());
            return output;
        }
    }

    private long resolveToolTimeoutMs(ToolRuntimeRequest request, ToolRuntimePolicy policy, ToolMetadata metadata) {
        Object value = firstPresent(
            request == null || request.getAttributes() == null ? null : request.getAttributes().get("toolTimeoutMs"),
            policy == null || policy.attributes() == null ? null : policy.attributes().get("toolTimeoutMs"),
            metadata == null ? null : metadata.getTimeoutMillis()
        );
        long configuredTimeoutMs = properties.safeDefaultToolTimeoutMs();
        if (value instanceof Number number) {
            configuredTimeoutMs = number.longValue();
        } else if (value != null) {
            try {
                configuredTimeoutMs = Long.parseLong(String.valueOf(value));
            } catch (NumberFormatException ignored) {
                configuredTimeoutMs = properties.safeDefaultToolTimeoutMs();
            }
        }
        if (configuredTimeoutMs <= 0L) {
            configuredTimeoutMs = properties.safeDefaultToolTimeoutMs();
        }
        long remainingMs = requestRemainingTimeMs(request);
        if (remainingMs < 0L) {
            return Math.max(0L, configuredTimeoutMs);
        }
        return Math.max(0L, Math.min(configuredTimeoutMs, remainingMs));
    }

    private long requestRemainingTimeMs(ToolRuntimeRequest request) {
        Map<String, Object> attributes = request == null || request.getAttributes() == null
            ? Map.of()
            : request.getAttributes();
        Object rawDeadline = firstPresent(
            attributes.get("__agentDeadlineAt"),
            attributes.get("diagnosticDeadlineAt"),
            attributes.get("deadlineAt")
        );
        Long deadlineAt = longValue(rawDeadline);
        if (deadlineAt == null || deadlineAt <= 0L) {
            return -1L;
        }
        return Math.max(0L, deadlineAt - System.currentTimeMillis());
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
            || normalized.contains("generic_web_site_search")
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
        if ("MCP_WORKFLOW_DENIED".equals(errorCode)) {
            runtimeMetadata.put("executionStatus", "BLOCKED");
            runtimeMetadata.put("blockedBeforeInvocation", true);
            runtimeMetadata.put("blockedReason", workflowBlockedReason(message));
        } else if (errorCode != null && errorCode.startsWith("BATCH_")) {
            runtimeMetadata.put("executionStatus", "FAILED");
            runtimeMetadata.put("blockedBeforeInvocation", true);
            runtimeMetadata.put("blockedReason", "batch_validation_failed");
        }
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
        if (DiagnosticRunStateMachine.FailureCode.TIME_BUDGET_EXHAUSTED.wireValue().equals(errorCode)) {
            runtimeMetadata.put("executionStatus",
                DiagnosticRunStateMachine.FailureCode.TIME_BUDGET_EXHAUSTED.wireValue());
            runtimeMetadata.put("blockedBeforeInvocation", true);
            runtimeMetadata.put("blockedReason", "time_budget_exhausted");
        }
        ToolOutput output = ToolOutput.failure(message);
        output.setExceptionType(errorCode);
        output.getMetadata().putAll(runtimeMetadata);
        if ("TOOL_CIRCUIT_OPEN".equals(errorCode)) {
            output.setExceptionType("TOOL_BUSY");
            output.getMetadata().put("runtimeErrorCode", errorCode);
            output.getMetadata().put("errorCode", "TOOL_BUSY");
            output.getMetadata().put("retryable", false);
            output.getMetadata().put("action", "STOP");
            output.getMetadata().put("executionState", Map.of(
                "state", "STOPPED",
                "step", toolName,
                "toolName", toolName,
                "retryCount", 0,
                "errorCode", "TOOL_BUSY",
                "action", "STOP",
                "circuit", "OPEN"
            ));
        }
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
    private boolean isRateLimited(String toolName,
                                  ToolMetadata metadata,
                                  String tenantId,
                                  String userId,
                                  ToolRuntimePolicy policy) {
        int limit = metadata != null && metadata.isRateLimited() && metadata.getMaxCallsPerMinute() > 0
            ? metadata.getMaxCallsPerMinute()
            : properties.getDefaultMaxCallsPerMinute();
        if (policy != null && policy.maxCallsPerMinute() != null) {
            limit = policy.maxCallsPerMinute();
        }
        int qpsLimit = Math.max(0, properties.getDefaultMaxCallsPerSecond());
        String actor = normalizeText(userId);
        String tenant = normalizeText(tenantId);
        String tenantToolKey = (tenant == null ? "default" : tenant) + "::" + toolName;
        String actorKey = tenantToolKey + "::" + (actor == null ? "anonymous" : actor);
        long now = System.currentTimeMillis();
        if (distributedRateLimiter != null) {
            return !distributedRateLimiter.tryAcquire(
                tenant == null ? "default" : tenant,
                toolName,
                actor == null ? "anonymous" : actor,
                limit,
                qpsLimit,
                Instant.ofEpochMilli(now));
        }
        return exceedsRateWindow(actorKey + "::minute", now, 60_000L, limit)
            || exceedsRateWindow(tenantToolKey + "::second", now, 1_000L, qpsLimit);
    }

    private boolean exceedsRateWindow(String key, long now, long windowMs, int limit) {
        if (limit <= 0) {
            return false;
        }
        long threshold = now - windowMs;
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

    /** Local policy and contract rejections do not describe remote tool health. */
    private boolean shouldRecordCircuitFailure(ToolOutput output) {
        if (output == null) {
            return true;
        }
        Map<String, Object> metadata = output.getMetadata() == null
            ? Map.of() : output.getMetadata();
        if (Boolean.FALSE.equals(firstPresent(metadata.get("mcpRetryable"), metadata.get("retryable")))) {
            return false;
        }
        String errorCode = firstText(
            output.getExceptionType(),
            stringValue(firstPresent(metadata.get("runtimeErrorCode"), metadata.get("errorCode")))
        );
        return errorCode == null
            || !(errorCode.startsWith("MCP_TEMPLATE_")
                || errorCode.startsWith("MCP_PARAM_")
                || errorCode.startsWith("MCP_POLICY_")
                || errorCode.startsWith("INVALID_TOOL_ARGUMENTS"));
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
        // Interaction traces are consumed by downstream evidence analysis. Keep
        // the complete returned payload here; chunking belongs to the analysis
        // layer and must not be confused with destructive result truncation.
        String outputText = stringify(traceOutputSummary(
            toolName, output == null ? null : output.getData()));
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
     * Keeps complete, credential-redacted evidence and the Runtime-owned routing
     * contract together. Operational logging may use compact summaries, but the
     * evidence trace must preserve every returned record and value.
     */
    private Object traceOutputSummary(String toolName, Object data) {
        Object summary = ToolLogSummarizer.redactComplete(data);
        Map<String, Object> projection = existingRoutingProjection(data);
        if (projection.isEmpty()) {
            projection = routingProjection(data);
        }
        if (projection.isEmpty()) {
            return summary;
        }
        Map<String, Object> enriched = summary instanceof Map<?, ?> map
            ? new LinkedHashMap<>(asMap(map))
            : new LinkedHashMap<>();
        if (!(summary instanceof Map<?, ?>)) {
            enriched.put("summary", summary);
        }
        enriched.put("routingProjection", projection);
        return enriched;
    }

    private Map<String, Object> existingRoutingProjection(Object data) {
        if (!(data instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Object projection = map.get("routingProjection");
        return projection instanceof Map<?, ?> projectionMap
            ? new LinkedHashMap<>(asMap(projectionMap))
            : Map.of();
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
        values.put("remoteToolInvoked", "success".equalsIgnoreCase(outcome) || "failed".equalsIgnoreCase(outcome));
        values.put("serviceId", resolveServiceId(metadata));
        values.put("serviceName", resolveServiceName(metadata));
        values.put("executionPlan", executionPlan == null ? null : executionPlan.toMap());
        if (request != null && request.getCanonicalInvocation() != null) {
            CanonicalToolInvocation canonical = request.getCanonicalInvocation();
            values.put("canonicalInvocationSchemaVersion", canonical.schemaVersion());
            values.put("compiledArgumentsSchemaVersion", canonical.arguments().schemaVersion());
            values.put("toolInputSchemaFingerprint", canonical.arguments().schemaFingerprint());
        }
        if (request != null && request.getToolInput() != null) {
            Map<String, Object> context = request.getToolInput().getContext();
            if (context != null && context.containsKey("runtimeRequiredToolParametersApplied")) {
                values.put("runtimeRequiredToolParametersApplied",
                    context.get("runtimeRequiredToolParametersApplied"));
            }
        }
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

    private ToolCallBatch toolCallBatch(ToolRuntimeRequest request) {
        if (request == null
            || request.getAttributes() != null && Boolean.TRUE.equals(request.getAttributes().get("__toolBatchChild"))
            || request.getToolInput() == null
            || request.getToolInput().getParameters() == null) {
            return null;
        }
        Map<String, Object> parameters = request.getToolInput().getParameters();
        Object rawCalls = firstPresent(parameters.get("calls"), parameters.get("toolCalls"), parameters.get("tool_calls"));
        if (!(rawCalls instanceof List<?> calls) || calls.isEmpty()) {
            return null;
        }
        List<ToolCallRequest> parsedCalls = new ArrayList<>();
        boolean runtimeOwnedPreflight = request.getAttributes() != null
            && Boolean.TRUE.equals(request.getAttributes().get("runtimeOwnedTemplatePreflight"));
        int index = 0;
        for (Object item : calls) {
            index++;
            Map<String, Object> call = asMap(item);
            String callId = firstText(
                stringValue(firstPresent(call.get("callId"), call.get("call_id"), call.get("id"))),
                "call-" + index
            );
            String toolName = firstText(
                stringValue(firstPresent(call.get("toolName"), call.get("tool_name"))),
                request.getToolName()
            );
            toolName = resolveBatchChildToolName(request, toolName);
            Map<String, Object> arguments = asMap(firstPresent(
                call.get("arguments"), call.get("input"), call.get("parameters")
            ));
            Boolean emptyResultIsSuccess = booleanValue(firstPresent(
                call.get("emptyResultIsSuccess"), call.get("empty_result_is_success")
            ));
            List<String> requiredFields = stringList(firstPresent(
                call.get("requiredFields"), call.get("required_fields")
            ));
            List<String> requiredMetrics = stringList(firstPresent(
                call.get("requiredMetrics"), call.get("required_metrics"), requiredFields
            ));
            ToolEvidencePolicy evidencePolicy = new ToolEvidencePolicy(
                stringValue(firstPresent(call.get("purpose"), call.get("templatePurpose"))),
                booleanValue(firstPresent(call.get("healthCapability"), call.get("health_capability"))),
                requiredMetrics,
                stringValue(firstPresent(call.get("timeSemantics"), call.get("time_semantics"))),
                stringList(firstPresent(call.get("requiresContext"), call.get("requires_context"))),
                integerValue(firstPresent(
                    call.get("freshnessMaxAgeSeconds"), call.get("freshness_max_age_seconds")))
            );
            parsedCalls.add(new ToolCallRequest(
                callId, toolName, arguments, emptyResultIsSuccess, requiredFields, evidencePolicy,
                runtimeOwnedPreflight ? stringValue(firstPresent(
                    call.get("preflightErrorCode"), call.get("preflight_error_code"))) : null,
                runtimeOwnedPreflight ? stringValue(firstPresent(
                    call.get("preflightMessage"), call.get("preflight_message"))) : null));
        }
        String mode = firstText(
            stringValue(firstPresent(parameters.get("executionMode"), parameters.get("execution_mode"))),
            BatchExecutionMode.SEQUENTIAL.name()
        );
        if (!BatchExecutionMode.SEQUENTIAL.name().equalsIgnoreCase(mode)) {
            throw new IllegalArgumentException("Only SEQUENTIAL tool call batches are supported");
        }
        boolean stopOnFailure = Boolean.TRUE.equals(booleanValue(firstPresent(
            parameters.get("stopOnFailure"), parameters.get("stop_on_failure")
        )));
        String batchId = firstText(
            stringValue(firstPresent(parameters.get("batchId"), parameters.get("batch_id"))),
            firstText(request.getRequestId(), UUID.randomUUID().toString()) + "-batch"
        );
        return new ToolCallBatch(batchId, BatchExecutionMode.SEQUENTIAL, stopOnFailure, parsedCalls);
    }

    private String resolveBatchChildToolName(ToolRuntimeRequest request, String declaredTool) {
        if (declaredTool == null || declaredTool.isBlank()) {
            return request == null ? declaredTool : request.getToolName();
        }
        if (request != null && request.getAllowedTools() != null) {
            for (String allowedTool : request.getAllowedTools()) {
                if (sameTool(allowedTool, declaredTool)) {
                    return allowedTool;
                }
            }
        }
        if (request != null && sameTool(request.getToolName(), declaredTool)) {
            return request.getToolName();
        }
        return declaredTool;
    }

    private BatchValidation validateBatchEnvelope(ToolRuntimeRequest request) {
        if (request == null || request.getToolInput() == null || request.getToolInput().getParameters() == null) {
            return BatchValidation.absent();
        }
        Map<String, Object> parameters = request.getToolInput().getParameters();
        boolean present = parameters.containsKey("calls")
            || parameters.containsKey("toolCalls")
            || parameters.containsKey("tool_calls");
        if (!present) {
            return BatchValidation.absent();
        }
        if (request.getAttributes() != null && Boolean.TRUE.equals(request.getAttributes().get("__toolBatchChild"))) {
            return BatchValidation.invalid("BATCH_NESTING_NOT_ALLOWED",
                "Nested tool call batches are not allowed");
        }
        String outerTool = normalizeText(request.getToolName());
        if (!batchCapableTool(outerTool)) {
            return BatchValidation.invalid("BATCH_TOOL_NOT_ALLOWED",
                "The outer batch tool must be a registered batch-capable executor");
        }
        try {
            if (objectMapper.writeValueAsBytes(parameters).length > properties.safeMaxBatchPayloadBytes()) {
                return BatchValidation.invalid("BATCH_PAYLOAD_TOO_LARGE",
                    "Tool call batch payload exceeds " + properties.safeMaxBatchPayloadBytes() + " bytes");
            }
        } catch (Exception ex) {
            return BatchValidation.invalid("BATCH_PAYLOAD_INVALID",
                "Tool call batch payload cannot be serialized");
        }
        Object rawCalls = firstPresent(parameters.get("calls"), parameters.get("toolCalls"), parameters.get("tool_calls"));
        if (!(rawCalls instanceof List<?> calls) || calls.isEmpty()) {
            return BatchValidation.invalid("BATCH_CALLS_REQUIRED",
                "Tool call batch calls must be a non-empty array");
        }
        if (calls.size() > properties.safeMaxBatchCalls()) {
            return BatchValidation.invalid("BATCH_CALL_LIMIT_EXCEEDED",
                "Tool call batch exceeds the maximum of " + properties.safeMaxBatchCalls() + " calls");
        }
        Set<String> callIds = new HashSet<>();
        boolean diagnosticBatch = batchAttributeInt(request, "diagnosticDeclaredCheckCount", 0) > 0;
        boolean templateAssetAuthorizationRequired = batchAttributeBoolean(
            request, "diagnosticTemplateAssetAuthorizationRequired");
        Map<String, String> authorizedTemplateAssets = batchAuthorizedTemplateAssets(request);
        String diagnosticAssetId = null;
        for (int index = 0; index < calls.size(); index++) {
            Object item = calls.get(index);
            if (!(item instanceof Map<?, ?> rawCall)) {
                return BatchValidation.invalid("BATCH_CALL_INVALID",
                    "Batch call " + (index + 1) + " must be an object");
            }
            Map<String, Object> call = asMap(rawCall);
            String callId = firstText(
                stringValue(firstPresent(call.get("callId"), call.get("call_id"), call.get("id"))),
                "call-" + (index + 1)
            );
            if (callId.length() > 128 || !callIds.add(callId)) {
                return BatchValidation.invalid("BATCH_CALL_ID_INVALID",
                    "Batch call ids must be unique strings of at most 128 characters");
            }
            String toolName = firstText(
                stringValue(firstPresent(call.get("toolName"), call.get("tool_name"))),
                outerTool
            );
            if (!batchCapableTool(toolName)) {
                return BatchValidation.invalid("BATCH_TOOL_NOT_ALLOWED",
                    "Batch call " + callId + " is not a registered batch-capable executor");
            }
            Object rawArguments = firstPresent(call.get("arguments"), call.get("input"), call.get("parameters"));
            if (!(rawArguments instanceof Map<?, ?> arguments)) {
                return BatchValidation.invalid("BATCH_ARGUMENTS_INVALID",
                    "Batch call " + callId + " arguments must be an object");
            }
            if (containsBatchEnvelope(arguments)) {
                return BatchValidation.invalid("BATCH_NESTING_NOT_ALLOWED",
                    "Batch call " + callId + " contains a nested batch");
            }
            if (diagnosticBatch) {
                @SuppressWarnings("unchecked")
                Map<String, Object> childArguments = new LinkedHashMap<>((Map<String, Object>) arguments);
                String childAssetId = assetId(childArguments);
                if (childAssetId == null || childAssetId.isBlank()) {
                    return BatchValidation.invalid("BATCH_ASSET_ID_REQUIRED",
                        "Diagnostic batch call " + callId + " must contain the canonical assetId");
                }
                String authorizationError = validateDiagnosticTemplateAsset(
                    callId, childArguments, childAssetId,
                    templateAssetAuthorizationRequired, authorizedTemplateAssets);
                if (authorizationError != null) {
                    return BatchValidation.invalid("BATCH_TEMPLATE_ASSET_MISMATCH", authorizationError);
                }
                if (templateAssetAuthorizationRequired) {
                    continue;
                } else if (diagnosticAssetId == null) {
                    diagnosticAssetId = childAssetId;
                } else if (!diagnosticAssetId.equals(childAssetId)) {
                    return BatchValidation.invalid("BATCH_ASSET_MISMATCH",
                        "All diagnostic batch calls must target the same canonical assetId");
                }
            }
        }
        return BatchValidation.accepted();
    }

    private BatchValidation validateBatchObject(ToolCallBatch batch, ToolRuntimeRequest context) {
        if (batch == null || batch.calls() == null || batch.calls().isEmpty()) {
            return BatchValidation.invalid("BATCH_CALLS_REQUIRED",
                "Tool call batch calls must be a non-empty array");
        }
        if (batch.executionMode() != BatchExecutionMode.SEQUENTIAL) {
            return BatchValidation.invalid("BATCH_MODE_NOT_ALLOWED",
                "Only SEQUENTIAL tool call batches are supported");
        }
        if (batch.calls().size() > properties.safeMaxBatchCalls()) {
            return BatchValidation.invalid("BATCH_CALL_LIMIT_EXCEEDED",
                "Tool call batch exceeds the maximum of " + properties.safeMaxBatchCalls() + " calls");
        }
        Set<String> callIds = new HashSet<>();
        boolean diagnosticBatch = batchAttributeInt(context, "diagnosticDeclaredCheckCount", 0) > 0;
        boolean templateAssetAuthorizationRequired = batchAttributeBoolean(
            context, "diagnosticTemplateAssetAuthorizationRequired");
        Map<String, String> authorizedTemplateAssets = batchAuthorizedTemplateAssets(context);
        String diagnosticAssetId = null;
        for (int index = 0; index < batch.calls().size(); index++) {
            ToolCallRequest call = batch.calls().get(index);
            String callId = firstText(call == null ? null : call.callId(), "call-" + (index + 1));
            if (callId.length() > 128 || !callIds.add(callId)) {
                return BatchValidation.invalid("BATCH_CALL_ID_INVALID",
                    "Batch call ids must be unique strings of at most 128 characters");
            }
            if (call == null || !batchCapableTool(call.toolName())) {
                return BatchValidation.invalid("BATCH_TOOL_NOT_ALLOWED",
                    "Batch call " + callId + " is not a registered batch-capable executor");
            }
            if (containsBatchEnvelope(call.arguments())) {
                return BatchValidation.invalid("BATCH_NESTING_NOT_ALLOWED",
                    "Batch call " + callId + " contains a nested batch");
            }
            if (diagnosticBatch) {
                String childAssetId = assetId(call.arguments());
                if (childAssetId == null || childAssetId.isBlank()) {
                    return BatchValidation.invalid("BATCH_ASSET_ID_REQUIRED",
                        "Diagnostic batch call " + callId + " must contain the canonical assetId");
                }
                String authorizationError = validateDiagnosticTemplateAsset(
                    callId, call.arguments(), childAssetId,
                    templateAssetAuthorizationRequired, authorizedTemplateAssets);
                if (authorizationError != null) {
                    return BatchValidation.invalid("BATCH_TEMPLATE_ASSET_MISMATCH", authorizationError);
                }
                if (templateAssetAuthorizationRequired) {
                    continue;
                } else if (diagnosticAssetId == null) {
                    diagnosticAssetId = childAssetId;
                } else if (!diagnosticAssetId.equals(childAssetId)) {
                    return BatchValidation.invalid("BATCH_ASSET_MISMATCH",
                        "All diagnostic batch calls must target the same canonical assetId");
                }
            }
        }
        try {
            if (objectMapper.writeValueAsBytes(batch).length > properties.safeMaxBatchPayloadBytes()) {
                return BatchValidation.invalid("BATCH_PAYLOAD_TOO_LARGE",
                    "Tool call batch payload exceeds " + properties.safeMaxBatchPayloadBytes() + " bytes");
            }
        } catch (Exception ex) {
            return BatchValidation.invalid("BATCH_PAYLOAD_INVALID",
                "Tool call batch payload cannot be serialized");
        }
        if (context != null && !batchCapableTool(context.getToolName())) {
            return BatchValidation.invalid("BATCH_TOOL_NOT_ALLOWED",
                "The outer batch tool must be a registered batch-capable executor");
        }
        return BatchValidation.accepted();
    }

    private ToolOutput executeThroughRuntimeKernel(String toolName,
                                                   ToolInput toolInput,
                                                   ToolRuntimeRequest request,
                                                   ToolMetadata metadata) {
        McpRuntimeKernel kernel = this.mcpRuntimeKernel;
        Map<String, Object> contractMetadata = metadata == null || metadata.getMetadata() == null
            ? Map.of() : metadata.getMetadata();
        String serviceId = stringValue(contractMetadata.get("serviceId"));
        if (kernel == null || serviceId == null || !isMcpGovernedTool(toolName, metadata)) {
            return toolRegistry.executeEnhancedTool(toolName, toolInput);
        }
        Map<String, Object> context = toolInput.getContext() == null
            ? new LinkedHashMap<>() : new LinkedHashMap<>(toolInput.getContext());
        putIfAbsentText(context, "tenantId", request == null ? null : request.getTenantId());
        putIfAbsentText(context, "userId", request == null ? null : request.getUserId());
        putIfAbsentText(context, "conversationId", request == null ? null : request.getConversationId());
        putIfAbsentText(context, "runtimeMode", request == null ? null : request.getRuntimeMode());
        Object templateId = firstPresent(
            context.get("templateId"),
            toolInput.getParameters() == null ? null : toolInput.getParameters().get("templateId"),
            toolInput.getParameters() == null ? null : toolInput.getParameters().get("template_id"),
            toolInput.getParameters() == null ? null : toolInput.getParameters().get("templateCode"),
            toolInput.getParameters() == null ? null : toolInput.getParameters().get("template_code"),
            toolInput.getParameters() == null ? null : toolInput.getParameters().get("template"));
        String boundTemplateId = templateId == null ? null : String.valueOf(templateId);
        if (boundTemplateId != null) context.putIfAbsent("templateId", boundTemplateId);
        runtimeTemplateBindingEvidence(request)
            .filter(binding -> binding.authorizes(boundTemplateId, toolName))
            .ifPresent(binding -> context.put(McpTemplateBindingEvidence.CONTEXT_KEY, binding.toMap()));
        long deadlineAt = 0L;
        if (request != null && request.getAttributes() != null) {
            Long configuredDeadline = longValue(firstPresent(request.getAttributes().get("__agentDeadlineAt"),
                request.getAttributes().get("diagnosticDeadlineAt"), request.getAttributes().get("deadlineAt")));
            deadlineAt = configuredDeadline == null ? 0L : configuredDeadline;
        }
        CanonicalToolInvocation canonicalInvocation = request == null ? null : request.getCanonicalInvocation();
        if (canonicalInvocation == null) {
            CompiledToolArguments fallbackArguments = toolArgumentCompiler.compileCanonical(
                toolInput.getParameters(), toolInputSchemaResolver.resolvePublished(metadata));
            canonicalInvocation = new CanonicalToolInvocation(null,
                firstText(toolInput.getRequestId(), request == null ? null : request.getRequestId()),
                null, toolName, fallbackArguments, context);
        } else {
            canonicalInvocation = canonicalInvocation.withContext(context);
        }
        McpServiceResult result = kernel.execute(new McpServiceCall(null,
            canonicalInvocation.requestId(), serviceId, canonicalInvocation.toolName(),
            canonicalInvocation.arguments().values(), canonicalInvocation.context(), deadlineAt));
        Map<String, Object> outputMetadata = new LinkedHashMap<>(result.metadata());
        outputMetadata.put("mcpServiceResult", result);
        outputMetadata.put("mcpKernelProtocolVersion", McpRuntimeKernel.KERNEL_PROTOCOL_VERSION);
        outputMetadata.put("mcpAction", result.recoveryAction());
        outputMetadata.put("mcpRetryable", result.retryable());
        ToolOutput output = ToolOutput.builder()
            .success(result.successful())
            .data(result.data())
            .message(result.successful() ? "MCP Runtime OS kernel invocation completed" : null)
            .errorMessage(result.successful() ? null : result.errorMessage())
            .exceptionType(result.successful() ? null : result.errorCode())
            .metadata(outputMetadata)
            .build();
        return output;
    }

    private Optional<McpTemplateBindingEvidence> runtimeTemplateBindingEvidence(ToolRuntimeRequest request) {
        if (request == null || request.getAttributes() == null) return Optional.empty();
        Object executionPlan = request.getAttributes().get("executionPlan");
        if (!(executionPlan instanceof Map<?, ?> plan)) return Optional.empty();
        Object parameters = plan.get("parameters");
        if (!(parameters instanceof Map<?, ?> values)) return Optional.empty();
        return McpTemplateBindingEvidence.from(values.get(McpTemplateBindingEvidence.CONTEXT_KEY));
    }

    private Object losslessMcpAnalysisPayload(ToolOutput output,
                                              ToolMetadata metadata,
                                              ToolRuntimeRequest request) {
        if (output == null || output.getMetadata() == null) return output == null ? null : output.getData();
        Object resultValue = output.getMetadata().remove("mcpServiceResult");
        if (!(resultValue instanceof McpServiceResult result)) return output.getData();
        Object governedRawData = processResultData(result.rawData(), metadata, request, output);
        output.getMetadata().put("mcpRawDataPreserved", result.rawData() != null);
        output.getMetadata().put("mcpAnalysisPayloadSchemaVersion", McpAnalysisPayload.SCHEMA_VERSION);
        return McpAnalysisPayload.from(result, output.getData(), governedRawData).toMap();
    }

    private String validateDiagnosticTemplateAsset(String callId,
                                                   Map<String, Object> arguments,
                                                   String childAssetId,
                                                   boolean authorizationRequired,
                                                   Map<String, String> authorization) {
        if (!authorizationRequired) {
            return null;
        }
        String templateId = templateId(arguments);
        String authorizedAssetId = templateId == null ? null : authorization.get(templateId);
        if (authorizedAssetId == null || authorizedAssetId.isBlank()) {
            return "Diagnostic batch call " + callId
                + " has no discovery-authorized template-to-asset binding";
        }
        if (!authorizedAssetId.equals(childAssetId)) {
            return "Diagnostic batch call " + callId
                + " does not target the asset authorized for template " + templateId;
        }
        return null;
    }

    private boolean batchAttributeBoolean(ToolRuntimeRequest request, String name) {
        return request != null && request.getAttributes() != null
            && Boolean.TRUE.equals(request.getAttributes().get(name));
    }

    private Map<String, String> batchAuthorizedTemplateAssets(ToolRuntimeRequest request) {
        if (request == null || request.getAttributes() == null
            || !(request.getAttributes().get("diagnosticAuthorizedTemplateAssets") instanceof Map<?, ?> raw)) {
            return Map.of();
        }
        Map<String, String> authorized = new LinkedHashMap<>();
        raw.forEach((key, value) -> {
            if (key != null && value != null
                && !String.valueOf(key).isBlank() && !String.valueOf(value).isBlank()) {
                authorized.put(String.valueOf(key), String.valueOf(value));
            }
        });
        return authorized;
    }

    private boolean containsBatchEnvelope(Map<?, ?> values) {
        return values != null && (values.containsKey("calls")
            || values.containsKey("toolCalls")
            || values.containsKey("tool_calls"));
    }

    private ToolCallBatchResult invalidBatchResult(String batchId,
                                                   String startedAt,
                                                   BatchValidation validation) {
        ToolCallResult failure = new ToolCallResult(
            "batch-validation",
            null,
            null,
            null,
            "BLOCKED",
            0L,
            null,
            null,
            errorPayload(validation.errorCode(), validation.message())
        );
        return new ToolCallBatchResult(
            batchId,
            BatchExecutionMode.SEQUENTIAL.name(),
            startedAt,
            Instant.now().toString(),
            "FAILED",
            new ToolCallBatchResult.Summary(0, 0, 0, 1, 0, 0),
            List.of(failure)
        );
    }

    private ToolRuntimeRequest batchChildRequest(ToolRuntimeRequest parent,
                                                 String batchId,
                                                 String callId,
                                                 String toolName,
                                                 Map<String, Object> arguments,
                                                 int index) {
        Map<String, Object> attributes = new LinkedHashMap<>(
            parent == null || parent.getAttributes() == null ? Map.of() : parent.getAttributes());
        attributes.put("__toolBatchChild", true);
        attributes.put("toolCallBatchId", batchId);
        attributes.put("toolCallBatchCallId", callId);
        attributes.put("toolCallBatchIndex", index);
        Object attempt = firstPresent(
            attributes.get("workflowExecutionAttempt"),
            attributes.get("interpretationPlanAttempt"),
            0
        );
        attributes.put("workflowExecutionAttempt", String.valueOf(attempt) + ".batch-" + index);
        Map<String, Object> childArguments = new LinkedHashMap<>(arguments == null ? Map.of() : arguments);
        Object rawBinding = childArguments.remove(McpTemplateBindingEvidence.CONTEXT_KEY);
        if (parent != null && parent.getAttributes() != null
            && Boolean.TRUE.equals(parent.getAttributes().get("runtimeOwnedTemplateBatch"))) {
            String childTemplateId = normalizeText(stringValue(firstPresent(
                childArguments.get("templateId"),
                childArguments.get("template_id"),
                childArguments.get("templateCode"),
                childArguments.get("template_code"),
                childArguments.get("template")
            )));
            McpTemplateBindingEvidence.from(rawBinding)
                .filter(binding -> binding.authorizes(childTemplateId, toolName))
                .ifPresent(binding -> {
                    Map<String, Object> executionPlan = new LinkedHashMap<>(
                        asMap(attributes.get("executionPlan")));
                    Map<String, Object> parameters = new LinkedHashMap<>(childArguments);
                    parameters.put(McpTemplateBindingEvidence.CONTEXT_KEY, binding.toMap());
                    executionPlan.put("parameters", parameters);
                    attributes.put("executionPlan", executionPlan);
                });
        }
        ToolInput parentInput = parent == null ? null : parent.getToolInput();
        return ToolRuntimeRequest.builder()
            .toolName(toolName)
            .runtimeMode(parent == null ? null : parent.getRuntimeMode())
            .requestId(firstText(parent == null ? null : parent.getRequestId(), batchId) + ":" + callId)
            .conversationId(parent == null ? null : parent.getConversationId())
            .tenantId(parent == null ? null : parent.getTenantId())
            .userId(parent == null ? null : parent.getUserId())
            .allowedTools(parent == null ? List.of() : parent.getAllowedTools())
            .toolInput(ToolInput.builder()
                .requestId(parentInput == null ? null : parentInput.getRequestId())
                .conversationId(parentInput == null ? null : parentInput.getConversationId())
                .userId(parentInput == null ? null : parentInput.getUserId())
                .parameters(childArguments)
                .context(parentInput == null || parentInput.getContext() == null
                    ? new LinkedHashMap<>()
                    : new LinkedHashMap<>(parentInput.getContext()))
                .build())
            .attributes(attributes)
            .build();
    }

    private boolean batchCapableTool(String toolName) {
        ToolMetadata metadata = toolName == null || toolRegistry == null
            ? null : toolRegistry.getToolMetadata(toolName);
        return ToolCallBatchSchema.supports(toolName, metadata);
    }

    private ToolCallResult skippedBatchResult(ToolRuntimeRequest context,
                                              String batchId,
                                              int index,
                                              String callId,
                                              String toolName,
                                              Map<String, Object> arguments,
                                              String status,
                                              String errorCode,
                                              String message) {
        return new ToolCallResult(
            batchAttributeText(context, "diagnosticRunId"),
            batchId,
            callId,
            callId,
            toolName,
            normalizeToolSemanticKey(toolName),
            templateId(arguments),
            templateCode(arguments),
            assetId(arguments),
            contextValue(arguments, "assetDisplayName", "asset_display_name", "displayName"),
            contextValue(arguments, "assetToolName", "asset_tool_name"),
            index + 1,
            false,
            status,
            false,
            0L,
            null,
            null,
            errorPayload(errorCode, message)
        );
    }

    private Map<String, Object> errorPayload(String code, String message) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", firstText(code, "TOOL_FAILED"));
        error.put("message", firstText(message, "Tool call failed"));
        return error;
    }

    private Map<String, Object> errorPayload(String code,
                                             String message,
                                             Map<String, Object> details) {
        Map<String, Object> error = errorPayload(code, message);
        if (details != null && !details.isEmpty()) {
            error.put("details", new LinkedHashMap<>(details));
        }
        return error;
    }

    private String templateCode(Map<String, Object> arguments) {
        return stringValue(firstPresent(
            arguments == null ? null : arguments.get("templateCode"),
            arguments == null ? null : arguments.get("template_code"),
            arguments == null ? null : arguments.get("templateId"),
            arguments == null ? null : arguments.get("template_id"),
            arguments == null ? null : arguments.get("template")
        ));
    }

    private boolean emptyResult(Object value) {
        if (value == null) {
            return true;
        }
        if (value instanceof Map<?, ?> map) {
            return map.isEmpty();
        }
        if (value instanceof Iterable<?> iterable) {
            return !iterable.iterator().hasNext();
        }
        if (value instanceof CharSequence text) {
            return text.toString().isBlank();
        }
        return false;
    }

    private String templateId(Map<String, Object> arguments) {
        return stringValue(firstPresent(
            arguments == null ? null : arguments.get("templateId"),
            arguments == null ? null : arguments.get("template_id"),
            arguments == null ? null : arguments.get("templateCode"),
            arguments == null ? null : arguments.get("template_code"),
            arguments == null ? null : arguments.get("template")
        ));
    }

    private String contextValue(Map<String, Object> arguments, String... keys) {
        if (arguments == null || keys == null) {
            return null;
        }
        Object context = firstPresent(arguments.get("executionContext"), arguments.get("mcpExecutionContext"));
        if (context instanceof Map<?, ?> map) {
            for (String key : keys) {
                Object value = map.get(key);
                if (value != null && !String.valueOf(value).isBlank()) {
                    return String.valueOf(value);
                }
            }
        }
        for (String key : keys) {
            Object value = arguments.get(key);
            if (value != null && !String.valueOf(value).isBlank()) {
                return String.valueOf(value);
            }
        }
        return null;
    }

    private int batchAttributeInt(ToolRuntimeRequest request, String key, int fallback) {
        Object value = request == null || request.getAttributes() == null
            ? null
            : request.getAttributes().get(key);
        Integer parsed = integerValue(value);
        return parsed == null || parsed < 0 ? fallback : parsed;
    }

    private String batchAttributeText(ToolRuntimeRequest request, String key) {
        Object value = request == null || request.getAttributes() == null
            ? null
            : request.getAttributes().get(key);
        return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value);
    }

    private List<String> batchAttributeStrings(ToolRuntimeRequest request, String key) {
        Object value = request == null || request.getAttributes() == null
            ? null
            : request.getAttributes().get(key);
        if (!(value instanceof Iterable<?> iterable)) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (Object item : iterable) {
            if (item != null && !String.valueOf(item).isBlank()) {
                values.add(String.valueOf(item));
            }
        }
        return List.copyOf(values);
    }

    private String workflowBlockedReason(String message) {
        String normalized = message == null ? "" : message.toLowerCase(Locale.ROOT);
        if (normalized.contains("required previous steps") || normalized.contains("dependency not completed")) {
            return "workflow_dependency_unsatisfied";
        }
        if (normalized.contains("max_steps")) {
            return "workflow_execution_budget_exhausted";
        }
        if (normalized.contains("condition is not satisfied")) {
            return "workflow_condition_unsatisfied";
        }
        return "workflow_policy_denied";
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
        WorkflowState attemptState = workflowStates.computeIfAbsent(
            workflowAttemptStateKey(request, workflowName),
            ignored -> new WorkflowState()
        );
        Set<String> completed = completedTools(request, state);
        Set<String> attempted = attemptedTools(request, attemptState);
        Set<String> completedFacts = completedWorkflowFacts(request, state);
        Set<String> targetRefs = workflowTargetRefs(request);
        List<String> matchedRules = new ArrayList<>();
        matchedRules.add("workflow." + firstText(workflowName, "global") + ".active");

        McpWorkflowProperties.ExecutionStrategy strategy = workflow == null
            ? new McpWorkflowProperties.ExecutionStrategy()
            : workflow.getExecutionStrategy();
        int maxSteps = strategy == null ? 0 : strategy.getMaxSteps();
        if (maxSteps > 0 && attemptState.attemptedSteps.get() + 1 > maxSteps) {
            return new WorkflowDecision(true, workflowName, stateKey, ToolRuntimeAction.DENY,
                "MCP workflow exceeded max_steps=" + maxSteps, matchedRules);
        }
        if (strategy != null && strategy.isStopOnError() && attemptState.failed.get()) {
            return new WorkflowDecision(true, workflowName, stateKey, ToolRuntimeAction.DENY,
                "MCP workflow is stopped because a previous required step failed", matchedRules);
        }

        List<String> dependencies = new ArrayList<>();
        List<String> authoritativeDependencies = authoritativeWorkflowDependencies(request, toolName);
        boolean authoritativeSequence = authoritativeDependencies != null
            && authoritativeWorkflowHasEdges(request);
        boolean authoritativeOptionalTool = authoritativeDependencies == null
            && authoritativeWorkflowConfigured(request)
            && workflow != null
            && workflowStep(workflow, toolName) != null
            && !workflowStep(workflow, toolName).isRequired();
        if (globalDependency != null && globalDependency.getDependsOn() != null) {
            dependencies.addAll(globalDependency.getDependsOn());
            matchedRules.add("tool_dependencies." + toolName + "=" + globalDependency.getDependsOn());
        }
        if (globalDependency != null && globalDependency.getRequiredDependsOn() != null) {
            dependencies.addAll(globalDependency.getRequiredDependsOn());
            matchedRules.add("tool_dependencies." + toolName + ".required=" + globalDependency.getRequiredDependsOn());
        }
        if (globalDependency != null && globalDependency.getOptionalDependsOn() != null && !globalDependency.getOptionalDependsOn().isEmpty()) {
            matchedRules.add("tool_dependencies." + toolName + ".optional=" + globalDependency.getOptionalDependsOn());
        }

        McpWorkflowProperties.WorkflowStep currentStep = workflow == null ? null : workflowStep(workflow, toolName);
        if (workflow != null) {
            if (currentStep == null) {
                return new WorkflowDecision(true, workflowName, stateKey, ToolRuntimeAction.DENY,
                    "Tool " + toolName + " is not part of MCP workflow " + workflowName, matchedRules);
            }
            if ((!authoritativeSequence || authoritativeOptionalTool)
                && currentStep.getDependsOn() != null) {
                dependencies.addAll(currentStep.getDependsOn());
            }
            if (currentStep.getOptionalDependsOn() != null && !currentStep.getOptionalDependsOn().isEmpty()) {
                matchedRules.add("workflow." + workflowName + "." + toolName + ".optionalDependsOn=" + currentStep.getOptionalDependsOn());
            }
            if (!authoritativeSequence && !authoritativeOptionalTool) {
                WorkflowDecision sequenceDecision = validateWorkflowSequence(
                    workflowName,
                    workflow,
                    currentStep,
                    toolName,
                    completed,
                    attempted,
                    completedFacts,
                    targetRefs,
                    strategy,
                    matchedRules,
                    stateKey
                );
                if (sequenceDecision.action() == ToolRuntimeAction.DENY) {
                    return sequenceDecision;
                }
            } else {
                if (authoritativeDependencies != null) {
                    dependencies.addAll(authoritativeDependencies);
                    matchedRules.add("authoritative_workflow_dag." + toolName + "=" + authoritativeDependencies);
                } else {
                    matchedRules.add("authoritative_workflow_dag.optional_tool." + toolName
                        + "=explicit_dependencies_only");
                }
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
            .filter(dependency -> !workflowDependencySatisfied(
                completed,
                completedFacts,
                dependency,
                targetRefs
            ))
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
            case "inherit", "inherit_policy" -> null;
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

    private boolean authoritativeWorkflowHasEdges(ToolRuntimeRequest request) {
        Object rawDag = request == null || request.getAttributes() == null
            ? null : request.getAttributes().get("authoritativeWorkflowDag");
        if (!(rawDag instanceof Collection<?> nodes)) {
            return false;
        }
        return nodes.stream()
            .filter(Map.class::isInstance)
            .map(Map.class::cast)
            .map(node -> node.get("dependsOnTools"))
            .filter(Collection.class::isInstance)
            .map(Collection.class::cast)
            .anyMatch(dependencies -> !dependencies.isEmpty());
    }

    private boolean summarizeAvailableResults(ToolRuntimeRequest request) {
        Map<String, Object> attributes = request == null || request.getAttributes() == null
            ? Map.of() : request.getAttributes();
        Map<String, Object> policy = asMap(attributes.get("resultHandlingPolicy"));
        if (policy.isEmpty()) {
            return true;
        }
        boolean failOnChild = Boolean.TRUE.equals(booleanValue(firstPresent(
            policy.get("failRunWhenAnyChildFails"), policy.get("fail_run_when_any_child_fails"))));
        Object continueValue = firstPresent(
            policy.get("continueOnPartialSuccess"), policy.get("continue_on_partial_success"));
        boolean continueOnPartial = continueValue == null
            || Boolean.TRUE.equals(booleanValue(continueValue));
        return !failOnChild && continueOnPartial;
    }

    private boolean authoritativeWorkflowConfigured(ToolRuntimeRequest request) {
        Object rawDag = request == null || request.getAttributes() == null
            ? null : request.getAttributes().get("authoritativeWorkflowDag");
        return rawDag instanceof Collection<?> nodes && !nodes.isEmpty();
    }

    /** Returns null when the current tool is not governed by the authoritative DAG. */
    private List<String> authoritativeWorkflowDependencies(ToolRuntimeRequest request, String toolName) {
        Object rawDag = request == null || request.getAttributes() == null
            ? null : request.getAttributes().get("authoritativeWorkflowDag");
        if (!(rawDag instanceof Collection<?> nodes)) {
            return null;
        }
        for (Object value : nodes) {
            if (!(value instanceof Map<?, ?> node)) {
                continue;
            }
            String configuredTool = stringValue(firstPresent(node.get("tool"), node.get("toolName")));
            if (!sameTool(configuredTool, toolName)) {
                continue;
            }
            return stringList(firstPresent(node.get("dependsOnTools"), node.get("depends_on_tools")));
        }
        return null;
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
                                                      Set<String> attempted,
                                                      Set<String> completedFacts,
                                                      Set<String> targetRefs,
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
        boolean failedAttemptAllowsNextStage = strategy != null && !strategy.isStopOnError();
        List<String> missingRequired = workflow.getSteps().stream()
            .filter(step -> step != null && !stepTools(step).isEmpty())
            .filter(McpWorkflowProperties.WorkflowStep::isRequired)
            .filter(step -> stepOrder(step) < currentOrder)
            .flatMap(step -> stepTools(step).stream())
            .filter(requiredTool -> !workflowDependencySatisfied(
                    completed,
                    completedFacts,
                    requiredTool,
                    targetRefs
                )
                && !(failedAttemptAllowsNextStage && containsTool(attempted, requiredTool)))
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
        WorkflowState attemptState = workflowStates.computeIfAbsent(
            workflowAttemptStateKey(
                request,
                firstText(workflowDecision.workflowName(), executionPlan == null ? null : executionPlan.workflow())
            ),
            ignored -> new WorkflowState()
        );
        attemptState.attemptedSteps.incrementAndGet();
        attemptState.attemptedTools.add(toolName);
        if (success) {
            state.completedTools.add(toolName);
            Set<String> targetRefs = workflowTargetRefs(request);
            if (targetRefs.isEmpty()) {
                state.completedFacts.add(workflowFact(toolName, "*"));
            } else {
                targetRefs.forEach(targetRef -> state.completedFacts.add(workflowFact(toolName, targetRef)));
            }
            attemptState.failed.set(false);
        } else {
            attemptState.failed.set(true);
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
    private Object processResultData(Object data, ToolMetadata metadata,
                                     ToolRuntimeRequest request, ToolOutput output) {
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
        Object masked = fields.isEmpty() ? data : maskValue(data, fields);
        return boundToolOutput(masked, request, output);
    }

    private Object boundToolOutput(Object data, ToolRuntimeRequest request, ToolOutput output) {
        // MCP execution results are authoritative evidence. The Runtime must
        // preserve the complete value; output size is controlled by the
        // registered template/remote command, not by this Java layer.
        return data;
    }

    /**
     * Resolves a Runtime-owned large-output reference for in-process evidence review.
     * The bounded reference remains the value used for persistence, audit, and UI
     * transport; reviewers use the complete stored value so a preview cannot be
     * mistaken for the tool's full result contract.
     */
    public Object resolveOutputForEvidenceReview(ToolOutput output) {
        if (output == null) {
            return null;
        }
        Object data = output.getData();
        if (!(data instanceof Map<?, ?> reference) || !isResolvableExternalizedReference(reference)) {
            return data;
        }
        Object documentIdValue = reference.get("documentId");
        if (documentIdValue == null || String.valueOf(documentIdValue).isBlank()) {
            return data;
        }
        String documentId = String.valueOf(documentIdValue);
        String evidenceId = stringValue(reference.get("evidenceId"));
        Map<String, Object> metadata = output.getMetadata();
        if (metadata == null
            || !documentId.equals(stringValue(metadata.get("outputDocumentId")))
            || evidenceId == null
            || !evidenceId.equals(stringValue(metadata.get("outputEvidenceId")))) {
            log.warn("Rejected unverified externalized tool output reference documentId={}", documentId);
            return data;
        }
        return resolveVerifiedExternalOutput(reference, documentId, evidenceId);
    }

    /**
     * Resolves Runtime-created large outputs nested inside an ordered batch.
     * Child ToolOutput metadata is deliberately not copied into the public batch
     * contract, so verification is performed against the signed-by-content
     * document/evidence identifiers that Runtime itself placed in the child.
     */
    public ToolCallBatchResult resolveBatchOutputForEvidenceReview(ToolCallBatchResult batch) {
        if (batch == null || batch.results() == null || batch.results().isEmpty()) {
            return batch;
        }
        boolean changed = false;
        List<ToolCallResult> resolvedResults = new ArrayList<>(batch.results().size());
        for (ToolCallResult child : batch.results()) {
            Object resolved = resolveRuntimeOwnedBatchChildOutput(child);
            changed |= child != null && resolved != child.output();
            if (child == null || resolved == child.output()) {
                resolvedResults.add(child);
                continue;
            }
            resolvedResults.add(new ToolCallResult(
                child.diagnosticRunId(), child.batchId(), child.callId(), child.checkId(),
                child.toolName(), child.normalizedToolName(), child.templateId(), child.templateCode(),
                child.assetId(), child.assetDisplayName(), child.assetToolName(), child.sequence(),
                child.evidenceUsable(), child.status(), child.invoked(), child.durationMs(),
                child.evidenceId(), resolved, child.error(), child.evidencePolicy(), child.evidenceQuality()
            ));
        }
        if (!changed) {
            return batch;
        }
        return new ToolCallBatchResult(
            batch.batchId(), batch.executionMode(), batch.startedAt(), batch.completedAt(),
            batch.status(), batch.cardinality(), batch.summary(), resolvedResults
        );
    }

    private Object resolveRuntimeOwnedBatchChildOutput(ToolCallResult child) {
        Object data = child == null ? null : child.output();
        if (!(data instanceof Map<?, ?> reference) || !isResolvableExternalizedReference(reference)) {
            return data;
        }
        String documentId = stringValue(reference.get("documentId"));
        String evidenceId = stringValue(reference.get("evidenceId"));
        if (documentId == null || !documentId.startsWith("tool-output:")
            || evidenceId == null || !evidenceId.startsWith("tool:")
            || child.callId() == null
            || !documentId.contains(":" + child.callId() + ":")) {
            return data;
        }
        return resolveVerifiedExternalOutput(reference, documentId, evidenceId);
    }

    /**
     * The public MCP bridge may wrap the Runtime-owned reference in a summary
     * contract and expose {@code summaryTruncated} instead of
     * {@code outputTruncated}. Both flags describe the same loss of inline
     * evidence and must use the same verified document lookup path. Keeping
     * this predicate protocol-level prevents executor-specific branches.
     */
    private boolean isResolvableExternalizedReference(Map<?, ?> reference) {
        if (reference == null
            || (!Boolean.TRUE.equals(reference.get("outputExternal"))
                && !Boolean.TRUE.equals(reference.get("runtimeReviewAvailable")))) {
            return false;
        }
        return Boolean.TRUE.equals(reference.get("outputTruncated"))
            || Boolean.TRUE.equals(reference.get("summaryTruncated"));
    }

    private Object resolveVerifiedExternalOutput(Map<?, ?> reference, String documentId, String evidenceId) {
        String cached = cachedReviewPayload(documentId);
        if (cached != null) {
            return decodeVerifiedReviewPayload(reference, documentId, evidenceId, cached, "runtime-cache");
        }
        EvidencePayloadStorePort store = evidenceStore;
        if (store == null || !store.isEnabled()) {
            log.warn("Externalized tool output unavailable for evidence review documentId={} "
                    + "runtimeCacheHit=false externalStoreEnabled=false",
                documentId);
            return reference;
        }
        try {
            Optional<String> stored = store.get(documentId);
            if (stored.isEmpty() || stored.get().isBlank()) {
                log.warn("Externalized tool output unavailable for evidence review documentId={}", documentId);
                return reference;
            }
            return decodeVerifiedReviewPayload(reference, documentId, evidenceId, stored.get(), "external-store");
        } catch (Exception ex) {
            log.warn("Failed to resolve externalized tool output for evidence review documentId={} error={}",
                documentId, ex.getMessage());
            return reference;
        }
    }

    private Object decodeVerifiedReviewPayload(Map<?, ?> reference,
                                               String documentId,
                                               String evidenceId,
                                               String json,
                                               String source) {
        try {
            String storedHash = sha256(json);
            if (!evidenceId.endsWith(storedHash.substring(0, 24))) {
                log.warn("Externalized tool output failed integrity verification documentId={} source={}",
                    documentId, source);
                return reference;
            }
            Object resolved = objectMapper.readValue(json, Object.class);
            log.info("Resolved full tool output for evidence review documentId={} source={} bytes={}",
                documentId, source, json.getBytes(StandardCharsets.UTF_8).length);
            return resolved;
        } catch (Exception ex) {
            log.warn("Failed to decode tool output for evidence review documentId={} source={} error={}",
                documentId, source, ex.getMessage());
            return reference;
        }
    }

    private boolean cacheReviewPayload(String documentId, String json) {
        if (documentId == null || json == null) return false;
        long bytes = json.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > REVIEW_CACHE_MAX_BYTES) return false;
        synchronized (reviewCacheLock) {
            String previous = reviewPayloads.put(documentId, json);
            if (previous != null) reviewPayloadBytes -= previous.getBytes(StandardCharsets.UTF_8).length;
            reviewPayloadBytes += bytes;
            while (reviewPayloads.size() > REVIEW_CACHE_MAX_ENTRIES
                || reviewPayloadBytes > REVIEW_CACHE_MAX_BYTES) {
                Map.Entry<String, String> eldest = reviewPayloads.entrySet().iterator().next();
                reviewPayloadBytes -= eldest.getValue().getBytes(StandardCharsets.UTF_8).length;
                reviewPayloads.remove(eldest.getKey());
            }
        }
        return true;
    }

    private String cachedReviewPayload(String documentId) {
        synchronized (reviewCacheLock) {
            return reviewPayloads.get(documentId);
        }
    }

    /**
     * Preserves the small, redacted control-plane portion of discovery results
     * when the evidence payload itself has to be externalized. The projection is
     * derived from MCP output contracts, never from model arguments. It keeps the
     * identity needed for asset routing and the registered contract needed for
     * template execution, while excluding connection coordinates and executable
     * bodies such as SQL or shell commands.
     */
    private Map<String, Object> routingProjection(Object data) {
        if (!(data instanceof Map<?, ?> source)) {
            return Map.of();
        }
        Map<String, Object> projection = new LinkedHashMap<>();
        Object schemaVersion = source.get("schemaVersion");
        if (schemaVersion != null) {
            projection.put("sourceSchemaVersion", String.valueOf(schemaVersion));
        }
        List<Map<String, Object>> projectedAssets = projectedAssets(source.get("assets"));
        if (!projectedAssets.isEmpty()) {
            projection.put("assets", List.copyOf(projectedAssets));
        }
        List<Map<String, Object>> projectedTemplates = projectedTemplates(source);
        if (!projectedTemplates.isEmpty()) {
            projection.put("templates", List.copyOf(projectedTemplates));
        }
        Map<String, Object> selectedAsset = projectedSelectedAsset(source);
        if (!selectedAsset.isEmpty()) {
            projection.put("queryIr", Map.of("asset", Map.of("selected", selectedAsset)));
        }
        if (projectedAssets.isEmpty() && projectedTemplates.isEmpty() && selectedAsset.isEmpty()) {
            return Map.of();
        }
        projection.put("returnedCount", !projectedTemplates.isEmpty()
            ? projectedTemplates.size() : projectedAssets.size());
        return Map.copyOf(projection);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> projectedSelectedAsset(Map<?, ?> source) {
        Object queryIr = source.get("queryIr");
        if (!(queryIr instanceof Map<?, ?> queryMap)
            || !(queryMap.get("asset") instanceof Map<?, ?> assetEnvelope)
            || !(assetEnvelope.get("selected") instanceof Map<?, ?> selected)) {
            return Map.of();
        }
        Map<String, Object> identity = new LinkedHashMap<>();
        copyRoutingField(identity, selected, "id", "id", "assetId");
        copyRoutingField(identity, selected, "name", "name", "assetName", "displayName");
        copyRoutingField(identity, selected, "displayName", "displayName", "name");
        copyRoutingField(identity, selected, "environment", "environment", "env");
        copyRoutingField(identity, selected, "toolName", "toolName", "tool_name");
        copyRoutingField(identity, selected, "databaseRole", "databaseRole", "database_role");
        copyRoutingField(identity, selected, "type", "type", "assetType");
        return identity.isEmpty() ? Map.of() : Map.copyOf(identity);
    }

    private List<Map<String, Object>> projectedAssets(Object value) {
        if (!(value instanceof Iterable<?> assets)) {
            return List.of();
        }
        List<Map<String, Object>> projectedAssets = new ArrayList<>();
        for (Object item : assets) {
            if (!(item instanceof Map<?, ?> candidate) || !(candidate.get("asset") instanceof Map<?, ?> asset)) {
                continue;
            }
            Map<String, Object> identity = new LinkedHashMap<>();
            copyRoutingField(identity, asset, "id", "id", "assetId");
            copyRoutingField(identity, asset, "name", "name", "displayName", "assetName");
            copyRoutingField(identity, asset, "displayName", "displayName", "name");
            copyRoutingField(identity, asset, "environment", "environment", "env");
            copyRoutingField(identity, asset, "toolName", "toolName", "tool_name");
            copyRoutingField(identity, asset, "databaseRole", "databaseRole", "database_role");
            copyRoutingField(identity, asset, "type", "type", "assetType");
            if (!identity.isEmpty()) {
                projectedAssets.add(Map.of("asset", Map.copyOf(identity)));
            }
        }
        return projectedAssets;
    }

    private List<Map<String, Object>> projectedTemplates(Map<?, ?> source) {
        List<Object> candidates = new ArrayList<>();
        addProjectionCandidates(candidates, source.get("templates"));
        Object results = source.get("results");
        if (results instanceof Iterable<?> iterable) {
            for (Object result : iterable) {
                if (!(result instanceof Map<?, ?> map)) continue;
                addProjectionCandidates(candidates, map.get("associatedTemplates"));
                addProjectionCandidates(candidates, map.get("associated_templates"));
                addProjectionCandidates(candidates, map.get("templates"));
            }
        }
        List<Map<String, Object>> projected = new ArrayList<>();
        for (Object item : candidates) {
            if (!(item instanceof Map<?, ?> template)) continue;
            Map<String, Object> contract = new LinkedHashMap<>();
            for (String key : List.of(
                "schemaVersion", "id", "templateId", "template_id", "code", "template",
                "mcpToolName", "name", "title", "category", "businessGroup", "intent", "tags",
                "rank", "relevanceScore", "decisionScore", "matchReasons", "intentSignals",
                "capabilitySpec", "outputSchema", "dependencySpec", "parameterSchema", "inputSchema",
                "requiredParameters", "parameterContract", "executionContext", "datasourceAsset",
                "sqlExecutionBinding", "executionBinding", "execution", "invocationExample", "enabled"
            )) {
                Object field = template.get(key);
                if (field != null) contract.put(key, field);
            }
            Object description = template.get("description");
            if (description != null) {
                contract.put("description", boundedProjectionText(description, 2_000));
            }
            if (!contract.isEmpty()) projected.add(Map.copyOf(contract));
        }
        return projected;
    }

    private void addProjectionCandidates(List<Object> target, Object value) {
        if (!(value instanceof Iterable<?> iterable)) return;
        for (Object item : iterable) target.add(item);
    }

    private String boundedProjectionText(Object value, int maxChars) {
        String text = String.valueOf(value);
        return text.length() <= maxChars ? text : text.substring(0, maxChars) + "...(truncated)";
    }

    private void copyRoutingField(Map<String, Object> target, Map<?, ?> source,
                                  String targetKey, String... sourceKeys) {
        for (String sourceKey : sourceKeys) {
            Object value = source.get(sourceKey);
            if (value != null && !String.valueOf(value).isBlank()) {
                target.put(targetKey, value);
                return;
            }
        }
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
        boolean toolMatched = workflow.getSteps() != null
            && workflow.getSteps().stream().anyMatch(step -> stepContainsTool(step, toolName));
        // Runtime settings (environment, result handling, budgets) are persisted in
        // the same JSON object as an optional static workflow.  A generated
        // InterpretationPlan must not turn that settings-only object into an empty
        // workflow merely because executionPlan.workflow is "interpretation_plan".
        // Membership is authoritative only when the persisted workflow actually
        // declares the current tool as one of its steps.
        if (!toolMatched) {
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
            step.setOptionalDependsOn(stringList(firstPresent(
                rawStep.get("optionalDependsOn"),
                rawStep.get("optional_depends_on"),
                rawStep.get("optionalDependencies"),
                rawStep.get("optional_dependencies")
            )));
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
            step.setOptionalDependsOn(step.getOptionalDependsOn() == null ? List.of() : step.getOptionalDependsOn().stream()
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
            List<String> dependsOn;
            List<String> requiredDependsOn = List.of();
            List<String> optionalDependsOn = List.of();
            if (entry.getValue() instanceof Map<?, ?> dependencyMap) {
                dependsOn = stringList(firstPresent(dependencyMap.get("dependsOn"), dependencyMap.get("depends_on")));
                requiredDependsOn = stringList(firstPresent(
                    dependencyMap.get("requiredDependsOn"),
                    dependencyMap.get("required_depends_on"),
                    dependencyMap.get("requiredDependencies"),
                    dependencyMap.get("required_dependencies")
                ));
                optionalDependsOn = stringList(firstPresent(
                    dependencyMap.get("optionalDependsOn"),
                    dependencyMap.get("optional_depends_on"),
                    dependencyMap.get("optionalDependencies"),
                    dependencyMap.get("optional_dependencies")
                ));
            } else {
                dependsOn = stringList(entry.getValue());
            }
            if (dependsOn.isEmpty() && requiredDependsOn.isEmpty() && optionalDependsOn.isEmpty()) {
                return null;
            }
            McpWorkflowProperties.ToolDependencySpec spec = new McpWorkflowProperties.ToolDependencySpec();
            spec.setDependsOn(dependsOn);
            spec.setRequiredDependsOn(requiredDependsOn);
            spec.setOptionalDependsOn(optionalDependsOn);
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
        if (first != null
            && ((first.getDependsOn() != null && !first.getDependsOn().isEmpty())
            || (first.getRequiredDependsOn() != null && !first.getRequiredDependsOn().isEmpty())
            || (first.getOptionalDependsOn() != null && !first.getOptionalDependsOn().isEmpty()))) {
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
        String configured = normalizeToolSemanticKey(configuredTool);
        String actual = normalizeToolSemanticKey(actualTool);
        return configured.equals(actual)
            || configured.endsWith("_" + actual)
            || actual.endsWith("_" + configured);
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
        return normalizePolicyKey(toolName);
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

    private Set<String> completedWorkflowFacts(ToolRuntimeRequest request, WorkflowState state) {
        Set<String> facts = new HashSet<>(state == null ? Set.of() : state.completedFacts);
        Object configured = request == null || request.getAttributes() == null
            ? null
            : request.getAttributes().get("workflowCompletedFacts");
        if (configured instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> rawFact) {
                    Map<String, Object> fact = new LinkedHashMap<>();
                    rawFact.forEach((key, value) -> {
                        if (key != null) {
                            fact.put(String.valueOf(key), value);
                        }
                    });
                    String tool = stringValue(firstPresent(
                        fact.get("tool"),
                        fact.get("toolName"),
                        fact.get("capability")
                    ));
                    String target = stringValue(firstPresent(
                        fact.get("targetRef"),
                        fact.get("targetAssetId"),
                        fact.get("assetId"),
                        fact.get("assetName")
                    ));
                    if (tool != null && !tool.isBlank()) {
                        facts.add(workflowFact(tool, firstText(target, "*")));
                    }
                } else {
                    String encoded = stringValue(item);
                    if (encoded != null && !encoded.isBlank()) {
                        facts.add(encoded.trim());
                    }
                }
            }
        }
        return facts;
    }

    private boolean workflowDependencySatisfied(Set<String> completedTools,
                                                Set<String> completedFacts,
                                                String dependencyTool,
                                                Set<String> targetRefs) {
        String semanticTool = normalizeToolSemanticKey(dependencyTool);
        Set<String> factTargets = new HashSet<>();
        if (completedFacts != null) {
            completedFacts.stream()
                .filter(Objects::nonNull)
                .map(String::valueOf)
                .filter(fact -> {
                    int separator = fact.indexOf("::target=");
                    return separator > 0 && sameTool(fact.substring(0, separator), semanticTool);
                })
                .map(fact -> fact.substring(fact.indexOf("::target=") + "::target=".length()))
                .forEach(factTargets::add);
        }
        if (factTargets.isEmpty()) {
            return containsTool(completedTools, dependencyTool);
        }
        if (targetRefs == null || targetRefs.isEmpty() || factTargets.contains("*")) {
            return true;
        }
        return targetRefs.stream()
            .map(this::normalizeWorkflowTargetRef)
            .anyMatch(factTargets::contains);
    }

    private String workflowFact(String toolName, String targetRef) {
        return normalizeToolSemanticKey(toolName)
            + "::target="
            + firstText(normalizeWorkflowTargetRef(targetRef), "*");
    }

    private Set<String> workflowTargetRefs(ToolRuntimeRequest request) {
        Set<String> refs = new HashSet<>();
        if (request == null) {
            return refs;
        }
        collectWorkflowTargetRefs(refs, request.getAttributes());
        if (request.getToolInput() != null) {
            collectWorkflowTargetRefs(refs, request.getToolInput().getParameters());
            collectWorkflowTargetRefs(refs, request.getToolInput().getContext());
        }
        refs.removeIf(value -> value == null || value.isBlank());
        return refs;
    }

    private void collectWorkflowTargetRefs(Set<String> refs, Map<String, ?> values) {
        if (refs == null || values == null || values.isEmpty()) {
            return;
        }
        for (String key : List.of(
            "workflowTargetRef", "targetAssetId", "targetAssetName",
            "assetId", "assetName", "databaseAssetId", "databaseAssetName"
        )) {
            Object value = values.get(key);
            String normalized = normalizeWorkflowTargetRef(stringValue(value));
            if (normalized != null) {
                refs.add(normalized);
            }
        }
        for (String nestedKey : List.of(
            "executionContext", "execution_context", "filters", "target",
            "defaultDataAsset", "mcpExecutionContext", "workflowContext"
        )) {
            Object nested = values.get(nestedKey);
            if (nested instanceof Map<?, ?> rawNested) {
                Map<String, Object> nestedValues = new LinkedHashMap<>();
                rawNested.forEach((key, value) -> {
                    if (key != null) {
                        nestedValues.put(String.valueOf(key), value);
                    }
                });
                collectWorkflowTargetRefs(refs, nestedValues);
            }
        }
    }

    private String normalizeWorkflowTargetRef(String value) {
        String normalized = normalizeText(value);
        return normalized == null ? null : normalized.toLowerCase(Locale.ROOT);
    }

    private Set<String> attemptedTools(ToolRuntimeRequest request, WorkflowState state) {
        Set<String> attempted = new HashSet<>(state == null ? Set.of() : state.attemptedTools);
        Object configured = request == null || request.getAttributes() == null
            ? null
            : firstPresent(request.getAttributes().get("workflowAttemptedTools"), request.getAttributes().get("attemptedTools"));
        if (configured instanceof List<?> list) {
            list.stream()
                .map(this::stringValue)
                .filter(value -> value != null)
                .filter(value -> !value.isBlank())
                .forEach(attempted::add);
        }
        return attempted;
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

    private String workflowAttemptStateKey(ToolRuntimeRequest request, String workflowName) {
        return workflowStateKey(request, workflowName)
            + "::attempt="
            + firstText(workflowExecutionAttempt(request), "0");
    }

    private String workflowExecutionAttempt(ToolRuntimeRequest request) {
        if (request == null || request.getAttributes() == null) {
            return null;
        }
        Object value = firstPresent(
            request.getAttributes().get("workflowExecutionAttempt"),
            request.getAttributes().get("interpretationPlanAttempt"),
            request.getAttributes().get("workflowAttempt")
        );
        return normalizeText(stringValue(value));
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

    private record BatchValidation(boolean present,
                                   boolean valid,
                                   String errorCode,
                                   String message) {

        private static BatchValidation absent() {
            return new BatchValidation(false, true, null, null);
        }

        private static BatchValidation accepted() {
            return new BatchValidation(true, true, null, null);
        }

        private static BatchValidation invalid(String errorCode, String message) {
            return new BatchValidation(true, false, errorCode, message);
        }
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

    private Long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return value == null ? null : Long.parseLong(String.valueOf(value).trim());
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
     * Performs the infer data scope operation.
     *
     * @param parameters the parameters value
     * @return the operation result
     */
    private String inferDataScope(Map<String, Object> parameters) {
        if (parameters == null || parameters.isEmpty()) {
            return "unknown";
        }
        String scope = firstText(
            firstText(stringValue(parameters.get("data_scope")), stringValue(parameters.get("dataScope"))),
            firstText(stringValue(parameters.get("scope")), stringValue(parameters.get("level")))
        );
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
            Future<?> audit;
            try {
                audit = auditExecutor.submit(() -> sink.record(record));
            } catch (RejectedExecutionException ex) {
                log.warn("Tool runtime audit queue is full; dropping audit tool={} requestId={}",
                    request == null ? null : request.getToolName(), request == null ? null : request.getRequestId());
                continue;
            }
            try {
                audit.get(properties.safeAuditSinkTimeoutMs(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException ex) {
                audit.cancel(true);
                log.warn("Tool runtime audit persistence timed out tool={} requestId={} timeoutMs={}",
                    request == null ? null : request.getToolName(), request == null ? null : request.getRequestId(),
                    properties.safeAuditSinkTimeoutMs());
            } catch (InterruptedException ex) {
                audit.cancel(true);
                Thread.currentThread().interrupt();
            } catch (ExecutionException ex) {
                log.warn("Tool runtime audit persistence failed tool={} requestId={} error={}",
                    request == null ? null : request.getToolName(), request == null ? null : request.getRequestId(),
                    ex.getCause() == null ? ex.getMessage() : ex.getCause().getMessage());
            }
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

    private String assetId(Map<String, Object> arguments) {
        if (arguments == null || arguments.isEmpty()) {
            return null;
        }
        Object direct = firstPresent(arguments.get("assetId"), arguments.get("asset_id"));
        if (direct != null && !String.valueOf(direct).isBlank()) {
            return String.valueOf(direct);
        }
        Object context = firstPresent(arguments.get("executionContext"), arguments.get("mcpExecutionContext"));
        if (context instanceof Map<?, ?> map) {
            Object nested = firstPresent(map.get("assetId"), map.get("asset_id"));
            if (nested != null && !String.valueOf(nested).isBlank()) {
                return String.valueOf(nested);
            }
        }
        return null;
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
        private final Set<String> completedFacts = ConcurrentHashMap.newKeySet();
        private final Set<String> attemptedTools = ConcurrentHashMap.newKeySet();
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
