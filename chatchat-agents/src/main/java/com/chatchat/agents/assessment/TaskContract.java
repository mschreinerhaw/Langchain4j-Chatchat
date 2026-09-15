package com.chatchat.agents.assessment;

import java.util.List;

/**
 * Runtime boundary for deciding whether a task needs evidence and which kind of
 * answer the user requested. The contract is established before answer review.
 */
public record TaskContract(
    String contractVersion,
    String taskType,
    String userGoal,
    EvidenceRequirement evidenceRequirement,
    boolean allowAssumptions,
    String answerMode,
    List<String> mandatoryTools,
    List<EvidenceItem> evidenceItems
) {
    public static final String CONTRACT_VERSION = "task_contract_v2";

    public TaskContract {
        contractVersion = CONTRACT_VERSION;
        taskType = normalized(taskType, "general");
        userGoal = normalized(userGoal, "");
        evidenceRequirement = evidenceRequirement == null ? EvidenceRequirement.OPTIONAL : evidenceRequirement;
        answerMode = normalized(answerMode, "answer");
        mandatoryTools = mandatoryTools == null ? List.of() : List.copyOf(mandatoryTools);
        evidenceItems = evidenceItems == null ? List.of() : List.copyOf(evidenceItems);
    }

    public TaskContract(String contractVersion, String taskType, String userGoal,
                        EvidenceRequirement evidenceRequirement, boolean allowAssumptions,
                        String answerMode, List<String> mandatoryTools) {
        this(contractVersion, taskType, userGoal, evidenceRequirement, allowAssumptions,
            answerMode, mandatoryTools, List.of());
    }

    public enum EvidenceRequirement {
        OPTIONAL,
        REQUIRED,
        STRICT
    }

    public enum EvidenceImportance {
        REQUIRED,
        IMPORTANT,
        OPTIONAL
    }

    public record EvidenceItem(String id, Integer sourceStepId, String sourceTool,
                               EvidenceImportance importance) {
        public EvidenceItem {
            id = normalized(id, sourceStepId == null ? sourceTool : "step:" + sourceStepId);
            sourceTool = normalized(sourceTool, "");
            importance = importance == null ? EvidenceImportance.IMPORTANT : importance;
        }
    }

    private static String normalized(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
