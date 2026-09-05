package com.chatchat.agents.orchestration.analysis.report;

import com.chatchat.agents.orchestration.analysis.insight.DeterministicInsightEngine;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class ReportComposerTest {
    private final ReportComposer composer = new ReportComposer();

    private VerifiedReportDataCatalog catalog(String status, String type) {
        var finding = new DeterministicInsightEngine.Finding("ranking", type, "规模排名",
            new BigDecimal("0.75"), "ratio", "top / total", List.of("d.records[1].value", "d.records[2].value"),
            Map.of("valueUnit", "万份", "items", List.of(
                Map.of("entity", "001234", "value", new BigDecimal("10.125")),
                Map.of("entity", "005678", "value", new BigDecimal("20.500")))));
        return VerifiedReportDataCatalog.fromRuntime(Map.of("deterministicInsightResults", List.of(
            Map.of("status", status, "findings", List.of(finding)))));
    }

    private AnalyticalInsightBlock compose(VerifiedReportDataCatalog catalog, String intent, String ref) {
        return composer.compose("F1", "CORE", "规模如何分布？", "规模集中", "需分层分析", "区分头尾产品",
            "HIGH", List.of(), List.of(Map.of("recordRefs", List.of("d.records[1]", "d.records[2]"))),
            ref, intent, catalog);
    }

    @Test
    void chartTableAndMetricUseExecutorValuesWithSeparateUnits() {
        var block = compose(catalog("executed", "concentration"), "CONTRIBUTION", "computed:0:ranking");
        assertThat(block.presentation().primaryConclusion()).isTrue();
        assertThat(block.presentation().primaryPresentation()).isEqualTo("CHART");
        assertThat(block.data()).containsEntry("metric", "0.75").containsEntry("metricUnit", "ratio")
            .containsEntry("unit", "万份");
        var dataset = (Map<?, ?>) block.visualization().get("dataset");
        assertThat(dataset.get("rows")).isEqualTo(block.data().get("rows"));
        assertThat(block.data().get("rows")).isEqualTo(List.of(
            Map.of("entity", "005678", "value", "20.500"), Map.of("entity", "001234", "value", "10.125")));
        assertThat(block.visualization()).containsEntry("orientation", "horizontal");
    }

    @Test
    void missingOrFailedDataCannotBecomePrimaryConclusion() {
        for (var block : List.of(compose(catalog("rejected", "top_n"), "RANK", "computed:0:ranking"),
            compose(catalog("executed", "top_n"), "RANK", "invented"))) {
            assertThat(block.presentation().primaryConclusion()).isFalse();
            assertThat(block.presentation().primaryPresentation()).isEqualTo("DATA_STATUS");
            assertThat(block.visualization()).isEmpty();
            assertThat(block.data()).isEmpty();
            assertThat(block.caveats()).isNotEmpty();
        }
    }

    @Test
    void unrelatedRecordLineageCannotBeBoundEvenWithSameValues() {
        var block = composer.compose("F1", "CORE", "q", "observation", "", "", "HIGH", List.of(),
            List.of(Map.of("recordRefs", List.of("other.records[1]", "d.records[20]"))),
            "computed:0:ranking", "RANK", catalog("executed", "top_n"));
        assertThat(block.presentation().primaryConclusion()).isFalse();
        assertThat(block.data()).isEmpty();
    }

    @Test
    void unsupportedIntentKeepsVerifiedTableInsteadOfInventingTrend() {
        var block = compose(catalog("executed", "top_n"), "TREND", "computed:0:ranking");
        assertThat(block.presentation().primaryPresentation()).isEqualTo("TABLE");
        assertThat(block.visualization()).isEmpty();
        assertThat(block.data().get("rows")).isNotNull();
    }

    @Test
    void modelShapedMapsAreNotExecutorResults() {
        var catalog = VerifiedReportDataCatalog.fromRuntime(Map.of("deterministicInsightResults", List.of(
            Map.of("status", "executed", "findings", List.of(Map.of("id", "forged", "value", 999))))));
        assertThat(catalog.promptView()).isEmpty();
    }
}
