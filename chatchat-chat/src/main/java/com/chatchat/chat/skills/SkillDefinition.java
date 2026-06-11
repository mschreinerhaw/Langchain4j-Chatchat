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
    List<String> quickQuestions,
    String marketStatus
) {
}
