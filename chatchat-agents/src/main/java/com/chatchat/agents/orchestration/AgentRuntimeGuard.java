package com.chatchat.agents.orchestration;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/**
 * Centralizes agent runtime limits, deadlines, and cancellation checks.
 */
class AgentRuntimeGuard {

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
        long deadlineAt = runtimeLong(runtimeAttributes == null ? null : runtimeAttributes.get(deadlineAtAttribute), 0L);
        return () -> {
            if (Thread.currentThread().isInterrupted()) {
                return true;
            }
            if (externalCancellation != null && externalCancellation.getAsBoolean()) {
                return true;
            }
            if (deadlineAt > 0 && System.currentTimeMillis() > deadlineAt) {
                throw new CancellationException("Agent run timed out");
            }
            return false;
        };
    }

    Map<String, Object> attributesWithDeadline(Map<String, Object> runtimeAttributes) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        if (runtimeAttributes != null) {
            attributes.putAll(runtimeAttributes);
        }
        long timeoutMs = runtimeLong(attributes.get(timeoutMsAttribute), 0L);
        if (timeoutMs > 0 && !attributes.containsKey(deadlineAtAttribute)) {
            attributes.put(deadlineAtAttribute, System.currentTimeMillis() + timeoutMs);
        }
        return attributes;
    }

    int maxSteps(Map<String, Object> runtimeAttributes) {
        return Math.max(1, (int) runtimeLong(
            runtimeAttributes == null ? null : runtimeAttributes.get(maxStepsAttribute),
            defaultMaxSteps
        ));
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
