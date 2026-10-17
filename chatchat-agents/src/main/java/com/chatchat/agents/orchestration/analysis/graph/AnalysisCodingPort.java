package com.chatchat.agents.orchestration.analysis.graph;

import com.chatchat.agents.orchestration.analysis.dataset.AnalysisEvidenceCoordinator.Dataset;
import com.chatchat.agents.orchestration.analysis.governance.AnalysisCoverageCoordinator;
import java.util.Map;

/** Optional Runtime-owned calculation boundary. It grants no discovery, template or business-source access. */
public interface AnalysisCodingPort {
    Map<String, String> capabilities();
    Map<String, Object> execute(AnalysisCoverageCoordinator.Request context,
        Map<String, Object> request, Map<String, Dataset> deliveredSources);
    default Map<String, Object> executeObserved(AnalysisCoverageCoordinator.Request context,
        Map<String, Object> request, Map<String, Dataset> deliveredSources,
        java.util.function.Consumer<Map<String,Object>> observer) {
        return execute(context, request, deliveredSources);
    }
}
