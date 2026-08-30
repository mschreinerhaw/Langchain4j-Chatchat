package com.chatchat.agents.orchestration.analysis;

import com.chatchat.agents.orchestration.analysis.insight.DeterministicInsightEngine;
import com.chatchat.agents.orchestration.analysis.model.SemanticInsightContract;



import com.chatchat.agents.runtime.governance.GovernanceIsolationScope;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DeterministicInsightEngineTest {
    private final DeterministicInsightEngine engine = new DeterministicInsightEngine();
    private final GovernanceIsolationScope tenantA = GovernanceIsolationScope.runtime(
        "tenant-a", "user-a", "run-a", "request-a", "conversation-a");

    @Test
    void executesGenericAggregationContributionConcentrationAndOutlierRecipes() {
        Map<String, Object> context = context("tenant-a", List.of(
            recipe("sum-value", "SUM", Map.of("metric", "value")),
            recipe("top-contribution", "CONTRIBUTION", Map.of(
                "valueMetric", "profit", "groupBy", "entity", "topN", 2, "absoluteValues", true)),
            recipe("concentration", "CONCENTRATION", Map.of(
                "valueMetric", "value", "groupBy", "entity", "topN", 2)),
            recipe("ratio-alert", "OUTLIER_RATIO", Map.of(
                "numerator", "profit", "denominator", "value", "entity", "entity",
                "threshold", 2, "comparator", ">", "absolute", true))));
        List<Map<String, Object>> rows = List.of(
            Map.of("NAME", "A", "VALUE", 60, "PROFIT", 10),
            Map.of("NAME", "B", "VALUE", 30, "PROFIT", -90),
            Map.of("NAME", "C", "VALUE", 10, "PROFIT", 5));

        DeterministicInsightEngine.Result result = engine.analyze(tenantA, "positions", contract(context), rows);

        assertThat(result.executed()).isTrue();
        assertThat(result.findings()).extracting(DeterministicInsightEngine.Finding::type)
            .contains("aggregate", "contribution", "concentration", "outlier");
        assertThat(result.findings().stream().filter(item -> "aggregate".equals(item.type())).findFirst().orElseThrow().value())
            .isEqualByComparingTo("100");
        assertThat(result.findings().stream().filter(item -> "concentration".equals(item.type())).findFirst().orElseThrow().value())
            .isEqualByComparingTo("0.9");
        assertThat(result.findings().stream().filter(item -> "outlier".equals(item.type())).findFirst().orElseThrow().evidenceRefs())
            .containsExactly("positions.records[2].PROFIT", "positions.records[2].VALUE");
    }

    @Test
    void evaluatesDeclaredReconciliationWithoutKnowingBusinessFieldNames() {
        Map<String, Object> context = context("tenant-a", List.of(recipe("balance-check", "RECONCILIATION", Map.of(
            "leftExpression", "total", "rightExpression", "part_a + part_b - liability", "tolerance", "0.01"))));
        Map<String, Object> withFormulaFields = replaceFields(context, List.of(
            field("RAW_TOTAL", "total"), field("RAW_A", "part_a"),
            field("RAW_B", "part_b"), field("RAW_L", "liability")));

        DeterministicInsightEngine.Result result = engine.analyze(tenantA, "dataset", contract(withFormulaFields),
            List.of(Map.of("RAW_TOTAL", "100.00", "RAW_A", "80", "RAW_B", "25", "RAW_L", "5")));

        DeterministicInsightEngine.Finding finding = result.findings().get(0);
        assertThat(finding.type()).isEqualTo("reconciliation_match");
        assertThat(finding.value()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(finding.evidenceRefs()).containsExactlyInAnyOrder(
            "dataset.records[1].RAW_TOTAL", "dataset.records[1].RAW_A",
            "dataset.records[1].RAW_B", "dataset.records[1].RAW_L");
    }

    @Test
    void rejectsCrossTenantAndDraftContracts() {
        DeterministicInsightEngine.Result crossTenant = engine.analyze(tenantA, "data",
            contract(context("tenant-b", List.of())), List.of());
        Map<String, Object> draft = context("tenant-a", List.of());
        @SuppressWarnings("unchecked") Map<String, Object> extension = (Map<String, Object>) draft.get("extensions");
        Map<String, Object> contract = new java.util.LinkedHashMap<>((Map<String, Object>) extension.get("deterministicInsights"));
        contract.put("status", "draft");
        DeterministicInsightEngine.Result unpublished = engine.analyze(tenantA, "data",
            SemanticInsightContract.fromMap(contract), List.of());

        assertThat(crossTenant.status()).isEqualTo("rejected");
        assertThat(crossTenant.issues().get(0).code()).isEqualTo("tenant_scope_mismatch");
        assertThat(unpublished.issues().get(0).code()).isEqualTo("contract_not_published");
    }

    @Test
    void rejectsUnsafeOrUnknownFormulaPerRecipeWithoutFailingOtherRecipes() {
        Map<String, Object> context = replaceFields(context("tenant-a", List.of(
            recipe("unsafe", "RECONCILIATION", Map.of(
                "leftExpression", "total", "rightExpression", "Runtime.exec(1)", "tolerance", 0)),
            recipe("valid", "SUM", Map.of("metric", "total")))), List.of(field("TOTAL", "total")));

        DeterministicInsightEngine.Result result = engine.analyze(tenantA, "data", contract(context),
            List.of(Map.of("TOTAL", 10)));

        assertThat(result.findings()).extracting(DeterministicInsightEngine.Finding::id).containsExactly("valid");
        assertThat(result.issues()).extracting(DeterministicInsightEngine.RecipeIssue::recipeId).containsExactly("unsafe");
    }

    @Test
    void boundsLargeDatasetFindingsAndMasksSensitiveEntityValues() {
        Map<String, Object> context = context("tenant-a", List.of(recipe("alerts", "OUTLIER_RATIO", Map.of(
            "numerator", "profit", "denominator", "value", "entity", "entity",
            "threshold", 1, "maxFindings", 7))));
        context = replaceFields(context, List.of(
            Map.of("field", "NAME", "semantic", "entity", "sensitive", true),
            field("VALUE", "value"), field("PROFIT", "profit")));
        List<Map<String, Object>> records = java.util.stream.IntStream.range(0, 25_000)
            .mapToObj(index -> Map.<String, Object>of("NAME", "customer-" + index, "VALUE", 1, "PROFIT", 2))
            .toList();

        DeterministicInsightEngine.Result result = engine.analyze(tenantA, "large", contract(context), records);

        assertThat(result.findings()).hasSize(7);
        assertThat(result.findings()).allSatisfy(finding ->
            assertThat(finding.details().get("entity")).isEqualTo("***"));
    }

    @Test
    void reconcilesExplicitSemanticsAcrossDatasetsWithoutBusinessNamesInJava() {
        Map<String, Object> recipe = recipe("bundle-check", "BUNDLE_RECONCILIATION", Map.of(
            "leftExpression", "summary.profit", "rightExpression", "open.profit + closed.profit",
            "tolerance", "0.01"));
        List<DeterministicInsightEngine.DatasetInput> datasets = List.of(
            bundleDataset("summary-result", "summary", "TOTAL_P", "profit", "FIRST", recipe,
                List.of(Map.of("TOTAL_P", "42263.81"))),
            bundleDataset("open-result", "open", "ROW_P", "profit", "SUM", recipe,
                List.of(Map.of("ROW_P", "20000.00"), Map.of("ROW_P", "20563.24"))),
            bundleDataset("closed-result", "closed", "ROW_P", "profit", "SUM", recipe,
                List.of(Map.of("ROW_P", "1700.57"))));

        DeterministicInsightEngine.Result result = engine.analyzeBundle(tenantA, datasets);

        assertThat(result.findings()).hasSize(1);
        assertThat(result.findings().get(0).type()).isEqualTo("bundle_reconciliation_match");
        assertThat(result.findings().get(0).value()).isEqualByComparingTo("0.00");
        assertThat(result.findings().get(0).evidenceRefs()).containsExactlyInAnyOrder(
            "summary-result.records[1].TOTAL_P", "open-result.records[1].ROW_P",
            "open-result.records[2].ROW_P", "closed-result.records[1].ROW_P");
    }

    private Map<String, Object> context(String tenantId, List<Map<String, Object>> recipes) {
        return Map.of("extensions", Map.of("deterministicInsights", Map.of(
            "schemaVersion", "semantic_insight_contract.v1", "tenantId", tenantId,
            "contractId", "generic-test", "version", "1", "status", "published",
            "fields", List.of(field("NAME", "entity"), field("VALUE", "value"), field("PROFIT", "profit")),
            "recipes", recipes)));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> replaceFields(Map<String, Object> context, List<Map<String, Object>> fields) {
        Map<String, Object> contract = new java.util.LinkedHashMap<>((Map<String, Object>)
            ((Map<String, Object>) context.get("extensions")).get("deterministicInsights"));
        contract.put("fields", fields);
        return Map.of("extensions", Map.of("deterministicInsights", contract));
    }

    @SuppressWarnings("unchecked")
    private SemanticInsightContract contract(Map<String, Object> context) {
        Map<String, Object> extensions = (Map<String, Object>) context.get("extensions");
        return SemanticInsightContract.fromMap(
            (Map<String, Object>) extensions.get("deterministicInsights"));
    }

    private Map<String, Object> field(String raw, String semantic) {
        return Map.of("field", raw, "semantic", semantic, "label", semantic, "unit", "unit");
    }

    private Map<String, Object> recipe(String id, String operator, Map<String, Object> parameters) {
        Map<String, Object> result = new java.util.LinkedHashMap<>(parameters);
        result.put("id", id); result.put("operator", operator); result.put("label", id);
        return result;
    }

    private DeterministicInsightEngine.DatasetInput bundleDataset(
        String reference, String alias, String rawField, String semantic, String aggregation,
        Map<String, Object> recipe, List<Map<String, Object>> records) {
        Map<String, Object> contract = Map.of(
            "tenantId", "tenant-a", "contractId", alias, "version", "1", "status", "published",
            "datasetAlias", alias,
            "fields", List.of(Map.of("field", rawField, "semantic", semantic, "aggregation", aggregation)),
            "recipes", List.of(recipe));
        return new DeterministicInsightEngine.DatasetInput(reference,
            SemanticInsightContract.fromMap(contract), records);
    }
}
