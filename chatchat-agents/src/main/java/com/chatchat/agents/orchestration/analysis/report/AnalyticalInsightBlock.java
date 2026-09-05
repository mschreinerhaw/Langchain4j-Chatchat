package com.chatchat.agents.orchestration.analysis.report;

import java.util.List;
import java.util.Map;

/** A publication unit: its chart and table are projections of the same runtime-owned data. */
public record AnalyticalInsightBlock(
    String id, String section, String question, String observation, String interpretation,
    String implication, String confidence, List<String> caveats, List<Map<String, Object>> evidence,
    Map<String, Object> data, Map<String, Object> visualization, PresentationStrategy presentation
) {
    public record PresentationStrategy(String primaryPresentation, boolean showDataTable,
                                       boolean showKeyMetrics, boolean primaryConclusion,
                                       String validationStatus) { }
}
