package com.chatchat.common.runtime.summary.analysis;

import com.chatchat.common.runtime.protocol.RuntimeProtocolPort;
import com.chatchat.common.runtime.summary.spi.ModelSummaryProgressReporter;

import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * Unified analysis behavior implemented by both Worker and Driver-side participants.
 *
 * <p>The template method is the normative entry point: it validates assignment ownership before
 * handing only assigned evidence to the implementation. Implementations differ by supported
 * scope and reduction strategy, not by role-specific analysis semantics.</p>
 */
public interface DataAnalysisParticipant<M, W extends DataAnalysisWork, R>
    extends RuntimeProtocolPort {

    List<DataAnalysisExecutionStep> EXECUTION_STEPS = List.of(
        DataAnalysisExecutionStep.VALIDATE_ASSIGNMENT,
        DataAnalysisExecutionStep.ANALYZE_ASSIGNED_EVIDENCE,
        DataAnalysisExecutionStep.PRODUCE_SCOPED_SUMMARY,
        DataAnalysisExecutionStep.RECONCILE_INPUT_LINEAGE);

    Set<DataAnalysisScope> supportedScopes();

    default R analyze(M model,
                      W work,
                      ModelSummaryProgressReporter progressReporter,
                      BooleanSupplier cancellationCheck) {
        validateAssignment(work);
        ModelSummaryProgressReporter progress = progressReporter == null
            ? ModelSummaryProgressReporter.NOOP : progressReporter;
        BooleanSupplier cancelled = cancellationCheck == null ? () -> false : cancellationCheck;
        if (cancelled.getAsBoolean()) {
            throw new java.util.concurrent.CancellationException(
                "Analysis assignment was cancelled before execution");
        }
        R result = analyzeAssigned(model, work, progress, cancelled);
        if (result == null) throw new IllegalStateException("analysis result is required");
        reconcile(work, result);
        return result;
    }

    default void validateAssignment(W work) {
        if (work == null || work.assignment() == null) {
            throw new IllegalArgumentException("immutable analysis assignment is required");
        }
        DataAnalysisAssignment assignment = work.assignment();
        if (!supportedScopes().contains(assignment.scope())) {
            throw new IllegalArgumentException(
                "Unsupported analysis scope " + assignment.scope() + " for "
                    + getClass().getSimpleName());
        }
    }

    R analyzeAssigned(M model,
                      W work,
                      ModelSummaryProgressReporter progressReporter,
                      BooleanSupplier cancellationCheck);

    /** Fails closed when an implementation loses, crosses or expands assigned evidence lineage. */
    void reconcile(W work, R result);
}
