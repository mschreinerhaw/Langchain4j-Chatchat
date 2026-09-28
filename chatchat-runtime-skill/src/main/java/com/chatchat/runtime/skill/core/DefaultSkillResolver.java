package com.chatchat.runtime.skill.core;

import com.chatchat.runtime.skill.api.AuthorizedSkillScope;
import com.chatchat.runtime.skill.api.ResolvedSkill;
import com.chatchat.runtime.skill.api.SkillResolution;
import com.chatchat.runtime.skill.api.SkillResolutionRequest;
import com.chatchat.runtime.skill.spi.SkillPolicy;
import com.chatchat.runtime.skill.spi.SkillResolver;
import com.chatchat.runtime.skill.spi.SkillSource;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Loads full instructions only after routing, then intersects requirements with database policy. */
public final class DefaultSkillResolver implements SkillResolver {
    private final List<SkillSource> sources;
    private final SkillPolicy policy;

    public DefaultSkillResolver(List<SkillSource> sources, SkillPolicy policy) {
        this.sources = sources == null ? List.of() : sources.stream()
            .sorted(Comparator.comparingInt(SkillSource::priority).reversed()).toList();
        this.policy = java.util.Objects.requireNonNull(policy, "policy");
    }

    @Override
    public SkillResolution resolve(SkillResolutionRequest request) {
        for (SkillSource source : sources) {
            Optional<ResolvedSkill> resolved = source.resolve(request);
            if (resolved.isEmpty()) continue;
            AuthorizedSkillScope scope = policy.authorize(request.roleContext(), resolved.get());
            return new SkillResolution(scope.skillAllowed() ? resolved.get() : null, scope, source.sourceId(),
                scope.skillAllowed() ? "RESOLVED" : "DENIED",
                Map.of("authorizationApplied", true, "requirementsAreAuthority", false));
        }
        return new SkillResolution(null, AuthorizedSkillScope.denied("SKILL_NOT_FOUND"), "", "NOT_FOUND", Map.of());
    }
}
