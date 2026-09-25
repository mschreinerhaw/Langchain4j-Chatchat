package com.chatchat.common.runtime.agent;

import com.chatchat.common.runtime.analysis.evidence.AnalysisEvidence;
import com.chatchat.common.runtime.analysis.plan.EvidenceRequirement;

import java.util.List;

/** Local-only, policy-scoped evidence acquisition. Remote agents never select executable tools. */
public interface AgentEvidenceSupplementPort {
    List<AnalysisEvidence> supplement(AgentDescriptor agent, AgentExecutionRequest request,
                                      EvidenceRequirement requirement);
}
