package com.chatchat.agents.orchestration.model;

import com.chatchat.agents.orchestration.analysis.context.ContextTokenEstimator;


import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import dev.langchain4j.model.output.TokenUsage;

/** Accounts every model invocation and enforces configured token/cost ceilings. */
@lombok.extern.slf4j.Slf4j
public final class MeteredChatModel implements ChatModel {

    private final ChatModel delegate;

    ChatModel toolDelegate() { return delegate; }
    private final ContextTokenEstimator estimator = new ContextTokenEstimator();
    private final Map<String, Object> usage;
    private final long tokenBudget;
    private final double costBudget;
    private final double inputCostPerThousandTokens;
    private final double outputCostPerThousandTokens;
    private final double alertRatio;
    private long inputTokens;
    private long outputTokens;
    private long invocations;
    private long latencyMs;
    private double estimatedCost;
    private long actualInputTokens;
    private long actualOutputTokens;
    private long actualUsageCalls;
    private long failures;
    private long largestContext;
    private int activeCalls;
    private int peakConcurrentCalls;
    private final List<Map<String, Object>> calls = new ArrayList<>();
    private static final ThreadLocal<CallTiming> CURRENT_TIMING = new ThreadLocal<>();

    static CallTiming currentTiming() { return CURRENT_TIMING.get(); }

    /** Reuses the existing per-call clock for Coding receipts without charging parent tokens. */
    public static TimingScope observeTiming() { return new TimingScope(); }
    public static void singleAttemptProvider() { var timing=CURRENT_TIMING.get();if(timing!=null)timing.retryTimeMs=0L; }
    public static final class TimingScope implements AutoCloseable {
        private final CallTiming previous = CURRENT_TIMING.get();
        private final CallTiming timing = new CallTiming();
        private TimingScope() { CURRENT_TIMING.set(timing); }
        public Long firstTokenMs() { return timing.firstTokenMs; }
        public Long generationTimeMs() { return timing.generationTimeMs; }
        public Long retryTimeMs() { return timing.retryTimeMs; }
        public String responseMode() { return timing.streaming?"STREAMING":"NON_STREAMING"; }
        public Long queueTimeMs() { return timing.executionStartedNanos==0?null:
            java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(timing.executionStartedNanos-timing.submittedNanos); }
        @Override public void close() { if(previous==null)CURRENT_TIMING.remove();else CURRENT_TIMING.set(previous); }
    }

