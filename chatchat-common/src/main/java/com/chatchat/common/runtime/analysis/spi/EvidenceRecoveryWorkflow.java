package com.chatchat.common.runtime.analysis.spi;

import com.chatchat.common.runtime.analysis.evidence.EvidenceBundle;
import com.chatchat.common.runtime.analysis.model.AnalysisContext;
import com.chatchat.common.runtime.analysis.recovery.EvidenceGap;
import com.chatchat.common.runtime.analysis.recovery.EvidenceRecoveryResult;

/** Capability-specific recovery workflow selected by Runtime, never by the planning model. */
public interface EvidenceRecoveryWorkflow {
    boolean supports(AnalysisContext context, EvidenceGap gap);
    int priority();
    EvidenceRecoveryResult recover(AnalysisContext context, EvidenceBundle currentEvidence,
                                   EvidenceGap gap, int recoveryRound);
}
