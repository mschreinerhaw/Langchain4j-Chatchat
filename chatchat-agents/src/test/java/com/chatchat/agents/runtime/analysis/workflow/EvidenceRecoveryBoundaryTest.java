package com.chatchat.agents.runtime.analysis.workflow;

import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.runtime.analysis.evidence.*;
import com.chatchat.common.runtime.analysis.execution.*;
import com.chatchat.common.runtime.analysis.model.*;
import com.chatchat.common.runtime.analysis.recovery.*;
import com.chatchat.common.runtime.analysis.spi.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.*;

class EvidenceRecoveryBoundaryTest {
    private final DocumentAnalysisEvidence original = document("original", true);
    private final VerificationResult rejected = new VerificationResult(false, List.of(), List.of("review required"));

    @Test void failurePreservesEvidenceJudgmentAndSynthesisAndReturnsToAnalysis() {
        AnalysisExecutionOutcome result = run(current -> { throw new IllegalStateException("timeout"); }, Map.of());
        assertThat(result.evidenceBundle().evidence()).containsExactly(original);
        assertThat(result.verification()).isSameAs(rejected);
        assertThat(result.synthesis()).isEqualTo("original analysis");
        assertThat(result.metadata()).containsEntry("evidenceRecoveryStatus", "FAILED")
            .containsEntry("runtimeRoute", "CONTINUE_ANALYSIS")
            .containsEntry("synthesisEvidenceScope", "PRIMARY_ANALYSIS_ONLY")
            .doesNotContainKeys("evidenceEvaluation", "evidenceTerminalState");
    }

    @Test void emptyRecoveryNeverDiscardsPreviouslyRetrievedEvidence() {
        AnalysisExecutionOutcome result = run(current -> recovery(RecoveryStatus.EXHAUSTED,
            EvidenceBundle.empty("source unavailable")), Map.of());
        assertThat(result.evidenceBundle().evidence()).containsExactly(original);
        assertThat(result.evidenceBundle().limitations()).contains("source unavailable");
        assertThat(result.verification()).isSameAs(rejected);
        assertThat(result.metadata()).containsEntry("evidenceRecoveryStatus", "EXHAUSTED");
    }

    @Test void completedRecoveryDoesNotApproveRejectedAnalysis() {
        AnalysisExecutionOutcome result = run(current -> recovery(RecoveryStatus.COMPLETE,
            new EvidenceBundle(null, List.of(document("new", false)), List.of(), Map.of())), Map.of());
        assertThat(result.evidenceBundle().evidence()).hasSize(2);
        assertThat(result.verification()).isSameAs(rejected);
        assertThat(result.metadata()).containsEntry("evidenceRecoveryStatus", "COMPLETE");
    }

    @Test void differentContentWithSameIdDoesNotOverwriteOriginalObservation() {
        var changed = new DocumentAnalysisEvidence(original.evidenceId(), "doc", "original", "doc.md",
            "section", "doc#original", "different source content", 0.9, Map.of());
        var result = run(current -> recovery(RecoveryStatus.COMPLETE,
            new EvidenceBundle(null, List.of(changed), List.of(), Map.of())), Map.of());
        assertThat(result.evidenceBundle().evidence()).containsExactly(original, changed);
    }

    @Test void durableExecutionAlsoContinuesAfterRecoveryFailure() {
        var result = run(current -> { throw new IllegalStateException("unavailable"); },
            Map.of(AnalysisContext.EXECUTION_MODE_ATTRIBUTE, "DURABLE"));
        assertThat(result.evidenceBundle().evidence()).containsExactly(original);
        assertThat(result.verification()).isEqualTo(rejected);
        assertThat(result.metadata()).containsEntry("executionMode", "DURABLE")
            .containsEntry("evidenceRecoveryStatus", "FAILED");
    }

    @Test void zeroBudgetDisablesRecovery() {
        AnalysisExecutionOutcome result = run(current -> { throw new AssertionError("disabled"); },
            Map.of("evidenceRecoveryMaxRounds", 0));
        assertThat(result.metadata()).containsEntry("evidenceRecoveryStatus", "DISABLED");
    }

    @Test void unchangedEvidenceStopsRetries() {
        AtomicInteger calls = new AtomicInteger();
        AnalysisExecutionOutcome result = run(current -> {
            calls.incrementAndGet();
            return recovery(RecoveryStatus.RETRY_REQUIRED, current);
        }, Map.of());
        assertThat(calls).hasValue(1);
        assertThat(result.metadata()).containsEntry("evidenceRecoveryStatus", "NO_NEW_EVIDENCE");
    }

    @Test void budgetExhaustionRetainsEachNewItemWithoutFailingAnalysis() {
        AtomicInteger calls = new AtomicInteger();
        AnalysisExecutionOutcome result = run(current -> recovery(RecoveryStatus.RETRY_REQUIRED,
            new EvidenceBundle(null, List.of(document("new-" + calls.incrementAndGet(), true)),
                List.of(), Map.of())), Map.of("evidenceRecoveryMaxRounds", 2));
        assertThat(calls).hasValue(2);
        assertThat(result.evidenceBundle().evidence()).hasSize(3);
        assertThat(result.metadata()).containsEntry("evidenceRecoveryStatus", "BUDGET_EXHAUSTED");
    }

