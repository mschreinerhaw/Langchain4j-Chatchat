package com.chatchat.agents.runtime.plan;

import java.util.ArrayList;
import java.util.Collection;
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
        return optimize(plan, null);
    }

    public OptimizationResult optimize(InterpretationPlan plan, Object authoritativeWorkflowDag) {
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

        boolean hasAuthoritativeWorkflowDag = !configuredWorkflowNodes(authoritativeWorkflowDag).isEmpty();
        ConfiguredDagRepairResult configuredDag = repairConfiguredWorkflowDag(
            steps, dependencyContracts, authoritativeWorkflowDag);
        steps = configuredDag.steps();
        dependencyContracts = configuredDag.dependencyContracts();
        if (configuredDag.changed()) {
            passes.add("AuthoritativeWorkflowDagPass");
        }

        TemplateDagRepairResult templateDag = repairTemplateExecutionDag(
            steps, edgeContracts, dependencyContracts, bindings, !hasAuthoritativeWorkflowDag);
        steps = templateDag.steps();
        edgeContracts = templateDag.edgeContracts();
        dependencyContracts = templateDag.dependencyContracts();
        bindings = templateDag.bindings();
        if (templateDag.changed()) {
            passes.add("TemplateExecutionDagRepairPass");
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
                remapStabilityForRenumber(steps, plan.plan().stability()),
                remapDiagnosticProfileForRenumber(steps, plan.plan().diagnosticProfile())
            ),
            retrievalPolicy.executionPolicy(),
            plan.review()
        );
        return new OptimizationResult(optimized, List.copyOf(passes));
    }

    /** Applies only user-configured workflow-to-workflow edges; model edges are not authoritative. */
    private ConfiguredDagRepairResult repairConfiguredWorkflowDag(
        List<InterpretationPlan.Step> sourceSteps,
        List<InterpretationPlan.DependencyContract> sourceDependencies,
        Object rawDag
    ) {
        List<ConfiguredWorkflowNode> nodes = configuredWorkflowNodes(rawDag);
        if (nodes.isEmpty()) {
            return new ConfiguredDagRepairResult(
                new ArrayList<>(sourceSteps), new ArrayList<>(sourceDependencies), false);
        }
        List<InterpretationPlan.Step> steps = new ArrayList<>(sourceSteps);
        List<InterpretationPlan.DependencyContract> contracts = new ArrayList<>(sourceDependencies);
        Map<String, InterpretationPlan.Step> planStepsByTool = new LinkedHashMap<>();
        for (ConfiguredWorkflowNode node : nodes) {
            List<InterpretationPlan.Step> matches = steps.stream()
                .filter(step -> sameProtocolTool(step == null ? null : step.toolName(), node.toolName()))
                .toList();
            if (matches.size() == 1) {
                planStepsByTool.put(semanticToolName(node.toolName()), matches.get(0));
            }
        }
        Set<Integer> configuredStepIds = new LinkedHashSet<>();
        planStepsByTool.values().stream()
            .map(InterpretationPlan.Step::id)
            .filter(Objects::nonNull)
            .forEach(configuredStepIds::add);
        boolean changed = false;
        for (ConfiguredWorkflowNode node : nodes) {
            InterpretationPlan.Step target = planStepsByTool.get(semanticToolName(node.toolName()));
            if (target == null || target.id() == null) {
                continue;
            }
            Set<Integer> requiredIds = node.dependsOnTools().stream()
                .map(tool -> planStepsByTool.get(semanticToolName(tool)))
                .filter(Objects::nonNull)
                .map(InterpretationPlan.Step::id)
                .filter(Objects::nonNull)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            List<Integer> repaired = new ArrayList<>();
            for (Integer dependency : target.dependsOn() == null ? List.<Integer>of() : target.dependsOn()) {
                if (!configuredStepIds.contains(dependency) || requiredIds.contains(dependency)) {
                    repaired.add(dependency);
                }
            }
            requiredIds.forEach(dependency -> {
                if (!repaired.contains(dependency)) {
                    repaired.add(dependency);
                }
            });
            if (!repaired.equals(target.dependsOn() == null ? List.of() : target.dependsOn())) {
                int index = indexOfStep(steps, target.id());
                steps.set(index, withDependencies(target, List.copyOf(repaired)));
                target = steps.get(index);
                planStepsByTool.put(semanticToolName(node.toolName()), target);
                changed = true;
            }
            for (Integer requiredId : requiredIds) {
                changed |= addRequiredDependencyContract(
                    contracts, requiredId, target.id(),
                    "Required by the user-defined task workflow DAG.");
            }
        }
        Set<String> configuredEdges = nodes.stream()
            .flatMap(node -> node.dependsOnTools().stream()
                .map(source -> semanticToolName(source) + "->" + semanticToolName(node.toolName())))
            .collect(java.util.stream.Collectors.toSet());
        int before = contracts.size();
        contracts.removeIf(contract -> {
            if (contract == null || contract.from() == null || contract.to() == null
                || !configuredStepIds.contains(contract.from()) || !configuredStepIds.contains(contract.to())) {
                return false;
            }
            InterpretationPlan.Step from = steps.stream()
                .filter(step -> Objects.equals(step.id(), contract.from())).findFirst().orElse(null);
            InterpretationPlan.Step to = steps.stream()
                .filter(step -> Objects.equals(step.id(), contract.to())).findFirst().orElse(null);
            return from != null && to != null && !configuredEdges.contains(
                semanticToolName(from.toolName()) + "->" + semanticToolName(to.toolName()));
        });
        changed |= before != contracts.size();
        return new ConfiguredDagRepairResult(steps, contracts, changed);
    }

    private List<ConfiguredWorkflowNode> configuredWorkflowNodes(Object rawDag) {
        if (!(rawDag instanceof Collection<?> collection)) {
            return List.of();
        }
        List<ConfiguredWorkflowNode> nodes = new ArrayList<>();
        for (Object value : collection) {
            if (!(value instanceof Map<?, ?> map)) {
                continue;
            }
            String tool = mapValue(map, "tool", "toolName");
            if (tool == null || tool.isBlank()) {
                continue;
            }
            Object dependencies = map.get("dependsOnTools");
            List<String> dependsOnTools = dependencies instanceof Collection<?> values
                ? values.stream().filter(Objects::nonNull).map(String::valueOf).filter(text -> !text.isBlank()).toList()
                : List.of();
            nodes.add(new ConfiguredWorkflowNode(tool.trim(), dependsOnTools));
        }
        return nodes;
    }

    private String mapValue(Map<?, ?> map, String... keys) {
        for (String key : keys) {
            Object value = map.get(key);
            if (value != null && !String.valueOf(value).isBlank()) {
                return String.valueOf(value).trim();
            }
        }
        return null;
    }

    private boolean sameProtocolTool(String left, String right) {
        return !semanticToolName(left).isBlank() && semanticToolName(left).equals(semanticToolName(right));
    }

    private TemplateDagRepairResult repairTemplateExecutionDag(
        List<InterpretationPlan.Step> sourceSteps,
        List<InterpretationPlan.EdgeContract> sourceEdges,
        List<InterpretationPlan.DependencyContract> sourceDependencies,
        List<InterpretationPlan.Binding> sourceBindings,
        boolean mayRepairWorkflowEdges
    ) {
        List<InterpretationPlan.Step> steps = new ArrayList<>(sourceSteps);
        List<InterpretationPlan.EdgeContract> edges = new ArrayList<>(sourceEdges);
        List<InterpretationPlan.DependencyContract> dependencies = new ArrayList<>(sourceDependencies);
        List<InterpretationPlan.Binding> bindings = new ArrayList<>(sourceBindings);
        List<InterpretationPlan.Step> assets = steps.stream()
            .filter(this::isAssetDiscoveryStep)
            .toList();
        List<InterpretationPlan.Step> templates = steps.stream()
            .filter(this::isTemplateDiscoveryStep)
            .toList();
        List<InterpretationPlan.Step> executors = steps.stream()
            .filter(this::isTemplateExecutionStep)
            .toList();
        if (assets.isEmpty() || templates.isEmpty() || executors.isEmpty()) {
            return new TemplateDagRepairResult(steps, edges, dependencies, bindings, false);
        }

        boolean changed = false;
        for (InterpretationPlan.Step template : templates) {
            InterpretationPlan.Step asset = bestProtocolPredecessor(template, assets);
            if (asset == null) {
                continue;
            }
            if (mayRepairWorkflowEdges) {
                changed |= addDependency(steps, template.id(), asset.id());
                changed |= addRequiredDependencyContract(
                    dependencies, asset.id(), template.id(),
                    "Template discovery requires the current task asset evidence.");
            }
            int templateIndex = indexOfStep(steps, template.id());
            if (templateIndex >= 0) {
                InterpretationPlan.Step current = steps.get(templateIndex);
                Map<String, Object> input = new LinkedHashMap<>(
                    current.input() == null ? Map.of() : current.input());
                boolean removedLiteral = input.remove("templateIds") != null
                    | input.remove("template_ids") != null;
                if (removedLiteral) {
                    steps.set(templateIndex, new InterpretationPlan.Step(
                        current.id(), current.actionType(), current.toolName(), input,
                        current.dependsOn(), current.outputContract(), current.validation()));
                    changed = true;
                }
            }
        }
        for (InterpretationPlan.Step executor : executors) {
            int executorIndex = indexOfStep(steps, executor.id());
            if (executorIndex >= 0) {
                InterpretationPlan.Step current = steps.get(executorIndex);
                Map<String, Object> input = new LinkedHashMap<>(
                    current.input() == null ? Map.of() : current.input());
                boolean removedLiteral = input.remove("templateId") != null
                    | input.remove("template_id") != null
                    | input.remove("template") != null
                    | input.remove("runtimeTemplateBinding") != null;
                if (removedLiteral) {
                    steps.set(executorIndex, new InterpretationPlan.Step(
                        current.id(), current.actionType(), current.toolName(), input,
                        current.dependsOn(), current.outputContract(), current.validation()));
                    changed = true;
                }
            }
            List<InterpretationPlan.Step> configuredPredecessors = mayRepairWorkflowEdges
                ? templates
                : templates.stream()
                    .filter(template -> dependsOnTransitively(executor.id(), template.id(), steps, new LinkedHashSet<>()))
                    .toList();
            InterpretationPlan.Step template = bestProtocolPredecessor(executor, configuredPredecessors);
            if (template == null) {
                continue;
            }
            if (mayRepairWorkflowEdges) {
                changed |= addDependency(steps, executor.id(), template.id());
                changed |= addRequiredDependencyContract(
                    dependencies, template.id(), executor.id(),
                    "Template execution requires a selected template contract.");
            }
            if (!hasTemplateIdBinding(bindings, template.id(), executor.id())) {
                bindings.add(new InterpretationPlan.Binding(
                    template.id(), "$.templates[0].templateId", executor.id(),
                    "$.templateId", "jsonpath", true));
                changed = true;
            }
            if (!hasEdgeContract(edges, template.id(), executor.id(), "$.templates[0].templateId")) {
                edges.add(new InterpretationPlan.EdgeContract(
                    template.id(), executor.id(), "$.templates[0].templateId", "string", true));
                changed = true;
            }
        }
        return new TemplateDagRepairResult(steps, edges, dependencies, bindings, changed);
    }

    private boolean dependsOnTransitively(Integer stepId,
                                          Integer dependencyId,
                                          List<InterpretationPlan.Step> steps,
                                          Set<Integer> visited) {
        if (stepId == null || dependencyId == null || !visited.add(stepId)) {
            return false;
        }
        InterpretationPlan.Step step = steps.stream()
            .filter(candidate -> candidate != null && Objects.equals(candidate.id(), stepId))
            .findFirst().orElse(null);
        if (step == null || step.dependsOn() == null) {
            return false;
        }
        return step.dependsOn().contains(dependencyId)
            || step.dependsOn().stream().anyMatch(parent ->
                dependsOnTransitively(parent, dependencyId, steps, visited));
    }

    private boolean addDependency(List<InterpretationPlan.Step> steps, Integer targetId, Integer dependencyId) {
        int index = indexOfStep(steps, targetId);
        if (index < 0 || dependencyId == null) {
            return false;
        }
        InterpretationPlan.Step step = steps.get(index);
        List<Integer> values = new ArrayList<>(step.dependsOn() == null ? List.of() : step.dependsOn());
        if (values.contains(dependencyId)) {
            return false;
        }
        values.add(dependencyId);
        steps.set(index, withDependencies(step, List.copyOf(values)));
        return true;
    }

    private int indexOfStep(List<InterpretationPlan.Step> steps, Integer stepId) {
        for (int index = 0; index < steps.size(); index++) {
            if (steps.get(index) != null && Objects.equals(stepId, steps.get(index).id())) {
                return index;
            }
        }
        return -1;
    }

    private boolean addRequiredDependencyContract(List<InterpretationPlan.DependencyContract> contracts,
                                                  Integer from,
                                                  Integer to,
                                                  String reason) {
        boolean exists = contracts.stream()
            .filter(Objects::nonNull)
            .anyMatch(contract -> Objects.equals(from, contract.from())
                && Objects.equals(to, contract.to())
                && !Boolean.FALSE.equals(contract.required()));
        if (exists) {
            return false;
        }
        contracts.add(new InterpretationPlan.DependencyContract(
            from, to, true, null, reason, "replan"));
        return true;
    }

    private boolean hasTemplateIdBinding(List<InterpretationPlan.Binding> bindings,
                                         Integer from,
                                         Integer to) {
        return bindings.stream()
            .filter(Objects::nonNull)
            .anyMatch(binding -> Objects.equals(from, binding.from())
                && Objects.equals(to, binding.to())
                && normalizeField(binding.outputPath()).contains("templateid")
                && normalizeField(binding.inputField()).contains("templateid"));
    }

    private boolean hasEdgeContract(List<InterpretationPlan.EdgeContract> edges,
                                    Integer from,
                                    Integer to,
                                    String field) {
        return edges.stream()
            .filter(Objects::nonNull)
            .anyMatch(edge -> Objects.equals(from, edge.from())
                && Objects.equals(to, edge.to())
                && normalizeField(field).equals(normalizeField(edge.field())));
    }

    private InterpretationPlan.Step bestProtocolPredecessor(InterpretationPlan.Step target,
                                                            List<InterpretationPlan.Step> candidates) {
        return candidates.stream()
            .max(Comparator
                .comparingInt((InterpretationPlan.Step candidate) -> protocolAffinity(
                    target == null ? null : target.toolName(), candidate.toolName()))
                .thenComparingInt(candidate -> candidate.id() != null
                    && target != null && target.id() != null && candidate.id() < target.id() ? 1 : 0)
                .thenComparingInt(candidate -> candidate.id() == null ? Integer.MIN_VALUE : candidate.id()))
            .orElse(null);
    }

    private int protocolAffinity(String left, String right) {
        Set<String> leftTokens = protocolTokens(left);
        Set<String> rightTokens = protocolTokens(right);
        leftTokens.retainAll(rightTokens);
        return leftTokens.size();
    }

    private Set<String> protocolTokens(String toolName) {
        String semantic = semanticToolName(toolName);
        Set<String> ignored = Set.of(
            "asset", "query", "search", "template", "execute", "execution",
            "request", "script", "command", "discovery", "ops");
        return java.util.Arrays.stream(semantic.split("_"))
            .filter(token -> !token.isBlank() && !ignored.contains(token))
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    private boolean isAssetDiscoveryStep(InterpretationPlan.Step step) {
        String semantic = step == null ? "" : semanticToolName(step.toolName());
        return step != null && step.mcpToolAction()
            && (semantic.equals("asset_query") || semantic.equals("asset_discovery")
                || semantic.endsWith("_asset_query") || semantic.endsWith("_asset_search"));
    }

    private boolean isTemplateDiscoveryStep(InterpretationPlan.Step step) {
        String semantic = step == null ? "" : semanticToolName(step.toolName());
        return step != null && step.mcpToolAction()
            && (semantic.equals("template_query") || semantic.equals("template_discovery")
                || semantic.endsWith("_template_query") || semantic.endsWith("_template_search"));
    }

    private boolean isTemplateExecutionStep(InterpretationPlan.Step step) {
        String semantic = step == null ? "" : semanticToolName(step.toolName());
        return step != null && step.mcpToolAction()
            && (semantic.equals("execute") || semantic.endsWith("_execute"));
    }

    private String semanticToolName(String toolName) {
        String value = normalize(toolName);
        while (value.startsWith("mcp_")) {
            value = value.substring(4);
        }
        for (String prefix : List.of("chatchat_mcp_server_", "chatchat_", "xxx_")) {
            if (value.startsWith(prefix)) {
                value = value.substring(prefix.length());
            }
        }
        return value;
    }

    private String normalizeField(String value) {
        return value == null ? "" : value.toLowerCase().replaceAll("[^a-z0-9]", "");
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

    private InterpretationPlan.DiagnosticProfile remapDiagnosticProfileForRenumber(
        List<InterpretationPlan.Step> originalSteps,
        InterpretationPlan.DiagnosticProfile profile
    ) {
        if (profile == null || profile.checks() == null) {
            return profile;
        }
        Map<Integer, Integer> idMap = new LinkedHashMap<>();
        int next = 1;
        for (InterpretationPlan.Step step : originalSteps) {
            if (step != null && step.id() != null) {
                idMap.put(step.id(), next++);
            }
        }
        List<InterpretationPlan.DiagnosticCheck> checks = profile.checks().stream()
            .map(check -> check == null ? null : new InterpretationPlan.DiagnosticCheck(
                check.checkId(),
                check.capability(),
                check.dimension(),
                check.required(),
                check.priority(),
                (check.stepIds() == null ? List.<Integer>of() : check.stepIds()).stream()
                    .filter(idMap::containsKey)
                    .map(idMap::get)
                    .distinct()
                    .toList()
            ))
            .toList();
        return new InterpretationPlan.DiagnosticProfile(profile.profileId(), profile.targetKind(), checks);
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

    private record TemplateDagRepairResult(
        List<InterpretationPlan.Step> steps,
        List<InterpretationPlan.EdgeContract> edgeContracts,
        List<InterpretationPlan.DependencyContract> dependencyContracts,
        List<InterpretationPlan.Binding> bindings,
        boolean changed
    ) {
    }

    private record ConfiguredWorkflowNode(String toolName, List<String> dependsOnTools) {
    }

    private record ConfiguredDagRepairResult(
        List<InterpretationPlan.Step> steps,
        List<InterpretationPlan.DependencyContract> dependencyContracts,
        boolean changed
    ) {
    }

    public record OptimizationResult(
        InterpretationPlan plan,
        List<String> appliedPasses
    ) {
    }
}
