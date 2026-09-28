package com.chatchat.runtime.skill.api;

public record SkillResourceRequest(String skillId, String resourceId, SkillRoleContext roleContext) {
    public SkillResourceRequest {
        if (skillId == null || skillId.isBlank()) throw new IllegalArgumentException("skillId is required");
        if (resourceId == null || resourceId.isBlank()) throw new IllegalArgumentException("resourceId is required");
        if (roleContext == null) throw new IllegalArgumentException("roleContext is required");
        skillId = skillId.trim();
        resourceId = resourceId.trim();
    }
}
