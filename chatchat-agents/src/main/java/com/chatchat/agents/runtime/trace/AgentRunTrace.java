package com.chatchat.agents.runtime.trace;

import com.chatchat.agents.runtime.AgentRunEvent;
import com.chatchat.agents.runtime.AgentRunStatus;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

public record AgentRunTrace(
    String contractVersion,
    String traceId,
    String taskId,
    String runId,
    String requestId,
    String conversationId,
    String tenantId,
    String userId,
    String agentId,
    String modelName,
    String modelCallId,
    String question,
    AgentRunStatus status,
    long startedAt,
    Long finishedAt,
    Long latencyMs,
    Map<String, Integer> tokenUsage,
    List<ToolCallTrace> toolCalls,
    List<EvidenceTrace> evidence,
    AnswerTrace answer,
    GroundingTrace grounding,
    List<String> failureReasons,
    List<AgentRunEvent> events
) {

    public static final String CONTRACT_VERSION = "agent_run_trace_v2";

    public AgentRunTrace {
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        failureReasons = failureReasons == null ? List.of() : List.copyOf(failureReasons);
        events = events == null ? List.of() : List.copyOf(events);
        tokenUsage = tokenUsage == null ? Map.of() : new LinkedHashMap<>(tokenUsage);
    }
}
