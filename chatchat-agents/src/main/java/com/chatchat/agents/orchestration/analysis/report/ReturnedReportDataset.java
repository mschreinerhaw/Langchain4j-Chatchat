package com.chatchat.agents.orchestration.analysis.report;

import java.util.*;

/** A bounded, typed copy made at the successful runtime dataset boundary, never from model output. */
public record ReturnedReportDataset(String reference, List<Map<String, Object>> rows,
                                    int returnedRowCount, boolean complete, Map<String, MetricPolicy> metricPolicies) {
    public record MetricPolicy(String unit, boolean preAggregated, Set<String> allowedOperations) {
        public MetricPolicy { allowedOperations = Set.copyOf(allowedOperations); }
    }
    public ReturnedReportDataset(String reference, List<Map<String, Object>> rows, int count, boolean complete) {
        this(reference, rows, count, complete, Map.of());
    }
    public ReturnedReportDataset {
        rows = rows.stream().map(row -> Collections.unmodifiableMap(new LinkedHashMap<>(row))).toList();
        metricPolicies = Map.copyOf(metricPolicies);
    }

    /** Producer metadata only; never read permissions from a visualization or report. */
    public static ReturnedReportDataset capture(String reference, List<Map<String, Object>> records,
                                                Map<String, Object> context) {
        var captured = capture(reference, records);
        Map<String, MetricPolicy> policies = new LinkedHashMap<>();
        if (context.get("reportMetricPolicies") instanceof Map<?, ?> configured) {
            configured.forEach((key, raw) -> {
                if (policies.size() >= 20 || !(raw instanceof Map<?, ?> policy)) return;
                Set<String> operations = new HashSet<>();
                if (policy.get("allowedOperations") instanceof List<?> list) list.forEach(value -> {
                    String operation = String.valueOf(value).toUpperCase(Locale.ROOT);
                    if (Set.of("SUM", "AVG", "COUNT", "SHARE").contains(operation)) operations.add(operation);
                });
                // Missing aggregation semantics are unknown, not raw records.
                policies.put(String.valueOf(key), new MetricPolicy(
                    policy.get("unit") instanceof String unit ? unit : "",
                    !Boolean.FALSE.equals(policy.get("preAggregated")), operations));
            });
        }
        return new ReturnedReportDataset(reference, captured.rows(), captured.returnedRowCount(), captured.complete(), policies);
    }

    public static ReturnedReportDataset capture(String reference, List<Map<String, Object>> records) {
        List<Map<String, Object>> rows = new ArrayList<>();
        int characters = 0;
        boolean complete = true;
        for (Map<String, Object> record : records) {
            if (rows.size() >= 120) break;
            complete &= !Boolean.FALSE.equals(record.get("sourceComplete"));
            Map<?, ?> fields = record.get("values") instanceof Map<?, ?> values ? values : record;
            Map<String, Object> row = new LinkedHashMap<>();
            for (var field : fields.entrySet()) {
                if (row.size() >= 20) break;
                Object value = field.getValue();
                if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean) {
                    if (String.valueOf(value).length() <= 500) row.put(String.valueOf(field.getKey()), value);
                }
            }
            characters += row.toString().length();
            if (characters > 16000) break;
            rows.add(row);
        }
        return new ReturnedReportDataset(reference, rows, records.size(), complete && rows.size() == records.size());
    }

    public Map<String, Object> promptView() {
        return Map.of("sourceRef", reference, "rows", rows, "returnedRowCount", returnedRowCount,
            "projectedRowCount", rows.size(), "complete", complete, "metricPolicies", metricPolicies,
            "scope", "本次返回记录的有限字段投影；完整性仅指返回记录，不证明业务总体完整");
    }
}