    @Test void cancellationIsNotConvertedToRecoveryFailure() {
        assertThatThrownBy(() -> run(current -> { throw new CancellationException("cancelled"); }, Map.of()))
            .isInstanceOf(CancellationException.class);
    }

    @Test void inspectorIgnoresSemanticOpinionsAndUnknownBoundaries() {
        EvidenceStateInspector inspector = new EvidenceStateInspector();
        var state = inspector.inspect(new EvidenceBundle(null, List.of(document("one", false)), List.of(),
            Map.of("semanticSufficient", false, "evidenceCoverage", 0.1,
                "conflictingEvidence", true, "sourceAuthoritative", false,
                "sectionComplete", false, "sequenceComplete", false)), Map.of());
        assertThat(state.issues()).isEmpty();
        assertThat(state.projection()).containsEntry("transportState", "AVAILABLE");
        var known = inspector.inspect(new EvidenceBundle(null, List.of(document("one", false)), List.of(),
            Map.of("sectionBoundaryKnown", true, "sectionComplete", false)), Map.of());
        assertThat(known.issues()).extracting(EvidenceGap::reason).containsExactly(EvidenceGapReason.SECTION_INCOMPLETE);
    }

    @Test void analysisContinuationReceivesMergedEvidenceAndOwnsUpdatedSynthesis() {
        AnalysisWorkflow workflow = new AnalysisWorkflow() {
            public AnalysisWorkflowType type() { return AnalysisWorkflowType.DOCUMENT; }
            public String workflowId() { return "test.continuation"; }
            public boolean supports(AnalysisContext context, AnalysisIntent intent) { return true; }
            public AnalysisExecutionOutcome execute(AnalysisContext context) { return primary(); }
            public AnalysisExecutionOutcome continueAfterRecovery(AnalysisContext context,
                    AnalysisExecutionOutcome primary, EvidenceBundle evidence, Map<String, Object> metadata) {
                assertThat(evidence.evidence()).hasSize(2);
                assertThat(metadata).containsEntry("evidenceRecoveryStatus", "COMPLETE");
                return new AnalysisExecutionOutcome(null, type(), null, rejected, evidence,
                    "analysis with new evidence; review required", Map.of());
            }
        };
        var runtime = new DefaultAnalysisWorkflowRuntime(List.of(workflow), null, null,
            List.of(recoverer(current -> recovery(RecoveryStatus.COMPLETE,
                new EvidenceBundle(null, List.of(document("new", false)), List.of(), Map.of())))));
        var result = runtime.analyze(context(Map.of()));
        assertThat(result.synthesis()).isEqualTo("analysis with new evidence; review required");
        assertThat(result.verification()).isSameAs(rejected);
    }

    private AnalysisExecutionOutcome run(Function<EvidenceBundle, EvidenceRecoveryResult> action,
                                         Map<String, Object> attributes) {
        AnalysisWorkflow workflow = new AnalysisWorkflow() {
            public AnalysisWorkflowType type() { return AnalysisWorkflowType.DOCUMENT; }
            public String workflowId() { return "test.primary"; }
            public boolean supports(AnalysisContext context, AnalysisIntent intent) { return true; }
            public AnalysisExecutionOutcome execute(AnalysisContext context) { return primary(); }
        };
        var durable = new com.chatchat.agents.runtime.execution.LocalWorkflowRuntime(
            java.util.concurrent.ForkJoinPool.commonPool(),
            new com.chatchat.agents.runtime.config.AgentRuntimeProperties());
        return new DefaultAnalysisWorkflowRuntime(List.of(workflow), durable, null, List.of(recoverer(action)))
            .analyze(context(attributes));
    }

    private AnalysisExecutionOutcome primary() {
        return new AnalysisExecutionOutcome(null, AnalysisWorkflowType.DOCUMENT, null, rejected,
            new EvidenceBundle(null, List.of(original), List.of(), Map.of()), "original analysis", Map.of());
    }

    private AnalysisContext context(Map<String, Object> attributes) {
        return new AnalysisContext("installation steps", KernelDataScope.system("recovery-test"), "skill",
            List.of("doc"), List.of(), List.of(), new AnalysisIntent("LOOKUP", List.of(),
                Set.of(AnalysisCapability.DOCUMENT_SEARCH), "UNSPECIFIED", true), attributes);
    }

    private EvidenceRecoveryWorkflow recoverer(Function<EvidenceBundle, EvidenceRecoveryResult> action) {
        return new EvidenceRecoveryWorkflow() {
            public int priority() { return 0; }
            public boolean supports(AnalysisContext context, EvidenceGap gap) { return true; }
            public EvidenceRecoveryResult recover(AnalysisContext context, EvidenceBundle evidence,
                                                   EvidenceGap gap, int round) { return action.apply(evidence); }
        };
    }

    private EvidenceRecoveryResult recovery(RecoveryStatus status, EvidenceBundle bundle) {
        return new EvidenceRecoveryResult(status, bundle, List.of(), 1, null, null, Map.of());
    }

    private DocumentAnalysisEvidence document(String id, boolean truncated) {
        return new DocumentAnalysisEvidence(id, "doc", id, "doc.md", "section", "doc#" + id,
            id, 0.9, Map.of("truncated", truncated));
    }
}
