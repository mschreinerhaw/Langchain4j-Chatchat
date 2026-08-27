package com.chatchat.agents.runtime.analysis;

import com.chatchat.agents.runtime.protocol.RuntimeResultAnalysisAdapter;
import com.chatchat.agents.runtime.protocol.RuntimeResultAnalysisProtocol;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.ServiceLoader;

/** Default adapter registry and canonical projection implementation for MCP result analysis. */
public final class McpResultAnalysisBridge implements RuntimeResultAnalysisProtocol {

    private final List<RuntimeResultAnalysisAdapter> analysisAdapters;

    public McpResultAnalysisBridge() {
        this(loadAnalysisAdapters());
    }

    public McpResultAnalysisBridge(List<RuntimeResultAnalysisAdapter> analysisAdapters) {
        List<RuntimeResultAnalysisAdapter> configured = new ArrayList<>();
        configured.add(new CommandStreamResultAnalysisAdapter());
        if (analysisAdapters != null) configured.addAll(analysisAdapters);
        configured.add(new GenericResultAnalysisAdapter());
        this.analysisAdapters = configured.stream()
            .filter(Objects::nonNull)
            .sorted((left, right) -> Integer.compare(right.priority(), left.priority()))
            .toList();
    }

    @Override
    public Map<String, Object> analysisProjection(String datasetReference, Object boundedPayload) {
        return analysisProjection(datasetReference, boundedPayload, 10_000);
    }

    @Override
    public Map<String, Object> analysisProjection(String datasetReference,
                                                  Object boundedPayload,
                                                  int maximumRecordChars) {
        return project(datasetReference, boundedPayload, maximumRecordChars, true);
    }

    @Override
    public Map<String, Object> protocolAnalysisProjection(String datasetReference,
                                                          Object boundedPayload,
                                                          int maximumRecordChars) {
        return project(datasetReference, boundedPayload, maximumRecordChars, false);
    }

    private Map<String, Object> project(String datasetReference,
                                        Object boundedPayload,
                                        int maximumRecordChars,
                                        boolean includeFallback) {
        RuntimeResultAnalysisAdapter.AnalysisRequest request =
            new RuntimeResultAnalysisAdapter.AnalysisRequest(
                datasetReference, boundedPayload, maximumRecordChars);
        RuntimeResultAnalysisAdapter adapter = analysisAdapters.stream()
            .filter(candidate -> includeFallback || !candidate.fallback())
            .filter(candidate -> candidate.supports(request))
            .findFirst()
            .orElse(null);
        if (adapter == null) return Map.of();
        RuntimeResultAnalysisAdapter.AnalysisResult result = adapter.adapt(request);
        if (result == null || result.datasets().isEmpty()) return Map.of();
        List<Map<String, Object>> datasets = result.datasets().stream()
            .filter(dataset -> dataset != null && !dataset.records().isEmpty())
            .map(dataset -> Map.<String, Object>of(
                "datasetReference", text(dataset.datasetReference()),
                "analysisContext", dataset.analysisContext(),
                "records", dataset.records()))
            .toList();
        if (datasets.isEmpty()) return Map.of();
        Map<String, Object> projection = new LinkedHashMap<>();
        projection.put("schemaVersion", PROJECTION_SCHEMA_VERSION);
        projection.put("adapterId", adapter.id());
        putIfPresent(projection, "sourceSchemaVersion", result.sourceSchemaVersion());
        projection.put("evidenceRole", text(result.evidenceRole()));
        projection.put("authoritativePayloadMutated", false);
        projection.put("datasets", datasets);
        return Map.copyOf(projection);
    }

    private static List<RuntimeResultAnalysisAdapter> loadAnalysisAdapters() {
        List<RuntimeResultAnalysisAdapter> adapters = new ArrayList<>();
        ServiceLoader.load(RuntimeResultAnalysisAdapter.class).forEach(adapters::add);
        return List.copyOf(adapters);
    }

    private String text(Object value) {
        return value == null || String.valueOf(value).isBlank() ? "unknown" : String.valueOf(value);
    }

    private void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (value != null && !String.valueOf(value).isBlank()) target.put(key, value);
    }
}
