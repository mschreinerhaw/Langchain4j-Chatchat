package com.chatchat.agents.orchestration.analysis.report;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;

class ReportFactBindingAuditTest {
    private final ReportFactBindingAudit audit = new ReportFactBindingAudit();
    private VerifiedReportDataCatalog catalog(boolean complete) {
        return VerifiedReportDataCatalog.fromRuntime(Map.of("runtimeReturnedReportDatasets", List.of(
            new ReturnedReportDataset("d", List.of(
                Map.of("客户", "001", "期间", "2026-09", "金额", 10, "单位", "元"),
                Map.of("客户", "002", "期间", "2026-08", "金额", 20, "单位", "元")), 2, complete))));
    }
    private String table(String row) {
        return "|客户|期间|金额|单位|\n|---|---|---|---|\n" + row;
    }
    @Test void bindsWholeTupleAndPreservesLeadingZeros() {
        var result = audit.audit(table("|001|2026-09|10.00|元|"), catalog(true));
        assertThat(result.checks().get(0)).containsEntry("status", "BOUND_SOURCE_ROW");
        assertThat(result.markdown()).isEqualTo(table("|001|2026-09|10.00|元|"));
    }
    @Test void detectsEntityPeriodUnitAndValueSwapsEvenWhenNumberExists() {
        for (String row : List.of("|002|2026-09|10|元|", "|001|2026-08|10|元|",
            "|001|2026-09|10|万元|", "|001|2026-09|20|元|", "|1|2026-09|10|元|")) {
            var result = audit.audit(table(row), catalog(true));
            assertThat(result.checks().get(0)).containsEntry("status", "ROW_BINDING_MISMATCH");
            assertThat(result.markdown()).isEqualTo(table(row));
        }
    }
    @Test void incompleteProjectionDoesNotCertifyContradictions() {
        var result = audit.audit(table("|003|2026-09|30|元|"), catalog(false));
        assertThat(result.checks().get(0)).containsEntry("status", "UNRESOLVED");
        assertThat(result.markdown()).doesNotContain("核验提示");
    }
    @Test void omittedDimensionsAndCodeExamplesRemainUncertified() {
        assertThat(audit.audit("|客户|金额|\n|---|---|\n|001|10|", catalog(true)).checks()).isEmpty();
        String code = "````markdown\n```\n" + table("|001|2026-09|20|元|") + "\n```\n````";
        assertThat(audit.audit(code, catalog(true)).checks()).isEmpty();
    }
    @Test void explicitProseChecksKeepEntityPeriodAndUnitTogether() {
        var conflict = audit.audit("客户001在2026-09的金额为20元。", catalog(true));
        assertThat(conflict.checks()).singleElement().satisfies(check -> assertThat(check)
            .containsEntry("status", "EXPLICIT_VALUE_CONFLICT").containsEntry("sourceValue", "10"));
        assertThat(audit.audit("客户001在2026-09的金额为10元。", catalog(true)).checks().get(0))
            .containsEntry("status", "EXPLICIT_VALUE_MATCH");
        assertThat(audit.audit("金额为10元。", catalog(true)).checks()).isEmpty();
        assertThat(conflict.markdown()).isEqualTo("客户001在2026-09的金额为20元。");
        assertThat(audit.audit("客户001在2026-09-01的金额为10元。", catalog(true)).checks()).isEmpty();
    }
    @Test void malformedTablesAndCompetingSourcesDoNotInventBindings() {
        assertThat(audit.audit("|\n|---|\n|", catalog(true)).checks()).isEmpty();
        var first = catalog(true).dataset("d");
        var second = new ReturnedReportDataset("other", List.of(
            Map.of("客户", "001", "期间", "2026-09", "金额", 20, "单位", "元")), 1, true);
        var ambiguous = VerifiedReportDataCatalog.fromRuntime(Map.of("runtimeReturnedReportDatasets", List.of(first, second)));
        var result = audit.audit("客户001在2026-09的金额为20元。", ambiguous);
        assertThat(result.checks()).isEmpty();
        assertThat(result.markdown()).doesNotContain("核验提示");
    }
}
