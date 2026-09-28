package com.chatchat.runtime.skill.api;

import java.util.List;
import java.util.Map;

public record SkillRouteResult(List<SkillDescriptor> candidates, String status,
                               Map<String, Object> diagnostics) {
    public SkillRouteResult {
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
        status = status == null || status.isBlank() ? "EMPTY" : status.trim();
        diagnostics = diagnostics == null ? Map.of() : Map.copyOf(diagnostics);
    }
}
