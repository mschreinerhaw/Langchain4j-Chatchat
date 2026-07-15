package com.chatchat.tools.workflow;

import java.util.List;
import java.util.Map;

/** Complete execution state ordered by topological workflow level. */
public record SqlWorkflowExecution(
    String executionNo,
    String status,
    List<List<String>> executionLevels,
    List<StepExecution> steps,
    long durationMs
) {
    public record StepExecution(
        SqlWorkflowNode node,
        int executionOrder,
        int executionLevel,
        String status,
        Map<String, Object> resolvedParameters,
        SqlWorkflowNodeResult result,
        String skipReason
    ) {
    }
}
