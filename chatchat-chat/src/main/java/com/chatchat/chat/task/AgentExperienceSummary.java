package com.chatchat.chat.task;

import java.time.Instant;
import java.util.List;

public record AgentExperienceSummary(
    String tenantId,
    long totalExperiences,
    long totalIndexes,
    List<IndexItem> indexes,
    List<ScenarioMetric> scenarios,
    List<ExperienceItem> experiences
) {

    public record ScenarioMetric(
        String scenarioKey,
        String scenarioName,
        long total,
        double averageScore
    ) {
    }

    public record ExperienceItem(
        String experienceId,
        String taskId,
        String agentId,
        String scenarioKey,
        String scenarioName,
        String question,
        String answerSummary,
        Boolean feedbackUseful,
        Boolean feedbackAdopted,
        Boolean feedbackResolved,
        String feedbackReasonCategory,
        String feedbackComment,
        Integer feedbackScore,
        String attributionSource,
        String attributionSummary,
        List<String> primaryFactors,
        List<String> successPattern,
        List<String> improvementSuggestions,
        Instant updateTime
    ) {
    }

    public record IndexItem(
        String id,
        String agentId,
        String scenario,
        String intentType,
        String workflowName,
        String toolChain,
        String keywords,
        String toolName,
        String errorCode,
        String dataSource,
        String feedbackResult,
        long usefulCount,
        long adoptedCount,
        long resolvedCount,
        long failedCount,
        long sampleCount,
        double successRate,
        String bestPractice,
        String avoidPattern,
        Instant updatedAt
    ) {
    }
}
