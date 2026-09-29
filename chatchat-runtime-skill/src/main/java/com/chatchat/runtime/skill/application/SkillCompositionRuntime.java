package com.chatchat.runtime.skill.application;

import com.chatchat.runtime.skill.api.execution.*;
import com.chatchat.runtime.skill.api.discovery.*;
import com.chatchat.runtime.skill.api.resolution.*;
import com.chatchat.runtime.skill.api.skill.SkillDescriptor;
import com.chatchat.runtime.skill.port.inbound.*;
import java.util.*;

/** Capability matching, bounded composition, and observable execution outcomes. */
public final class SkillCompositionRuntime {
    private final SkillRouter router;
    private final SkillResolver resolver;
    private final WorkflowResolver workflows;
    private final SkillRuntime runtime;
    public SkillCompositionRuntime(SkillRouter router, SkillResolver resolver, WorkflowResolver workflows, SkillRuntime runtime) {
        this.router = router; this.resolver = resolver; this.workflows = workflows; this.runtime = runtime;
    }

    public SkillCompositionPlan plan(SkillCompositionRequest request) {
        return plan(request, Set.of());
    }

    SkillCompositionPlan plan(SkillCompositionRequest request, Set<String> completedCapabilities) {
        Set<String> required = new LinkedHashSet<>(request.capabilities());
        Map<String, SkillDescriptor> candidates = new LinkedHashMap<>();
        Map<String, SkillCompositionPlan.Selection> selected = new LinkedHashMap<>();
        List<String> rejected = new ArrayList<>();
        Set<String> covered = new LinkedHashSet<>(completedCapabilities);
        Set<String> attempted = new HashSet<>();
        for (int round = 0; round < request.maxSkills() + 1; round++) {
            var route = router.route(new SkillSearchRequest(request.query(), request.identity(), request.skillIds(), 100,
                Map.of("requiredCapabilities", List.copyOf(required))));
            route.candidates().forEach(item -> candidates.put(item.id(), item));
            if (required.isEmpty() && !route.candidates().isEmpty())
                required.addAll(strings(route.candidates().get(0).metadata().get("capabilities")));
            Set<String> missing = new LinkedHashSet<>(required); missing.removeAll(covered);
            if (missing.isEmpty() || selected.size() >= request.maxSkills()) break;
            boolean added = false;
            List<SkillDescriptor> ranked = candidates.values().stream().filter(item -> !attempted.contains(item.id()))
                .filter(item -> strings(item.metadata().get("capabilities")).stream().anyMatch(missing::contains))
                .sorted(Comparator.<SkillDescriptor>comparingLong(item -> strings(item.metadata().get("capabilities"))
                    .stream().filter(missing::contains).count()).reversed()
                    .thenComparing(Comparator.comparingDouble(SkillDescriptor::score).reversed()).thenComparing(SkillDescriptor::id)).toList();
            for (var descriptor : ranked) {
                attempted.add(descriptor.id());
                var resolution = resolver.resolve(new SkillResolutionRequest(descriptor.id(), descriptor.version(), request.identity()));
                if (!resolution.resolved()) { rejected.add(descriptor.id() + ":RESOLUTION_DENIED"); continue; }
                var intent = new LinkedHashMap<String, Object>();
                intent.put("workflowType", "DATA_ANALYSIS");
                intent.put("allowInstructionOnly", Boolean.TRUE.equals(request.attributes().get("allowInstructionOnly")));
                if (request.workflowIds().containsKey(descriptor.id())) intent.put("workflowId", request.workflowIds().get(descriptor.id()));
                var workflow = workflows.resolve(resolution.skill(), resolution.authorizedScope(), request.identity(), intent);
                if (!workflow.resolved()) { rejected.add(descriptor.id() + ":" + workflow.status()); continue; }
                var capabilities = strings(resolution.skill().descriptor().metadata().get("capabilities"));
                if (capabilities.stream().noneMatch(missing::contains)) continue;
                var needs = strings(resolution.skill().descriptor().metadata().get("requiresCapabilities"));
                selected.put(descriptor.id(), new SkillCompositionPlan.Selection(descriptor.id(), descriptor.version(),
                    workflow.workflow().workflowId(), capabilities, needs));
                covered.addAll(capabilities); required.addAll(needs); added = true; break;
            }
            if (!added) break;
        }
        var missing = new LinkedHashSet<>(required); missing.removeAll(covered);
        List<SkillCompositionPlan.Selection> ordered = new ArrayList<>();
        var pending = new ArrayList<>(selected.values());
        Set<String> preceding = new HashSet<>(completedCapabilities);
        while (!pending.isEmpty()) {
            var ready = pending.stream().filter(item -> preceding.containsAll(item.requiresCapabilities())).findFirst();
            if (ready.isEmpty()) break;
            ordered.add(ready.get()); preceding.addAll(ready.get().capabilities()); pending.remove(ready.get());
        }
        pending.forEach(item -> missing.addAll(item.capabilities()));
        return new SkillCompositionPlan(ordered.isEmpty() ? "NO_EXECUTABLE_PLAN" : missing.isEmpty() ? "READY" : "PARTIAL",
            ordered, List.copyOf(missing), Map.of("candidateCount", candidates.size(), "rejected", rejected,
                "blockedDependencies", pending.stream().map(SkillCompositionPlan.Selection::skillId).toList(),
                "requirementSource", request.capabilities().isEmpty() ? "METADATA_RETRIEVAL" : "EXPLICIT_CAPABILITIES"));
    }

