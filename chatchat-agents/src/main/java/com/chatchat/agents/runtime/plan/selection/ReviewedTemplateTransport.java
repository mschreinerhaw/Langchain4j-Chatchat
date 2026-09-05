package com.chatchat.agents.runtime.plan.selection;

import com.chatchat.agents.runtime.plan.InterpretationPlanRuntime;
import java.util.Collection;
import java.util.Set;
import java.util.function.Function;

/** Explicit source-local selection supersedes stale discovery transport paths, never execution validation. */
public final class ReviewedTemplateTransport {
    private ReviewedTemplateTransport() {}

    public static boolean owns(InterpretationPlanRuntime.StepExecution discovery,
                        Function<Object, Set<String>> candidateIds) {
        if (discovery == null || !discovery.success()
            || !Boolean.TRUE.equals(discovery.metadata().get("semanticCandidateReviewSatisfied"))) return false;
        Object selected = discovery.metadata().get("runtimeSelectedTemplateIds");
        if (!(selected instanceof Collection<?> ids) || ids.isEmpty()
            || ids.stream().anyMatch(id -> !(id instanceof String value) || value.isBlank())) return false;
        return candidateIds.apply(discovery.output()).containsAll(ids);
    }
}
