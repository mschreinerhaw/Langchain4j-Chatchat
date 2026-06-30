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
        List<InterpretationPlan.Binding> bindings = plan.plan().bindings() == null
            ? List.of()
            : new ArrayList<>(plan.plan().bindings());
        List<InterpretationPlan.DependencyContract> dependencyContracts = plan.plan().dependencyContracts() == null
            ? List.of()
            : new ArrayList<>(plan.plan().dependencyContracts());
        InterpretationPlan.Stability stability = plan.plan().stability();
        boolean lockedEdges = stability != null && Boolean.TRUE.equals(stability.lockedEdges());

        StepInputSanitizeResult sanitized = sanitizeDocumentSearchInputs(steps);
        steps = sanitized.steps();
        if (sanitized.changed()) {
            passes.add("DocumentSearchInputSanitizerPass");
        }

        if (!lockedEdges) {
            RewriteState pruned = pruneNoopSteps(plan, steps, edgeContracts, bindings);
            steps = pruned.steps();
            edgeContracts = pruned.edgeContracts();
            bindings = pruned.bindings();
            if (pruned.changed()) {
                passes.add("PruneNoopPass");
            }

            RewriteState deduped = dedupeToolCalls(plan, steps, edgeContracts, bindings);
            steps = deduped.steps();
            edgeContracts = deduped.edgeContracts();
            bindings = deduped.bindings();
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

        PolicyResult retrievalPolicy = retrievalPolicyGuard(plan, steps, parallel.executionPolicy());
        if (retrievalPolicy.changed()) {
            passes.add("RetrievalPolicyGuardPass");
        }

        InterpretationPlan optimized = new InterpretationPlan(
            plan.version(),
            plan.intent(),
            plan.context(),
            new InterpretationPlan.Plan(
                renumber(steps),
                remapContractsForRenumber(steps, edgeContracts),
                remapDependencyContractsForRenumber(steps, dependencyContracts),
                remapBindingsForRenumber(steps, bindings),
                remapStabilityForRenumber(steps, plan.plan().stability())
            ),
            retrievalPolicy.executionPolicy(),
            plan.review()
        );
        return new OptimizationResult(optimized, List.copyOf(passes));
    }

    private StepInputSanitizeResult sanitizeDocumentSearchInputs(List<InterpretationPlan.Step> steps) {
        boolean changed = false;
        List<InterpretationPlan.Step> sanitized = new ArrayList<>(steps.size());
        for (InterpretationPlan.Step step : steps) {
            if (step == null || !isDocumentSearchStep(step) || step.input() == null || strictDocumentScope(step.input())) {
                sanitized.add(step);
                continue;
            }
            Map<String, Object> input = new LinkedHashMap<>(step.input());
            boolean removed = false;
            for (String key : List.of(
                "document_ids",
                "documentIds",
                "fileIds",
                "file_ids",
                "selectedDocumentIds",
                "selected_document_ids",
                "selectedFileIds",
                "selected_file_ids",
                "allowedDocIds",
                "allowed_doc_ids",
                "documentVisibilityEnforced",
                "document_visibility_enforced",
                "tags"
            )) {
                removed = input.remove(key) != null || removed;
            }
            if (!removed) {
                sanitized.add(step);
                continue;
            }
            changed = true;
            sanitized.add(new InterpretationPlan.Step(
                step.id(),
                step.actionType(),
                step.toolName(),
                input,
                step.dependsOn(),
                step.outputContract(),
                step.validation()
            ));
        }
        return new StepInputSanitizeResult(sanitized, changed);
    }

    private PolicyResult retrievalPolicyGuard(InterpretationPlan plan,
                                              List<InterpretationPlan.Step> steps,
                                              InterpretationPlan.ExecutionPolicy policy) {
        if (!isDocumentRetrievalPlan(plan, steps) || policy == null) {
            return new PolicyResult(policy, false);
        }
        Integer maxSteps = policy.maxSteps();
        Integer maxRewriteTimes = policy.maxRewriteTimes();
        Integer guardedMaxSteps = maxSteps != null && maxSteps < 4 ? Integer.valueOf(4) : maxSteps;
        Integer guardedMaxRewriteTimes = maxRewriteTimes != null && maxRewriteTimes < 2 ? Integer.valueOf(2) : maxRewriteTimes;
        if (Objects.equals(maxSteps, guardedMaxSteps) && Objects.equals(maxRewriteTimes, guardedMaxRewriteTimes)) {
            return new PolicyResult(policy, false);
        }
        return new PolicyResult(new InterpretationPlan.ExecutionPolicy(
            guardedMaxSteps,
            policy.allowParallel(),
            policy.allowTool(),
            policy.denyTool(),
            policy.timeoutMs(),
            guardedMaxRewriteTimes,
            policy.fallbackMode(),
            policy.toolPriority(),
            policy.costBudget(),
            policy.latencyBudgetMs(),
            policy.accuracyVsSpeed()
        ), true);
    }

    private RewriteState pruneNoopSteps(InterpretationPlan plan,
                                        List<InterpretationPlan.Step> steps,
                                        List<InterpretationPlan.EdgeContract> edgeContracts,
                                        List<InterpretationPlan.Binding> bindings) {
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
            return new RewriteState(steps, edgeContracts, bindings, false);
        }
        Map<Integer, List<Integer>> dependencies = dependencyMap(steps);
        List<InterpretationPlan.Step> rewritten = steps.stream()
            .filter(step -> step != null && !removed.contains(step.id()))
            .map(step -> withDependencies(step, collapseDependencies(step.dependsOn(), removed, dependencies)))
            .toList();
        List<InterpretationPlan.EdgeContract> contracts = edgeContracts.stream()
            .filter(contract -> contract != null && !removed.contains(contract.from()) && !removed.contains(contract.to()))
            .toList();
        List<InterpretationPlan.Binding> rewrittenBindings = bindings.stream()
            .filter(binding -> binding != null && !removed.contains(binding.from()) && !removed.contains(binding.to()))
            .toList();
        return new RewriteState(rewritten, contracts, rewrittenBindings, true);
    }

    private RewriteState dedupeToolCalls(InterpretationPlan plan,
                                         List<InterpretationPlan.Step> steps,
                                         List<InterpretationPlan.EdgeContract> edgeContracts,
                                         List<InterpretationPlan.Binding> bindings) {
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
            return new RewriteState(steps, edgeContracts, bindings, false);
        }
        List<InterpretationPlan.Step> rewritten = steps.stream()
            .filter(step -> step != null && !redirects.containsKey(step.id()))
            .map(step -> withDependencies(step, redirectDependencies(step.dependsOn(), redirects)))
            .toList();
        List<InterpretationPlan.EdgeContract> contracts = edgeContracts.stream()
            .map(contract -> redirectContract(contract, redirects))
            .filter(contract -> contract != null && !Objects.equals(contract.from(), contract.to()))
            .toList();
        List<InterpretationPlan.Binding> rewrittenBindings = bindings.stream()
            .map(binding -> redirectBinding(binding, redirects))
            .filter(binding -> binding != null && !Objects.equals(binding.from(), binding.to()))
            .toList();
        return new RewriteState(rewritten, contracts, rewrittenBindings, true);
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

    private List<InterpretationPlan.Binding> remapBindingsForRenumber(List<InterpretationPlan.Step> originalSteps,
                                                                      List<InterpretationPlan.Binding> bindings) {
        Map<Integer, Integer> idMap = new LinkedHashMap<>();
        int next = 1;
        for (InterpretationPlan.Step step : originalSteps) {
            idMap.put(step.id(), next++);
        }
        return bindings.stream()
            .map(binding -> new InterpretationPlan.Binding(
                idMap.getOrDefault(binding.from(), binding.from()),
                binding.outputPath(),
                idMap.getOrDefault(binding.to(), binding.to()),
                binding.inputField(),
                binding.type(),
                binding.required()
            ))
            .toList();
    }

    private List<InterpretationPlan.DependencyContract> remapDependencyContractsForRenumber(
        List<InterpretationPlan.Step> originalSteps,
        List<InterpretationPlan.DependencyContract> contracts
    ) {
        Map<Integer, Integer> idMap = new LinkedHashMap<>();
        int next = 1;
        for (InterpretationPlan.Step step : originalSteps) {
            idMap.put(step.id(), next++);
        }
        return contracts.stream()
            .map(contract -> new InterpretationPlan.DependencyContract(
                idMap.getOrDefault(contract.from(), contract.from()),
                idMap.getOrDefault(contract.to(), contract.to()),
                contract.required(),
                contract.condition(),
                contract.reason(),
                contract.onFailure()
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

    private InterpretationPlan.Binding redirectBinding(InterpretationPlan.Binding binding,
                                                       Map<Integer, Integer> redirects) {
        if (binding == null) {
            return null;
        }
        return new InterpretationPlan.Binding(
            redirects.getOrDefault(binding.from(), binding.from()),
            binding.outputPath(),
            redirects.getOrDefault(binding.to(), binding.to()),
            binding.inputField(),
            binding.type(),
            binding.required()
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

    private boolean isDocumentRetrievalPlan(InterpretationPlan plan, List<InterpretationPlan.Step> steps) {
        String intentType = plan == null || plan.intent() == null ? "" : normalize(plan.intent().type());
        if (intentType.contains("document_retrieval")) {
            return true;
        }
        return steps != null && steps.stream().anyMatch(this::isDocumentSearchStep);
    }

    private boolean isDocumentSearchStep(InterpretationPlan.Step step) {
        return step != null && step.mcpToolAction() && normalize(step.toolName()).contains("document_search");
    }

    private boolean strictDocumentScope(Map<String, Object> input) {
        Object strict = firstPresent(input, "strict_document_scope", "strictDocumentScope");
        if (strict instanceof Boolean flag) {
            return flag;
        }
        Object scopeMode = firstPresent(input, "scope_mode", "scopeMode");
        return scopeMode != null && "strict".equalsIgnoreCase(String.valueOf(scopeMode).trim());
    }

    private Object firstPresent(Map<String, Object> input, String... keys) {
        if (input == null) {
            return null;
        }
        for (String key : keys) {
            if (input.containsKey(key)) {
                return input.get(key);
            }
        }
        return null;
    }

    private record RewriteState(
        List<InterpretationPlan.Step> steps,
        List<InterpretationPlan.EdgeContract> edgeContracts,
        List<InterpretationPlan.Binding> bindings,
        boolean changed
    ) {
    }

    private record ParallelResult(
        InterpretationPlan.ExecutionPolicy executionPolicy,
        boolean changed
    ) {
    }

    private record PolicyResult(
        InterpretationPlan.ExecutionPolicy executionPolicy,
        boolean changed
    ) {
    }

    private record OrderingResult(
        List<InterpretationPlan.Step> steps,
        boolean changed
    ) {
    }

    private record StepInputSanitizeResult(
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
