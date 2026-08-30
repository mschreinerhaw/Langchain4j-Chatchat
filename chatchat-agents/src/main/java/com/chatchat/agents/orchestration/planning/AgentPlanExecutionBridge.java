package com.chatchat.agents.orchestration.planning;

import com.chatchat.agents.runtime.AgentRunRequest;
import com.chatchat.agents.runtime.AgentRunResult;
import com.chatchat.agents.runtime.plan.InterpretationPlan;
import com.chatchat.agents.runtime.plan.InterpretationPlanRuntime;
import com.chatchat.agents.runtime.plan.execution.AgentPlanPipelineContinuation;
import com.chatchat.agents.runtime.plan.execution.AgentPlanSuspendedException;
import com.chatchat.agents.runtime.plan.execution.AgentRunExecutionSlice;
import com.chatchat.agents.runtime.plan.execution.PlanExecutionContinuation;
import com.chatchat.common.interaction.InteractionToolTrace;
import com.chatchat.common.kernel.KernelDataScope;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Supplier;

/** Owns thread-confined suspension/resumption state outside the orchestration engine. */
public final class AgentPlanExecutionBridge {
    private final ObjectMapper objectMapper;
    private final String cancellationAttribute;
    private final ThreadLocal<Boolean> enabled = ThreadLocal.withInitial(() -> Boolean.FALSE);
    private final ThreadLocal<AgentRunRequest> activeRequest = new ThreadLocal<>();
    private final ThreadLocal<Resume> activeResume = new ThreadLocal<>();

