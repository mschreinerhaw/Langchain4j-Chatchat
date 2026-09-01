package com.chatchat.agents.runtime.plan.persistence;

import com.chatchat.agents.runtime.plan.InterpretationPlan;
import com.chatchat.agents.runtime.plan.InterpretationPlanRuntime;
import com.chatchat.agents.runtime.store.AgentRunStore;
import lombok.extern.slf4j.Slf4j;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** Owns materialized plan-node checkpoint recovery and persistence. */
@Slf4j
public final class PlanCheckpointCoordinator {

    public Set<Integer> seedReusable(InterpretationPlanRuntime.ExecutionRequest request,
                                     Map<Integer, InterpretationPlan.Step> stepsById,
                                     Map<Integer, InterpretationPlanRuntime.StepExecution> completed,
                                     List<InterpretationPlanRuntime.StepExecution> executions) {
        Set<Integer> reused = new LinkedHashSet<>();
        Object raw = request == null || request.attributes() == null ? null
            : request.attributes().get("reusablePlanSteps");
        if (!(raw instanceof Iterable<?> values)) return reused;
        for (Object value : values) {
            if (!(value instanceof InterpretationPlanRuntime.ReusableStep candidate)
                || candidate.step() == null || candidate.execution() == null
                || !candidate.execution().success() || candidate.step().id() == null
                || !Objects.equals(candidate.step(), stepsById.get(candidate.step().id()))) continue;
            Map<String, Object> metadata = new LinkedHashMap<>(candidate.execution().metadata());
            metadata.put("reusedFromPlanRevision", true);
            InterpretationPlanRuntime.StepExecution restored = new InterpretationPlanRuntime.StepExecution(
                candidate.execution().stepId(), candidate.execution().actionType(), candidate.execution().toolName(),
                true, candidate.execution().output(), null, candidate.execution().toolExecution(),
                candidate.execution().finalAnswer(), 0L, Map.copyOf(metadata));
            completed.put(restored.stepId(), restored);
            executions.add(restored);
            reused.add(restored.stepId());
        }
        return reused;
    }

    public Recovery recover(String runId,
                            InterpretationPlanRuntime.ExecutionRequest request,
                            Map<Integer, InterpretationPlan.Step> stepsById,
                            Map<Integer, InterpretationPlanRuntime.StepExecution> completed,
                            AgentRunStore runStore,
                            NodeAttemptStore attemptStore,
                            Support support) {
        Set<Integer> reused = new LinkedHashSet<>();
        if (runStore == null || runId == null || runId.isBlank() || stepsById.isEmpty()) return Recovery.none();
        List<PlanStepCheckpoint> stored;
        try {
            stored = runStore.planStepCheckpoints(runId);
        } catch (RuntimeException ex) {
            log.warn("Failed to load persisted plan checkpoints. runId={} error={}", runId, ex.getMessage());
            return Recovery.rejected("CHECKPOINT_STORE_UNAVAILABLE");
        }
        Map<Integer, PlanStepCheckpoint> byStep = stored.stream().filter(Objects::nonNull)
            .filter(value -> value.stepId() != null).collect(Collectors.toMap(PlanStepCheckpoint::stepId,
                value -> value, (left, right) -> left.updatedAt() >= right.updatedAt() ? left : right,
                LinkedHashMap::new));
        String requestedToken = request == null || request.attributes() == null ? null
            : text(request.attributes().get("resumeToken"));
        Set<String> committedAttempts = committedAttemptIds(request, runId, attemptStore);
        boolean reconcileAttempts = attemptStore != null && attemptStore.supportsRecoveryQueries();
        Map<Integer, InterpretationPlanRuntime.StepExecution> recovered = new LinkedHashMap<>(completed);
        Set<String> recoveryAttemptIds = new LinkedHashSet<>();
        boolean progressed;
        do {
            progressed = false;
            for (InterpretationPlan.Step step : stepsById.values()) {
                if (recovered.containsKey(step.id())) continue;
                PlanStepCheckpoint checkpoint = byStep.get(step.id());
                String attemptId = checkpointAttemptId(checkpoint);
                if (reconcileAttempts && (attemptId == null || !committedAttempts.contains(attemptId))) continue;
                if (!support.validCheckpoint(checkpoint, step, recovered, request)) continue;
                InterpretationPlanRuntime.StepExecution value = checkpoint.materializedResult();
                Map<String, Object> metadata = new LinkedHashMap<>(value.metadata());
                metadata.put("reusedFromCheckpoint", true);
                metadata.put("checkpointSchemaVersion", checkpoint.schemaVersion());
                metadata.put("checkpointUpdatedAt", checkpoint.updatedAt());
                metadata.put("checkpointFingerprint", checkpoint.checkpointFingerprint());
                metadata.put("checkpointIdentityFingerprints", checkpoint.identityFingerprints());
                InterpretationPlanRuntime.StepExecution restored = new InterpretationPlanRuntime.StepExecution(
                    value.stepId(), value.actionType(), value.toolName(), true, value.output(), null,
                    value.toolExecution(), value.finalAnswer(), 0L, Map.copyOf(metadata));
                recovered.put(restored.stepId(), restored);
                reused.add(restored.stepId());
                if (attemptId != null) recoveryAttemptIds.add(attemptId);
                progressed = true;
            }
        } while (progressed);
        if (reused.isEmpty()) return requestedToken == null || requestedToken.isBlank()
            ? Recovery.none() : Recovery.rejected("NO_CONSISTENT_COMMITTED_BOUNDARY");
        String resumeToken = support.recoveryToken(request, byStep, reused, recoveryAttemptIds);
        if (requestedToken != null && !requestedToken.isBlank() && !requestedToken.equals(resumeToken)) {
            log.warn("Rejected plan recovery because resume token does not match latest consistent boundary. runId={} recoveredStepIds={}", runId, reused);
            return Recovery.rejected("RESUME_TOKEN_MISMATCH");
        }
        completed.putAll(recovered);
        log.info("Restored plan from latest consistent checkpoint boundary. runId={} stepIds={} attemptReconciled={} resumeToken={}",
            runId, reused, reconcileAttempts, resumeToken);
        return new Recovery("RESUMED", resumeToken, immutable(reused), immutable(recoveryAttemptIds), null);
    }

