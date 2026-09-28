package com.chatchat.runtime.skill.api.skill;

import com.chatchat.runtime.skill.api.resource.SkillResourceDescriptor;
import java.util.List;
import java.util.Map;

/** L2 Skill payload loaded only after metadata routing. */
public record ResolvedSkill(SkillDescriptor descriptor, String instructions,
                            List<SkillResourceDescriptor> resources,
                            SkillRequirements requirements, Map<String, Object> metadata) {
    public ResolvedSkill {
        if (descriptor == null) throw new IllegalArgumentException("descriptor is required");
        instructions = instructions == null ? "" : instructions;
        resources = resources == null ? List.of() : List.copyOf(resources);
        requirements = requirements == null ? SkillRequirements.empty() : requirements;
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
