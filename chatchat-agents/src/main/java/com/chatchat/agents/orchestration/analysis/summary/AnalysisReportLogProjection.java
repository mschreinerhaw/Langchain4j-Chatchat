package com.chatchat.agents.orchestration.analysis.summary;

import com.chatchat.agents.orchestration.analysis.model.AnalysisSummaryResult;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Data-minimized log view of an analysis product.
 *
 * <p>Prompts, raw records, facts/supporting values and full evidence envelopes are intentionally
 * excluded. The projection retains only the analytical report needed to inspect how conclusions
 * moved from Worker to Reducer to Driver.</p>
 */
public final class AnalysisReportLogProjection {

    public static final String SCHEMA_VERSION = "analysis_report_log.v1";

    private AnalysisReportLogProjection() {
    }

    public static Map<String, Object> project(String layer, AnalysisSummaryResult report) {
        return project(layer, report, report == null ? 0 : report.inputSummaryResultIds().size());
    }

    public static Map<String, Object> project(String layer, AnalysisSummaryResult report,
                                              int inputReportCount) {
        if (report == null) return Map.of();
        Map<String, Object> evidence = report.evidence();
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("schemaVersion", SCHEMA_VERSION);
        value.put("layer", text(layer));
        value.put("resultId", report.resultId());
        value.put("scope", report.scope());
        value.put("outcome", report.outcome());
        value.put("summary", report.content());
        value.put("inputReportCount", Math.max(0, inputReportCount));
        putIfPresent(value, "demandAnalysis", evidence.get("demandAnalysis"));
        putIfPresent(value, "metricAssociations", evidence.get("metricAssociations"));
        putIfPresent(value, "businessConclusions", evidence.get("businessConclusions"));
        putIfPresent(value, "missingEvidence", evidence.get("missingEvidence"));
        putIfPresent(value, "objectiveAlignment", evidence.get("objectiveAlignment"));
        putIfPresent(value, "analysisDepth", evidence.get("analysisDepth"));
        Object narrativeStatus = evidence.get("analysisNarrativeStatus");
        if (narrativeStatus != null && !String.valueOf(narrativeStatus).isBlank()) {
            value.put("analysisNarrativeStatus", String.valueOf(narrativeStatus));
        }
        value.put("insightCount", size(evidence.get("insights")));
        value.put("observedFactClaimCount", size(evidence.get("observedFactClaims")));
        value.put("analysisItemCount", size(evidence.get("analysisItems")));
        value.put("analysisArtifactCount", size(evidence.get(AnalysisArtifactProtocol.EVIDENCE_KEY)));
        value.put("conflictCount", size(evidence.get("conflicts")));
        value.put("unsupportedQuestionCount", size(evidence.get("unsupportedQuestions")));
        value.put("publishedClaimIds", strings(evidence.get("analysisPublishedClaimIds")));
        return Map.copyOf(value);
    }

    private static void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (value instanceof Map<?, ?> map && !map.isEmpty()) target.put(key, value);
        else if (value instanceof List<?> list && !list.isEmpty()) target.put(key, value);
    }

    private static int size(Object value) {
        return value instanceof List<?> list ? list.size() : 0;
    }

    private static List<String> strings(Object value) {
        if (!(value instanceof Iterable<?> values)) return List.of();
        java.util.ArrayList<String> result = new java.util.ArrayList<>();
        values.forEach(item -> {
            if (item != null && !String.valueOf(item).isBlank()) result.add(String.valueOf(item));
        });
        return List.copyOf(result);
    }

    private static String text(String value) {
        return value == null ? "" : value.trim();
    }
}
