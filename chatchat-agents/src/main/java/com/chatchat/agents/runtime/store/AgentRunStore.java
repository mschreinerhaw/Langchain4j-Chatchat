package com.chatchat.agents.runtime.store;

import com.chatchat.agents.runtime.AgentRunRequest;
import com.chatchat.agents.runtime.AgentRunResult;
import com.chatchat.agents.runtime.AgentRuntimeSnapshot;

import com.chatchat.agents.runtime.event.AgentRunEvent;
import com.chatchat.agents.runtime.observation.AgentObservation;
import com.chatchat.agents.runtime.run.AgentRun;
import com.chatchat.agents.runtime.run.AgentRunQuery;
import com.chatchat.agents.runtime.run.AgentRunStep;

import com.chatchat.common.runtime.event.RuntimeEventJournal;
import com.chatchat.agents.runtime.plan.PlanStepCheckpoint;

import java.util.List;
import java.util.Optional;

public interface AgentRunStore extends RuntimeEventJournal<AgentRunEvent> {

    AgentRun submit(AgentRunRequest request);

    AgentRun start(AgentRunRequest request);

    AgentRun complete(String runId, AgentRunResult result);

    AgentRun cancel(String runId, String reason);

    AgentRun fail(String runId, Throwable error);

    AgentRun recordStep(String runId, AgentRunStep step);

    AgentRun recordObservation(String runId, AgentObservation observation);

    /** Persists and publishes a first-class Runtime event without disguising it as an observation. */
    AgentRun recordEvent(String runId, AgentRunEvent event);

    Optional<AgentRun> find(String runId);

    List<AgentRun> list(AgentRunQuery query);

    List<AgentRunStep> steps(String runId);

    List<AgentRunStep> steps(String runId, int afterStep, int limit);

    List<AgentObservation> observations(String runId);

    List<AgentObservation> observations(String runId, int offset, int limit);

    default Optional<Object> evidence(String documentId) {
        return Optional.empty();
    }

    /** Saves or replaces the durable materialization for one plan node. */
    default void savePlanStepCheckpoint(PlanStepCheckpoint checkpoint) {
    }

    /** Returns all durable plan-node materializations for a run. */
    default List<PlanStepCheckpoint> planStepCheckpoints(String runId) {
        return List.of();
    }

    /** Removes all durable plan-node materializations for a run. */
    default void deletePlanStepCheckpoints(String runId) {
    }

    /**
     * Removes terminal runs whose retention window has expired.
     *
     * @return number of removed runs
     */
    int cleanupExpiredRuns();

    AgentRuntimeSnapshot snapshot();
}
