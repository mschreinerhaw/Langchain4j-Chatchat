package com.chatchat.agents.orchestration.analysis.graph;

import com.chatchat.agents.orchestration.analysis.dataset.AnalysisEvidenceCoordinator.Dataset;
import com.chatchat.agents.orchestration.analysis.governance.AnalysisCoverageCoordinator;
import java.util.Map;

/** Runtime-owned analysis boundary; discovery is possible only through existing task authorization. */
public interface AnalysisCodingPort {
    Map<String, String> capabilities();
    /** Authorization preflight only. Never runs a model/query or grants a new source. */
    default boolean canExploreAuthorizedResources(AnalysisCoverageCoordinator.Request context) { return false; }
    /** Original, authorized user files only; no Java data exploration or new business access. */
    default Map<String, Dataset> prepareFileSources(AnalysisCoverageCoordinator.Request context) {
        return Map.of();
    }
    Map<String, Object> execute(AnalysisCoverageCoordinator.Request context,
        Map<String, Object> request, Map<String, Dataset> deliveredSources);
    default Map<String, Object> executeObserved(AnalysisCoverageCoordinator.Request context,
        Map<String, Object> request, Map<String, Dataset> deliveredSources,
        java.util.function.Consumer<Map<String,Object>> observer) {
        return execute(context, request, deliveredSources);
    }

    /**
     * Runs the primary DATA_ANALYSIS assignment over all data already delivered by Runtime OS.
     * The implementation owns analysis planning, programming and technical repair. Runtime keeps
     * authorization, immutable input capture and structural result admission.
     */
    default Map<String, Object> executePrimaryAnalysis(
        AnalysisCoverageCoordinator.Request context,
        Map<String, Dataset> deliveredSources,
        java.util.function.Consumer<Map<String,Object>> observer) {
        throw new IllegalStateException("Primary Coding analysis is unavailable");
    }
}