    public static StreamTiming observeStream() { return new StreamTiming(CURRENT_TIMING.get()); }
    public static final class StreamTiming {
        private final CallTiming timing;
        private final long started=System.nanoTime();
        private final java.util.concurrent.atomic.AtomicLong first=new java.util.concurrent.atomic.AtomicLong(-1);
        private StreamTiming(CallTiming timing) { this.timing=timing;if(timing!=null)timing.streaming=true; }
        public void token() {
            long elapsed=java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started);
            if(first.compareAndSet(-1,elapsed) && timing!=null)timing.firstTokenMs=elapsed;
        }
        public void complete() {
            if(first.get()>=0 && timing!=null)timing.generationTimeMs=Math.max(0,
                java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started)-first.get());
        }
    }

    static <T> T withTiming(CallTiming timing, java.util.concurrent.Callable<T> action) throws Exception {
        CallTiming previous=CURRENT_TIMING.get();
        if(timing!=null)CURRENT_TIMING.set(timing);
        try{return action.call();}finally{if(previous==null)CURRENT_TIMING.remove();else CURRENT_TIMING.set(previous);}
    }

    static final class CallTiming {
        final long submittedNanos = System.nanoTime();
        volatile long executionStartedNanos;
        volatile Long firstTokenMs, generationTimeMs, retryTimeMs;
        volatile boolean streaming;
        void started() { executionStartedNanos = System.nanoTime(); }
    }


    public MeteredChatModel(ChatModel delegate,
                            Map<String, Object> usage,
                            long tokenBudget,
                            double costBudget,
                            double inputCostPerThousandTokens,
                            double outputCostPerThousandTokens,
                            double alertRatio) {
        this.delegate = delegate;
        this.usage = usage == null ? new LinkedHashMap<>() : usage;
        this.tokenBudget = Math.max(0, tokenBudget);
        this.costBudget = Math.max(0D, costBudget);
        this.inputCostPerThousandTokens = Math.max(0D, inputCostPerThousandTokens);
        this.outputCostPerThousandTokens = Math.max(0D, outputCostPerThousandTokens);
        this.alertRatio = Math.max(0.01D, Math.min(1D, alertRatio));
        publish();
    }

    @Override
    public String chat(String message) {
        // Production SDK models inherit ChatModel.chat(String), which constructs exactly
        // this native request. Keep custom text overrides on their original path.
        if (delegate instanceof DeadlineAwareChatModel deadline
                && deadline.toolDelegate() instanceof CapacityGovernedChatModel governed
                && governed.textUsesNativeRequest()) {
            ChatRequest request = ChatRequest.builder()
                .messages(dev.langchain4j.data.message.UserMessage.from(message)).build();
            ChatResponse response = invoke(estimator.estimate(message).tokens(),
                () -> delegate.chat(request),
                value -> estimator.estimate(value == null || value.aiMessage() == null
                    ? "" : value.aiMessage().toString()).tokens(),
                value -> value == null ? null : value.tokenUsage());
            return response.aiMessage().text();
        }
        return invoke(estimator.estimate(message).tokens(), () -> delegate.chat(message),
            value -> estimator.estimate(value).tokens(), value -> null);
    }

    @Override
    public ChatResponse doChat(ChatRequest request) {
        return invoke(estimator.estimate(request == null ? "" : request.toString()).tokens(),
            () -> delegate.chat(request),
            response -> estimator.estimate(response == null || response.aiMessage() == null
                ? "" : response.aiMessage().toString()).tokens(),
            response -> response == null ? null : response.tokenUsage());
    }

    private <T> T invoke(long promptTokens, Supplier<T> operation,
                         java.util.function.ToLongFunction<T> estimateOutput,
                         java.util.function.Function<T, TokenUsage> actualUsage) {
        Map<String, Object> call = new LinkedHashMap<>();
        synchronized (this) {
            enforce(inputTokens + outputTokens + promptTokens,
                estimatedCost + promptTokens * inputCostPerThousandTokens / 1_000D);
            // Reserve submitted input so concurrent calls cannot all spend the same budget.
            inputTokens += promptTokens;
            estimatedCost += promptTokens * inputCostPerThousandTokens / 1_000D;
            invocations++;
            activeCalls++;
            peakConcurrentCalls = Math.max(peakConcurrentCalls, activeCalls);
            largestContext = Math.max(largestContext, promptTokens);
            call.put("callId", invocations);
            call.put("nodeName", caller());
            call.put("startedAt", System.currentTimeMillis());
            call.put("inputTokensEstimated", promptTokens);
            call.put("status", "RUNNING");
            calls.add(call);
            publish();
            log.info("Runtime model call runId={} node={} callId={} status=STARTED inputEstimated={}",
                usage.get("runId"), call.get("nodeName"), call.get("callId"), promptTokens);
        }
        CallTiming timing = new CallTiming();
        CallTiming previous = CURRENT_TIMING.get();
        CURRENT_TIMING.set(timing);
        long responseTokens = 0;
        TokenUsage actual = null;
        boolean success = false;
        try {
            T response = operation.get();
            responseTokens = estimateOutput.applyAsLong(response);
            actual = actualUsage.apply(response);
            success = true;
            return response;
        } catch (RuntimeException failure) {
            call.put("errorType", failure.getClass().getName());
            throw failure;
        } finally {
            if (previous == null) CURRENT_TIMING.remove(); else CURRENT_TIMING.set(previous);
            long elapsed = Math.max(0, (System.nanoTime() - timing.submittedNanos) / 1_000_000);
            synchronized (this) {
                activeCalls--;
                if (!success) failures++;
                outputTokens += responseTokens;
                latencyMs += elapsed;
                estimatedCost += responseTokens * outputCostPerThousandTokens / 1_000D;
                call.put("status", success ? "SUCCEEDED" : "FAILED");
                call.put("finishedAt", System.currentTimeMillis());
                call.put("elapsedMs", elapsed);
                Long queue = timing.executionStartedNanos == 0 ? null
                    : Math.min(elapsed, Math.max(0, (timing.executionStartedNanos - timing.submittedNanos) / 1_000_000));
                call.put("queueTimeMs", queue);
                call.put("firstTokenMs",timing.firstTokenMs);
                call.put("generationTimeMs",timing.generationTimeMs);
                call.put("retryTimeMs",timing.retryTimeMs);
                call.put("responseMode",timing.streaming?"STREAMING":"NON_STREAMING");
                call.put("executionTimeMs", queue == null ? null : elapsed - queue);
                call.put("outputTokensEstimated", success ? responseTokens : null);
                call.put("inputTokens", actual == null ? null : actual.inputTokenCount());
                call.put("outputTokens", actual == null ? null : actual.outputTokenCount());
                var details=ModelUsageMetrics.observed(actual);
                for(String key:List.of("cacheReadTokens","cacheWriteTokens","reasoningTokens"))
                    if(details.containsKey(key))call.put(key,details.get(key));
                if (actual != null && actual.inputTokenCount() != null && actual.outputTokenCount() != null) {
                    actualUsageCalls++;
                    actualInputTokens += actual.inputTokenCount();
                    actualOutputTokens += actual.outputTokenCount();
                }
                publish();
                log.info("Runtime model call runId={} node={} callId={} status={} elapsedMs={} queueMs={} inputEstimated={} outputEstimated={} errorType={}",
                    usage.get("runId"), call.get("nodeName"), call.get("callId"), call.get("status"),
                    elapsed, queue, promptTokens, call.get("outputTokensEstimated"), call.get("errorType"));
                // Preserve the original transport failure when accounting for failed attempts.
                if (success) enforce(inputTokens + outputTokens, estimatedCost);
            }
        }
    }

    private String caller() {
        return StackWalker.getInstance().walk(frames -> frames
            .filter(frame -> frame.getClassName().startsWith("com.chatchat."))
            .filter(frame -> !frame.getClassName().equals(MeteredChatModel.class.getName()))
            .findFirst().map(frame -> frame.getClassName() + "#" + frame.getMethodName())
            .orElse("unattributed"));
    }

    private void enforce(long tokens, double cost) {
        if (tokenBudget > 0 && tokens > tokenBudget) {
            throw new AgentBudgetExceededException(
                "AGENT_TOKEN_BUDGET_EXCEEDED: used=" + tokens + ", budget=" + tokenBudget);
        }
        if (costBudget > 0D && cost > costBudget) {
            throw new AgentBudgetExceededException(
                "AGENT_COST_BUDGET_EXCEEDED: used=" + cost + ", budget=" + costBudget);
        }
    }

    private void publish() {
        usage.put("contractVersion", "agent_model_usage_v1");
        usage.put("invocations", invocations);
        usage.put("failedInvocations", failures);
        usage.put("actualUsageCalls", actualUsageCalls);
        usage.put("inputTokensReported", actualInputTokens);
        usage.put("outputTokensReported", actualOutputTokens);
        usage.put("activeInvocations", activeCalls);
        usage.put("peakConcurrentInvocations", peakConcurrentCalls);
        usage.put("largestInputTokensEstimated", largestContext);
        usage.put("criticalPathLlmCalls", null); // Requires execution DAG dependencies, not interval sums.
        usage.put("waterfallCoverage", "model_wrapper_calls; provider_internal_retries_not_observed");
        usage.put("calls", calls.stream().map(LinkedHashMap::new).toList());
        usage.put("inputTokensEstimated", inputTokens);
        usage.put("outputTokensEstimated", outputTokens);
        usage.put("totalTokensEstimated", inputTokens + outputTokens);
        usage.put("modelLatencyMs", latencyMs);
        usage.put("estimatedCost", round(estimatedCost));
        usage.put("tokenBudget", tokenBudget == 0 ? null : tokenBudget);
        usage.put("costBudget", costBudget == 0D ? null : costBudget);
        usage.put("budgetAlert", alert());
    }

    private boolean alert() {
        boolean tokenAlert = tokenBudget > 0 && inputTokens + outputTokens >= tokenBudget * alertRatio;
        boolean costAlert = costBudget > 0D && estimatedCost >= costBudget * alertRatio;
        return tokenAlert || costAlert;
    }

    private double round(double value) {
        return Math.round(value * 1_000_000D) / 1_000_000D;
    }
}
