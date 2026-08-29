package com.chatchat.agents.orchestration.answer;

import com.chatchat.common.interaction.InteractionToolTrace;

import java.util.List;
import java.util.Map;

/** Accounts remote invocations and applies the tool-call budget stop policy. */
final class DefaultAgentToolBudgetPolicy implements AgentToolBudgetPort {

    @Override
    public boolean markToolBudgetExceeded(String requestedToolName,
                                          int maxToolCalls,
                                          List<InteractionToolTrace> traces,
                                          Map<String, Object> metadata,
                                          List<String> observations) {
        long remoteToolCalls = remoteToolCallCount(traces);
        if (maxToolCalls == Integer.MAX_VALUE || remoteToolCalls < maxToolCalls) {
            return false;
        }
        metadata.put("stopReason", "tool_budget_exceeded");
        metadata.put("toolBudgetExceeded", true);
        metadata.put("maxToolCalls", maxToolCalls);
        metadata.put("remoteToolCalls", remoteToolCalls);
        metadata.put("requestedToolAfterBudget", requestedToolName);
        observations.add("Agent run stopped before executing " + requestedToolName
            + " because the max tool calls budget was reached.");
        return true;
    }

    private long remoteToolCallCount(List<InteractionToolTrace> traces) {
        if (traces == null || traces.isEmpty()) {
            return 0L;
        }
        return traces.stream().mapToLong(this::remoteToolInvocationCount).sum();
    }

    private long remoteToolInvocationCount(InteractionToolTrace trace) {
        if (trace == null || trace.getRuntimeMetadata() == null) {
            return remoteToolInvoked(trace) ? 1L : 0L;
        }
        Object count = trace.getRuntimeMetadata().get("remoteToolInvocationCount");
        if (count instanceof Number number) {
            return Math.max(0L, number.longValue());
        }
        if (count != null) {
            try {
                return Math.max(0L, Long.parseLong(String.valueOf(count)));
            } catch (NumberFormatException ignored) {
                // Fall through to legacy single-call accounting.
            }
        }
        return remoteToolInvoked(trace) ? 1L : 0L;
    }

    private boolean remoteToolInvoked(InteractionToolTrace trace) {
        if (trace == null || trace.getRuntimeMetadata() == null) {
            return trace != null;
        }
        Object explicit = trace.getRuntimeMetadata().get("remoteToolInvoked");
        if (explicit instanceof Boolean bool) {
            return bool;
        }
        String outcome = String.valueOf(trace.getRuntimeMetadata().get("outcome"));
        return !"denied".equalsIgnoreCase(outcome)
            && !"confirmation_required".equalsIgnoreCase(outcome)
            && !"rate_limited".equalsIgnoreCase(outcome)
            && !"circuit_open".equalsIgnoreCase(outcome);
    }
}

