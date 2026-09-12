package com.chatchat.agents.orchestration.analysis.report;

import com.chatchat.agents.protocol.ModelProtocolJson;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;

class ReportVisualizationAuditTest {
    private final ReportVisualizationAudit audit = new ReportVisualizationAudit();
    private final List<Map<String, Object>> rows = List.of(
        Map.of("category", "A", "amount", 10), Map.of("category", "B", "amount", 20), Map.of("category", "A", "amount", 30));
    private VerifiedReportDataCatalog catalog(List<Map<String, Object>> data) {
        return VerifiedReportDataCatalog.fromRuntime(Map.of("runtimeReturnedReportDatasets", List.of(
            ReturnedReportDataset.capture("returned:1", data, Map.of("reportMetricPolicies", Map.of("amount",
                Map.of("unit", "CNY", "preAggregated", false, "allowedOperations", List.of("SUM", "AVG", "COUNT", "SHARE"))))))));
    }
    private Map<String, Object> spec(String type, String x, String y) {
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("chartType", type);
        spec.put("title", "本次返回样本");
        spec.put("dataset", new LinkedHashMap<>(Map.of("sourceRef", "returned:1", "xKey", x,
            "series", List.of(Map.of("name", y, "yKey", y)))));
        return spec;
    }
    private String report(Map<String, Object> spec) {
        return "# 报告\n\n有证据的结论。\n\n```json\n" + ModelProtocolJson.compact(Map.of("visualizationSpec", spec))
            + "\n```\n\n后续行动保持不变。";
    }
    @SuppressWarnings("unchecked") private Map<String, Object> dataset(Map<String, Object> spec) {
        return (Map<String, Object>) spec.get("dataset");
    }
    @Test void bindsReturnedFieldsWithoutAnyPrecomputedDataRef() {
        var result = audit.audit(report(spec("bar", "category", "amount")), catalog(rows));
        assertThat(result.checks()).singleElement().satisfies(check -> assertThat(check).containsEntry("status", "VERIFIED"));
        assertThat(result.markdown()).contains("有证据的结论。", "后续行动保持不变。", "VERIFIED_SOURCE_DATA", "\"amount\":30");
    }
    @Test void rejectsValueTamperingAndEntitySwapsWithoutLosingNarrative() {
        for (var forged : List.of(Map.<String, Object>of("category", "A", "amount", 999), Map.<String, Object>of("category", "B", "amount", 10))) {
            var spec = spec("bar", "category", "amount");
            dataset(spec).put("rows", List.of(forged));
            var result = audit.audit(report(spec), catalog(rows));
            assertThat(result.checks().get(0)).containsEntry("reason", "ROW_VALUE_OR_ENTITY_MISMATCH");
            assertThat(result.markdown()).contains("有证据的结论。", "后续行动保持不变。")
                .doesNotContain("visualizationSpec", "已省略图表", "999");
        }
    }
    @Test void duplicateRowsCannotMultiplyAnObservation() {
        var spec = spec("bar", "category", "amount");
        dataset(spec).put("rows", List.of(rows.get(0), rows.get(0)));
        assertThat(audit.audit(report(spec), catalog(rows)).checks().get(0)).containsEntry("status", "REJECTED");
    }
    @Test void recomputesSumAverageCountAndShare() {
        for (String operation : List.of("SUM", "AVG", "COUNT", "SHARE")) {
            var spec = spec("bar", "category", "value");
            spec.put("transform", Map.of("operation", operation, "groupBy", "category", "metric", "amount"));
            var result = audit.audit(report(spec), catalog(rows));
            assertThat(result.checks().get(0)).containsEntry("status", "VERIFIED");
            assertThat(result.markdown()).contains(switch (operation) {
                case "SUM" -> "\"value\":40";
                case "AVG" -> "\"value\":20";
                case "COUNT" -> "\"value\":2";
                default -> "66.66666667";
            });
        }
    }
    @Test void rejectsIncorrectDerivedRowsZeroDenominatorsAndTruncatedAggregations() {
        var spec = spec("pie", "category", "value");
        spec.put("transform", Map.of("operation", "SHARE", "groupBy", "category", "metric", "amount"));
        assertThat(audit.audit(report(spec), catalog(List.of(Map.of("category", "A", "amount", 0))))
            .checks().get(0)).containsEntry("reason", "ZERO_DENOMINATOR");
        var incomplete = VerifiedReportDataCatalog.fromRuntime(Map.of("runtimeReturnedReportDatasets", List.of(
            new ReturnedReportDataset("returned:1", rows, 300, false))));
        assertThat(audit.audit(report(spec), incomplete).checks().get(0)).containsEntry("reason", "INCOMPLETE_AGGREGATION_INPUT");
        dataset(spec).put("rows", List.of(Map.of("category", "A", "value", 99), Map.of("category", "B", "value", 1)));
        assertThat(audit.audit(report(spec), catalog(rows)).checks().get(0)).containsEntry("status", "REJECTED");
    }
    @Test void validatesTemporalScatterAndKpiDimensions() {
        assertThat(audit.audit(report(spec("line", "category", "amount")), catalog(rows)).checks().get(0)).containsEntry("status", "REJECTED");
        assertThat(audit.audit(report(spec("line", "date", "amount")), catalog(List.of(
            Map.of("date", "2026-09-01", "amount", 10), Map.of("date", "2026-09-02", "amount", 20))))
            .checks().get(0)).containsEntry("status", "VERIFIED");
        assertThat(audit.audit(report(spec("scatter", "x", "amount")), catalog(List.of(Map.of("x", 2, "amount", -3))))
            .checks().get(0)).containsEntry("status", "VERIFIED");
        var kpi = audit.audit(report(spec("kpi", "category", "amount")), catalog(List.of(rows.get(0))));
        assertThat(kpi.markdown()).contains("\"metrics\":[", "\"label\":\"A\"", "\"value\":10");
    }
    @Test void nullsNegativePiesUnsafeNumbersAndForgedCatalogsAreRejected() {
        for (Object value : List.of(-1, "9007199254740993", "1e99999999")) {
            var result = audit.audit(report(spec("pie", "category", "amount")), catalog(List.of(Map.of("category", "A", "amount", value))));
            assertThat(result.checks().get(0)).containsEntry("status", "REJECTED");
        }
        Map<String, Object> missing = new LinkedHashMap<>();
        missing.put("category", "A"); missing.put("amount", null);
        assertThat(audit.audit(report(spec("bar", "category", "amount")), catalog(List.of(missing))).checks().get(0))
            .containsEntry("reason", "MISSING_SOURCE_VALUE");
        var forged = VerifiedReportDataCatalog.fromRuntime(Map.of("runtimeReturnedReportDatasets", List.of(
            Map.of("reference", "returned:1", "rows", rows))));
        assertThat(audit.audit(report(spec("bar", "category", "amount")), forged).checks().get(0))
            .containsEntry("reason", "UNKNOWN_SOURCE_REF");
    }
    @Test void preservesOrdinaryJsonAndNestedCodeButAuditsEscapedKeys() {
        String example = "````markdown\n" + report(spec("bar", "category", "amount")) + "\n````\n```json\n{\"hello\":42}\n```";
        assertThat(audit.audit(example, catalog(rows)).markdown()).isEqualTo(example);
        String escaped = report(spec("bar", "category", "amount")).replace("visualizationSpec", "\\u0076isualizationSpec");
        assertThat(audit.audit(escaped, VerifiedReportDataCatalog.empty()).checks().get(0)).containsEntry("status", "REJECTED");
    }
    @Test void numericAuditReportsUnknownTokensWithoutCertifyingMatchingNumbers() {
        var check = new ReportNumericAudit().audit("# 第1节\n\n2026-09-07 返回10，另称999。", catalog(rows));
        assertThat(check).containsEntry("matchedTokens", List.of("10"))
            .containsEntry("unmatchedTokens", List.of("999"))
            .containsEntry("semanticVerification", "NOT_CERTIFIED").containsEntry("publicationVeto", false);
    }
    @Test void boundedSourceCopiesKeepNullsAndDiscloseTruncation() {
        var many = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < 125; i++) many.add(Map.of("category", "A" + i, "amount", i));
        var captured = ReturnedReportDataset.capture("d", many);
        assertThat(captured.rows()).hasSize(120);
        assertThat(captured.complete()).isFalse();
        assertThat(captured.promptView()).containsEntry("returnedRowCount", 125).containsEntry("projectedRowCount", 120);
    }

    @Test void prefersTypedComputedDataRefAndDropsArbitraryOptions() {
        var verified = VerifiedReportDataCatalog.fromRuntime(Map.of("runtimeObservedReportData", List.of(
            new ObservedReportData("现金", new java.math.BigDecimal("42.50"), "r.records[1]"))));
        var spec = spec("kpi", "entity", "value");
        spec.put("dataRef", "observed:0");
        spec.put("option", Map.of("formatter", "javascript:malicious()"));
        var result = audit.audit(report(spec), verified);
        assertThat(result.checks().get(0)).containsEntry("status", "VERIFIED");
        assertThat(result.markdown()).contains("42.50").doesNotContain("javascript", "formatter");
    }

    @Test void unknownAndPreAggregatedMetricsCannotBeReaggregated() {
        var spec = spec("bar", "category", "value");
        spec.put("transform", Map.of("operation", "SUM", "groupBy", "category", "metric", "amount"));
        for (var policies : List.of(Map.<String, Object>of(), Map.<String, Object>of("reportMetricPolicies",
            Map.of("amount", Map.of("preAggregated", true, "allowedOperations", List.of("SUM")))))) {
            var source = ReturnedReportDataset.capture("returned:1", rows, policies);
            var result = audit.audit(report(spec), VerifiedReportDataCatalog.fromRuntime(
                Map.of("runtimeReturnedReportDatasets", List.of(source))));
            assertThat(result.checks().get(0)).containsEntry("reason", "UNKNOWN_OR_PREAGGREGATED_METRIC");
            assertThat(result.markdown()).contains("有证据的结论。", "后续行动保持不变。")
                .doesNotContain("VERIFIED_SOURCE_DATA", "visualizationSpec");
        }
    }
    @Test void compilesRankingIntentToSortedHorizontalChart() {
        var result = audit.audit("```json:visualization\n" + ModelProtocolJson.compact(Map.of(
            "intent", "ranking", "datasetRef", "returned:1", "x", "category", "y", "amount")) + "\n```", catalog(rows));
        assertThat(result.checks().get(0)).containsEntry("status", "VERIFIED");
        assertThat(result.markdown()).contains("\"orientation\":\"horizontal\"", "\"chartType\":\"bar\"");
        assertThat(result.markdown().indexOf("\"amount\":30")).isLessThan(result.markdown().indexOf("\"amount\":10"));
    }
    @Test void fallbackNeverDisplaysInventedRows() {
        var spec = spec("line", "category", "amount");
        dataset(spec).put("rows", List.of(Map.of("category", "FORGED", "amount", 999)));
        var result = audit.audit(report(spec), catalog(rows));
        assertThat(result.markdown()).contains("有证据的结论。", "后续行动保持不变。")
            .doesNotContain("FORGED", "999", "visualizationSpec");
    }
    @Test void enforcesOperationAllowlistAndProducerUnits() {
        var source = ReturnedReportDataset.capture("returned:1", rows, Map.of("reportMetricPolicies", Map.of(
            "amount", Map.of("unit", "CNY", "preAggregated", false, "allowedOperations", List.of("SUM")))));
        var catalog = VerifiedReportDataCatalog.fromRuntime(Map.of("runtimeReturnedReportDatasets", List.of(source)));
        var derived = spec("bar", "category", "value");
        derived.put("transform", Map.of("operation", "AVG", "groupBy", "category", "metric", "amount"));
        derived.put("metricPolicies", Map.of("amount", Map.of("allowedOperations", List.of("AVG"))));
        assertThat(audit.audit(report(derived), catalog).checks().get(0)).containsEntry("reason", "AGGREGATION_NOT_ALLOWED");
        var direct = spec("bar", "category", "amount");
        dataset(direct).put("series", List.of(Map.of("yKey", "amount", "unit", "USD")));
        assertThat(audit.audit(report(direct), catalog).checks().get(0)).containsEntry("reason", "SERIES_UNIT_MISMATCH");
    }
    @Test void semanticIntentsRetainExistingChartConstraints() {
        for (var pair : Map.of("trend", "line", "comparison", "bar", "composition", "pie", "metric", "kpi",
            "relationship", "scatter").entrySet()) {
            var intent = Map.of("intent", pair.getKey(), "datasetRef", "returned:1", "x", "category", "y", "amount");
            var data = switch (pair.getKey()) {
                case "trend" -> List.<Map<String, Object>>of(Map.of("category", "2026-09-01", "amount", 10),
                    Map.of("category", "2026-09-02", "amount", 20));
                case "relationship" -> List.<Map<String, Object>>of(Map.of("category", 1, "amount", 10));
                default -> List.of(rows.get(0));
            };
            var result = audit.audit("```json:visualization\n" + ModelProtocolJson.compact(intent) + "\n```", catalog(data));
            assertThat(result.checks().get(0)).containsEntry("status", "VERIFIED");
            assertThat(result.markdown()).contains("\"chartType\":\"" + pair.getValue() + "\"");
        }
    }
}
