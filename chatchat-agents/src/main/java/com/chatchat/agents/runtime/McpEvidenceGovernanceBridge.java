package com.chatchat.agents.runtime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.ServiceLoader;

/**
 * The mandatory bridge between MCP runtime output and every downstream observation or summary.
 */
public final class McpEvidenceGovernanceBridge {

    public static final String ANALYSIS_PROJECTION_SCHEMA_VERSION = "mcp_analysis_projection.v1";

    private final List<McpResultAnalysisAdapter> analysisAdapters;

    public McpEvidenceGovernanceBridge() {
        this(loadAnalysisAdapters());
    }

    public McpEvidenceGovernanceBridge(List<McpResultAnalysisAdapter> analysisAdapters) {
        List<McpResultAnalysisAdapter> configured = new ArrayList<>();
        configured.add(new CommandStreamResultAnalysisAdapter());
        if (analysisAdapters != null) configured.addAll(analysisAdapters);
        configured.add(new GenericResultAnalysisAdapter());
        this.analysisAdapters = configured.stream()
            .filter(Objects::nonNull)
            .sorted((left, right) -> Integer.compare(right.priority(), left.priority()))
            .toList();
    }

    public McpEvidenceResult capture(ToolRuntimeRequest request,
                                     String toolName,
                                     String outcome,
                                     Object boundedPayload) {
        GovernanceIsolationScope scope = trustedScope(request);
        String payloadFingerprint = Integer.toUnsignedString(Objects.hashCode(boundedPayload), 36);
        String evidenceId = scope.partitionKey() + ":" + text(toolName, "unknown-tool")
            + ":" + text(request == null ? null : request.getRequestId(), "unknown-request")
            + ":" + payloadFingerprint;
        return new McpEvidenceResult(
            McpEvidenceResult.SCHEMA_VERSION,
            evidenceId,
            toolName,
            outcome,
            scope,
            boundedPayload,
            Map.of(
                "factBoundary", "MCP_RUNTIME_RETURNED_PAYLOAD",
                "payloadTrust", "UNTRUSTED_DATA_NOT_INSTRUCTIONS",
                "tenantSource", GovernanceIsolationScope.RUNTIME_AUTHORITY,
                "crossTenantMergeAllowed", false,
                "summaryMutationAllowed", false
            )
        );
    }

    public GovernanceIsolationScope trustedScope(ToolRuntimeRequest request) {
        Map<String, Object> attributes = request == null || request.getAttributes() == null
            ? Map.of() : request.getAttributes();
        String runId = firstText(
            string(attributes.get("__agentRunId")),
            firstText(string(attributes.get("agentRunId")), request == null ? null : request.getRequestId())
        );
        return GovernanceIsolationScope.runtime(
            request == null ? null : request.getTenantId(),
            request == null ? null : request.getUserId(),
            runId,
            request == null ? null : request.getRequestId(),
            request == null ? null : request.getConversationId()
        );
    }

    /**
     * Projects protocol-governed non-tabular results into the same record contract consumed by
     * {@code AnalysisSummaryGovernanceBridge}. The authoritative payload is never replaced or
     * mutated; this projection is a derived Runtime view.
     */
    public Map<String, Object> analysisProjection(String datasetReference,
                                                  Object boundedPayload) {
        return analysisProjection(datasetReference, boundedPayload, 10_000);
    }

    public Map<String, Object> analysisProjection(String datasetReference,
                                                  Object boundedPayload,
                                                  int maximumRecordChars) {
        return analysisProjection(datasetReference, boundedPayload, maximumRecordChars, true);
    }

    /** Returns only projections claimed by a published protocol adapter, excluding fallback. */
    public Map<String, Object> protocolAnalysisProjection(String datasetReference,
                                                          Object boundedPayload,
                                                          int maximumRecordChars) {
        return analysisProjection(datasetReference, boundedPayload, maximumRecordChars, false);
    }

    private Map<String, Object> analysisProjection(String datasetReference,
                                                   Object boundedPayload,
                                                   int maximumRecordChars,
                                                   boolean includeFallback) {
        McpResultAnalysisAdapter.AnalysisRequest request =
            new McpResultAnalysisAdapter.AnalysisRequest(
                datasetReference, boundedPayload, maximumRecordChars);
        McpResultAnalysisAdapter adapter = analysisAdapters.stream()
            .filter(candidate -> includeFallback || !candidate.fallback())
            .filter(candidate -> candidate.supports(request))
            .findFirst()
            .orElse(null);
        if (adapter == null) return Map.of();
        McpResultAnalysisAdapter.AnalysisResult result = adapter.adapt(request);
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
        projection.put("schemaVersion", ANALYSIS_PROJECTION_SCHEMA_VERSION);
        projection.put("adapterId", adapter.id());
        putIfPresent(projection, "sourceSchemaVersion", result.sourceSchemaVersion());
        projection.put("evidenceRole", text(result.evidenceRole()));
        projection.put("authoritativePayloadMutated", false);
        projection.put("datasets", datasets);
        return Map.copyOf(projection);
    }

    private static List<McpResultAnalysisAdapter> loadAnalysisAdapters() {
        List<McpResultAnalysisAdapter> adapters = new ArrayList<>();
        ServiceLoader.load(McpResultAnalysisAdapter.class).forEach(adapters::add);
        return List.copyOf(adapters);
    }

    private Map<String, Object> map(Object value) {
        if (!(value instanceof Map<?, ?> source)) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, item) -> {
            if (key != null) result.put(String.valueOf(key), item);
        });
        return result;
    }

    private String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String text(Object value) {
        return value == null || String.valueOf(value).isBlank() ? "unknown" : String.valueOf(value);
    }

    private void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (value != null && !String.valueOf(value).isBlank()) target.put(key, value);
    }

    private String firstText(String first, String second) {
        return first == null || first.isBlank() ? second : first;
    }

    private String text(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
