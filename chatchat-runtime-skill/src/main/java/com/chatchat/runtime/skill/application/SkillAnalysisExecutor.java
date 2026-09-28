package com.chatchat.runtime.skill.application;

import com.chatchat.runtime.skill.api.execution.*;
import com.chatchat.runtime.skill.api.identity.SkillRoleContext;
import com.chatchat.runtime.skill.api.skill.SkillRequirements;
import com.chatchat.runtime.skill.port.outbound.SkillAnalysisOperator;
import java.util.*;
import java.util.function.Supplier;

/** Enforces declared dependencies. It does not assess the truth of the resulting analysis. */
public final class SkillAnalysisExecutor {
    public static final String RESULTS = "skillStepResults";
    private final Supplier<List<SkillAnalysisOperator>> operators;
    public SkillAnalysisExecutor(Supplier<List<SkillAnalysisOperator>> operators) { this.operators = operators; }

    public List<SkillStepResult> execute(SkillRequirements requirements, List<SkillDataResult> data, SkillRoleContext identity) {
        Map<String, SkillDataResult> datasets = new LinkedHashMap<>();
        data.forEach(item -> datasets.put(item.requirement().id(), item));
        Map<String, SkillStepResult> results = new LinkedHashMap<>();
        var pending = new ArrayList<>(requirements.steps());
        while (!pending.isEmpty()) {
            var step = pending.stream().filter(item -> results.keySet().containsAll(item.dependsOn())).findFirst().orElseThrow();
            pending.remove(step);
            if (step.dependsOn().stream().anyMatch(id -> !"COMPLETED".equals(results.get(id).status()))) {
                results.put(step.id(), SkillStepResult.skipped(step.id(), "DEPENDENCY_NOT_COMPLETED"));
                continue;
            }
            var source = datasets.get(step.datasetId());
            boolean missing = requirements.data().stream().filter(item -> !item.optional() && item.requiredFor().contains(step.id()))
                .anyMatch(item -> !available(datasets.get(item.id())));
            if (!available(source) || missing) {
                results.put(step.id(), SkillStepResult.skipped(step.id(), "REQUIRED_DATA_UNAVAILABLE"));
                continue;
            }
            try {
                var matching = operators.get().stream().filter(item -> item.supports(step.operator())).toList();
                if (matching.size() != 1) {
                    results.put(step.id(), SkillStepResult.skipped(step.id(), matching.isEmpty()
                        ? "OPERATOR_NOT_REGISTERED" : "AMBIGUOUS_OPERATOR"));
                } else {
                    var result = matching.get(0).execute(step, source, identity);
                    if (result == null || !step.id().equals(result.stepId())) throw new IllegalStateException("Invalid step result");
                    results.put(step.id(), result);
                }
            } catch (java.util.concurrent.CancellationException cancellation) { throw cancellation;
            } catch (RuntimeException failure) {
                if (Thread.currentThread().isInterrupted()) throw failure;
                results.put(step.id(), new SkillStepResult(step.id(), "FAILED", Map.of(), List.of(), List.of("OPERATOR_FAILED")));
            }
        }
        return List.copyOf(results.values());
    }
    private boolean available(SkillDataResult data) {
        return data != null && (data.status() == SkillDataResult.Status.AVAILABLE || data.status() == SkillDataResult.Status.EMPTY);
    }
}
