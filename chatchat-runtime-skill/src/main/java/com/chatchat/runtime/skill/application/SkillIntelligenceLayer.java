package com.chatchat.runtime.skill.application;

import com.chatchat.runtime.skill.api.discovery.SkillSearchRequest;
import com.chatchat.runtime.skill.api.execution.*;
import com.chatchat.runtime.skill.api.skill.SkillDescriptor;
import com.chatchat.runtime.skill.port.inbound.*;
import com.chatchat.runtime.skill.port.outbound.SkillIntentPlanner;
import java.util.*;
import java.util.concurrent.CancellationException;

/** Engine-neutral, bounded analysis loop. All execution still passes through policy and data workflows. */
public final class SkillIntelligenceLayer {
    private final SkillRouter router;
    private final SkillIntentPlanner intentPlanner;
    private final SkillCompositionRuntime composition;
    private final SkillRuntime runtime;

    public SkillIntelligenceLayer(SkillRouter router, SkillIntentPlanner intentPlanner,
                                  SkillCompositionRuntime composition, SkillRuntime runtime) {
        this.router = router; this.intentPlanner = intentPlanner; this.composition = composition; this.runtime = runtime;
    }

    public record Plan(SkillIntentPlanner.Intent intent, SkillCompositionRequest request, SkillCompositionPlan composition) {}
    public record Event(String stage, int round, String skillId, String status) {}
    public record Result(String status, String stopReason, SkillIntentPlanner.Intent intent,
                         Map<String, SkillExecutionResult> results, List<String> missingCapabilities,
                         List<Event> events, long elapsedMs) {
        public Result { results = Collections.unmodifiableMap(new LinkedHashMap<>(results));
            missingCapabilities = List.copyOf(missingCapabilities); events = List.copyOf(events); }
    }

    public Plan plan(SkillCompositionRequest request) {
        var candidates = router.route(new SkillSearchRequest(request.query(), request.identity(),
            request.skillIds(), 40, Map.of("requiredCapabilities", request.capabilities()))).candidates();
        var intent = candidates.isEmpty()
            ? new SkillIntentPlanner.Intent("", request.query(), List.of(), "NO_AUTHORIZED_CANDIDATES")
            : intentPlanner.understand(request, candidates);
        // Explicit requested objectives are never silently dropped by a planner.
        var required = new LinkedHashSet<>(request.capabilities()); required.addAll(intent.capabilities());
        if (required.size() > 32 || intent.tasks().size() > 8)
            throw new IllegalArgumentException("Intent exceeds analysis limits");
        var planned = copy(request, List.copyOf(required), request.skillIds(), request.attributes());
        var executionPlan = required.isEmpty()
            ? new SkillCompositionPlan("NO_EXECUTABLE_PLAN", List.of(), List.of(), Map.of("reason", "NO_ANALYSIS_INTENT"))
            : composition.plan(planned);
        return new Plan(intent, planned, executionPlan);
    }

    public Result execute(SkillCompositionRequest request) {
        return execute(request, event -> {});
    }

