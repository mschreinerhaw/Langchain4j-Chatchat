package com.chatchat.common.runtime.analysis.workflow;

import java.util.Map;

public record DocumentAnalysisEvidence(String evidenceId, String documentId, String chunkId,
                                       String documentName, String section, String citation,
                                       String content, double score,
                                       Map<String, Object> attributes) implements AnalysisEvidence {
    public DocumentAnalysisEvidence {
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        content = content == null ? "" : content;
    }
    @Override public AnalysisCapability capability() { return AnalysisCapability.DOCUMENT_SEARCH; }
}
