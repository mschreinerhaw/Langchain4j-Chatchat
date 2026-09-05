package com.chatchat.agents.orchestration.analysis.graph;

import com.chatchat.agents.orchestration.analysis.report.ReportComposer;
import java.util.List;
import java.util.Map;

/** Only a completed, admitted structured report can bypass legacy answer rewriting. */
public final class AnalysisFinalizationPolicy {
    private AnalysisFinalizationPolicy() {}
    public static String directPublicationReason(Map<String, Object> metadata) {
        if (Boolean.TRUE.equals(metadata.get("analysisFinalAdmissionBlocked")))
            return "ANALYSIS_PREFLIGHT_TERMINAL";
        if (!Boolean.TRUE.equals(metadata.get("interpretationPlanFinalResultProduced"))
            || !Boolean.TRUE.equals(metadata.get("finalClaimSelectionAccepted"))) return "";
        if (!List.of("COMPLETED", "COMPLETED_WITH_LIMITATIONS")
            .contains(String.valueOf(metadata.get("analysisGraphStatus")))) return "";
        Object raw = metadata.get("analyticalReport");
        if (!(raw instanceof Map<?, ?> report) || !ReportComposer.VERSION.equals(report.get("schemaVersion"))
            || !(report.get("blocks") instanceof List<?> blocks) || blocks.isEmpty()) return "";
        return "GRAPH_REPORT_ALREADY_GOVERNED";
    }
}
