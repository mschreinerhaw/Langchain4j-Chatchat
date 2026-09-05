package com.chatchat.agents.orchestration.analysis.report;

import java.util.List;
import java.util.Map;

/** Intent selection is constrained by the operation actually executed, never by a model chart payload. */
public final class VisualizationPlanningContract {
    public enum VisualizationIntent {
        TREND, COMPARE, RANK, DISTRIBUTION, COMPOSITION, CORRELATION, CONTRIBUTION, FLOW, ANOMALY, KPI
    }

    public record Plan(String chartId, VisualizationIntent purpose, String chartType,
                       String title, String xField, String yField, String sort, int limit,
                       String unit, List<String> supportsFindingIds) { }

    public Plan plan(String blockId, VerifiedReportDataCatalog.Data data, String requestedIntent) {
        VisualizationIntent intent = switch (data.operation()) {
            case "top_n" -> VisualizationIntent.RANK;
            case "concentration", "contribution" -> VisualizationIntent.CONTRIBUTION;
            default -> VisualizationIntent.KPI;
        };
        if (!requestedIntent.isBlank() && !intent.name().equals(requestedIntent)) return null;
        if (data.rows().size() < 2 || data.unit().isBlank() || intent == VisualizationIntent.KPI) return null;
        if (data.rows().stream().map(row -> row.get("entity")).distinct().count() != data.rows().size()) return null;
        if (data.rows().stream().anyMatch(row -> ((java.math.BigDecimal) row.get("value")).abs()
            .compareTo(new java.math.BigDecimal("9007199254740991")) > 0)) return null;
        // A Top-N slice is not a complete partition: never turn it into a pie/100% chart.
        return new Plan("chart-" + blockId, intent, "HORIZONTAL_BAR", data.title(), "value", "entity",
            "DESC", Math.min(30, data.rows().size()), data.unit(), List.of(blockId));
    }

    public Map<String, Object> render(Plan plan, List<Map<String, Object>> rows) {
        if (plan == null) return Map.of();
        return Map.of("type", "chart", "chartType", "bar", "orientation", "horizontal",
            "title", plan.title(), "chartId", plan.chartId(), "supportsFindingIds", plan.supportsFindingIds(), "plan", plan,
            "dataset", Map.of("columns", List.of("entity", "value"), "rows", rows,
                "xKey", "entity", "xLabel", "对象", "series", List.of(Map.of(
                    "name", plan.title(), "yKey", "value", "unit", plan.unit()))),
            "ui", Map.of("allowSwitch", true, "defaultView", "chart"));
    }
}
