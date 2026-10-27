package com.chatchat.common.runtime.capability;

import java.util.List;

/** Workflow-independent public task plan, not private model reasoning or an authorization grant. */
public record ProblemAnalysisPlan(Status status, String objective, String subject, String domain,
                                  String explanation, List<Task> tasks, String clarificationQuestion,
                                  @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                                  FileAnalysis fileAnalysis,
                                  @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                                  SqlIntent sqlIntent) {
    public enum SqlIntent { EXPLAIN, VALIDATE, EXECUTE }
    public ProblemAnalysisPlan(Status status, String objective, String subject, String domain,
            String explanation, List<Task> tasks, String clarificationQuestion, FileAnalysis fileAnalysis) {
        this(status, objective, subject, domain, explanation, tasks, clarificationQuestion, fileAnalysis, null);
    }
    public ProblemAnalysisPlan(Status status, String objective, String subject, String domain,
                               String explanation, List<Task> tasks, String clarificationQuestion) {
        this(status, objective, subject, domain, explanation, tasks, clarificationQuestion, null, null);
    }
    /** Registered references only. Neither the planner nor the client can manufacture file locations. */
    public record FileInput(String fileName, String reference, String contentType, long size, String sha256) {
        public FileInput {
            if (fileName == null || fileName.isBlank() || contentType == null || size < 0
                || sha256 == null || !sha256.matches("[a-f0-9]{64}") || reference == null
                || !reference.matches("user-asset:[a-f0-9]{64}:" + sha256))
                throw new IllegalArgumentException("Invalid registered file reference");
        }
    }
    public enum ToolDataDependency { NONE, OPTIONAL, REQUIRED }
    public record FileAnalysis(List<FileInput> inputs, ToolDataDependency toolDataDependency) {
        public FileAnalysis {
            inputs = List.copyOf(inputs);
            if (inputs.isEmpty() || inputs.size() > 8 || toolDataDependency == null)
                throw new IllegalArgumentException("File inputs and data dependency are required");
        }
    }
    public static final String METADATA_KEY = "problemAnalysisPlan";
    public enum Status { READY, NEEDS_CLARIFICATION, PLANNING_FAILED }
    public enum Intent { DIRECT_ANSWER, DOCUMENT_UNDERSTANDING, DATA_ANALYSIS, ASSET_USAGE_GUIDANCE, ACTION_EXECUTION }
    public enum TaskType { QA, DATA_ANALYSIS }
    public record Task(String objective, Intent intent, List<String> dataRequirements, String expectedResult) {
        /** Public two-way classification derived solely from the model's accepted intent. */
        @com.fasterxml.jackson.annotation.JsonProperty(value="taskType", access=com.fasterxml.jackson.annotation.JsonProperty.Access.READ_ONLY)
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        public TaskType taskType() {
            // Existing action workflows retain their separate authorization contract.
            if (intent == Intent.ACTION_EXECUTION) return null;
            return intent == Intent.DATA_ANALYSIS ? TaskType.DATA_ANALYSIS : TaskType.QA;
        }
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
