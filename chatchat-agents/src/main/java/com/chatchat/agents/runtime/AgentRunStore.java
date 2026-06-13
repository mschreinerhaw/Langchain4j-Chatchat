package com.chatchat.agents.runtime;

import java.util.List;
import java.util.Optional;

public interface AgentRunStore {

    AgentRun submit(AgentRunRequest request);

    AgentRun start(AgentRunRequest request);

    AgentRun complete(String runId, AgentRunResult result);

    AgentRun cancel(String runId, String reason);

    AgentRun fail(String runId, Throwable error);

    AgentRun recordStep(String runId, AgentRunStep step);

    AgentRun recordObservation(String runId, AgentObservation observation);

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
