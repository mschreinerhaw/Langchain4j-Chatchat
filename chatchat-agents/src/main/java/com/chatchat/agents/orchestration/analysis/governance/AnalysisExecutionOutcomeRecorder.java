package com.chatchat.agents.orchestration.analysis.governance;

import com.chatchat.agents.orchestration.analysis.model.AnalysisSummaryResult;
import com.chatchat.agents.protocol.ModelProtocolJson;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Records non-publishable analysis execution state as technical provenance. */
public final class AnalysisExecutionOutcomeRecorder {


    public List<Map<String, Object>> unresolvedGaps(
        Map<String, Object> metadata,
        List<AnalysisSummaryResult> reports
    ) {
        LinkedHashMap<String, Map<String, Object>> gaps = new LinkedHashMap<>();
        for (String key : List.of("analysisGapRequests", "semanticClaimGapRequests", "gapRequests")) {
            for (Map<String, Object> gap : maps(metadata.get(key))) {
                gaps.putIfAbsent(gapKey(gap), gap);
            }
        }
        for (AnalysisSummaryResult report : reports) {
            if (report == null || report.evidence() == null) continue;
            for (String key : List.of("semanticGapRequests", "analysisDepthGapRequests")) {
                for (Map<String, Object> gap : maps(report.evidence().get(key))) {
                    gaps.putIfAbsent(gapKey(gap), gap);
                }
            }
        }
        return List.copyOf(gaps.values());
    }

    private String gapKey(Map<String, Object> gap) {
        Object id = gap.get("requestId");
        if (id == null) id = gap.get("questionId");
        if (id == null) id = gap.get("gapId");
        return id == null || String.valueOf(id).isBlank()
            ? ModelProtocolJson.sha256Hex(gap) : String.valueOf(id);
    }

    private int number(Object value) {
        if (value instanceof Number number) return number.intValue();
        try {
            return value == null ? 0 : Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private List<Map<String, Object>> maps(Object value) {
        if (!(value instanceof Iterable<?> iterable)) return List.of();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : iterable) {
            if (item instanceof Map<?, ?> source) result.add(map(source));
        }
        return List.copyOf(result);
    }

    private Map<String, Object> map(Map<?, ?> source) {
        Map<String, Object> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> copy.put(String.valueOf(key), value));
        return copy;
    }

    private String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
