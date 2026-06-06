package com.chatchat.api.skills;

import java.util.List;

/**
 * Per-skill MCP tool configuration snapshot.
 */
public record SkillToolConfig(
    String toolName,
    String displayName,
    String serviceId,
    String description,
    List<String> tags,
    String permissionScope,
    Integer callWeight,
    Boolean enabled
) {
}
