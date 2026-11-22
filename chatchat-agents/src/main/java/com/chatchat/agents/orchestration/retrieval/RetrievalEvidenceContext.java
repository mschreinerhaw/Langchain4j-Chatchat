package com.chatchat.agents.orchestration.retrieval;

import java.util.Map;

public record RetrievalEvidenceContext(String userQuery, Map<Integer, Object> completedStepOutputs) {
    public RetrievalEvidenceContext {
        completedStepOutputs = completedStepOutputs == null ? Map.of() : Map.copyOf(completedStepOutputs);
    }
}
