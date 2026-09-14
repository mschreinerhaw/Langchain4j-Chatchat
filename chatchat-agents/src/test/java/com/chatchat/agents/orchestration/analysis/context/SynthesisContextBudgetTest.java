package com.chatchat.agents.orchestration.analysis.context;

import com.chatchat.agents.orchestration.planning.model.AgentContextBudget;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SynthesisContextBudgetTest {
    @Test void derivesEverySynthesisAllocationFromTheActiveModelWindowAndOutputReserve() {
        var budget = SynthesisContextBudget.from(new AgentContextBudget(100_000, 5_000, 10_000, 15_000));

        assertThat(budget.inputTokens()).isEqualTo(70_000);
        assertThat(budget.pipelineTokens()).isEqualTo(17_500);
        assertThat(budget.claimLedgerTokens()).isEqualTo(21_000);
        assertThat(budget.datasetTokens()).isEqualTo(14_000);
        assertThat(budget.reservedOutputTokens()).isEqualTo(15_000);
        assertThat(SynthesisContextBudget.fromRuntime(
            java.util.Map.of(SynthesisContextBudget.RUNTIME_KEY, budget.toMap()))).isEqualTo(budget);
    }
}
