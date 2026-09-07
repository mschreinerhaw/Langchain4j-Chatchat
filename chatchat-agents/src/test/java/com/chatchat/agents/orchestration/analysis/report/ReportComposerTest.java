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
    void compatibleComparisonIntentUsesVerifiedValuesWithoutInventingACompletePartition() {
        var catalog = catalog("executed", "top_n");
        var comparison = compose(catalog, "compare", "computed:0:ranking");
        assertThat(comparison.presentation().primaryPresentation()).isEqualTo("CHART");
        assertThat(comparison.data().get("rows"))
            .isEqualTo(compose(catalog, "RANK", "computed:0:ranking").data().get("rows"));
        assertThat(compose(catalog, "COMPOSITION", "computed:0:ranking").visualization()).isEmpty();
        assertThat(compose(catalog, null, "computed:0:ranking").visualization()).isNotEmpty();
    }

    @Test
    void modelShapedMapsAreNotExecutorResults() {
        var catalog = VerifiedReportDataCatalog.fromRuntime(Map.of("deterministicInsightResults", List.of(
            Map.of("status", "executed", "findings", List.of(Map.of("id", "forged", "value", 999))))));
        assertThat(catalog.promptView()).isEmpty();
    }

    @Test
    void returnedRecordEvidenceRemainsAPrimaryFindingWithoutChartData() {
        var block = composer.compose("F1", "CORE", "what happened", "one returned row has value 42",
            "this is a fact for the observed row", "use it within the observed scope", "HIGH", List.of(),
            List.of(Map.of(
                "status", "SUPPORTED",
                "recordRefs", List.of("result.records[0]"),
                "supportingValues", List.of("42")
            )), "", "", VerifiedReportDataCatalog.fromRuntime(Map.of()));

        assertThat(block.presentation().primaryConclusion()).isTrue();
        assertThat(block.presentation().primaryPresentation()).isEqualTo("TEXT");
        assertThat(block.presentation().validationStatus()).isEqualTo("VERIFIED_EVIDENCE_BOUND");
        assertThat(block.caveats()).doesNotContain("未绑定可验证的计算数据；保留为待验证说明，不进入核心业务结论。");
    }

    @Test
    void limitedEvidenceIsPublishedWithBoundariesWhileRejectedEvidenceIsNot() {
        var limited = composer.compose("F1", "CORE", "question", "supported observation", "", "", "MEDIUM",
            List.of(), List.of(Map.of("status", "REVIEW_REQUIRED",
                "recordRefs", List.of("result.records[0]"), "supportingValues", List.of("42"),
                "reviewReasons", List.of("scope requires review"))), "", "",
            VerifiedReportDataCatalog.fromRuntime(Map.of()));
        var rejected = composer.compose("F2", "CORE", "question", "unsupported observation", "", "", "LOW",
            List.of(), List.of(Map.of("status", "REJECTED",
                "recordRefs", List.of("result.records[0]"), "supportingValues", List.of("42"))), "", "",
            VerifiedReportDataCatalog.fromRuntime(Map.of()));

        assertThat(limited.presentation().primaryConclusion()).isTrue();
        assertThat(limited.presentation().validationStatus()).isEqualTo("LIMITED_EVIDENCE_BOUND");
        assertThat(rejected.presentation().primaryConclusion()).isFalse();
        assertThat(rejected.presentation().validationStatus()).isEqualTo("INSUFFICIENT_DATA");
    }

    @Test
    void verifiedFindingIsPromotedIntoExecutiveSummaryRegardlessOfModelSection() {
        var verified = composer.compose("F1", "LIMITATION", "", "history fields are absent", "", "", "HIGH",
            List.of(), List.of(Map.of("status", "SUPPORTED",
                "recordRefs", List.of("result.records[0]"), "supportingValues", List.of("null"))), "", "",
            VerifiedReportDataCatalog.fromRuntime(Map.of()));

        assertThat(composer.markdown("can flow be measured", List.of(verified)))
            .contains("## 核心业务判断", "- history fields are absent", "## 分析发现 1")
            .doesNotContain("暂无同时绑定计算数据与证据的核心结论");
    }

    @Test
    void executiveSummaryQuotesOnlyTheLeadSentenceOfEachConclusion() {
        var block = composer.compose("F1", "CORE", "资产结构如何？",
            "总资产为847174.25元。证券市值占比99.89%，呈现满仓状态。", "", "", "HIGH",
            List.of(), List.of(Map.of("status", "SUPPORTED",
                "recordRefs", List.of("r.records[0]"), "supportingValues", List.of("847174.25"))), "", "",
            VerifiedReportDataCatalog.fromRuntime(Map.of()));

        String markdown = composer.markdown("客户资产如何", List.of(block));

        assertThat(markdown).contains("- 总资产为847174.25元。\n");
        assertThat(markdown).doesNotContain("- 总资产为847174.25元。证券市值占比99.89%");
        assertThat(markdown).contains("总资产为847174.25元。证券市值占比99.89%，呈现满仓状态。");
    }

    @Test
    void markdownRendersLabeledDetailsChineseConfidenceAndMergedSources() {
        var block = composer.compose("F1", "CORE", "资产结构如何？", "总资产为847174.25元。",
            "比较基准：总资产100%。\n比较结果：证券市值占比99.89%。\n资金几乎全部转化为证券持仓。",
            "满仓状态缺乏现金缓冲", "HIGH", List.of(),
            List.of(Map.of("status", "SUPPORTED", "recordRefs", List.of("r.records[0]"),
                    "supportingValues", List.of("847174.25"), "sourceScope", "livedata_cx_mncg_khzc_r"),
                Map.of("status", "SUPPORTED", "recordRefs", List.of("q.records[0]"),
                    "supportingValues", List.of("99.89"), "sourceScope", "livedata_cx_mncg_qcfx")),
            "", "", VerifiedReportDataCatalog.fromRuntime(Map.of()));

        String markdown = composer.markdown("客户资产如何", List.of(block));

        assertThat(markdown)
            .contains("解释与判断：\n\n- 比较基准：总资产100%。\n- 比较结果：证券市值占比99.89%。\n- 资金几乎全部转化为证券持仓。")
            .contains("判断可信度：高")
            .contains("数据来源：livedata_cx_mncg_khzc_r、livedata_cx_mncg_qcfx")
            .doesNotContain("判断可信度：HIGH");
    }

    @Test
    void repeatedCaveatsAppearOnlyOnceAcrossTheReport() {
        var evidence = List.<Map<String, Object>>of(Map.of("status", "SUPPORTED",
            "recordRefs", List.of("r.records[0]"), "supportingValues", List.of("42")));
        var first = composer.compose("F1", "CORE", "q1", "observation one", "", "", "HIGH",
            List.of("资产数据为单日截面数据", "仅展示部分持仓记录"), evidence, "", "",
            VerifiedReportDataCatalog.fromRuntime(Map.of()));
        var second = composer.compose("F2", "DEEP_DIVE", "q2", "observation two", "", "", "MEDIUM",
            List.of("资产数据为单日截面数据"), evidence, "", "",
            VerifiedReportDataCatalog.fromRuntime(Map.of()));

        String markdown = composer.markdown("q", List.of(first, second));

        assertThat(markdown.indexOf("限制：资产数据为单日截面数据"))
            .isEqualTo(markdown.lastIndexOf("限制：资产数据为单日截面数据"));
        assertThat(markdown).contains("限制：仅展示部分持仓记录", "判断可信度：中");
    }
}
