package com.chatchat.agents.orchestration;

import com.chatchat.agents.runtime.GovernanceIsolationScope;
import com.chatchat.agents.runtime.protocol.RuntimeAnalysisSummary;
import com.chatchat.common.tool.DataAnalysisContextProtocol;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Canonical governed result of model-assisted or deterministic analysis summarization.
 * The user-facing text lives in {@link #content()}; all governance and lineage travel with it.
 */
public record AnalysisSummaryResult(
    String schemaVersion,
    String resultId,
    String scope,
    String content,
    String outcome,
    GovernanceIsolationScope isolationScope,
    Map<String, Object> position,
    Map<String, Object> analysisContext,
    Map<String, Object> coverage,
    List<String> inputSummaryResultIds,
    Map<String, Object> evidence,
    Map<String, Object> governance
) implements RuntimeAnalysisSummary {

    public static final String SCHEMA_VERSION = "analysis_summary_result.v1";

    public AnalysisSummaryResult {
        schemaVersion = SCHEMA_VERSION;
        resultId = text(resultId, "summary-result");
        scope = text(scope, "DATASET_CHUNK");
        content = content == null ? "" : content;
        outcome = text(outcome, "UNKNOWN");
        isolationScope = isolationScope == null
            ? GovernanceIsolationScope.runtime(null, null, null, null, null)
            : isolationScope;
        position = immutable(position);
        analysisContext = immutable(analysisContext);
        coverage = immutable(coverage);
        inputSummaryResultIds = inputSummaryResultIds == null ? List.of() : List.copyOf(inputSummaryResultIds);
        evidence = immutable(evidence);
        governance = immutable(governance);
    }

    public static AnalysisSummaryResult chunk(GovernanceIsolationScope isolationScope,
                                              Map<String, Object> position,
                                              Map<String, Object> analysisContext,
                                              String content,
                                              String outcome) {
        return chunk(isolationScope, position, analysisContext, content, outcome, Map.of());
    }

    public static AnalysisSummaryResult chunk(GovernanceIsolationScope isolationScope,
                                              Map<String, Object> position,
                                              Map<String, Object> analysisContext,
                                              String content,
                                              String outcome,
                                              Map<String, Object> evidence) {
        GovernanceIsolationScope safeScope = isolationScope == null
            ? GovernanceIsolationScope.runtime(null, null, null, null, null)
            : isolationScope;
        String dataset = string(position, "datasetReference", "result");
        String chunk = string(position, "chunkIndex", "1");
        return new AnalysisSummaryResult(
            SCHEMA_VERSION,
            safeScope.partitionKey() + ":" + dataset + "#chunk-" + chunk,
            "DATASET_CHUNK",
            content,
            outcome,
            safeScope,
            position,
            analysisContext,
            Map.of(
                "recordFrom", value(position, "recordFrom", 0),
                "recordTo", value(position, "recordTo", 0),
                "totalRecords", value(position, "totalRecords", 0),
                "chunkComplete", true
            ),
            List.of(),
            evidence,
            governanceContract()
        );
    }

    public static AnalysisSummaryResult finalSummary(GovernanceIsolationScope isolationScope,
                                                     String stage,
                                                     String content,
                                                     String outcome,
                                                     Map<String, Object> coverage,
                                                     List<AnalysisSummaryResult> inputs) {
        return finalSummary(isolationScope, stage, content, outcome, coverage, inputs, List.of());
    }

    public static AnalysisSummaryResult intermediateSummary(
        GovernanceIsolationScope isolationScope,
        String scope,
        String summaryKey,
        String content,
        String outcome,
        Map<String, Object> position,
        Map<String, Object> analysisContext,
        Map<String, Object> coverage,
        List<AnalysisSummaryResult> inputs,
        Map<String, Object> evidence
    ) {
        GovernanceIsolationScope safeScope = isolationScope == null
            ? GovernanceIsolationScope.runtime(null, null, null, null, null)
            : isolationScope;
        List<AnalysisSummaryResult> safeInputs = inputs == null ? List.of() : List.copyOf(inputs);
        safeInputs.forEach(input -> safeScope.requireSamePartition(input.isolationScope()));
        String safeKey = text(summaryKey, "intermediate");
        Map<String, Object> lineage = new LinkedHashMap<>();
        lineage.put("schemaVersion", "intermediate_evidence_lineage.v1");
        lineage.put("upstreamEvidenceProtocol", "traceable_chunk_evidence.v1");
        lineage.put("evidenceId", safeScope.partitionKey() + ":" + safeKey);
        lineage.put("inputEvidenceIds", safeInputs.stream()
            .map(AnalysisSummaryResult::evidence)
            .map(item -> item.get("evidenceId"))
            .filter(java.util.Objects::nonNull)
            .map(String::valueOf)
            .filter(id -> !id.isBlank())
            .distinct()
            .toList());
        lineage.put("inputSummaryResultIds", safeInputs.stream()
            .map(AnalysisSummaryResult::resultId).toList());
        if (evidence != null) {
            evidence.forEach((key, value) -> {
                if (key != null && value != null) lineage.put(key, value);
            });
        }
        return new AnalysisSummaryResult(
            SCHEMA_VERSION,
            safeScope.partitionKey() + ":" + safeKey,
            text(scope, "DATASET_SYNTHESIS"),
            content,
            outcome,
            safeScope,
            position,
            analysisContext,
            coverage,
            safeInputs.stream().map(AnalysisSummaryResult::resultId).toList(),
            lineage,
            governanceContract()
        );
    }

    public static AnalysisSummaryResult finalSummary(GovernanceIsolationScope isolationScope,
                                                     String stage,
                                                     String content,
                                                     String outcome,
                                                     Map<String, Object> coverage,
                                                     List<AnalysisSummaryResult> inputs,
                                                     List<String> upstreamResultIds) {
        GovernanceIsolationScope safeScope = isolationScope == null
            ? GovernanceIsolationScope.runtime(null, null, null, null, null)
            : isolationScope;
        List<AnalysisSummaryResult> safeInputs = inputs == null ? List.of() : List.copyOf(inputs);
        safeInputs.forEach(input -> safeScope.requireSamePartition(input.isolationScope()));
        List<String> inputIds = new java.util.ArrayList<>(
            safeInputs.stream().map(AnalysisSummaryResult::resultId).toList());
        if (upstreamResultIds != null) {
            upstreamResultIds.stream()
                .filter(id -> id != null && !id.isBlank())
                .filter(id -> !inputIds.contains(id))
                .forEach(inputIds::add);
        }
        List<String> evidenceIds = safeInputs.stream()
            .map(AnalysisSummaryResult::evidence)
            .map(item -> item.get("evidenceId"))
            .filter(java.util.Objects::nonNull)
            .map(String::valueOf)
            .filter(id -> !id.isBlank())
            .distinct()
            .toList();
        return new AnalysisSummaryResult(
            SCHEMA_VERSION,
            safeScope.partitionKey() + ":final-summary#" + text(stage, "final"),
            "FINAL_SYNTHESIS",
            content,
            outcome,
            safeScope,
            Map.of("stage", text(stage, "final")),
            Map.of(),
            coverage,
            inputIds,
            Map.of(
                "schemaVersion", "final_evidence_lineage.v1",
                "inputEvidenceIds", evidenceIds,
                "traceableInputCount", evidenceIds.size(),
                "inputSummaryCount", safeInputs.size(),
                "traceComplete", evidenceIds.size() == safeInputs.size()
            ),
            governanceContract()
        );
    }

    public Map<String, Object> toMap() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schemaVersion", schemaVersion);
        result.put("resultId", resultId);
        result.put("scope", scope);
        result.put("content", content);
        result.put("outcome", outcome);
        result.put("isolationScope", isolationScope.toMap());
        result.put("position", position);
        result.put("analysisContext", analysisContext);
        result.put("coverage", coverage);
        result.put("inputSummaryResultIds", inputSummaryResultIds);
        result.put("evidence", evidence);
        result.put("governance", governance);
        return Collections.unmodifiableMap(result);
    }

    public AnalysisSummaryResult withEvidence(Map<String, Object> additions) {
        Map<String, Object> merged = new LinkedHashMap<>(evidence);
        if (additions != null) {
            additions.forEach((key, value) -> {
                if (key != null && value != null) merged.put(key, value);
            });
        }
        return new AnalysisSummaryResult(schemaVersion, resultId, scope, content, outcome,
            isolationScope, position, analysisContext, coverage, inputSummaryResultIds,
            merged, governance);
    }

    private static Map<String, Object> governanceContract() {
        return Map.of(
            "protocolVersion", DataAnalysisContextProtocol.GOVERNANCE_VERSION,
            "contentRole", "GOVERNED_ANALYSIS_SUMMARY",
            "factBoundary", "RETURNED_STRUCTURED_EVIDENCE",
            "evidenceProtocol", "traceable_chunk_evidence.v1",
            "rawReplayAuthority", "RUNTIME_EXECUTION_RESULT",
            "presentationPolicy", "CONTENT_IS_PRESENTATION_EXACT_FIELDS_REMAIN_AUTHORITATIVE",
            "semanticInferenceAllowed", false
        );
    }

    private static Map<String, Object> immutable(Map<String, Object> source) {
        return source == null || source.isEmpty()
            ? Map.of()
            : Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }

    private static Object value(Map<String, Object> source, String key, Object fallback) {
        if (source == null) return fallback;
        Object value = source.get(key);
        return value == null ? fallback : value;
    }

    private static String string(Map<String, Object> source, String key, String fallback) {
        return String.valueOf(value(source, key, fallback));
    }

    private static String text(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
