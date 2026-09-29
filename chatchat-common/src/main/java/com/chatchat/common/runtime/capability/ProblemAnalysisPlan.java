package com.chatchat.common.runtime.capability;

import java.util.List;

/** Workflow-independent public task plan, not private model reasoning or an authorization grant. */
public record ProblemAnalysisPlan(Status status, String objective, String subject, String domain,
                                  String explanation, List<Task> tasks, String clarificationQuestion) {
    public static final String METADATA_KEY = "problemAnalysisPlan";
    public enum Status { READY, NEEDS_CLARIFICATION, PLANNING_FAILED }
    public enum Intent { DIRECT_ANSWER, DOCUMENT_UNDERSTANDING, DATA_ANALYSIS, ASSET_USAGE_GUIDANCE, ACTION_EXECUTION }
    public record Task(String objective, Intent intent, List<String> dataRequirements, String expectedResult) {
        public Task {
            if (objective == null || objective.isBlank() || intent == null || expectedResult == null || expectedResult.isBlank())
                throw new IllegalArgumentException("Task objective, intent and expected result are required");
            dataRequirements = List.copyOf(dataRequirements);
        }
    }
    public ProblemAnalysisPlan {
        if (status == null) throw new IllegalArgumentException("Plan status is required");
        tasks = List.copyOf(tasks);
        if (tasks.size() > 8) throw new IllegalArgumentException("Too many analysis tasks");
        if (status == Status.READY && (objective == null || objective.isBlank() || tasks.isEmpty()
            || explanation == null || explanation.isBlank()))
            throw new IllegalArgumentException("Ready plan requires objective, explanation and tasks");
        if (status == Status.NEEDS_CLARIFICATION && (clarificationQuestion == null || clarificationQuestion.isBlank()))
            throw new IllegalArgumentException("Clarification question is required");
    }
    public static ProblemAnalysisPlan unavailable() {
        return new ProblemAnalysisPlan(Status.PLANNING_FAILED, "", "", "", "问题分析计划生成失败，未选择或执行工作流。", List.of(), "");
    }
}
