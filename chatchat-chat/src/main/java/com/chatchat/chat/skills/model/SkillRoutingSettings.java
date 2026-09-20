package com.chatchat.chat.skills.model;

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
