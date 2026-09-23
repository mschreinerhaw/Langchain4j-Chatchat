package com.chatchat.common.runtime.analysis.workflow;

import java.util.List;
import java.util.Map;

public record ComputationEvidence(String evidenceId, String formula, List<String> inputEvidenceIds,
                                  String content, Map<String, Object> attributes) implements AnalysisEvidence {
    public ComputationEvidence {
        inputEvidenceIds = inputEvidenceIds == null ? List.of() : List.copyOf(inputEvidenceIds);
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        content = content == null ? "" : content;
    }
    @Override public AnalysisCapability capability() { return AnalysisCapability.COMPUTATION; }
}
