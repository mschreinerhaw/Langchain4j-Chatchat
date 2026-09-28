package com.chatchat.runtime.skill.api.skill;

import java.util.List;

/** Declarative calls to registered computation operators, never executable scripts. */
public record SkillAnalysisStep(String id, String operator, String datasetId, String field, List<String> dependsOn) {
    public SkillAnalysisStep {
        if (id == null || !id.matches("[A-Za-z0-9_.-]{1,80}") || operator == null || operator.isBlank()
            || datasetId == null || datasetId.isBlank()) throw new IllegalArgumentException("Invalid analysis step");
        operator = operator.trim().toUpperCase(java.util.Locale.ROOT);
        field = field == null ? "" : field.trim();
        if (field.length() > 128) throw new IllegalArgumentException("Analysis field is too long");
        dependsOn = dependsOn == null ? List.of() : List.copyOf(dependsOn);
    }
}
