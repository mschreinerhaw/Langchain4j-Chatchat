package com.chatchat.agents.runtime;

import lombok.Builder;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Builder
public record AgentRun(
    String runId,
    AgentRunStatus status,
    AgentRunRequest request,
    AgentRunResult result,
    List<AgentRunStep> steps,
    List<AgentObservation> observations,
    List<AgentRunEvent> events,
    Map<String, Object> metadata,
    long startedAt,
    Long finishedAt,
    String errorMessage
) {

    public AgentRun {
        status = status == null ? AgentRunStatus.PENDING : status;
        steps = steps == null ? List.of() : List.copyOf(steps);
        observations = observations == null ? List.of() : List.copyOf(observations);
        events = events == null ? List.of() : List.copyOf(events);
        metadata = metadata == null ? Map.of() : new LinkedHashMap<>(metadata);
    }
}
