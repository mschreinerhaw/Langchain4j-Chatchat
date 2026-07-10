package com.chatchat.chat.skills;

import java.util.List;
import java.util.Map;

/**
 * Skill definition used by interaction orchestration.
 */
public record SkillDefinition(
    String id,
    String label,
    String description,
    List<String> usageScenarios,
    List<String> skillTags,
    String defaultMode,
    String modelName,
    String systemPrompt,
    String firstUseGreeting,
    List<String> preferredToolPrefixes,
    List<String> boundMcpServiceIds,
    List<String> boundMcpToolNames,
    List<String> boundDocumentIds,
    List<String> boundDocumentTags,
    List<SkillToolConfig> toolConfigs,
    SkillRoutingSettings routingSettings,
    Map<String, Object> workflowConfig,
    DefaultDataAsset defaultDataAsset,
    AssetSelectionPolicy assetSelectionPolicy,
    List<String> quickQuestions,
    String marketStatus,
    Boolean defaultAgent
) {
    public SkillDefinition(String id,
                           String label,
                           String description,
                           List<String> usageScenarios,
                           List<String> skillTags,
                           String defaultMode,
                           String modelName,
                           String systemPrompt,
                           String firstUseGreeting,
                           List<String> preferredToolPrefixes,
                           List<String> boundMcpServiceIds,
                           List<String> boundMcpToolNames,
                           List<String> boundDocumentIds,
                           List<String> boundDocumentTags,
                           List<SkillToolConfig> toolConfigs,
                           SkillRoutingSettings routingSettings,
                           Map<String, Object> workflowConfig,
                           List<String> quickQuestions,
                           String marketStatus,
                           Boolean defaultAgent) {
        this(id,
            label,
            description,
            usageScenarios,
            skillTags,
            defaultMode,
            modelName,
            systemPrompt,
            firstUseGreeting,
            preferredToolPrefixes,
            boundMcpServiceIds,
            boundMcpToolNames,
            boundDocumentIds,
            boundDocumentTags,
            toolConfigs,
            routingSettings,
            workflowConfig,
            null,
            null,
            quickQuestions,
            marketStatus,
            defaultAgent);
    }

    public SkillDefinition(String id,
                           String label,
                           String description,
                           List<String> usageScenarios,
                           List<String> skillTags,
                           String defaultMode,
                           String modelName,
                           String systemPrompt,
                           String firstUseGreeting,
                           List<String> preferredToolPrefixes,
                           List<String> boundMcpServiceIds,
                           List<String> boundMcpToolNames,
                           List<String> boundDocumentIds,
                           List<String> boundDocumentTags,
                           List<SkillToolConfig> toolConfigs,
                           SkillRoutingSettings routingSettings,
                           Map<String, Object> workflowConfig,
                           List<String> quickQuestions,
                           String marketStatus) {
        this(id,
            label,
            description,
            usageScenarios,
            skillTags,
            defaultMode,
            modelName,
            systemPrompt,
            firstUseGreeting,
            preferredToolPrefixes,
            boundMcpServiceIds,
            boundMcpToolNames,
            boundDocumentIds,
            boundDocumentTags,
            toolConfigs,
            routingSettings,
            workflowConfig,
            null,
            null,
            quickQuestions,
            marketStatus,
            false);
    }

    public record DefaultDataAsset(
        String assetId,
        String assetName,
        String assetType,
        String warehouseId,
        Boolean enabled
    ) {
    }

    public record AssetSelectionPolicy(
        String strategy,
        Double minRelevanceScore,
        Boolean fallbackWhenEmpty,
        Boolean fallbackWhenInvalid
    ) {
    }
}
