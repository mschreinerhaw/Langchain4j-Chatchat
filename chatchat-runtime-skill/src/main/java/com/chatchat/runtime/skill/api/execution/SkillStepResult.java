package com.chatchat.runtime.skill.api.execution;

import java.util.List;
import java.util.Map;

public record SkillStepResult(String stepId, String status, Map<String, Object> output,
                              List<String> evidenceIds, List<String> observations) {
    public SkillStepResult {
        output = output == null ? Map.of() : Map.copyOf(output);
        evidenceIds = evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
        observations = observations == null ? List.of() : List.copyOf(observations);
    }
    public static SkillStepResult skipped(String id, String reason) {
        return new SkillStepResult(id, "SKIPPED", Map.of(), List.of(), List.of(reason));
    }
}
