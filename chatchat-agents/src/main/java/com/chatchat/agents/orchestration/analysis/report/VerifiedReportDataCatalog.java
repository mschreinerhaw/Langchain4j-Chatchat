package com.chatchat.agents.orchestration.analysis.report;

import com.chatchat.agents.orchestration.analysis.insight.DeterministicInsightEngine;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Reads only typed results emitted by the runtime's deterministic executor in this run. */
public final class VerifiedReportDataCatalog {
    public record Data(String id, String title, String operation, String unit, BigDecimal metric,
                       String metricUnit, String calculation, List<String> recordRefs,
                       List<Map<String, Object>> rows) {
        public Map<String, Object> toMap() {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("dataRef", id); value.put("title", title); value.put("operation", operation);
            value.put("unit", unit); value.put("metricUnit", metricUnit);
            if (metric != null) value.put("metric", metric.toPlainString());
            value.put("calculation", calculation); value.put("recordRefs", recordRefs);
            value.put("columns", List.of("entity", "value")); value.put("rows", rows);
            value.put("scope", "仅对应计算结果覆盖的记录；排名明细可能是样本子集");
            return Collections.unmodifiableMap(value);
        }
    }

    private final Map<String, Data> entries;
    private final Map<String, ReturnedReportDataset> datasets;
    private VerifiedReportDataCatalog(Map<String, Data> entries) { this(entries, Map.of()); }
    private VerifiedReportDataCatalog(Map<String, Data> entries, Map<String, ReturnedReportDataset> datasets) {
        this.entries = Map.copyOf(entries);
        this.datasets = Map.copyOf(datasets);
    }
    public static VerifiedReportDataCatalog empty() { return new VerifiedReportDataCatalog(Map.of()); }

    public static VerifiedReportDataCatalog fromRuntime(Map<String, Object> metadata) {
        Map<String, Data> entries = new LinkedHashMap<>();
        List<?> results = metadata.get("deterministicInsightResults") instanceof List<?> list ? list : List.of();
        int resultIndex = 0;
        for (Object raw : results) {
            int index = resultIndex++;
            if (!(raw instanceof Map<?, ?> result) || !"executed".equals(result.get("status"))
                || !(result.get("findings") instanceof List<?> findings)) continue;
            for (Object item : findings) {
                // Arbitrary model maps with the same shape are deliberately not admitted.
                if (!(item instanceof DeterministicInsightEngine.Finding finding)
                    || finding.evidenceRefs() == null || finding.evidenceRefs().isEmpty()
                    || finding.value() == null) continue;
                if (entries.size() >= 60) break;
                String id = "computed:" + index + ":" + finding.id();
                Map<String, Object> details = finding.details() == null ? Map.of() : finding.details();
                String unit = String.valueOf(details.getOrDefault("valueUnit", finding.unit() == null ? "" : finding.unit()));
                List<Map<String, Object>> rows = new ArrayList<>();
                if (details.get("items") instanceof List<?> items && !"ratio".equals(unit)) {
                    for (Object row : items) {
                        if (!(row instanceof Map<?, ?> cells) || cells.get("entity") == null
                            || !(cells.get("value") instanceof BigDecimal number)) { rows.clear(); break; }
                        rows.add(Map.of("entity", cells.get("entity").toString(), "value", number));
                    }
                }
                entries.put(id, new Data(id, safe(finding.label()), safe(finding.type()), unit,
                    finding.value(), safe(finding.unit()), safe(finding.calculation()),
                    List.copyOf(finding.evidenceRefs()), List.copyOf(rows)));
            }
        }
        if (metadata.get("runtimeObservedReportData") instanceof List<?> observations) {
            int ordinal = 0;
            for (Object raw : observations) {
                if (!(raw instanceof ObservedReportData observed) || observed.value() == null
                    || observed.recordRef() == null || observed.recordRef().isBlank()) continue;
                if (entries.size() >= 60) break;
                String id = "observed:" + ordinal++;
                entries.put(id, new Data(id, observed.label(), "observe", "", observed.value(), "",
                    "Exact returned value; no computation or inferred unit", List.of(observed.recordRef()), List.of()));
            }
        }
        Map<String, ReturnedReportDataset> datasets = new LinkedHashMap<>();
        if (metadata.get("runtimeReturnedReportDatasets") instanceof List<?> returned) {
            for (Object raw : returned) {
                if (raw instanceof ReturnedReportDataset dataset) datasets.putIfAbsent(dataset.reference(), dataset);
            }
        }
        return new VerifiedReportDataCatalog(entries, datasets);
    }

    public Data get(String id) { return entries.get(id); }
    public ReturnedReportDataset dataset(String reference) { return datasets.get(reference); }
    public List<Map<String, Object>> datasetPromptView() {
        return datasetPromptProjection(32_000).datasets();
    }
    public DatasetPromptProjection datasetPromptProjection(int tokenBudget) {
        List<Map<String, Object>> projected = new ArrayList<>();
        List<String> omitted = new ArrayList<>();
        var estimator = new com.chatchat.agents.orchestration.analysis.context.ContextTokenEstimator();
        long tokens = 0;
        for (var dataset : datasets.values().stream().sorted(java.util.Comparator.comparing(ReturnedReportDataset::reference)).toList()) {
            Map<String, Object> view = dataset.promptView();
            long size = estimator.estimate(view).tokens();
            if (tokens + size > Math.max(0, tokenBudget)) {
                omitted.add(dataset.reference());
                continue;
            }
            projected.add(view);
            tokens += size;
        }
        return new DatasetPromptProjection(List.copyOf(projected), List.copyOf(omitted), tokens);
    }
    public List<String> datasetReferences() { return datasets.keySet().stream().sorted().toList(); }
    public int datasetCount() { return datasets.size(); }
    public List<Map<String, Object>> promptView() {
        return entries.values().stream().sorted(java.util.Comparator.comparing(Data::id)).map(Data::toMap).toList();
    }
    private static String safe(String text) { return text == null ? "" : text; }

    public record DatasetPromptProjection(List<Map<String, Object>> datasets,
                                          List<String> omittedDatasetReferences,
                                          long estimatedTokens) {}
}
