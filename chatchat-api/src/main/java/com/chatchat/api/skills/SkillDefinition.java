package com.chatchat.api.skills;

import java.util.List;

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
    String systemPrompt,
    String firstUseGreeting,
    List<String> preferredToolPrefixes,
    List<String> boundMcpServiceIds,
    List<String> boundMcpToolNames,
    List<SkillToolConfig> toolConfigs,
    SkillRoutingSettings routingSettings,
    List<String> quickQuestions
) {
}
