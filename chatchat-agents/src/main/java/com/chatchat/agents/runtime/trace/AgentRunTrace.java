package com.chatchat.agents.runtime.trace;

import com.chatchat.agents.runtime.AgentRunEvent;
import com.chatchat.agents.runtime.AgentRunStatus;

import java.util.List;

public record AgentRunTrace(
    String contractVersion,
    String runId,
    String requestId,
    String conversationId,
    String tenantId,
    String userId,
    String question,
    AgentRunStatus status,
    long startedAt,
    Long finishedAt,
    List<ToolCallTrace> toolCalls,
    List<EvidenceTrace> evidence,
    AnswerTrace answer,
    GroundingTrace grounding,
    List<String> failureReasons,
    List<AgentRunEvent> events
) {

    public static final String CONTRACT_VERSION = "agent_run_trace_v1";

    public AgentRunTrace {
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        failureReasons = failureReasons == null ? List.of() : List.copyOf(failureReasons);
        events = events == null ? List.of() : List.copyOf(events);
    }
}
