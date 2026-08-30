package com.chatchat.agents.orchestration.analysis.dispatch;

import com.chatchat.agents.orchestration.AgentRunResultAdapter;
import com.chatchat.agents.runtime.governance.GovernanceIsolationScope;
import com.chatchat.common.runtime.summary.ModelSummaryProgress;

import java.util.Map;

/** Persists transport-neutral analysis progress as business-facing run observations. */
public final class AnalysisProgressRecorder {
    private final AgentRunResultAdapter resultAdapter;
    private final String runIdAttribute;

    public AnalysisProgressRecorder(AgentRunResultAdapter resultAdapter, String runIdAttribute) {
        this.resultAdapter = resultAdapter;
        this.runIdAttribute = runIdAttribute;
    }

    public void record(Map<String, Object> runtimeAttributes,
                       GovernanceIsolationScope isolationScope,
                       ModelSummaryProgress progress) {
        if (progress == null) return;
        Map<String, Object> metadata = BusinessAnalysisProgressProjector.metadata(progress);
        metadata.put("tenantId", isolationScope.tenantId());
        metadata.put("runId", isolationScope.runId());
        resultAdapter.recordRuntimeObservation(runtimeAttributes, runIdAttribute,
            BusinessAnalysisProgressProjector.content(progress),
            "business_analysis_progress", metadata);
    }
}
