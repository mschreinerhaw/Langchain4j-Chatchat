package com.chatchat.agents.orchestration.lifecycle;

import com.chatchat.agents.orchestration.AgentOrchestrator;
import com.chatchat.agents.orchestration.AgentRunResultAdapter;

import com.chatchat.agents.orchestration.model.AgentDeadlineExceededException;
import com.chatchat.agents.runtime.AgentRunRequest;
import com.chatchat.agents.runtime.AgentRunResult;
import com.chatchat.agents.runtime.observation.AgentObservation;
import com.chatchat.agents.runtime.plan.PlanStepCheckpoint;
import com.chatchat.agents.runtime.run.AgentOutcomeProjection;
import com.chatchat.agents.runtime.run.AgentRun;
import com.chatchat.agents.runtime.run.AgentRunStatus;
import com.chatchat.agents.runtime.store.AgentRunStore;
import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;

/**
 * Owns the durable AgentRun lifecycle and terminal outcome projection.
 * Domain execution is supplied as a callback so persistence/error semantics
 * cannot leak back into the orchestration workflow.
 */
@Slf4j
public final class AgentRunLifecycleCoordinator {

    private final AgentRunStore runStore;
    private final AgentRunResultAdapter resultAdapter;
    private final AgentOutcomeProjection outcomeProjection = new AgentOutcomeProjection();

    public AgentRunLifecycleCoordinator(AgentRunStore runStore, AgentRunResultAdapter resultAdapter) {
        this.runStore = Objects.requireNonNull(runStore, "runStore");
        this.resultAdapter = Objects.requireNonNull(resultAdapter, "resultAdapter");
    }

    public AgentRunResult execute(AgentRunRequest request, RunOperation operation) {
        if (request == null) {
            throw new IllegalArgumentException("Agent run request is required");
        }
        AgentRun run = runStore.start(request);
        try {
            AgentOrchestrator.AgentExecutionResult result = operation.execute(request);
            AgentRunResult runtimeResult = resultAdapter.toAgentRunResult(run.runId(), result);
            if (runtimeResult.status() == AgentRunStatus.RUNNING) {
                AgentRun current = runStore.find(run.runId()).orElse(run);
                return runtimeResult.withStatusAndEvents(current.status(), current.events());
            }
            AgentRun completed = runStore.complete(run.runId(), runtimeResult);
            return runtimeResult.withStatusAndEvents(completed.status(), completed.events());
        } catch (AgentDeadlineExceededException ex) {
            return completeDeadlineExceeded(run, ex);
        } catch (CancellationException ex) {
            return cancelled(runStore.cancel(run.runId(), ex.getMessage()));
        } catch (RuntimeException ex) {
            log.error("Agent orchestration failed. runId={} requestId={} errorType={} error={}",
                run.runId(), request.getRequestId(), ex.getClass().getName(), ex.getMessage(), ex);
            return failed(runStore.fail(run.runId(), ex));
        }
    }

    private AgentRunResult completeDeadlineExceeded(AgentRun run, AgentDeadlineExceededException error) {
        AgentRun current = runStore.find(run.runId()).orElse(run);
        List<AgentObservation> observations = runStore.observations(run.runId());
        List<PlanStepCheckpoint> checkpoints = runStore.planStepCheckpoints(run.runId());
        long evidenceCount = observations.stream().filter(this::isEvidence).count();
        boolean preserved = evidenceCount > 0 || !checkpoints.isEmpty();
        String answer = preserved
            ? "执行时间预算已耗尽；已完成步骤的证据和检查点均已保留，可从最近一致检查点继续执行。"
            : "";
        List<String> observationTexts = observations.stream()
            .map(AgentObservation::content)
            .filter(Objects::nonNull)
            .filter(content -> !content.isBlank())
            .toList();

        Map<String, Object> deadlineMetadata = new LinkedHashMap<>();
        deadlineMetadata.put("stopReason", "time_budget_exhausted");
        deadlineMetadata.put("errorCode", "TIME_BUDGET_EXHAUSTED");
        deadlineMetadata.put("errorMessage", error.getMessage());
        deadlineMetadata.put("completedEvidencePreservedAfterTimeout", preserved);
        deadlineMetadata.put("preservedObservationCount", evidenceCount);
        deadlineMetadata.put("preservedCheckpointCount", checkpoints.size());
        deadlineMetadata.put("observations", observationTexts);
        Map<String, Object> metadata = outcomeProjection.enrich(deadlineMetadata, answer);

        AgentRunResult result = AgentRunResult.builder()
            .runId(run.runId())
            .status(AgentRunStatus.COMPLETED)
            .answer(answer)
            .stopReason("time_budget_exhausted")
            .errorMessage(error.getMessage())
            .steps(current.steps())
            .observations(observations)
            .metadata(metadata)
            .build();
        AgentRun completed = runStore.complete(run.runId(), result);
        return result.withStatusAndEvents(completed.status(), completed.events());
    }

    private boolean isEvidence(AgentObservation observation) {
        if (observation == null || observation.type() == null) {
            return false;
        }
        String type = observation.type().trim().toLowerCase(java.util.Locale.ROOT);
        return "tool".equals(type) || "tool_failure".equals(type)
            || "batch".equals(type) || "batch_tool".equals(type);
    }

    private AgentRunResult cancelled(AgentRun run) {
        return AgentRunResult.builder()
            .runId(run.runId())
            .status(AgentRunStatus.CANCELLED)
            .answer("")
            .stopReason("cancelled")
            .errorMessage(run.errorMessage())
            .events(run.events())
            .metadata(run.metadata())
            .build();
    }

    private AgentRunResult failed(AgentRun run) {
        return AgentRunResult.builder()
            .runId(run.runId())
            .status(AgentRunStatus.FAILED)
            .answer("")
            .stopReason("failed")
            .errorMessage(run.errorMessage())
            .events(run.events())
            .metadata(run.metadata())
            .build();
    }

    @FunctionalInterface
    public interface RunOperation {
        AgentOrchestrator.AgentExecutionResult execute(AgentRunRequest request);
    }
}