    public SkillCompositionResult execute(SkillCompositionRequest request) {
        return execute(request, new SkillDataSession());
    }

    public SkillCompositionResult execute(SkillCompositionRequest request, SkillDataSession session) {
        return execute(request, session, plan(request));
    }

    public SkillCompositionResult execute(SkillCompositionRequest request, SkillDataSession session, SkillCompositionPlan plan) {
        long start = System.nanoTime();
        Map<String, SkillExecutionResult> results = new LinkedHashMap<>();
        Map<String, String> skipped = new LinkedHashMap<>();
        Set<String> completed = new HashSet<>();
        int datasetCount = 0, availableCount = 0, stepCount = 0, completedSteps = 0;
        for (var selection : plan.selections()) {
            if (!completed.containsAll(selection.requiresCapabilities())) {
                skipped.put(selection.skillId(), "CAPABILITY_DEPENDENCY_NOT_COMPLETED"); continue;
            }
            Map<String, Object> attributes = new LinkedHashMap<>(request.attributes());
            attributes.put(SkillDataAcquisition.INPUTS, request.inputs());
            attributes.put("upstreamSkillResults", results.entrySet().stream().map(entry -> Map.of(
                "skillId", entry.getKey(), "steps", entry.getValue().diagnostics().getOrDefault(SkillAnalysisExecutor.RESULTS, List.of()))).toList());
            SkillExecutionResult result;
            try {
                result = runtime.execute(new SkillExecutionRequest(request.query(), request.identity(), List.of(selection.skillId()), 1,
                    request.engine(), Map.of("workflowId", selection.workflowId(), "workflowType", "DATA_ANALYSIS",
                        "skillVersion", selection.version(), "dataContractsRequired", true), attributes), session);
            } catch (java.util.concurrent.CancellationException cancelled) { throw cancelled;
            } catch (RuntimeException failure) {
                if (Thread.currentThread().isInterrupted()) throw failure;
                skipped.put(selection.skillId(), "SKILL_EXECUTION_FAILED"); continue;
            }
            results.put(selection.skillId(), result);
            boolean inputsPresent = true, stepsCompleted = true;
            if (result.diagnostics().get(SkillDataAcquisition.RESULTS) instanceof List<?> data) for (Object item : data) {
                if (item instanceof SkillDataResult dataset) {
                    datasetCount++;
                    boolean present = dataset.status() == SkillDataResult.Status.AVAILABLE || dataset.status() == SkillDataResult.Status.EMPTY;
                    if (present) availableCount++;
                    if (!dataset.requirement().optional() && !present) inputsPresent = false;
                }
            }
            if (result.diagnostics().get(SkillAnalysisExecutor.RESULTS) instanceof List<?> steps) for (Object item : steps) {
                if (item instanceof SkillStepResult step) {
                    stepCount++; if ("COMPLETED".equals(step.status())) completedSteps++; else stepsCompleted = false;
                }
            }
            if ("COMPLETED".equals(result.status()) && inputsPresent && stepsCompleted) completed.addAll(selection.capabilities());
        }
        boolean complete = !plan.selections().isEmpty() && plan.missingCapabilities().isEmpty() && skipped.isEmpty()
            && plan.selections().stream().allMatch(item -> completed.containsAll(item.capabilities()));
        return new SkillCompositionResult(complete ? "COMPLETED" : "COMPLETED_WITH_LIMITATIONS", plan, results, skipped,
            Map.of("selectedSkillCount", plan.selections().size(), "executedSkillCount", results.size(),
                "datasetCount", datasetCount, "availableDatasetCount", availableCount, "stepCount", stepCount,
                "completedStepCount", completedSteps, "elapsedMs", (System.nanoTime() - start) / 1_000_000));
    }
    private List<String> strings(Object value) {
        return value instanceof List<?> values ? values.stream().filter(String.class::isInstance).map(String.class::cast).toList() : List.of();
    }
}
