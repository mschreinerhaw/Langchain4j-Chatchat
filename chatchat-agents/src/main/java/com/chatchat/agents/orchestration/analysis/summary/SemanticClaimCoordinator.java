package com.chatchat.agents.orchestration.analysis.summary;

import com.chatchat.agents.orchestration.AgentRunResultAdapter;
import com.chatchat.agents.orchestration.analysis.model.AnalysisSummaryResult;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/** Public orchestration boundary for claim admission, lineage, gap routing and termination. */
public final class SemanticClaimCoordinator {

    private final SemanticGapEvidenceBridge evidenceBridge;

    public SemanticClaimCoordinator(AgentRunResultAdapter runResultAdapter, String runIdAttribute) {
        this.evidenceBridge = new SemanticGapEvidenceBridge(runResultAdapter, runIdAttribute);
    }

    public <T> T preflight(Supplier<T> analysis, Supplier<T> emptyResult,
                           Map<String, Object> runtimeAttributes, Map<String, Object> metadata) {
        return evidenceBridge.preflight(analysis, emptyResult, runtimeAttributes, metadata);
    }

    public Map<String, Object> evaluate(Map<String, Object> evidence,
                                        List<AnalysisSummaryResult> summaries,
                                        int iteration,
                                        Map<String, Object> runtimeAttributes,
                                        Map<String, Object> metadata) {
        return evidenceBridge.merge(evidence, summaries, iteration, runtimeAttributes, metadata);
    }
}
