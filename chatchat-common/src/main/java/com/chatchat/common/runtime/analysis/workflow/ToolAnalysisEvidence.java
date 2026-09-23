package com.chatchat.common.runtime.analysis.workflow;

import java.util.Map;

public record ToolAnalysisEvidence(String evidenceId, String toolName, String invocationId,
                                   String content, Map<String, Object> attributes) implements AnalysisEvidence {
    public ToolAnalysisEvidence {
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        content = content == null ? "" : content;
    }
    @Override public AnalysisCapability capability() { return AnalysisCapability.TOOL_CALL; }
}
