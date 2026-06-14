package com.chatchat.agents.runtime.plan;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Comparator;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Lightweight optimization passes for InterpretationPlan before DAG execution.
 */
public class InterpretationPlanOptimizer {

    public OptimizationResult optimize(InterpretationPlan plan) {
        if (plan == null || plan.plan() == null || plan.steps().isEmpty()) {
            return new OptimizationResult(plan, List.of());
        }
        List<String> passes = new ArrayList<>();
        List<InterpretationPlan.Step> steps = new ArrayList<>(plan.steps());
        List<InterpretationPlan.EdgeContract> edgeContracts = plan.plan().edgeContracts() == null
            ? List.of()
            : new ArrayList<>(plan.plan().edgeContracts());
        InterpretationPlan.Stability stability = plan.plan().stability();
        boolean lockedEdges = stability != null && Boolean.TRUE.equals(stability.lockedEdges());

        if (!lockedEdges) {
            RewriteState pruned = pruneNoopSteps(plan, steps, edgeContracts);
            steps = pruned.steps();
            edgeContracts = pruned.edgeContracts();
            if (pruned.changed()) {
                passes.add("PruneNoopPass");
            }

            RewriteState deduped = dedupeToolCalls(plan, steps, edgeContracts);
            steps = deduped.steps();
            edgeContracts = deduped.edgeContracts();
            if (deduped.changed()) {
                passes.add("DedupeToolCallPass");
            }
        }

        OrderingResult ordering = policyAwareOrdering(plan, steps);
        steps = ordering.steps();
        if (ordering.changed()) {
            passes.add("PolicyAwareOrderingPass");
        }

        ParallelResult parallel = parallelHint(plan, steps);
        if (parallel.changed()) {
            passes.add("ParallelHintPass");
        }

        InterpretationPlan optimized = new InterpretationPlan(
            plan.version(),
            plan.intent(),
            plan.context(),
            new InterpretationPlan.Plan(
                renumber(steps),
                remapContractsForRenumber(steps, edgeContracts),
                remapStabilityForRenumber(steps, plan.plan().stability())
            ),
            parallel.executionPolicy(),
            plan.review()
        );
        return new OptimizationResult(optimized, List.copyOf(passes));
    }

    private RewriteState pruneNoopSteps(InterpretationPlan plan,
                                        List<InterpretationPlan.Step> steps,
                                        List<InterpretationPlan.EdgeContract> edgeContracts) {
        Set<Integer> removed = new LinkedHashSet<>();
        Set<Integer> stableNodes = stableNodes(plan);
        Set<String> mutableTypes = mutableActionTypes(plan);
        for (InterpretationPlan.Step step : steps) {
            if (step == null || step.id() == null || step.mcpToolAction() || step.finalAnswerAction()) {
                continue;
            }
            if (stableNodes.contains(step.id()) || !mutableTypes.contains(normalize(step.actionType()))) {
                continue;
            }
            if (step.input() == null || step.input().isEmpty()) {
                removed.add(step.id());
            }
        }
        if (removed.isEmpty()) {
            return new RewriteState(steps, edgeContracts, false);
        }
        Map<Integer, List<Integer>> dependencies = dependencyMap(steps);
        List<InterpretationPlan.Step> rewritten = steps.stream()
            .filter(step -> step != null && !removed.contains(step.id()))
            .map(step -> withDependencies(step, collapseDependencies(step.dependsOn(), removed, dependencies)))
            .toList();
        List<InterpretationPlan.EdgeContract> contracts = edgeContracts.stream()
            .filter(contract -> contract != null && !removed.contains(contract.from()) && !removed.contains(contract.to()))
            .toList();
        return new RewriteState(rewritten, contracts, true);
    }

    private RewriteState dedupeToolCalls(InterpretationPlan plan,
                                         List<InterpretationPlan.Step> steps,
                                         List<InterpretationPlan.EdgeContract> edgeContracts) {
        Map<String, Integer> firstBySignature = new LinkedHashMap<>();
        Map<Integer, Integer> redirects = new LinkedHashMap<>();
        Set<Integer> stableNodes = stableNodes(plan);
        Set<String> criticalTools = criticalTools(plan);
        for (InterpretationPlan.Step step : steps) {
            if (step == null || !step.mcpToolAction()) {
                continue;
            }
            if (stableNodes.contains(step.id()) || criticalTools.contains(normalize(step.toolName()))) {
                continue;
            }
            String signature = step.toolName() + "::" + Objects.toString(step.input());
            Integer existing = firstBySignature.putIfAbsent(signature, step.id());
            if (existing != null && !stableNodes.contains(existing)) {
                redirects.put(step.id(), existing);
            }
        }
        if (redirects.isEmpty()) {
            return new RewriteState(steps, edgeContracts, false);
        }
        List<InterpretationPlan.Step> rewritten = steps.stream()
            .filter(step -> step != null && !redirects.containsKey(step.id()))
            .map(step -> withDependencies(step, redirectDependencies(step.dependsOn(), redirects)))
            .toList();
        List<InterpretationPlan.EdgeContract> contracts = edgeContracts.stream()
            .map(contract -> redirectContract(contract, redirects))
            .filter(contract -> contract != null && !Objects.equals(contract.from(), contract.to()))
            .toList();
        return new RewriteState(rewritten, contracts, true);
    }

