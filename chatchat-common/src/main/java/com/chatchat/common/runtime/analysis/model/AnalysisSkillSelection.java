package com.chatchat.common.runtime.analysis.model;

import java.util.List;

/** One independently authorized Skill and its explicitly selected documents. */
public record AnalysisSkillSelection(String skillId, List<String> documentIds, List<String> roles) {
    public AnalysisSkillSelection {
        if (skillId == null || skillId.isBlank()) throw new IllegalArgumentException("skillId is required");
        skillId = skillId.trim();
        documentIds = documentIds == null ? List.of() : List.copyOf(documentIds);
        roles = roles == null ? List.of() : List.copyOf(roles);
    }
}
