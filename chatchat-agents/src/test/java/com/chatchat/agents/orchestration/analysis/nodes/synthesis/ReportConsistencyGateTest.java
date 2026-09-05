package com.chatchat.agents.orchestration.analysis.nodes.synthesis;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ReportConsistencyGateTest {
    private final ReportConsistencyGate gate = new ReportConsistencyGate();
    private final Map<String, ReportConsistencyGate.Evidence> evidence = Map.of(
        "a", new ReportConsistencyGate.Evidence("MEDIUM", List.of()),
        "b", new ReportConsistencyGate.Evidence("HIGH", List.of()));

    @Test void rejectsSummaryContradictingLaterLimitationOnSameEvidence() {
        assertThat(gate.validate(List.of(
            statement("CORE", "客户共持有20只证券", "a"),
            statement("LIMITATION", "返回持仓仅为采样20条", "a")), evidence))
            .extracting(ReportConsistencyGate.Violation::code)
            .contains("CONTRADICTS_SHARED_EVIDENCE_LIMITATION");
    }
    @Test void unrelatedDatasetLimitationDoesNotVetoSupportedAnalysis() {
        assertThat(gate.validate(List.of(
            statement("CORE", "客户共持有20只证券", "b"),
            statement("LIMITATION", "返回持仓仅为采样20条", "a")), evidence)).isEmpty();
    }
    @Test void scopedSampleAnalysisRemainsPublishable() {
        assertThat(gate.validate(List.of(
            statement("CORE", "持仓样本涉及20只证券", "a"),
            statement("LIMITATION", "返回持仓仅为采样20条", "a")), evidence)).isEmpty();
    }
    @Test void cannotRaiseConfidenceAboveSharedEvidence() {
        assertThat(gate.validate(List.of(new ReportConsistencyGate.Statement(
            "CORE", "当前观测", "HIGH", List.of("a"))), evidence))
            .extracting(ReportConsistencyGate.Violation::code).contains("CONFIDENCE_EXCEEDS_EVIDENCE");
    }
    @Test void explicitUncertaintyCannotBecomeHabitInAnotherChapter() {
        assertThat(gate.validate(List.of(
            statement("CORE", "客户通常满仓", "a"),
            statement("LIMITATION", "无法判断通常的仓位水平", "a")), evidence)).isNotEmpty();
    }
    private ReportConsistencyGate.Statement statement(String section, String text, String id) {
        return new ReportConsistencyGate.Statement(section, text, "", List.of(id));
    }
}
