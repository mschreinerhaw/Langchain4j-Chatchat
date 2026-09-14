package com.chatchat.agents.orchestration.analysis.governance;

import com.chatchat.agents.orchestration.analysis.model.AnalysisExecutionOutcome;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AnalysisExecutionOutcomeRecorderTest {

    private final AnalysisExecutionOutcomeRecorder recorder =
        new AnalysisExecutionOutcomeRecorder();

    @Test
    void successfulEmptyProjectionIsNotClassifiedAsDataFailure() {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("analysisDatasetProjectionCompleted", true);
        metadata.put("analysisCompletionOutcome", "NO_DATA");

        AnalysisExecutionOutcome outcome = recorder.recordBlocked(
            metadata, 0, List.of(), List.of(), "NO_ANALYSIS_REQUIRED", false);

        assertThat(outcome.failureCategory())
            .isEqualTo(AnalysisExecutionOutcome.FailureCategory.ANALYSIS_FAILURE);
        assertThat(outcome.dataStatus())
            .isEqualTo(AnalysisExecutionOutcome.PhaseStatus.COMPLETED);
        assertThat(metadata).containsEntry("validEmptyEvidence", true);
    }

    @Test
    void explicitSourceFailureRemainsADataFailure() {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("analysisDatasetProjectionCompleted", true);
        metadata.put("analysisCompletionOutcome", "FAILED");
        metadata.put("recordAnalysisFailedDatasetCount", 1);

        AnalysisExecutionOutcome outcome = recorder.recordBlocked(
            metadata, 0, List.of(), List.of(), "SOURCE_EXECUTION_FAILED", false);

        assertThat(outcome.failureCategory())
            .isEqualTo(AnalysisExecutionOutcome.FailureCategory.DATA_FAILURE);
        assertThat(outcome.dataStatus())
            .isEqualTo(AnalysisExecutionOutcome.PhaseStatus.FAILED);
        assertThat(metadata).containsEntry("validEmptyEvidence", false);
    }
}
