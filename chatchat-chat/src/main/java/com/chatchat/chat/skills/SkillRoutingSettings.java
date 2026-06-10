package com.chatchat.chat.skills;

/**
 * Strategy settings used by one skill during tool routing.
 */
public record SkillRoutingSettings(
    Boolean smartSelectionEnabled,
    Boolean limitParallelCalls,
    Integer maxParallelCalls,
    Integer maxRelevantMcpTools
) {
}