    private OrderingResult policyAwareOrdering(InterpretationPlan plan, List<InterpretationPlan.Step> steps) {
        Map<String, Double> priority = plan.executionPolicy() == null || plan.executionPolicy().toolPriority() == null
            ? Map.of()
            : plan.executionPolicy().toolPriority();
        if (priority.isEmpty()) {
            return new OrderingResult(steps, false);
        }
        Set<Integer> stableNodes = stableNodes(plan);
        Comparator<InterpretationPlan.Step> comparator = Comparator
            .comparingInt((InterpretationPlan.Step step) -> step.dependsOn() == null ? 0 : step.dependsOn().size())
            .thenComparing((InterpretationPlan.Step step) -> -toolPriority(priority, step))
            .thenComparing(InterpretationPlan.Step::id);
        List<InterpretationPlan.Step> ordered;
        if (stableNodes.isEmpty()) {
            ordered = new ArrayList<>(steps);
            ordered.sort(comparator);
        } else {
            List<InterpretationPlan.Step> mutableOrdered = steps.stream()
                .filter(step -> step != null && !stableNodes.contains(step.id()))
                .sorted(comparator)
                .toList();
            ordered = new ArrayList<>(steps.size());
            int mutableIndex = 0;
            for (InterpretationPlan.Step step : steps) {
                if (step != null && stableNodes.contains(step.id())) {
                    ordered.add(step);
                } else {
                    ordered.add(mutableOrdered.get(mutableIndex++));
                }
            }
        }
        boolean changed = !ordered.stream().map(InterpretationPlan.Step::id).toList()
            .equals(steps.stream().map(InterpretationPlan.Step::id).toList());
        return new OrderingResult(ordered, changed);
    }

    private ParallelResult parallelHint(InterpretationPlan plan, List<InterpretationPlan.Step> steps) {
        InterpretationPlan.ExecutionPolicy policy = plan.executionPolicy();
        if (policy == null || policy.allowParallel() != null) {
            return new ParallelResult(policy, false);
        }
        long independentToolSteps = steps.stream()
            .filter(step -> step != null && step.mcpToolAction())
            .filter(step -> step.dependsOn() == null || step.dependsOn().isEmpty())
            .count();
        if (independentToolSteps <= 1) {
            return new ParallelResult(policy, false);
        }
        return new ParallelResult(new InterpretationPlan.ExecutionPolicy(
            policy.maxSteps(),
            true,
            policy.allowTool(),
            policy.denyTool(),
            policy.timeoutMs(),
            policy.maxRewriteTimes(),
            policy.fallbackMode(),
            policy.toolPriority(),
            policy.costBudget(),
            policy.latencyBudgetMs(),
            policy.accuracyVsSpeed()
        ), true);
    }

    private List<InterpretationPlan.Step> renumber(List<InterpretationPlan.Step> steps) {
        Map<Integer, Integer> idMap = new LinkedHashMap<>();
        int next = 1;
        for (InterpretationPlan.Step step : steps) {
            idMap.put(step.id(), next++);
        }
        return steps.stream()
            .map(step -> new InterpretationPlan.Step(
                idMap.get(step.id()),
                step.actionType(),
                step.toolName(),
                step.input(),
                redirectDependencies(step.dependsOn(), idMap),
                step.outputContract(),
                step.validation()
            ))
            .toList();
    }

    private List<InterpretationPlan.EdgeContract> remapContractsForRenumber(List<InterpretationPlan.Step> originalSteps,
                                                                             List<InterpretationPlan.EdgeContract> contracts) {
        Map<Integer, Integer> idMap = new LinkedHashMap<>();
        int next = 1;
        for (InterpretationPlan.Step step : originalSteps) {
            idMap.put(step.id(), next++);
        }
        return contracts.stream()
            .map(contract -> new InterpretationPlan.EdgeContract(
                idMap.getOrDefault(contract.from(), contract.from()),
                idMap.getOrDefault(contract.to(), contract.to()),
                contract.field(),
                contract.type(),
                contract.required()
            ))
            .toList();
    }

