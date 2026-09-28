package com.chatchat.runtime.skill.api.execution;

import java.util.*;

public record SkillCompositionResult(String status, SkillCompositionPlan plan,
        Map<String, SkillExecutionResult> results, Map<String, String> skipped, Map<String, Object> metrics) {
    public SkillCompositionResult { results = Map.copyOf(results); skipped = Map.copyOf(skipped); metrics = Map.copyOf(metrics); }
}
