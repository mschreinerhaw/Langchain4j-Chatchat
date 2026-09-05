package com.chatchat.agents.orchestration.analysis.report;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ObservedReportDataTest {
    @Test
    void bindsExactReturnedNumbersWithoutInferringUnitsOrAcceptingModelMaps() {
        var observations = ObservedReportData.capture("logs", List.of(Map.of(
            "sourcePath", "$.result", "values", Map.of("errors", 237,
                "fraction", new BigDecimal("0.1234567890123456789"),
                "missing", "unknown", "invalid", Double.NaN))));
        assertThat(observations).hasSize(2);
        assertThat(observations).allMatch(value -> value.recordRef().equals("logs.records[1]"));
        var catalog = VerifiedReportDataCatalog.fromRuntime(Map.of("runtimeObservedReportData", observations));
        assertThat(catalog.promptView()).hasSize(2);
        assertThat(catalog.promptView()).allSatisfy(value -> {
            assertThat(value).containsEntry("operation", "observe").containsEntry("unit", "");
            assertThat(value.get("recordRefs")).isEqualTo(List.of("logs.records[1]"));
        });
        assertThat(VerifiedReportDataCatalog.fromRuntime(Map.of("runtimeObservedReportData",
            List.of(Map.of("label", "invented", "value", 999, "recordRef", "logs.records[1]"))))
            .promptView()).isEmpty();
    }
}