    private InterpretationPlan.Stability remapStabilityForRenumber(List<InterpretationPlan.Step> originalSteps,
                                                                    InterpretationPlan.Stability stability) {
        if (stability == null) {
            return null;
        }
        Map<Integer, Integer> idMap = new LinkedHashMap<>();
        int next = 1;
        for (InterpretationPlan.Step step : originalSteps) {
            idMap.put(step.id(), next++);
        }
        List<Integer> stableNodes = stability.stableNodes() == null
            ? null
            : stability.stableNodes().stream()
                .map(stepId -> idMap.getOrDefault(stepId, stepId))
                .distinct()
                .toList();
        return new InterpretationPlan.Stability(
            stableNodes,
            stability.criticalTools(),
            stability.lockedEdges(),
            stability.mutableActionTypes()
        );
    }

    private Map<Integer, List<Integer>> dependencyMap(List<InterpretationPlan.Step> steps) {
        Map<Integer, List<Integer>> values = new LinkedHashMap<>();
        for (InterpretationPlan.Step step : steps) {
            values.put(step.id(), step.dependsOn() == null ? List.of() : step.dependsOn());
        }
        return values;
    }

    private List<Integer> collapseDependencies(List<Integer> dependencies,
                                               Set<Integer> removed,
                                               Map<Integer, List<Integer>> dependencyMap) {
        List<Integer> values = new ArrayList<>();
        for (Integer dependency : dependencies == null ? List.<Integer>of() : dependencies) {
            if (removed.contains(dependency)) {
                values.addAll(collapseDependencies(dependencyMap.get(dependency), removed, dependencyMap));
            } else if (!values.contains(dependency)) {
                values.add(dependency);
            }
        }
        return values;
    }

    private List<Integer> redirectDependencies(List<Integer> dependencies, Map<Integer, Integer> redirects) {
        if (dependencies == null || dependencies.isEmpty()) {
            return List.of();
        }
        List<Integer> values = new ArrayList<>();
        for (Integer dependency : dependencies) {
            Integer redirected = redirects.getOrDefault(dependency, dependency);
            if (!values.contains(redirected)) {
                values.add(redirected);
            }
        }
        return values;
    }

    private InterpretationPlan.Step withDependencies(InterpretationPlan.Step step, List<Integer> dependencies) {
        return new InterpretationPlan.Step(
            step.id(),
            step.actionType(),
            step.toolName(),
            step.input(),
            dependencies,
            step.outputContract(),
            step.validation()
        );
    }

    private InterpretationPlan.EdgeContract redirectContract(InterpretationPlan.EdgeContract contract,
                                                             Map<Integer, Integer> redirects) {
        if (contract == null) {
            return null;
        }
        return new InterpretationPlan.EdgeContract(
            redirects.getOrDefault(contract.from(), contract.from()),
            redirects.getOrDefault(contract.to(), contract.to()),
            contract.field(),
            contract.type(),
            contract.required()
        );
    }

    private Set<Integer> stableNodes(InterpretationPlan plan) {
        if (plan == null || plan.plan() == null || plan.plan().stability() == null || plan.plan().stability().stableNodes() == null) {
            return Set.of();
        }
        return new LinkedHashSet<>(plan.plan().stability().stableNodes());
    }

    private Set<String> criticalTools(InterpretationPlan plan) {
        if (plan == null || plan.plan() == null || plan.plan().stability() == null || plan.plan().stability().criticalTools() == null) {
            return Set.of();
        }
        Set<String> values = new LinkedHashSet<>();
        plan.plan().stability().criticalTools().forEach(tool -> values.add(normalize(tool)));
        return values;
    }

    private Set<String> mutableActionTypes(InterpretationPlan plan) {
        if (plan == null || plan.plan() == null || plan.plan().stability() == null
            || plan.plan().stability().mutableActionTypes() == null
            || plan.plan().stability().mutableActionTypes().isEmpty()) {
            return Set.of("reasoning", "retrieval", "aggregation", "validation");
        }
        Set<String> values = new LinkedHashSet<>();
        plan.plan().stability().mutableActionTypes().forEach(type -> values.add(normalize(type)));
        return values;
    }

    private double toolPriority(Map<String, Double> priority, InterpretationPlan.Step step) {
        if (step == null || step.toolName() == null || priority == null || priority.isEmpty()) {
            return 0.0;
        }
        Double value = priority.get(step.toolName());
        if (value != null) {
            return value;
        }
        return priority.getOrDefault(normalize(step.toolName()), 0.0);
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase().replace('-', '_');
    }

    private record RewriteState(
        List<InterpretationPlan.Step> steps,
        List<InterpretationPlan.EdgeContract> edgeContracts,
        boolean changed
    ) {
    }

    private record ParallelResult(
        InterpretationPlan.ExecutionPolicy executionPolicy,
        boolean changed
    ) {
    }

    private record OrderingResult(
        List<InterpretationPlan.Step> steps,
        boolean changed
    ) {
    }

    public record OptimizationResult(
        InterpretationPlan plan,
        List<String> appliedPasses
    ) {
    }
}
