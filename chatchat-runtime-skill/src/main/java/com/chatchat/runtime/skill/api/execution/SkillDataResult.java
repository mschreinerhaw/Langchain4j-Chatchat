package com.chatchat.runtime.skill.api.execution;

import com.chatchat.runtime.skill.api.skill.SkillDataRequirement;
import java.util.List;
import java.util.Map;

/** Acquisition outcome, not a sufficiency or truth judgment. */
public record SkillDataResult(SkillDataRequirement requirement, Status status,
                              List<Map<String, Object>> rows, Map<String, Object> provenance,
                              List<String> observations) {
    public enum Status { AVAILABLE, EMPTY, NO_BINDING, AMBIGUOUS_BINDING, DENIED, MISSING_INPUT, INVALID_DATA, FAILED }
    public SkillDataResult {
        if (requirement == null || status == null) throw new IllegalArgumentException("Requirement and status are required");
        rows = rows == null ? List.of() : rows.stream()
            .map(row -> java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(row))).toList();
        provenance = provenance == null ? Map.of() : Map.copyOf(provenance);
        observations = observations == null ? List.of() : List.copyOf(observations);
    }
    public static SkillDataResult unavailable(SkillDataRequirement requirement, Status status, String observation) {
        return new SkillDataResult(requirement, status, List.of(), Map.of(), List.of(observation));
    }
}