    public void persist(String runId,
                        InterpretationPlanRuntime.ExecutionRequest request,
                        Map<Integer, InterpretationPlan.Step> stepsById,
                        Map<Integer, InterpretationPlanRuntime.StepExecution> completed,
                        List<InterpretationPlanRuntime.StepExecution> waveResults,
                        AgentRunStore runStore,
                        boolean writesEnabled,
                        Support support) {
        if (!writesEnabled || runStore == null || runId == null || runId.isBlank() || waveResults == null) return;
        long now = System.currentTimeMillis();
        for (InterpretationPlanRuntime.StepExecution execution : waveResults) {
            InterpretationPlan.Step step = execution == null ? null : stepsById.get(execution.stepId());
            if (step == null || !execution.success()) continue;
            try {
                Map<Integer, String> dependencies = new LinkedHashMap<>();
                boolean materialized = true;
                for (Integer dependencyId : step.dependsOn() == null ? List.<Integer>of() : step.dependsOn()) {
                    InterpretationPlanRuntime.StepExecution dependency = completed.get(dependencyId);
                    if (dependency == null || !dependency.success()) { materialized = false; break; }
                    dependencies.put(dependencyId, support.resultFingerprint(dependency));
                }
                if (!materialized) continue;
                Map<String, String> identities = support.identityFingerprints(step, request, completed, execution);
                runStore.savePlanStepCheckpoint(new PlanStepCheckpoint(PlanStepCheckpoint.SCHEMA_VERSION, runId,
                    support.planExecutionScope(request), support.workflowExecutionAttempt(request), step.id(),
                    support.stepFingerprint(step), support.fingerprint(identities), identities, dependencies,
                    support.resultFingerprint(execution), execution, true, now, now));
            } catch (RuntimeException ex) {
                log.warn("Failed to persist plan step checkpoint. runId={} stepId={} error={}", runId, step.id(), ex.getMessage());
            }
        }
    }

    private Set<String> committedAttemptIds(InterpretationPlanRuntime.ExecutionRequest request,
                                            String runId, NodeAttemptStore store) {
        if (store == null || !store.supportsRecoveryQueries()) return Set.of();
        try {
            return store.committedAttempts(request.tenantId(), runId).stream().filter(Objects::nonNull)
                .filter(value -> value.state() == NodeAttemptStore.State.COMMITTED)
                .map(NodeAttemptStore.AttemptSnapshot::attemptId).filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        } catch (RuntimeException ex) {
            log.warn("Failed to reconcile committed node Attempts. runId={} error={}", runId, ex.getMessage());
            return Set.of();
        }
    }

    private String checkpointAttemptId(PlanStepCheckpoint checkpoint) {
        if (checkpoint == null || checkpoint.materializedResult() == null
            || checkpoint.materializedResult().metadata() == null) return null;
        Map<String, Object> metadata = checkpoint.materializedResult().metadata();
        if (metadata.get("nodeAttemptState") != null
            && !NodeAttemptStore.State.COMMITTED.name().equals(String.valueOf(metadata.get("nodeAttemptState")))) return null;
        return text(metadata.get("nodeAttemptId"));
    }

    private String text(Object value) { return value == null ? null : String.valueOf(value); }
    private <T> Set<T> immutable(Set<T> values) { return Collections.unmodifiableSet(new LinkedHashSet<>(values)); }

    public interface Support {
        boolean validCheckpoint(PlanStepCheckpoint checkpoint, InterpretationPlan.Step step,
                                Map<Integer, InterpretationPlanRuntime.StepExecution> completed,
                                InterpretationPlanRuntime.ExecutionRequest request);
        String recoveryToken(InterpretationPlanRuntime.ExecutionRequest request,
                             Map<Integer, PlanStepCheckpoint> checkpoints, Set<Integer> stepIds, Set<String> attemptIds);
        Map<String, String> identityFingerprints(InterpretationPlan.Step step,
                                                 InterpretationPlanRuntime.ExecutionRequest request,
                                                 Map<Integer, InterpretationPlanRuntime.StepExecution> completed,
                                                 InterpretationPlanRuntime.StepExecution materializedExecution);
        String resultFingerprint(InterpretationPlanRuntime.StepExecution execution);
        String stepFingerprint(InterpretationPlan.Step step);
        String fingerprint(Object value);
        String planExecutionScope(InterpretationPlanRuntime.ExecutionRequest request);
        String workflowExecutionAttempt(InterpretationPlanRuntime.ExecutionRequest request);
    }

    public record Recovery(String status, String resumeToken, Set<Integer> stepIds,
                           Set<String> attemptIds, String rejectedReason) {
        public static Recovery none() { return new Recovery("NONE", null, Set.of(), Set.of(), null); }
        public static Recovery rejected(String reason) { return new Recovery("REJECTED", null, Set.of(), Set.of(), reason); }
    }
}
