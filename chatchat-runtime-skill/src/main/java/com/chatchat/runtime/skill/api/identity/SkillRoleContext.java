package com.chatchat.runtime.skill.api.identity;

import java.util.List;
import java.util.Map;

/** Authenticated request identity passed through every Skill Runtime stage. */
public record SkillRoleContext(String tenantId, String userId, List<String> roleIds,
                               List<String> organizationIds, Map<String, Object> attributes) {
    public SkillRoleContext {
        tenantId = clean(tenantId);
        userId = clean(userId);
        roleIds = immutable(roleIds);
        organizationIds = immutable(organizationIds);
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }

    private static List<String> immutable(List<String> values) {
        return values == null ? List.of() : values.stream().filter(value -> value != null && !value.isBlank())
            .map(String::trim).distinct().toList();
    }

    private static String clean(String value) { return value == null ? "" : value.trim(); }
}
