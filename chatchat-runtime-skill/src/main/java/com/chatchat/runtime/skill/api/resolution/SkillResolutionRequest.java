package com.chatchat.runtime.skill.api;

public record SkillResolutionRequest(String skillId, String version, SkillRoleContext roleContext) {
    public SkillResolutionRequest {
        if (skillId == null || skillId.isBlank()) throw new IllegalArgumentException("skillId is required");
        if (roleContext == null) throw new IllegalArgumentException("roleContext is required");
        skillId = skillId.trim();
        version = version == null ? "" : version.trim();
    }
}
