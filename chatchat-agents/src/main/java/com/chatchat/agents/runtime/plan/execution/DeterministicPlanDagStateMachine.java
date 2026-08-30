package com.chatchat.agents.runtime.plan.execution;

import com.chatchat.agents.runtime.plan.InterpretationPlan;

import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Pure DAG transitions shared by the in-process runtime and Temporal Workflow code. */
public final class DeterministicPlanDagStateMachine {

    public Graph compile(InterpretationPlan plan) {
        return compileSteps(plan == null ? List.of() : plan.steps());
    }

    public Graph compileSteps(Collection<InterpretationPlan.Step> steps) {
        Map<Integer, List<Integer>> dependencies = new LinkedHashMap<>();
        if (steps != null) {
            steps.stream()
                .filter(Objects::nonNull)
                .filter(step -> step.id() != null)
                .sorted(Comparator.comparing(InterpretationPlan.Step::id))
                .forEach(step -> dependencies.put(
                    step.id(), normalizedIds(step.dependsOn())));
        }
        return new Graph(Map.copyOf(dependencies));
    }

    public List<Integer> ready(Graph graph,
                               Collection<Integer> remainingStepIds,
                               Collection<Integer> completedStepIds) {
        if (graph == null || remainingStepIds == null || remainingStepIds.isEmpty()) {
            return List.of();
        }
        Set<Integer> completed = normalizedSet(completedStepIds);
        return normalizedIds(remainingStepIds).stream()
            .filter(graph.dependencies()::containsKey)
            .filter(stepId -> completed.containsAll(graph.dependencies().get(stepId)))
            .toList();
    }

    public BarrierDecision decideBarrier(Collection<NodeOutcome> outcomes,
                                         boolean commitIndependentSuccesses) {
        List<NodeOutcome> normalized = outcomes == null ? List.of() : outcomes.stream()
            .filter(Objects::nonNull)
            .filter(outcome -> outcome.stepId() != null)
            .sorted(Comparator.comparing(NodeOutcome::stepId))
            .toList();
        List<Integer> successful = normalized.stream()
            .filter(NodeOutcome::success)
            .map(NodeOutcome::stepId)
            .toList();
        List<Integer> failed = normalized.stream()
            .filter(outcome -> !outcome.success())
            .map(NodeOutcome::stepId)
            .toList();
        if (failed.isEmpty()) {
            return new BarrierDecision(successful, List.of(), List.of(), "COMMIT_ALL");
        }
        if (commitIndependentSuccesses) {
            return new BarrierDecision(successful, List.of(), failed, "COMMIT_INDEPENDENT");
        }
        return new BarrierDecision(List.of(), successful, failed, "REJECT_WAVE");
    }

    public Set<Integer> descendants(Graph graph, Collection<Integer> rootStepIds) {
        if (graph == null || rootStepIds == null || rootStepIds.isEmpty()) {
            return Set.of();
        }
        Set<Integer> roots = normalizedSet(rootStepIds);
        Set<Integer> descendants = new LinkedHashSet<>();
        boolean changed;
        do {
            changed = false;
            for (Map.Entry<Integer, List<Integer>> entry : graph.dependencies().entrySet()) {
                Integer stepId = entry.getKey();
                if (roots.contains(stepId) || descendants.contains(stepId)) {
                    continue;
                }
                boolean affected = entry.getValue().stream()
                    .anyMatch(dependency -> roots.contains(dependency) || descendants.contains(dependency));
                if (affected) {
                    changed |= descendants.add(stepId);
                }
            }
        } while (changed);
        return Set.copyOf(descendants);
    }

    private List<Integer> normalizedIds(Collection<Integer> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream().filter(Objects::nonNull).distinct().sorted().toList();
    }

    private Set<Integer> normalizedSet(Collection<Integer> values) {
        return new LinkedHashSet<>(normalizedIds(values));
    }

    public record Graph(Map<Integer, List<Integer>> dependencies) {
        public Graph {
            Map<Integer, List<Integer>> copy = new LinkedHashMap<>();
            if (dependencies != null) {
                dependencies.entrySet().stream()
                    .filter(entry -> entry.getKey() != null)
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> copy.put(entry.getKey(), entry.getValue() == null
                        ? List.of() : List.copyOf(entry.getValue())));
            }
            dependencies = Map.copyOf(copy);
        }
    }

    public record NodeOutcome(Integer stepId, boolean success) {
    }

    public record BarrierDecision(
        List<Integer> commitStepIds,
        List<Integer> rejectStepIds,
        List<Integer> failedStepIds,
        String action
    ) {
        public BarrierDecision {
            commitStepIds = commitStepIds == null ? List.of() : List.copyOf(commitStepIds);
            rejectStepIds = rejectStepIds == null ? List.of() : List.copyOf(rejectStepIds);
            failedStepIds = failedStepIds == null ? List.of() : List.copyOf(failedStepIds);
        }
    }
}
