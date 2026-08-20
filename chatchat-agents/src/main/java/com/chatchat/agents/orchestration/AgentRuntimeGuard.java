package com.chatchat.agents.orchestration;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/**
 * Centralizes agent runtime limits, deadlines, and cancellation checks.
 */
class AgentRuntimeGuard {

    private static final int MAX_CONFIGURABLE_STEPS = 50;
    private final int defaultMaxSteps;
    private final String cancellationAttribute;
    private final String maxStepsAttribute;
    private final String maxToolCallsAttribute;
    private final String timeoutMsAttribute;
    private final String deadlineAtAttribute;

    AgentRuntimeGuard(int defaultMaxSteps,
                      String cancellationAttribute,
                      String maxStepsAttribute,
                      String maxToolCallsAttribute,
                      String timeoutMsAttribute,
                      String deadlineAtAttribute) {
        this.defaultMaxSteps = defaultMaxSteps;
        this.cancellationAttribute = cancellationAttribute;
        this.maxStepsAttribute = maxStepsAttribute;
        this.maxToolCallsAttribute = maxToolCallsAttribute;
        this.timeoutMsAttribute = timeoutMsAttribute;
        this.deadlineAtAttribute = deadlineAtAttribute;
    }

    BooleanSupplier cancellationCheck(Map<String, Object> runtimeAttributes) {
        Object value = runtimeAttributes == null ? null : runtimeAttributes.get(cancellationAttribute);
        BooleanSupplier externalCancellation = value instanceof BooleanSupplier supplier ? supplier : null;
        return () -> {
            if (Thread.currentThread().isInterrupted()) {
                return true;
            }
            if (externalCancellation != null && externalCancellation.getAsBoolean()) {
                return true;
            }
            long deadlineAt = runtimeLong(
                runtimeAttributes == null ? null : runtimeAttributes.get(deadlineAtAttribute), 0L);
            if (deadlineAt > 0 && System.currentTimeMillis() >= deadlineAt) {
                throw new AgentDeadlineExceededException("Agent execution time budget exhausted");
            }
            return false;
        };
    }

    Map<String, Object> attributesWithDeadline(Map<String, Object> runtimeAttributes) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        if (runtimeAttributes != null) {
            attributes.putAll(runtimeAttributes);
        }
        long startedAt = System.currentTimeMillis();
        long timeoutMs = runtimeLong(attributes.get(timeoutMsAttribute), 0L);
        if (timeoutMs > 0 && !attributes.containsKey(deadlineAtAttribute)) {
            attributes.put(deadlineAtAttribute, startedAt + timeoutMs);
        }
        return attributes;
    }

    long remainingTimeMs(Map<String, Object> runtimeAttributes) {
        long deadlineAt = runtimeLong(
            runtimeAttributes == null ? null : runtimeAttributes.get(deadlineAtAttribute), 0L);
        return deadlineAt <= 0L ? -1L : Math.max(0L, deadlineAt - System.currentTimeMillis());
    }

    int maxSteps(Map<String, Object> runtimeAttributes) {
        Object configured = configuredMaxSteps(runtimeAttributes);
        long value = runtimeLong(configured, defaultMaxSteps);
        return (int) Math.max(1, Math.min(MAX_CONFIGURABLE_STEPS, value));
    }

    boolean hasConfiguredMaxSteps(Map<String, Object> runtimeAttributes) {
        return configuredMaxSteps(runtimeAttributes) != null;
    }

    private Object configuredMaxSteps(Map<String, Object> runtimeAttributes) {
        Object configured = runtimeAttributes == null ? null : runtimeAttributes.get(maxStepsAttribute);
        return configured != null
            ? configured
            : workflowMaxSteps(runtimeAttributes == null ? null : runtimeAttributes.get("mcpWorkflow"));
    }

    private Object workflowMaxSteps(Object workflow) {
        if (!(workflow instanceof Map<?, ?> workflowMap)) {
            return null;
        }
        Object nestedWorkflow = firstPresent(workflowMap.get("mcpWorkflow"), workflowMap.get("mcp_workflow"));
        if (nestedWorkflow != null && nestedWorkflow != workflow) {
            Object nestedMaxSteps = workflowMaxSteps(nestedWorkflow);
            if (nestedMaxSteps != null) {
                return nestedMaxSteps;
            }
        }
        Object strategy = firstPresent(workflowMap.get("executionStrategy"), workflowMap.get("execution_strategy"));
        if (!(strategy instanceof Map<?, ?> strategyMap)) {
            return null;
        }
        return firstPresent(strategyMap.get("maxSteps"), strategyMap.get("max_steps"));
    }

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

    int maxToolCalls(Map<String, Object> runtimeAttributes) {
        Object value = runtimeAttributes == null ? null : runtimeAttributes.get(maxToolCallsAttribute);
        if (value == null) {
            return Integer.MAX_VALUE;
        }
        long maxToolCalls = runtimeLong(value, Integer.MAX_VALUE);
        if (maxToolCalls < 0) {
            return Integer.MAX_VALUE;
        }
        return (int) Math.min(Integer.MAX_VALUE, maxToolCalls);
    }

    long runtimeLong(Object value, long defaultValue) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Long.parseLong(text.trim());
            } catch (NumberFormatException ex) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    void checkCancelled(BooleanSupplier cancellationCheck) {
        if (Thread.currentThread().isInterrupted() || (cancellationCheck != null && cancellationCheck.getAsBoolean())) {
            throw new CancellationException("Agent task cancelled");
        }
    }
}
