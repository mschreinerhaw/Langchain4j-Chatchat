package com.chatchat.agents.runtime.plan;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Applies a model-produced repair as a bounded DAG patch. The failed node and
 * its transitive consumers form the mutable region; every other original node
 * is frozen and restored verbatim before validation.
 */
final class InterpretationPlanIncrementalRepair {

    RepairRegion region(InterpretationPlan original, InterpretationPlan.Step repairRoot) {
        if (original == null || original.steps().isEmpty() || repairRoot == null || repairRoot.id() == null) {
            return new RepairRegion(Set.of(), Set.of(), false);
        }
        Map<Integer, InterpretationPlan.Step> originalById = stepsById(original.steps());
        if (!originalById.containsKey(repairRoot.id())) {
            return new RepairRegion(Set.of(), Set.of(), false);
        }
        Set<Integer> affected = new LinkedHashSet<>();
        affected.add(repairRoot.id());
        boolean changed;
        do {
            changed = false;
            for (InterpretationPlan.Step step : original.steps()) {
                if (step == null || step.id() == null || affected.contains(step.id())) {
                    continue;
                }
                if (safeIds(step.dependsOn()).stream().anyMatch(affected::contains)) {
                    changed |= affected.add(step.id());
                }
            }
        } while (changed);
        Set<Integer> frozen = new LinkedHashSet<>(originalById.keySet());
        frozen.removeAll(affected);
        return new RepairRegion(Set.copyOf(affected), Set.copyOf(frozen), true);
    }

    InterpretationPlan apply(InterpretationPlan original,
                             InterpretationPlan candidate,
                             InterpretationPlan.Step repairRoot) {
        RepairRegion region = region(original, repairRoot);
        if (!region.bounded() || candidate == null || candidate.plan() == null) {
            return candidate;
        }
        if (candidate.steps().size() == 1 && candidate.steps().get(0) != null
            && candidate.steps().get(0).finalAnswerAction()
            && safeIds(candidate.steps().get(0).dependsOn()).isEmpty()) {
            return new InterpretationPlan(
                original.version(), original.intent(), candidate.context(), candidate.plan(),
                candidate.executionPolicy(), candidate.review());
        }
        List<InterpretationPlan.Step> candidateSteps = alignConflictingCandidateIds(
            original, candidate.steps(), region);
        Map<Integer, InterpretationPlan.Step> candidateById = stepsById(candidateSteps);
        List<InterpretationPlan.Step> mergedSteps = new ArrayList<>();
        Set<Integer> emitted = new LinkedHashSet<>();
        for (InterpretationPlan.Step originalStep : original.steps()) {
            if (originalStep == null || originalStep.id() == null) {
                continue;
            }
            InterpretationPlan.Step selected = region.frozenStepIds().contains(originalStep.id())
                ? originalStep : candidateById.get(originalStep.id());
            if (selected != null && emitted.add(selected.id())) {
                mergedSteps.add(selected);
            }
        }
        for (InterpretationPlan.Step candidateStep : candidateSteps) {
            if (candidateStep != null && candidateStep.id() != null && emitted.add(candidateStep.id())) {
                mergedSteps.add(candidateStep);
            }
        }

        InterpretationPlan.Plan originalBody = original.plan();
        InterpretationPlan.Plan candidateBody = candidate.plan();
        InterpretationPlan.Plan mergedBody = new InterpretationPlan.Plan(
            List.copyOf(mergedSteps),
            mergeFrozenContracts(originalBody.edgeContracts(), candidateBody.edgeContracts(), region.frozenStepIds()),
            mergeFrozenDependencyContracts(originalBody.dependencyContracts(), candidateBody.dependencyContracts(), region.frozenStepIds()),
            mergeFrozenBindings(originalBody.bindings(), candidateBody.bindings(), region.frozenStepIds()),
            mergeStability(originalBody.stability(), candidateBody.stability(), region.frozenStepIds()),
            candidateBody.diagnosticProfile() == null
                ? originalBody.diagnosticProfile() : candidateBody.diagnosticProfile(),
            candidateBody.conditionalEdges() == null
                ? originalBody.conditionalEdges() : candidateBody.conditionalEdges(),
            candidateBody.branchGroups() == null
                ? originalBody.branchGroups() : candidateBody.branchGroups()
        );
        return new InterpretationPlan(
            original.version(),
            original.intent(),
            candidate.context(),
            mergedBody,
            candidate.executionPolicy(),
            candidate.review()
        );
    }

