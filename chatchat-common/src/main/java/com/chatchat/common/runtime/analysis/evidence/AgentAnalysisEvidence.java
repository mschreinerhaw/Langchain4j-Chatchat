package com.chatchat.common.runtime.analysis.evidence;

import com.chatchat.common.runtime.analysis.model.AnalysisCapability;

import java.util.List;
import java.util.Map;

/** A claim or artifact produced by agent compute and bound to Runtime-owned evidence. */
public record AgentAnalysisEvidence(String evidenceId, String agentId, String executionId,
                                    List<String> sourceEvidenceIds, String content,
                                    Map<String, Object> attributes) implements AnalysisEvidence {
    public AgentAnalysisEvidence {
        sourceEvidenceIds = sourceEvidenceIds == null ? List.of() : List.copyOf(sourceEvidenceIds);
        content = content == null ? "" : content;
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }
    @Override public AnalysisCapability capability() { return AnalysisCapability.DOMAIN_INTELLIGENCE; }
}
