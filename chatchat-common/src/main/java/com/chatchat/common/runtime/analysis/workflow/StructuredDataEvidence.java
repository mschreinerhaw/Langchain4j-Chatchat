package com.chatchat.common.runtime.analysis.workflow;

import java.util.Map;

public record StructuredDataEvidence(String evidenceId, String dataset, String query,
                                     long rows, String asOf, String content,
                                     Map<String, Object> attributes) implements AnalysisEvidence {
    public StructuredDataEvidence {
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        content = content == null ? "" : content;
    }
    @Override public AnalysisCapability capability() { return AnalysisCapability.STRUCTURED_DATA; }
}
