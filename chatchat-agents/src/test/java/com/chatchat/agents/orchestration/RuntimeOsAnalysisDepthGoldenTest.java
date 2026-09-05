package com.chatchat.agents.orchestration;

import com.chatchat.agents.orchestration.analysis.model.AnalysisSummaryResult;
import com.chatchat.agents.orchestration.analysis.worker.AnalysisSummaryGovernanceBridge;
import com.chatchat.agents.orchestration.analysis.semantic.SemanticClaimCoordinator;
import com.chatchat.agents.runtime.governance.GovernanceIsolationScope;
import com.chatchat.common.runtime.summary.analysis.model.DataAnalysisPosition;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RuntimeOsAnalysisDepthGoldenTest {

    @Test
    void operationalDiagnosisRetainsDepthGapsAlongsideUsableObservedFacts() {
        AnalysisSummaryGovernanceBridge governance = new AnalysisSummaryGovernanceBridge();
        GovernanceIsolationScope scope = GovernanceIsolationScope.runtime(
            "tenant", "user", "run", "request", "conversation");
        List<Map<String, Object>> records = List.of(Map.of(
            "CURRENT_LEVEL", 37,
            "CONFIGURED_LIMIT", 500,
            "CUMULATIVE_EVENTS", 338844
        ));
        Map<String, Object> context = governance.govern("operational-state", Map.of(), records);
        ChatModel model = mock(ChatModel.class);
        when(model.chat(org.mockito.ArgumentMatchers.anyString())).thenReturn("""
            {
              "summary":"The returned snapshot contains three observed values.",
              "objectiveAlignment":{"addressedAspects":["current state"],
                "unsupportedAspects":["diagnosis"],"contribution":"current observations only"},
              "analysisQuality":{"observedScope":"single snapshot","grain":"instance"},
              "analysisDepth":{"objectiveMode":"DIAGNOSE","addressedDimensions":["STATE"],
                "unsupportedDimensions":["BASELINE","DEVIATION","IMPACT","HYPOTHESIS","VERIFICATION"],
                "comparisonBasis":[],"materialDeviations":[],"impacts":[],"hypotheses":[],
                "verificationNeeds":["comparable baseline and time series"],"prioritizedActions":[]},
              "insights":[{"claimClass":"OBSERVED_RETURNED_FACT",
                "claim":"Current level is 37","significance":"Establishes current state",
                "recordRefs":["operational-state.records[1]"],"supportingValues":["37"],
                "operation":"OBSERVE","confidence":"HIGH","caveats":["single snapshot"]}],
              "facts":[{"claim":"Three values were returned",
                "recordRefs":["operational-state.records[1]"],
                "exactValues":["37","500","338844"]}],
              "recommendedFollowupRequests":[{
                "retrievalGoal":"Retrieve a declared comparable baseline and time series for diagnosis",
                "requiredCapabilities":["COMPARE","TREND"],"timeHorizon":"USER_REQUESTED_SCOPE",
                "grain":"OBSERVATION","priority":"CORE",
                "reason":"Required diagnostic depth is unsupported"}],
              "rawReplayRecommended":false
            }
            """);
        DataAnalysisPosition position = governance.position(
            "operational-state", 1, 1, 1, 1, 1);

        AnalysisSummaryResult worker = governance.summarize(
            model::chat, scope, position, context, records, "Analyze the complete operational state");
        Map<String, Object> metadata = new LinkedHashMap<>();
        Map<String, Object> evidence = new SemanticClaimCoordinator(
            mock(AgentRunResultAdapter.class), "agentRunId").evaluate(
            Map.of("sufficient", true, "gapRequests", List.of()), List.of(worker), 1,
            Map.of("agentRunId", "run"), metadata);

        assertThat(worker.evidence().get("analysisDepth").toString())
            .contains("objectiveMode=DIAGNOSE", "addressedDimensions=[STATE]")
            .contains("BASELINE", "DEVIATION", "IMPACT", "HYPOTHESIS", "VERIFICATION");
        assertThat(worker.evidence().get("insights").toString())
            .contains("Current level is 37")
            .doesNotContain("healthy", "abnormal", "root cause");
        // Sufficiency describes usable evidence. The augmentation policy decides whether
        // to retrieve more or publish limitations; semantic gaps must survive either route.
        assertThat(evidence).containsEntry("sufficient", true)
            .containsEntry("analysisGapsAdvisoryOnly", true);
        assertThat(evidence.get("gapRequests").toString())
            .contains("ANALYSIS_DEPTH", "COMPARE", "TREND", "comparable baseline");
    }
}
