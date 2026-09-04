package com.chatchat.agents.orchestration.analysis.semantic;

import com.chatchat.agents.orchestration.AgentRunResultAdapter;
import com.chatchat.agents.orchestration.analysis.model.AnalysisSummaryResult;
import com.chatchat.agents.runtime.governance.GovernanceIsolationScope;
import com.chatchat.common.runtime.summary.analysis.semantic.SemanticClaimLifecycleContract;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class SemanticGapEvidenceBridgeTest {

    @Test
    void semanticGapRemainsAdvisoryWithoutChangingEvidenceSufficiency() {
        AgentRunResultAdapter adapter = mock(AgentRunResultAdapter.class);
        SemanticClaimCoordinator bridge = new SemanticClaimCoordinator(adapter, "agentRunId");
        Map<String, Object> gap = Map.of("gapId", "gap-1", "route", "RETRIEVE_MORE");
        Map<String, Object> request = Map.of(
            "questionId", "gap-1",
            "requiredCapabilities", List.of("TREND"),
            "targetGrain", "DAY"
        );
        AnalysisSummaryResult summary = AnalysisSummaryResult.chunk(
            GovernanceIsolationScope.runtime("tenant", "user", "run", "request", "conversation"),
            Map.of("datasetReference", "dataset-1"), Map.of(), "analysis", "COMPLETE",
            Map.of("semanticGaps", List.of(gap), "semanticGapRequests", List.of(request, request))
        );
        Map<String, Object> metadata = new java.util.LinkedHashMap<>();

        Map<String, Object> merged = bridge.evaluate(
            Map.of("sufficient", true, "gapRequests", List.of()),
            List.of(summary), 2, Map.of("agentRunId", "run"), metadata);

        assertThat(merged).containsEntry("sufficient", true)
            .containsEntry("analysisGapsAdvisoryOnly", true);
        assertThat((List<?>) merged.get("semanticGaps")).hasSize(1);
        assertThat((List<?>) merged.get("gapRequests")).hasSize(1);
        assertThat(metadata).containsEntry("semanticClaimGapCount", 1)
            .containsEntry("semanticClaimActionableGapCount", 1);
        verify(adapter).recordRuntimeObservation(any(), eq("agentRunId"), any(),
            eq("business_analysis_progress"), any());

        Map<String, Object> repeated = bridge.evaluate(merged, List.of(summary), 3,
            Map.of("agentRunId", "run"), metadata);
        assertThat((List<?>) repeated.get("semanticGapRequests")).isEmpty();
        assertThat(metadata).containsEntry("semanticGapTerminalCount", 1);
        assertThat(asMapList(metadata.get("semanticGapResolutionStates")).get(0))
            .containsEntry("terminalReason", "NO_NEW_EVIDENCE")
            .containsEntry("lastResolution", "ANALYZE_WITH_LIMITATIONS");
    }

    @Test
    void preflightPreservesCancellationAndDegradesOtherFailures() {
        SemanticClaimCoordinator bridge = new SemanticClaimCoordinator(
            mock(AgentRunResultAdapter.class), "agentRunId");
        Map<String, Object> metadata = new java.util.LinkedHashMap<>();

        assertThatThrownBy(() -> bridge.preflight(
            () -> { throw new CancellationException("cancelled"); }, () -> "empty", Map.of(), metadata))
            .isInstanceOf(CancellationException.class);
        assertThat(bridge.preflight(
            () -> { throw new IllegalStateException("unavailable"); }, () -> "empty", Map.of(), metadata))
            .isEqualTo("empty");
        assertThat(metadata).containsEntry("semanticClaimPreflightFailed", true)
            .containsEntry("semanticClaimPreflightFailure", "unavailable");
    }

    @Test
    void newEvidenceCreatesChildClaimRevisionWithoutOverwritingHistory() {
        SemanticClaimCoordinator bridge = new SemanticClaimCoordinator(
            mock(AgentRunResultAdapter.class), "agentRunId");
        Map<String, Object> metadata = new java.util.LinkedHashMap<>();
        SemanticClaimLifecycleContract.Revision rejected = SemanticClaimLifecycleContract.evolve(
            "claim-fingerprint", "evidence-v1", false, List.of("TIME_SCOPE_MISMATCH"), "gap-1", null);
        AnalysisSummaryResult first = summary(Map.of("claimLifecycle", List.of(rejected.toMap())));
        bridge.evaluate(Map.of(), List.of(first), 1, Map.of(), metadata);

        SemanticClaimLifecycleContract.Revision admittedDraft = SemanticClaimLifecycleContract.evolve(
            "claim-fingerprint", "evidence-v2", true, List.of(), "", null);
        Map<String, Object> merged = bridge.evaluate(Map.of(),
            List.of(summary(Map.of("claimLifecycle", List.of(admittedDraft.toMap())))),
            2, Map.of(), metadata);

        List<Map<String, Object>> latest = asMapList(merged.get("claimLifecycle"));
        assertThat(latest.get(0)).containsEntry("revision", 2).containsEntry("state", "ADMITTED")
            .containsEntry("parentClaimId", rejected.claimId());
        assertThat(asMapList(metadata.get("semanticClaimHistory"))).hasSize(2);
    }

    @Test
    void analyticalDepthGapIsRetainedForReviewWithoutBlockingAnalysis() {
        SemanticClaimCoordinator bridge = new SemanticClaimCoordinator(
            mock(AgentRunResultAdapter.class), "agentRunId");
        Map<String, Object> metadata = new java.util.LinkedHashMap<>();
        Map<String, Object> request = Map.of(
            "retrievalGoal", "Retrieve a comparable baseline and time series for the requested diagnosis",
            "requiredCapabilities", List.of("COMPARE", "TREND"),
            "timeHorizon", "USER_REQUESTED_SCOPE",
            "grain", "OBSERVATION",
            "priority", "CORE",
            "reason", "Baseline and deviation are unsupported"
        );
        AnalysisSummaryResult summary = summary(Map.of(
            "contentSha256", "evidence-v1",
            "recommendedFollowupRequests", List.of(request)));

        Map<String, Object> first = bridge.evaluate(
            Map.of("sufficient", true, "gapRequests", List.of()), List.of(summary), 1,
            Map.of("agentRunId", "run"), metadata);

        assertThat(first).containsEntry("sufficient", true)
            .containsEntry("analysisGapsAdvisoryOnly", true);
        assertThat(asMapList(first.get("analysisDepthGapRequests"))).singleElement()
            .satisfies(gap -> assertThat(gap)
                .containsEntry("gapSource", "ANALYSIS_DEPTH")
                .containsEntry("retrievalGoal",
                    "Retrieve a comparable baseline and time series for the requested diagnosis"));
        assertThat(asMapList(first.get("gapRequests"))).hasSize(1);

        Map<String, Object> repeated = bridge.evaluate(first, List.of(summary), 2,
            Map.of("agentRunId", "run"), metadata);
        assertThat(asMapList(repeated.get("analysisDepthGapRequests"))).isEmpty();
        assertThat(metadata).containsEntry("analysisDepthGapTerminalCount", 1);
        assertThat(asMapList(metadata.get("analysisDepthGapResolutionStates")).get(0))
            .containsEntry("terminalReason", "NO_NEW_EVIDENCE")
            .containsEntry("lastResolution", "ANALYZE_WITH_LIMITATIONS");
    }

    private AnalysisSummaryResult summary(Map<String, Object> evidence) {
        return AnalysisSummaryResult.chunk(
            GovernanceIsolationScope.runtime("tenant", "user", "run", "request", "conversation"),
            Map.of("datasetReference", "dataset-1"), Map.of(), "analysis", "COMPLETE", evidence);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> asMapList(Object value) {
        return (List<Map<String, Object>>) value;
    }
}
