package com.chatchat.agents.runtime;

import com.chatchat.common.interaction.InteractionToolTrace;
import lombok.Builder;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Builder
public record AgentRunResult(
    String runId,
    AgentRunStatus status,
    String answer,
    String stopReason,
    boolean confirmationRequired,
    String errorMessage,
    List<AgentRunStep> steps,
    List<AgentObservation> observations,
    List<AgentRunEvent> events,
    List<InteractionToolTrace> toolTraces,
    Map<String, Object> metadata
) {

    public AgentRunResult {
        status = status == null ? AgentRunStatus.COMPLETED : status;
        steps = steps == null ? List.of() : List.copyOf(steps);
        observations = observations == null ? List.of() : List.copyOf(observations);
        events = events == null ? List.of() : List.copyOf(events);
        toolTraces = toolTraces == null ? List.of() : List.copyOf(toolTraces);
        metadata = metadata == null ? Map.of() : new LinkedHashMap<>(metadata);
    }

    public AgentRunResult withStatusAndEvents(AgentRunStatus nextStatus, List<AgentRunEvent> nextEvents) {
        return new AgentRunResult(
            runId,
            nextStatus,
            answer,
            stopReason,
            confirmationRequired,
            errorMessage,
            steps,
            observations,
            nextEvents,
            toolTraces,
            metadata
        );
    }
}
