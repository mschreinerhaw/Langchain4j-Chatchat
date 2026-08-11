package com.chatchat.agents.runtime.plan;

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
    Integer stepId,
    String definitionFingerprint,
    Map<Integer, String> dependencyResultFingerprints,
    String resultFingerprint,
    InterpretationPlanRuntime.StepExecution materializedResult,
    long createdAt,
    long updatedAt
) {
    public static final String SCHEMA_VERSION = "plan_step_checkpoint_v1";

    public PlanStepCheckpoint {
        dependencyResultFingerprints = dependencyResultFingerprints == null
            ? Map.of() : Map.copyOf(dependencyResultFingerprints);
    }
}
