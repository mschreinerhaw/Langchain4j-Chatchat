package com.chatchat.common.runtime.analysis.spi;

import com.chatchat.common.runtime.analysis.execution.AnalysisExecutionOutcome;
import com.chatchat.common.runtime.analysis.evidence.EvidenceBundle;
import com.chatchat.common.runtime.analysis.model.AnalysisContext;
import com.chatchat.common.runtime.analysis.model.AnalysisIntent;
import com.chatchat.common.runtime.analysis.model.AnalysisWorkflowType;

import com.chatchat.common.runtime.workflow.RuntimeWorkflow;

import java.util.LinkedHashMap;
import java.util.Map;

public interface AnalysisWorkflow extends RuntimeWorkflow<AnalysisContext, AnalysisExecutionOutcome> {
    AnalysisWorkflowType type();
    boolean supports(AnalysisContext context, AnalysisIntent intent);
    default int priority() { return 0; }

    /**
     * Receives acquisition results without replaying execution or side effects. Implementations
     * may reanalyze here; the compatibility default preserves the original judgment and labels
     * the original synthesis rather than pretending it incorporates newly acquired evidence.
     */
    default AnalysisExecutionOutcome continueAfterRecovery(AnalysisContext context,
            AnalysisExecutionOutcome primary,
            EvidenceBundle evidence, Map<String, Object> recoveryMetadata) {
        Map<String, Object> metadata = new LinkedHashMap<>(primary.metadata());
        metadata.putAll(recoveryMetadata);
        metadata.put("synthesisEvidenceScope", "PRIMARY_ANALYSIS_ONLY");
        return new AnalysisExecutionOutcome(primary.schemaVersion(), primary.workflowType(), primary.plan(),
            primary.verification(), evidence, primary.synthesis(), metadata);
    }
}
