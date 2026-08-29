package com.chatchat.agents.runtime.plan.persistence;

import com.chatchat.agents.runtime.plan.InterpretationPlanRuntime;

import java.util.Map;

/**
 * Durable materialization of a successfully completed InterpretationPlan node.
 *
 * <p>The definition fingerprint protects against reusing a changed node, while
 * dependency result fingerprints invalidate downstream nodes when an upstream
 * materialization changes.</p>
 */
public record PlanStepCheckpoint(
    String schemaVersion,
    String runId,
    String planExecutionScope,
    String workflowExecutionAttempt,
    Integer stepId,
    String definitionFingerprint,
    String checkpointFingerprint,
    Map<String, String> identityFingerprints,
    Map<Integer, String> dependencyResultFingerprints,
    String resultFingerprint,
    InterpretationPlanRuntime.StepExecution materializedResult,
    boolean committed,
    long createdAt,
    long updatedAt
) {
    public static final String SCHEMA_VERSION = "plan_step_checkpoint_v3";

    public PlanStepCheckpoint {
        dependencyResultFingerprints = dependencyResultFingerprints == null
            ? Map.of() : Map.copyOf(dependencyResultFingerprints);
        identityFingerprints = identityFingerprints == null
            ? Map.of() : Map.copyOf(identityFingerprints);
    }

    /** Compatibility constructor for persisted-store tests; legacy records are not reusable by the v3 Runtime. */
    public PlanStepCheckpoint(String schemaVersion,
                              String runId,
                              Integer stepId,
                              String definitionFingerprint,
                              Map<Integer, String> dependencyResultFingerprints,
                              String resultFingerprint,
                              InterpretationPlanRuntime.StepExecution materializedResult,
                              long createdAt,
                              long updatedAt) {
        this(schemaVersion, runId, null, null, stepId, definitionFingerprint,
            null, Map.of(), dependencyResultFingerprints, resultFingerprint, materializedResult,
            false, createdAt, updatedAt);
    }
}
