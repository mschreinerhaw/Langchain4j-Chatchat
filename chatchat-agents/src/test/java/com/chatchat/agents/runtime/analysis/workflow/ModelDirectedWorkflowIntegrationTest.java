package com.chatchat.agents.runtime.analysis.workflow;

import com.chatchat.agents.protocol.ModelProtocolJson;
import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.runtime.analysis.evidence.EvidenceBundle;
import com.chatchat.common.runtime.analysis.execution.*;
import com.chatchat.common.runtime.analysis.model.*;
import com.chatchat.common.runtime.analysis.recovery.*;
import com.chatchat.common.runtime.analysis.spi.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ModelDirectedWorkflowIntegrationTest {
    private AnalysisContext context() {
        return new AnalysisContext("calculate", KernelDataScope.system("model-integration"), "skill", List.of(), List.of(),
            List.of(), new AnalysisIntent("CALCULATION", List.of(), Set.of(AnalysisCapability.COMPUTATION), "UNSPECIFIED", true),
            Map.of("evidenceRecoveryMaxRounds", 2));
    }
    private AnalysisExecutionOutcome result(String action) {
        var metadata = new LinkedHashMap<String,Object>();
        metadata.put("modelAnalysisProtocol", ModelAnalysisIntent.VERSION);
        metadata.put("modelDecision", Map.of("action", action));
        metadata.put("modelEvidenceSnapshotRef", "snapshot");
        metadata.put("publicationState", action.equals("PUBLISH") ? "REQUESTED" : "NOT_REQUESTED");
        metadata.put("modelPublicationRequest", Map.of("reportSha256", ModelProtocolJson.sha256Hex("Working report"), "evidenceSnapshotRef", "snapshot"));
        return new AnalysisExecutionOutcome(null, AnalysisWorkflowType.COMPUTATION, null,
            new VerificationResult(false, List.of(), List.of("Observed gap")), EvidenceBundle.empty("Observed gap"),
            "Working report", metadata);
    }
    private class Workflow implements AnalysisWorkflow {
        int continuations;
        String next = "PUBLISH";
        List<EvidenceGap> requests = List.of();
        Map<String,Object> receipts;
        public String workflowId() { return "test.model-directed"; }
        public AnalysisWorkflowType type() { return AnalysisWorkflowType.COMPUTATION; }
        public boolean supports(AnalysisContext context, AnalysisIntent intent) { return true; }
        public AnalysisExecutionOutcome execute(AnalysisContext context) { return result("CONTINUE"); }
        public List<EvidenceGap> recoveryRequests(AnalysisContext context, AnalysisExecutionOutcome outcome) { return requests; }
        public AnalysisExecutionOutcome continueAfterRecovery(AnalysisContext context, AnalysisExecutionOutcome previous,
                EvidenceBundle evidence, Map<String,Object> metadata) {
            continuations++; receipts = metadata; return result(next);
        }
    }
    @Test void continuesWithIdenticalEvidenceAndPublishesDespiteAdvisoryVerification() {
        var workflow = new Workflow(); var recovery = mock(EvidenceRecoveryWorkflow.class);
        var outcome = new DefaultAnalysisWorkflowRuntime(List.of(workflow), null, null, List.of(recovery)).analyze(context());
        assertThat(workflow.continuations).isEqualTo(1);
        assertThat(outcome.synthesis()).isEqualTo("Working report");
        assertThat(outcome.metadata()).containsEntry("publicationState", "DELIVERED");
        assertThat(outcome.verification().accepted()).isFalse();
        verifyNoInteractions(recovery);
    }
    @Test void completionWaitAndBudgetNeverAutoPublishRetainedWork() {
        for (String next : List.of("COMPLETE", "PARTIAL_COMPLETE", "WAIT", "CONTINUE")) {
            var workflow = new Workflow(); workflow.next = next;
            var outcome = new DefaultAnalysisWorkflowRuntime(List.of(workflow)).analyze(context());
            assertThat(outcome.synthesis()).isEmpty();
            assertThat(outcome.metadata()).containsEntry("publicationState", "NOT_REQUESTED")
                .containsEntry("modelNativeReportDraft", "Working report");
            assertThat(((Map<?,?>) outcome.metadata().get("modelDecision")).get("action")).isEqualTo(next);
            if (next.equals("CONTINUE")) assertThat(outcome.metadata()).containsEntry("executionStopReason", "RESOURCE_BUDGET_EXHAUSTED");
            if (next.equals("WAIT")) assertThat(outcome.metadata()).containsEntry("executionStopReason", "WAIT_UNSUPPORTED");
        }
    }
    @Test void explicitEvidenceRequestExecutesAndFailureReturnsToModelRatherThanCompletingAnalysis() {
        var workflow = new Workflow(); workflow.next = "COMPLETE";
        var request = new EvidenceGap(EvidenceGapReason.DATA_INCOMPLETE, "claim", null, null, 0, 1, false, false, List.of("chunk2"));
        workflow.requests = List.of(request);
        var recovery = mock(EvidenceRecoveryWorkflow.class);
        when(recovery.supports(any(), eq(request))).thenReturn(true);
        when(recovery.recover(any(), any(), eq(request), eq(1))).thenThrow(new IllegalStateException("tool unavailable"));
        var outcome = new DefaultAnalysisWorkflowRuntime(List.of(workflow), null, null, List.of(recovery)).analyze(context());
        verify(recovery).recover(any(), any(), eq(request), eq(1));
        assertThat(workflow.continuations).isEqualTo(1);
        assertThat(workflow.receipts.get("modelRecoveryReceipts").toString()).contains("TOOL_FAILED");
        assertThat(((Map<?,?>) outcome.metadata().get("modelDecision")).get("action")).isEqualTo("COMPLETE");
        assertThat(outcome.metadata()).containsEntry("executionStopReason", "MODEL_DECISION");
    }
    @Test void explicitRecoverySuccessDoesNotReplaceTheModelsNextDecision() {
        var workflow = new Workflow(); workflow.next = "PARTIAL_COMPLETE";
        var request = new EvidenceGap(EvidenceGapReason.DATA_INCOMPLETE, "claim", null, null, 0, 1, false, false, List.of("chunk2"));
        workflow.requests = List.of(request);
        var recovery = mock(EvidenceRecoveryWorkflow.class);
        when(recovery.supports(any(), eq(request))).thenReturn(true);
        when(recovery.recover(any(), any(), eq(request), eq(1))).thenReturn(new EvidenceRecoveryResult(
            RecoveryStatus.COMPLETE, EvidenceBundle.empty("Acquisition completed"), List.of(), 1,
            RecoveryStrategy.CONTEXT_EXPANSION, RecoveryLevel.L1_CHUNK_EXPANSION, Map.of()));
        var outcome = new DefaultAnalysisWorkflowRuntime(List.of(workflow), null, null, List.of(recovery)).analyze(context());
        verify(recovery).recover(any(), any(), eq(request), eq(1));
        assertThat(((Map<?,?>) outcome.metadata().get("modelDecision")).get("action")).isEqualTo("PARTIAL_COMPLETE");
        assertThat(outcome.metadata()).containsEntry("publicationState", "NOT_REQUESTED");
        assertThat(outcome.synthesis()).isEmpty();
    }
    @Test void explicitGovernanceRejectionBlocksModelPublication() {
        var workflow = new Workflow() {
            public AnalysisExecutionOutcome execute(AnalysisContext context) {
                var original = result("PUBLISH"); var metadata = new LinkedHashMap<>(original.metadata());
                metadata.put("confirmationRequired", true);
                return new AnalysisExecutionOutcome(null, type(), null, original.verification(), original.evidenceBundle(), original.synthesis(), metadata);
            }
        };
        var outcome = new DefaultAnalysisWorkflowRuntime(List.of(workflow)).analyze(context());
        assertThat(outcome.synthesis()).isEmpty();
        assertThat(outcome.metadata()).containsEntry("publicationState", "REJECTED").containsEntry("executionStopReason", "GOVERNANCE_REJECTION");
        assertThat(workflow.continuations).isZero();
    }
}
