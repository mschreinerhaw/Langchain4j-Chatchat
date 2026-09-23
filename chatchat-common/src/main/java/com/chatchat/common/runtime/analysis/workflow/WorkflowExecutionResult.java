package com.chatchat.common.runtime.analysis.workflow;

import java.util.List;
import java.util.Map;

public record WorkflowExecutionResult(List<AnalysisEvidence> evidence,
                                      Map<String, Object> outputs,
                                      List<String> observations) {
    public WorkflowExecutionResult {
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        outputs = outputs == null ? Map.of() : Map.copyOf(outputs);
        observations = observations == null ? List.of() : List.copyOf(observations);
    }
}
