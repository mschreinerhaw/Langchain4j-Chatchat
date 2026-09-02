package com.chatchat.agents.orchestration.analysis;

import com.chatchat.agents.orchestration.analysis.model.AnalysisExecutionOutcome;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AnalysisExecutionOutcomeTest {

    @Test
    void reanalysisOutcomeKeepsDatasetAndProhibitsDuplicateAcquisition() {
        AnalysisExecutionOutcome outcome = new AnalysisExecutionOutcome(
            AnalysisExecutionOutcome.SCHEMA_VERSION,
            AnalysisExecutionOutcome.ExecutionStatus.NEEDS_REANALYSIS,
            AnalysisExecutionOutcome.FailureCategory.ANALYSIS_FAILURE,
            AnalysisExecutionOutcome.PhaseStatus.COMPLETED,
            AnalysisExecutionOutcome.PhaseStatus.REJECTED,
            AnalysisExecutionOutcome.PhaseStatus.BLOCKED,
            AnalysisExecutionOutcome.PhaseStatus.BLOCKED,
            AnalysisExecutionOutcome.PhaseStatus.NOT_STARTED,
            List.of(),
            new AnalysisExecutionOutcome.RetryDirective(
                "RETRY_ANALYSIS", "WORKER_ANALYSIS", true, false, 1),
            AnalysisExecutionOutcome.Publishability.GOVERNED_FAILURE_REPORT_ONLY,
            "NO_ADMITTED_WORKER_REPORT");

        assertThat(outcome.toMap())
            .containsEntry("status", "NEEDS_REANALYSIS")
            .containsEntry("failureCategory", "ANALYSIS_FAILURE");
        assertThat((Map<String, Object>) outcome.toMap().get("retryDirective"))
            .containsEntry("reuseExistingDataset", true)
            .containsEntry("dataAcquisitionAllowed", false);
        assertThat(outcome.failureReport())
            .contains("分析未完成", "支撑数据", "禁止重新查询相同数据")
            .doesNotContain("请稍后重试", "查询结果明细");
    }

    @Test
    void evidenceGapRoutesToGapPlannerInsteadOfWorkerOnlyRetry() {
        AnalysisExecutionOutcome outcome = new AnalysisExecutionOutcome(
            AnalysisExecutionOutcome.SCHEMA_VERSION,
            AnalysisExecutionOutcome.ExecutionStatus.NEEDS_MORE_EVIDENCE,
            AnalysisExecutionOutcome.FailureCategory.EVIDENCE_GAP,
            AnalysisExecutionOutcome.PhaseStatus.COMPLETED,
            AnalysisExecutionOutcome.PhaseStatus.COMPLETED,
            AnalysisExecutionOutcome.PhaseStatus.COMPLETED,
            AnalysisExecutionOutcome.PhaseStatus.BLOCKED,
            AnalysisExecutionOutcome.PhaseStatus.REJECTED,
            List.of(Map.of("requestId", "gap-1", "goal", "obtain comparison baseline")),
            new AnalysisExecutionOutcome.RetryDirective(
                "PLAN_GAP_RETRIEVAL", "GAP_PLANNER", true, true, 1),
            AnalysisExecutionOutcome.Publishability.GOVERNED_FAILURE_REPORT_ONLY,
            "SEMANTIC_EVIDENCE_GAP");

        assertThat(outcome.failureReport())
            .contains("1 个机器可处理的证据或分析缺口", "Gap Planner");
        assertThat(outcome.retryDirective().resumeFrom()).isEqualTo("GAP_PLANNER");
    }
}
