package com.chatchat.agents.orchestration.analysis.report;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** Executes an admitted presentation plan against computed data, never against LLM-supplied rows. */
public final class ChartDataExecutor {
    public List<Map<String, Object>> execute(VerifiedReportDataCatalog.Data data,
                                            VisualizationPlanningContract.Plan plan) {
        var rows = data.rows().stream();
        if (plan != null) rows = rows.sorted(Comparator.comparing(
            row -> (BigDecimal) row.get("value"), Comparator.reverseOrder())).limit(plan.limit());
        // Decimal strings preserve exact table/export values across the JSON/JavaScript boundary.
        return rows.map(row -> Map.<String, Object>of("entity", row.get("entity"),
            "value", ((BigDecimal) row.get("value")).toPlainString())).toList();
    }
}
