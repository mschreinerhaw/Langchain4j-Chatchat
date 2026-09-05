package com.chatchat.agents.orchestration.analysis.graph;

import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class FindingAnalysisGraphTest {
    @Test void validFindingDoesNotInvokeRepair() {
        var result = new FindingAnalysisGraph().execute(() -> "valid", value -> false,
            value -> { throw new AssertionError("Unexpected model call"); });
        assertThat(result.product()).isEqualTo("valid");
        assertThat(result.visitedNodes()).containsExactly("analyze", "validate");
    }

    @Test void invalidFindingIsRepairedAndRevalidatedOnlyOnce() {
        var result = new FindingAnalysisGraph().execute(() -> 1, value -> true, value -> value + 1);
        assertThat(result.product()).isEqualTo(2);
        assertThat(result.visitedNodes()).containsExactly("analyze", "validate", "repair", "validate");
    }

    @Test void unavailableRepairPreservesProductAndCancellationPropagates() {
        var result = new FindingAnalysisGraph().execute(() -> "original", value -> true,
            value -> { throw new IllegalStateException("unavailable"); });
        assertThat(result.product()).isEqualTo("original");
        var cancelled = new CancellationException("cancelled");
        assertThatThrownBy(() -> new FindingAnalysisGraph().execute(() -> "original", value -> true,
            value -> { throw cancelled; })).isSameAs(cancelled);
    }
}
