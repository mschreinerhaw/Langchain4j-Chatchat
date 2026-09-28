package com.chatchat.runtime.skill.api.resolution;

import com.chatchat.runtime.skill.api.skill.ResolvedSkill;
import java.util.Map;

public record SkillResolution(ResolvedSkill skill, AuthorizedSkillScope authorizedScope,
                              String sourceId, String status, Map<String, Object> diagnostics) {
    public SkillResolution {
        authorizedScope = authorizedScope == null
            ? AuthorizedSkillScope.denied("POLICY_RESULT_MISSING") : authorizedScope;
        sourceId = sourceId == null ? "" : sourceId.trim();
        status = status == null || status.isBlank() ? "NOT_FOUND" : status.trim();
        diagnostics = diagnostics == null ? Map.of() : Map.copyOf(diagnostics);
    }

    public boolean resolved() { return skill != null && authorizedScope.skillAllowed(); }
}
