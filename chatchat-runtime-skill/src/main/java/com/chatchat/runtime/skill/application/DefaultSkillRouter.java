package com.chatchat.runtime.skill.application;

import com.chatchat.runtime.skill.api.SkillDescriptor;
import com.chatchat.runtime.skill.api.SkillRouteResult;
import com.chatchat.runtime.skill.api.SkillSearchRequest;
import com.chatchat.runtime.skill.port.outbound.SkillPolicy;
import com.chatchat.runtime.skill.port.inbound.SkillRouter;
import com.chatchat.runtime.skill.port.outbound.SkillSource;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Metadata-only router. It never loads SKILL.md instructions or delegates authorization to a model. */
public final class DefaultSkillRouter implements SkillRouter {
    private final List<SkillSource> sources;
    private final SkillPolicy policy;

    public DefaultSkillRouter(List<SkillSource> sources, SkillPolicy policy) {
        this.sources = sources == null ? List.of() : sources.stream()
            .sorted(Comparator.comparingInt(SkillSource::priority).reversed()).toList();
        this.policy = java.util.Objects.requireNonNull(policy, "policy");
    }

    @Override
    public SkillRouteResult route(SkillSearchRequest request) {
        Map<String, SkillDescriptor> routed = new LinkedHashMap<>();
        List<String> failures = new ArrayList<>();
        Set<String> requested = Set.copyOf(request.requestedSkillIds());
        for (SkillSource source : sources) {
            try {
                List<SkillDescriptor> found = source.search(request);
                if (found == null) continue;
                found.stream().filter(java.util.Objects::nonNull)
                    .filter(item -> requested.isEmpty() || requested.contains(item.id()))
                    .filter(item -> policy.canDiscover(request.roleContext(), item))
                    .forEach(item -> routed.merge(item.id(), item,
                        (left, right) -> right.score() > left.score() ? right : left));
            } catch (RuntimeException failure) {
                failures.add(source.sourceId() + ":" + safe(failure.getMessage()));
            }
        }
        List<SkillDescriptor> candidates = routed.values().stream()
            .sorted(Comparator.comparingDouble(SkillDescriptor::score).reversed()
                .thenComparing(SkillDescriptor::id))
            .limit(request.limit()).toList();
        String status = candidates.isEmpty() ? (failures.isEmpty() ? "EMPTY" : "SOURCE_FAILED")
            : failures.isEmpty() ? "ROUTED" : "PARTIAL_SOURCE_FAILURE";
        return new SkillRouteResult(candidates, status, Map.of(
            "sourceCount", sources.size(), "candidateCount", candidates.size(), "sourceFailures", failures,
            "contentLoaded", false, "authorizationApplied", true));
    }

    private String safe(String value) { return value == null ? "unknown" : value; }
}
