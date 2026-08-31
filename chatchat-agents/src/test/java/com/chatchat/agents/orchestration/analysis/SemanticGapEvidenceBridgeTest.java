package com.chatchat.agents.orchestration.analysis;

import com.chatchat.agents.orchestration.AgentRunResultAdapter;
import com.chatchat.agents.orchestration.analysis.model.AnalysisSummaryResult;
import com.chatchat.agents.orchestration.analysis.summary.SemanticGapEvidenceBridge;
import com.chatchat.agents.runtime.governance.GovernanceIsolationScope;
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
    void actionableSemanticGapJoinsExistingEvidenceLoopWithoutDuplicates() {
        AgentRunResultAdapter adapter = mock(AgentRunResultAdapter.class);
        SemanticGapEvidenceBridge bridge = new SemanticGapEvidenceBridge(adapter, "agentRunId");
        Map<String, Object> gap = Map.of("gapId", "gap-1", "route", "RETRIEVE_MORE");
        Map<String, Object> request = Map.of(
            "questionId", "semantic-gap:gap-1",
            "requiredCapabilities", List.of("TREND"),
            "targetGrain", "DAY"
        );
        AnalysisSummaryResult summary = AnalysisSummaryResult.chunk(
            GovernanceIsolationScope.runtime("tenant", "user", "run", "request", "conversation"),
            Map.of("datasetReference", "dataset-1"), Map.of(), "analysis", "COMPLETE",
            Map.of("semanticGaps", List.of(gap), "semanticGapRequests", List.of(request, request))
        );
        Map<String, Object> metadata = new java.util.LinkedHashMap<>();

        Map<String, Object> merged = bridge.merge(
            Map.of("sufficient", true, "gapRequests", List.of()),
            List.of(summary), 2, Map.of("agentRunId", "run"), metadata);

        assertThat(merged.get("sufficient")).isEqualTo(false);
        assertThat((List<?>) merged.get("semanticGaps")).hasSize(1);
        assertThat((List<?>) merged.get("gapRequests")).hasSize(1);
        assertThat(metadata).containsEntry("semanticClaimGapCount", 1)
            .containsEntry("semanticClaimActionableGapCount", 1);
        verify(adapter).recordRuntimeObservation(any(), eq("agentRunId"), any(),
            eq("business_analysis_progress"), any());
    }

    @Test
    void preflightPreservesCancellationAndDegradesOtherFailures() {
        SemanticGapEvidenceBridge bridge = new SemanticGapEvidenceBridge(
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
}