    private List<InterpretationPlan.Step> alignConflictingCandidateIds(
        InterpretationPlan original,
        List<InterpretationPlan.Step> candidateSteps,
        RepairRegion region
    ) {
        Map<Integer, InterpretationPlan.Step> originalById = stepsById(original.steps());
        Set<Integer> claimedAffectedIds = new LinkedHashSet<>();
        for (InterpretationPlan.Step step : safeList(candidateSteps)) {
            if (step != null && region.affectedStepIds().contains(step.id())) {
                claimedAffectedIds.add(step.id());
            }
        }
        List<InterpretationPlan.Step> aligned = new ArrayList<>();
        for (InterpretationPlan.Step step : safeList(candidateSteps)) {
            if (step == null || step.id() == null || !region.frozenStepIds().contains(step.id())
                || Objects.equals(step, originalById.get(step.id()))) {
                aligned.add(step);
                continue;
            }
            Integer replacementId = original.steps().stream()
                .filter(Objects::nonNull)
                .filter(originalStep -> originalStep.id() != null
                    && region.affectedStepIds().contains(originalStep.id())
                    && !claimedAffectedIds.contains(originalStep.id())
                    && Objects.equals(originalStep.actionType(), step.actionType()))
                .map(InterpretationPlan.Step::id)
                .findFirst()
                .orElse(null);
            if (replacementId == null) {
                continue;
            }
            claimedAffectedIds.add(replacementId);
            aligned.add(new InterpretationPlan.Step(
                replacementId, step.actionType(), step.toolName(), step.input(),
                step.dependsOn(), step.outputContract(), step.validation()));
        }
        return List.copyOf(aligned);
    }

    private List<InterpretationPlan.EdgeContract> mergeFrozenContracts(
        List<InterpretationPlan.EdgeContract> original,
        List<InterpretationPlan.EdgeContract> candidate,
        Set<Integer> frozen
    ) {
        List<InterpretationPlan.EdgeContract> merged = new ArrayList<>(safeList(candidate));
        for (InterpretationPlan.EdgeContract contract : safeList(original)) {
            if (contract != null && frozen.contains(contract.from()) && frozen.contains(contract.to())
                && !merged.contains(contract)) {
                merged.add(contract);
            }
        }
        return List.copyOf(merged);
    }

    private List<InterpretationPlan.DependencyContract> mergeFrozenDependencyContracts(
        List<InterpretationPlan.DependencyContract> original,
        List<InterpretationPlan.DependencyContract> candidate,
        Set<Integer> frozen
    ) {
        List<InterpretationPlan.DependencyContract> merged = new ArrayList<>(safeList(candidate));
        for (InterpretationPlan.DependencyContract contract : safeList(original)) {
            if (contract != null && frozen.contains(contract.from()) && frozen.contains(contract.to())
                && !merged.contains(contract)) {
                merged.add(contract);
            }
        }
        return List.copyOf(merged);
    }

    private List<InterpretationPlan.Binding> mergeFrozenBindings(
        List<InterpretationPlan.Binding> original,
        List<InterpretationPlan.Binding> candidate,
        Set<Integer> frozen
    ) {
        List<InterpretationPlan.Binding> merged = new ArrayList<>(safeList(candidate));
        for (InterpretationPlan.Binding binding : safeList(original)) {
            if (binding != null && frozen.contains(binding.from()) && frozen.contains(binding.to())
                && !merged.contains(binding)) {
                merged.add(binding);
            }
        }
        return List.copyOf(merged);
    }

    private InterpretationPlan.Stability mergeStability(InterpretationPlan.Stability original,
                                                         InterpretationPlan.Stability candidate,
                                                         Set<Integer> frozen) {
        Set<Integer> stableNodes = new LinkedHashSet<>(frozen);
        if (candidate != null) {
            stableNodes.addAll(safeIds(candidate.stableNodes()));
        }
        List<String> criticalTools = candidate != null && candidate.criticalTools() != null
            ? candidate.criticalTools()
            : original == null ? List.of() : safeList(original.criticalTools());
        Boolean lockedEdges = Boolean.TRUE.equals(original == null ? null : original.lockedEdges())
            || Boolean.TRUE.equals(candidate == null ? null : candidate.lockedEdges());
        List<String> mutableActionTypes = candidate == null
            ? original == null ? List.of() : safeList(original.mutableActionTypes())
            : safeList(candidate.mutableActionTypes());
        return new InterpretationPlan.Stability(
            List.copyOf(stableNodes), criticalTools, lockedEdges, mutableActionTypes);
    }

    private Map<Integer, InterpretationPlan.Step> stepsById(List<InterpretationPlan.Step> steps) {
        Map<Integer, InterpretationPlan.Step> byId = new LinkedHashMap<>();
        for (InterpretationPlan.Step step : safeList(steps)) {
            if (step != null && step.id() != null) {
                byId.putIfAbsent(step.id(), step);
            }
        }
        return byId;
    }

    private List<Integer> safeIds(List<Integer> values) {
        return values == null ? List.of() : values.stream().filter(Objects::nonNull).toList();
    }

    private <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : values;
    }

    record RepairRegion(Set<Integer> affectedStepIds, Set<Integer> frozenStepIds, boolean bounded) {
    }
}
