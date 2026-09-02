package com.chatchat.common.runtime.summary.analysis;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DataAnalysisRepairExecutionPolicyTest {

    private final DataAnalysisRepairExecutionPolicy policy =
        new DataAnalysisRepairExecutionPolicy();

    @Test
    void terminatesWhenAnotherRoundProducesNoNewEvidence() {
        DataAnalysisRepairExecutionPolicy.State first = policy.evaluate(
            "repair-1", "gap-a", "evidence-v1", 1, 1, 20L,
            false, null, DataAnalysisRepairExecutionPolicy.Budget.DEFAULT);
        DataAnalysisRepairExecutionPolicy.State second = policy.evaluate(
            "repair-1", "gap-a", "evidence-v1", 0, 0, 10L,
            false, first, DataAnalysisRepairExecutionPolicy.Budget.DEFAULT);

        assertThat(first.executable()).isTrue();
        assertThat(second.executable()).isFalse();
        assertThat(second.terminalReason())
            .isEqualTo(DataAnalysisRepairExecutionPolicy.TerminalReason.NO_NEW_EVIDENCE);
        assertThat(second.modelCallCount()).isEqualTo(1);
        assertThat(second.elapsedMs()).isEqualTo(30L);
        assertThat(policy.evaluate("repair-1", "gap-b", "evidence-v2", 1, 1, 10L,
            false, second, DataAnalysisRepairExecutionPolicy.Budget.DEFAULT)).isEqualTo(second);
    }

    @Test
    void enforcesConfiguredCumulativeModelBudget() {
        DataAnalysisRepairExecutionPolicy.Budget budget =
            new DataAnalysisRepairExecutionPolicy.Budget(4, 1, 4, 1_000L);
        DataAnalysisRepairExecutionPolicy.State first = policy.evaluate(
            "repair-1", "gap-a", "evidence-v1", 1, 0, 10L,
            false, null, budget);
        DataAnalysisRepairExecutionPolicy.State second = policy.evaluate(
            "repair-1", "gap-b", "evidence-v2", 1, 0, 10L,
            false, first, budget);

        assertThat(second.status()).isEqualTo(DataAnalysisRepairExecutionPolicy.Status.TERMINAL);
        assertThat(second.terminalReason())
            .isEqualTo(DataAnalysisRepairExecutionPolicy.TerminalReason.MODEL_BUDGET_EXHAUSTED);
    }
}
