package com.chatchat.agents.runtime.plan;

import com.chatchat.common.tool.ToolOutput;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RetrievalQualityGateTest {

    @Test
    void usesDeclaredResultCountPaths() {
        RetrievalQualityGate.Evaluation evaluation = RetrievalQualityGate.evaluate(
            ToolOutput.success(Map.of("coverage", Map.of("processedFieldCount", 3))),
            Map.of(
                "minimumResultCount", 1,
                "countPaths", List.of("coverage.processedFieldCount", "results")
            )
        );

        assertThat(evaluation.success()).isTrue();
        assertThat(evaluation.resultCount()).isEqualTo(3);
        assertThat(evaluation.sufficient()).isTrue();
    }

    @Test
    void successfulFallbackWinsOnlyWhenItImprovesQuality() {
        RetrievalQualityGate.Evaluation enhanced =
            new RetrievalQualityGate.Evaluation(true, 0, 1, false, "below_result_threshold");
        RetrievalQualityGate.Evaluation fallback =
            new RetrievalQualityGate.Evaluation(true, 2, 1, true, "threshold_met");

        assertThat(RetrievalQualityGate.preferFallback(enhanced, fallback)).isTrue();
        assertThat(RetrievalQualityGate.preferFallback(fallback, enhanced)).isFalse();
    }
}
