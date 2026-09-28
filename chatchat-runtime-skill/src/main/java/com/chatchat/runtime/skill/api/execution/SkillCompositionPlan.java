package com.chatchat.runtime.skill.api.execution;

import java.util.*;

public record SkillCompositionPlan(String status, List<Selection> selections, List<String> missingCapabilities,
                                    Map<String, Object> diagnostics) {
    public SkillCompositionPlan {
        selections = List.copyOf(selections); missingCapabilities = List.copyOf(missingCapabilities); diagnostics = Map.copyOf(diagnostics);
    }
    public record Selection(String skillId, String version, String workflowId, List<String> capabilities,
                             List<String> requiresCapabilities) {
        public Selection { capabilities = List.copyOf(capabilities); requiresCapabilities = List.copyOf(requiresCapabilities); }
    }
}