    public Result execute(SkillCompositionRequest request, java.util.function.Consumer<Event> observer) {
        long started = System.nanoTime();
        long deadline = started + 120_000_000_000L;
        Plan prepared = plan(request);
        var events = new ArrayList<Event>() {
            @Override public boolean add(Event event) { observer.accept(event); return super.add(event); }
        };
        events.add(new Event("INTENT_UNDERSTOOD", 0, "", prepared.intent().mode()));
        events.add(new Event("PLAN_CREATED", 0, "", prepared.composition().status()));
        var results = new LinkedHashMap<String, SkillExecutionResult>();
        var completed = new LinkedHashSet<String>();
        var attempted = new LinkedHashSet<String>();
        var required = new LinkedHashSet<>(prepared.request().capabilities());
        var session = new SkillDataSession();
        var current = prepared.composition();
        String stop = "NO_EXECUTABLE_PLAN";
        for (int round = 1; round <= 3 && !current.selections().isEmpty(); round++) {
            int before = attempted.size();
            for (var selection : current.selections()) {
                if (Thread.currentThread().isInterrupted()) throw new CancellationException("Analysis cancelled");
                if (System.nanoTime() >= deadline) { stop = "TIME_BUDGET"; break; }
                if (attempted.size() >= request.maxSkills()) { stop = "SKILL_BUDGET"; break; }
                if (attempted.contains(selection.skillId()) || !completed.containsAll(selection.requiresCapabilities())) continue;
                attempted.add(selection.skillId());
                var attributes = new LinkedHashMap<>(request.attributes());
                attributes.put(SkillDataAcquisition.INPUTS, request.inputs());
                attributes.put("timeoutMs", Math.max(1L, Math.min(60_000L, (deadline - System.nanoTime()) / 1_000_000)));
                attributes.put("upstreamSkillResults", results.entrySet().stream().map(entry -> Map.of(
                    "skillId", entry.getKey(), "status", entry.getValue().status(),
                    "analysis", boundedOutput(entry.getValue()),
                    "steps", entry.getValue().diagnostics().getOrDefault(SkillAnalysisExecutor.RESULTS, List.of()))).toList());
                events.add(new Event("SKILL_EXECUTING", round, selection.skillId(), "RUNNING"));
                SkillExecutionResult result;
                try {
                    result = runtime.execute(new SkillExecutionRequest(request.query(), request.identity(),
                        List.of(selection.skillId()), 1, request.engine(), Map.of("workflowId", selection.workflowId(),
                        "workflowType", "DATA_ANALYSIS", "skillVersion", selection.version(), "allowInstructionOnly", true,
                        "dataContractsRequired", !"builtin:skill-instructions".equals(selection.workflowId())), attributes), session);
                } catch (CancellationException cancelled) { throw cancelled;
                } catch (RuntimeException failure) {
                    if (Thread.currentThread().isInterrupted()) throw new CancellationException("Analysis cancelled");
                    events.add(new Event("SKILL_EVALUATED", round, selection.skillId(), "EXECUTION_FAILED"));
                    continue;
                }
                results.put(selection.skillId(), result);
                boolean usable = usable(result);
                events.add(new Event("SKILL_EVALUATED", round, selection.skillId(), usable ? "SUPPORTED" : "LIMITED"));
                if (usable) completed.addAll(selection.capabilities());
                if (missingInput(result)) { stop = "USER_INPUT_REQUIRED"; break; }
            }
            var missing = new LinkedHashSet<>(required); missing.removeAll(completed);
            if (!required.isEmpty() && missing.isEmpty()) { stop = "OBJECTIVES_COVERED"; break; }
            if (Set.of("TIME_BUDGET", "SKILL_BUDGET", "USER_INPUT_REQUIRED").contains(stop)) break;
            if (attempted.size() == before) { stop = "NO_PROGRESS"; break; }
            if (round == 3) { stop = "ROUND_BUDGET"; break; }
            var remaining = router.route(new SkillSearchRequest(request.query(), request.identity(), request.skillIds(),
                50, Map.of("requiredCapabilities", List.copyOf(missing)))).candidates().stream()
                .map(SkillDescriptor::id).filter(id -> !attempted.contains(id)).toList();
            if (remaining.isEmpty()) { stop = "NO_SUPPLEMENTARY_SKILL"; break; }
            current = composition.plan(copy(request, List.copyOf(missing), remaining, request.attributes()), completed);
            stop = "NO_EXECUTABLE_PLAN";
            events.add(new Event("SUPPLEMENT_PLANNED", round, "", current.status()));
        }
        var missing = new LinkedHashSet<>(required); missing.removeAll(completed);
        String status = "OBJECTIVES_COVERED".equals(stop) ? "COMPLETED" : "COMPLETED_WITH_LIMITATIONS";
        events.add(new Event("ANALYSIS_FINISHED", 0, "", stop));
        return new Result(status, stop, prepared.intent(), results, List.copyOf(missing), events,
            (System.nanoTime() - started) / 1_000_000);
    }

    private boolean usable(SkillExecutionResult result) {
        if (!"COMPLETED".equals(result.status()) || result.execution() == null || result.execution().output().isBlank()) return false;
        if (result.workflow() != null && result.workflow().resolved()
            && Boolean.TRUE.equals(result.workflow().workflow().configuration().get("instructionOnly"))) return true;
        if (!(result.diagnostics().get(SkillDataAcquisition.RESULTS) instanceof List<?> data) || data.isEmpty()) return false;
        for (Object item : data) if (item instanceof SkillDataResult row && !row.requirement().optional()
            && (row.status() != SkillDataResult.Status.AVAILABLE || row.provenance().isEmpty())) return false;
        if (result.diagnostics().get(SkillAnalysisExecutor.RESULTS) instanceof List<?> steps)
            for (Object item : steps) if (item instanceof SkillStepResult step && !"COMPLETED".equals(step.status())) return false;
        return true;
    }
    private String boundedOutput(SkillExecutionResult result) {
        String text = result.execution() == null ? "" : result.execution().output();
        return text == null ? "" : text.substring(0, Math.min(8000, text.length()));
    }
    private boolean missingInput(SkillExecutionResult result) {
        return result.diagnostics().get(SkillDataAcquisition.RESULTS) instanceof List<?> data
            && data.stream().anyMatch(item -> item instanceof SkillDataResult row && !row.requirement().optional()
                && row.status() == SkillDataResult.Status.MISSING_INPUT);
    }
    private SkillCompositionRequest copy(SkillCompositionRequest request, List<String> capabilities,
                                         List<String> ids, Map<String, Object> attributes) {
        var controlled = new LinkedHashMap<>(attributes); controlled.put("allowInstructionOnly", true);
        return new SkillCompositionRequest(request.query(), request.identity(), capabilities, ids, request.workflowIds(),
            request.inputs(), request.engine(), controlled, request.maxSkills());
    }
}
