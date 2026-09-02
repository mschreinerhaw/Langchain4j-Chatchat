package com.chatchat.agents.orchestration.analysis.summary;

import com.chatchat.agents.orchestration.analysis.model.AnalysisSummaryResult;
import com.chatchat.agents.runtime.governance.GovernanceIsolationScope;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AnalysisReportLogProjectionTest {

    @Test
    void retainsAnalysisFormationButExcludesPromptsRawRowsAndSupportingValues() {
        AnalysisSummaryResult report = AnalysisSummaryResult.chunk(
            GovernanceIsolationScope.runtime("tenant", "user", "run", "request", "conversation"),
            Map.of("datasetReference", "dataset", "chunkIndex", 1), Map.of(),
            "本轮分析摘要", "MODEL_SUMMARY", Map.of(
                "prompt", "secret system prompt",
                "rawRecords", List.of(Map.of("customer", "sensitive value")),
                "facts", List.of(Map.of("supportingValues", List.of("sensitive value"))),
                "demandAnalysis", Map.of("decisionGoal", "定位变化原因"),
                "metricAssociations", List.of(Map.of("metric", "变化率")),
                "businessConclusions", List.of("需要补充基线"),
                "missingEvidence", List.of("历史同期"),
                "insights", List.of(Map.of("claimId", "claim-1"))));

        Map<String, Object> projection = AnalysisReportLogProjection.project("WORKER", report);

        assertThat(projection).containsEntry("summary", "本轮分析摘要")
            .containsEntry("insightCount", 1)
            .containsKeys("demandAnalysis", "metricAssociations", "businessConclusions",
                "missingEvidence")
            .doesNotContainKeys("prompt", "rawRecords", "facts", "evidence", "position");
        assertThat(projection.toString()).doesNotContain("secret system prompt", "sensitive value");
        assertThat(AnalysisReportLogProjection.project("DRIVER", report, 3))
            .containsEntry("inputReportCount", 3);
    }
}
