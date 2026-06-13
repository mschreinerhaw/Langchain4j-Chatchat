package com.chatchat.agents.runtime;

import java.util.List;
import java.util.Optional;

public interface AgentRuntime {

    AgentRunResult run(AgentRunRequest request);

    AgentRunHandle submit(AgentRunRequest request);

    AgentRun cancel(String runId);

    Optional<AgentRun> find(String runId);

    List<AgentRun> list(AgentRunQuery query);

    List<AgentRunEvent> events(String runId);

    List<AgentRunEvent> events(String runId, long afterCreatedAt, int limit);

    List<AgentRunStep> steps(String runId);

    List<AgentRunStep> steps(String runId, int afterStep, int limit);

    List<AgentObservation> observations(String runId);

    List<AgentObservation> observations(String runId, int offset, int limit);

    AgentRuntimeSnapshot snapshot();
}