    public AgentPlanExecutionBridge(ObjectMapper objectMapper, String cancellationAttribute) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.cancellationAttribute = cancellationAttribute;
    }

    public AgentRunExecutionSlice executeUntilSuspension(
        AgentRunRequest request, KernelDataScope scope,
        BiFunction<AgentRunRequest, KernelDataScope, AgentRunResult> execution) {
        enabled.set(Boolean.TRUE);
        try {
            return AgentRunExecutionSlice.completed(execution.apply(request, scope));
        } catch (AgentPlanSuspendedException suspension) {
            return AgentRunExecutionSlice.suspended(suspension.continuation());
        } finally {
            enabled.remove();
        }
    }

    public AgentRunExecutionSlice resume(
        AgentPlanPipelineContinuation continuation,
        InterpretationPlanRuntime.ExecutionResult result,
        KernelDataScope scope,
        BiFunction<AgentRunRequest, KernelDataScope, AgentRunResult> execution) {
        enabled.set(Boolean.TRUE);
        activeResume.set(new Resume(continuation, result));
        try {
            return AgentRunExecutionSlice.completed(execution.apply(continuation.request(), scope));
        } catch (AgentPlanSuspendedException suspension) {
            return AgentRunExecutionSlice.suspended(suspension.continuation());
        } finally {
            activeResume.remove();
            enabled.remove();
        }
    }

    public <T> T withRequest(AgentRunRequest request, Supplier<T> operation) {
        activeRequest.set(request);
        try { return operation.get(); }
        finally { activeRequest.remove(); }
    }

    public AgentPlanPipelineContinuation resumedPipeline() {
        Resume resume = activeResume.get();
        return resume == null ? null : resume.continuation;
    }

    public InterpretationPlanRuntime.ExecutionResult consume(InterpretationPlan plan,
                                                              Object fingerprint) {
        Resume resume = activeResume.get();
        if (resume == null || resume.consumed) return null;
        if (!Objects.equals(resume.planFingerprint, fingerprint)) {
            throw new IllegalStateException("Resumed plan result does not match suspended plan");
        }
        resume.consumed = true;
        return resume.result;
    }

    public void suspend(InterpretationPlan initialPlan, InterpretationPlan currentPlan,
                        InterpretationPlanRuntime.ExecutionRequest executionRequest,
                        int rewriteCount, int maxRewriteTimes,
                        List<InterpretationPlanRuntime.ExecutionResult> attemptResults,
                        List<Map<String, Object>> evidenceHistory,
                        Map<String, Object> runtimeAttributes,
                        List<InteractionToolTrace> traces, List<String> observations,
                        Map<String, Object> metadata, Object planFingerprint) {
        if (!Boolean.TRUE.equals(enabled.get())) return;
        AgentRunRequest source = Objects.requireNonNull(activeRequest.get(),
            "Durable plan suspension requires the originating request");
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("tenantId", executionRequest.tenantId());
        context.put("requestId", executionRequest.requestId());
        context.put("conversationId", executionRequest.conversationId());
        context.put("userId", executionRequest.userId());
        context.put("query", source.getQuery());
        context.put("systemPrompt", source.getSystemPrompt());
        context.put("modelName", source.getModelName());
        context.put("allowedTools", List.copyOf(executionRequest.allowedTools() == null
            ? List.of() : executionRequest.allowedTools()));
        context.put("attributes", durableMap(executionRequest.attributes()));
        int attempt = Math.max(1, rewriteCount + 1);
        String runId = text(source.getRunId(), text(source.getRequestId(), executionRequest.requestId()));
        String id = text(executionRequest.tenantId(), "default") + "::"
            + text(runId, "unscoped") + "::plan-attempt:" + attempt;
        PlanExecutionContinuation planState = new PlanExecutionContinuation(null, id, currentPlan,
            currentPlan.steps().stream().map(InterpretationPlan.Step::id)
                .filter(Objects::nonNull).toList(), List.of(), List.of(), List.of(), 0,
            durableMap(context));
        throw new AgentPlanSuspendedException(new AgentPlanPipelineContinuation(
            null, id, durableRequest(source), initialPlan, currentPlan, planState,
            attempt, rewriteCount, maxRewriteTimes, attemptResults, evidenceHistory,
            traces, observations, durableMap(runtimeAttributes), durableMap(metadata)));
    }

    private AgentRunRequest durableRequest(AgentRunRequest source) {
        return AgentRunRequest.builder().runId(source.getRunId()).query(source.getQuery())
            .tenantId(source.getTenantId()).availableTools(copy(source.getAvailableTools()))
            .systemPrompt(source.getSystemPrompt()).modelName(source.getModelName())
            .boundDocumentIds(copy(source.getBoundDocumentIds()))
            .boundDocumentTags(copy(source.getBoundDocumentTags())).skillId(source.getSkillId())
            .requestId(source.getRequestId()).conversationId(source.getConversationId())
            .userId(source.getUserId()).webSearchResultLimit(source.getWebSearchResultLimit())
            .requiredToolNames(copy(source.getRequiredToolNames()))
            .requireBoundToolCall(source.isRequireBoundToolCall()).maxSteps(source.getMaxSteps())
            .maxToolCalls(source.getMaxToolCalls()).timeoutMs(source.getTimeoutMs())
            .attributes(durableMap(source.getAttributes())).build();
    }

    private List<String> copy(List<String> values) {
        return List.copyOf(values == null ? List.of() : values);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> durableMap(Map<String, Object> source) {
        Map<String, Object> values = new LinkedHashMap<>(source == null ? Map.of() : source);
        values.remove(cancellationAttribute);
        return objectMapper.convertValue(values, Map.class);
    }

    private String text(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static final class Resume {
        private final AgentPlanPipelineContinuation continuation;
        private final InterpretationPlanRuntime.ExecutionResult result;
        private final Object planFingerprint;
        private boolean consumed;
        private Resume(AgentPlanPipelineContinuation continuation,
                       InterpretationPlanRuntime.ExecutionResult result) {
            this.continuation = Objects.requireNonNull(continuation, "continuation");
            this.result = Objects.requireNonNull(result, "result");
            this.planFingerprint = com.chatchat.agents.orchestration.tool.ToolCallFingerprint
                .forPlan(continuation.currentPlan());
        }
    }
}
