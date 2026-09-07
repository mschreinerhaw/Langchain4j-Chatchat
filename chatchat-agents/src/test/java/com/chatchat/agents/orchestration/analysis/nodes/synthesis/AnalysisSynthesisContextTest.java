package com.chatchat.agents.orchestration.analysis.nodes.synthesis;

import com.chatchat.agents.orchestration.analysis.model.AnalysisSummaryResult;
import com.chatchat.agents.runtime.governance.GovernanceIsolationScope;
import com.chatchat.agents.protocol.ModelProtocolJson;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class AnalysisSynthesisContextTest {
    @Test void carriesAuthoredAnalysisAndDeclaredSemanticsWithExplicitBounds() {
        var scope = GovernanceIsolationScope.runtime("tenant", "run", "request", "conversation", "user");
        var report = AnalysisSummaryResult.intermediateSummary(scope, "DATASET_SYNTHESIS", "source-a",
            "The device's measure changed; the calibration basis remains unknown.", "MODEL_DATASET_REDUCE",
            Map.of(), Map.of(), Map.of(), List.of(), Map.of("analysisSemanticContract", Map.of(
                "semanticAuthority", "PRODUCER_DECLARED", "semantics", Map.of("MEASURE", "observed reading, calibration unknown"))));
        var context = new AnalysisSynthesisContext().build(List.of(report), List.of(), Map.of(), Map.of());
        String inputs = ModelProtocolJson.compact(context.get("modelAnalysisInputs"));
        assertThat(inputs).contains(report.content(), "observed reading, calibration unknown", "PRODUCER_DECLARED")
            .contains("\"narrativeTruncated\":false", "\"semanticsTruncated\":false", "\"omittedReportCount\":0");

        var large = AnalysisSummaryResult.intermediateSummary(scope, "DATASET_SYNTHESIS", "source-b",
            "Model analysis. ".repeat(1000), "MODEL_DATASET_REDUCE", Map.of(), Map.of(), Map.of(), List.of(), Map.of());
        String bounded = ModelProtocolJson.compact(new AnalysisSynthesisContext()
            .build(java.util.Collections.nCopies(20, large), List.of(), Map.of(), Map.of()).get("modelAnalysisInputs"));
        assertThat(bounded).contains("\"narrativeTruncated\":true");
        assertThat(bounded.length()).isLessThan(22_000);
    }

    @Test void consolidatedInputDoesNotReplayWorkerAnalysisButKeepsItsIdentity() {
        var scope = GovernanceIsolationScope.runtime("tenant", "run", "request", "conversation", "user");
        var worker = AnalysisSummaryResult.intermediateSummary(scope, "DATASET_SYNTHESIS", "worker",
            "worker narrative", "SUCCESS", Map.of(), Map.of(), Map.of(), List.of(), Map.of("demandAnalysis",
                Map.of("detail", "UNIQUE_WORKER_DETAIL".repeat(1000))));
        var reducer = AnalysisSummaryResult.intermediateSummary(scope, "DATASET_SYNTHESIS", "reducer",
            "consolidated", "SUCCESS", Map.of(), Map.of(), Map.of(), List.of(), Map.of());
        var builder = new AnalysisSynthesisContext();
        String original = ModelProtocolJson.compact(builder.build(List.of(worker), List.of(), Map.of(), Map.of()));
        String compact = ModelProtocolJson.compact(builder.build(List.of(worker), List.of(reducer), Map.of(), Map.of()));
        assertThat(original).contains("UNIQUE_WORKER_DETAIL");
        assertThat(compact).doesNotContain("UNIQUE_WORKER_DETAIL").contains(worker.resultId(), reducer.resultId());
        assertThat(compact.length()).isLessThan(original.length());
    }

    @Test void carriesAdaptivePromptGuidanceIntoFinalCompositionContext() {
        var context = new AnalysisSynthesisContext().build(List.of(), List.of(), Map.of(),
            Map.of("adaptiveAnalysisPromptContract", Map.of(
                "schemaVersion", "dynamic_analysis_prompt.v1",
                "authority", "ANALYSIS_GUIDANCE_ONLY",
                "output", List.of("EXECUTIVE_SUMMARY", "KEY_FINDINGS"))));
        assertThat(context.get("adaptiveAnalysisPrompt").toString())
            .contains("dynamic_analysis_prompt.v1", "EXECUTIVE_SUMMARY", "ANALYSIS_GUIDANCE_ONLY");
    }
}
