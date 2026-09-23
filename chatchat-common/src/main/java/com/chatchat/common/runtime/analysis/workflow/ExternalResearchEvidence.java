package com.chatchat.common.runtime.analysis.workflow;

import java.util.Map;

public record ExternalResearchEvidence(String evidenceId, String url, String publisher,
                                       String publishedAt, String content,
                                       Map<String, Object> attributes) implements AnalysisEvidence {
    public ExternalResearchEvidence {
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        content = content == null ? "" : content;
    }
    @Override public AnalysisCapability capability() { return AnalysisCapability.EXTERNAL_RESEARCH; }
}
