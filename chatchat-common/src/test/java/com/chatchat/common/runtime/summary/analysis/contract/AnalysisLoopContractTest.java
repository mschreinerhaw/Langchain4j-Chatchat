package com.chatchat.common.runtime.summary.analysis.contract;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AnalysisLoopContractTest {

    @Test
    void coreCoverageAndGapFingerprintAreDeterministic() {
        AnalysisLoopContract.QuestionCoverage level = new AnalysisLoopContract.QuestionCoverage(
            "q-level", "current level", AnalysisLoopContract.Criticality.SUPPORTING,
            AnalysisLoopContract.CoverageStatus.SUPPORTED, List.of("level"), List.of("e1"), List.of());
        AnalysisLoopContract.QuestionCoverage flow = new AnalysisLoopContract.QuestionCoverage(
            "q-flow", "change and flow", AnalysisLoopContract.Criticality.CORE,
            AnalysisLoopContract.CoverageStatus.UNSUPPORTED, List.of("history", "change"),
            List.of(), List.of("comparison period"));
        AnalysisLoopContract.GapRequest gap = new AnalysisLoopContract.GapRequest(
            "q-flow", "retrieve comparison-period observations", List.of("history", "change"),
            "comparison periods required by the question", "same entity and metric grain",
            AnalysisLoopContract.Criticality.CORE, "only a current-period observation is available");

        AnalysisLoopContract first = AnalysisLoopContract.of("analyze flow", List.of(level, flow), List.of(gap));
        AnalysisLoopContract second = AnalysisLoopContract.of("analyze flow", List.of(level, flow), List.of(gap));

        assertThat(first.coreCoverageComplete()).isFalse();
        assertThat(first.gapFingerprint()).isEqualTo(second.gapFingerprint()).hasSize(64);
        assertThat(first.toMap()).containsEntry("schemaVersion", AnalysisLoopContract.SCHEMA_VERSION);
    }
}
