package com.chatchat.agents.runtime.plan;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Evidence coverage and assessment snapshot for a diagnostic InterpretationPlan.
 *
 * <p>The runtime only aggregates explicit plan/check mappings and structured assessment values
 * returned by tools. It never derives a health score from template names or domain keywords.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DiagnosticRun(
    @JsonProperty("profile_id")
    String profileId,
    @JsonProperty("target_kind")
    String targetKind,
    List<CheckResult> checks,
    Coverage coverage,
    Assessment assessment
) {

    public static DiagnosticRun evaluate(InterpretationPlan plan,
                                         List<InterpretationPlanRuntime.StepExecution> executions,
                                         Set<Integer> remainingStepIds,
                                         int evidenceIteration) {
        InterpretationPlan.DiagnosticProfile profile = plan == null || plan.plan() == null
            ? null
            : plan.plan().diagnosticProfile();
        if (profile == null || profile.checks() == null || profile.checks().isEmpty()) {
            return null;
        }

        Map<Integer, InterpretationPlan.Step> plannedSteps = new LinkedHashMap<>();
        for (InterpretationPlan.Step step : plan.steps()) {
            if (step != null && step.id() != null) {
                plannedSteps.put(step.id(), step);
            }
        }
        Map<Integer, InterpretationPlanRuntime.StepExecution> executedSteps = new LinkedHashMap<>();
        for (InterpretationPlanRuntime.StepExecution execution : safeExecutions(executions)) {
            if (execution != null && execution.stepId() != null) {
                executedSteps.put(execution.stepId(), execution);
            }
        }

        boolean planBudgetSaturated = plan.executionPolicy() != null
            && plan.executionPolicy().maxSteps() != null
            && plan.steps().size() >= plan.executionPolicy().maxSteps();
        List<CheckResult> results = profile.checks().stream()
            .filter(check -> check != null && hasText(check.checkId()))
            .sorted(Comparator.comparingInt(check -> check.priority() == null ? Integer.MAX_VALUE : check.priority()))
            .map(check -> evaluateCheck(
                check,
                plannedSteps,
                executedSteps,
                remainingStepIds == null ? Set.of() : remainingStepIds,
                planBudgetSaturated,
                Math.max(1, evidenceIteration)
            ))
            .toList();

        int required = (int) results.stream().filter(CheckResult::required).count();
        int completed = (int) results.stream().filter(CheckResult::required)
            .filter(result -> "completed".equals(result.status())).count();
        int failed = (int) results.stream().filter(CheckResult::required)
            .filter(result -> "failed".equals(result.status())).count();
        int missing = Math.max(0, required - completed - failed);
        double ratio = required == 0 ? 1.0D : round((double) completed / required);
        Coverage coverage = new Coverage(required, completed, failed, missing, ratio);
        return new DiagnosticRun(
            profile.profileId(),
            profile.targetKind(),
            results,
            coverage,
            assessment(results, coverage)
        );
    }

    private static CheckResult evaluateCheck(
        InterpretationPlan.DiagnosticCheck check,
        Map<Integer, InterpretationPlan.Step> plannedSteps,
        Map<Integer, InterpretationPlanRuntime.StepExecution> executedSteps,
        Set<Integer> remainingStepIds,
        boolean planBudgetSaturated,
        int evidenceIteration
    ) {
        Set<Integer> mappedStepIds = new LinkedHashSet<>();
        if (check.stepIds() != null) {
            check.stepIds().stream().filter(plannedSteps::containsKey).forEach(mappedStepIds::add);
        }
        if (mappedStepIds.isEmpty()) {
            plannedSteps.values().stream()
                .filter(step -> stepMatchesCheck(step, check))
                .map(InterpretationPlan.Step::id)
                .forEach(mappedStepIds::add);
        }

        List<InterpretationPlanRuntime.StepExecution> evidenceSteps = mappedStepIds.stream()
            .map(executedSteps::get)
            .filter(execution -> execution != null)
            .toList();
        List<String> evidenceRefs = evidenceSteps.stream()
            .map(execution -> evidenceRef(evidenceIteration, execution))
            .toList();

        String status;
        String reason = null;
        if (evidenceSteps.stream().anyMatch(execution -> !execution.success())) {
            status = "failed";
            reason = evidenceSteps.stream()
                .filter(execution -> !execution.success())
                .map(InterpretationPlanRuntime.StepExecution::errorMessage)
                .filter(DiagnosticRun::hasText)
                .findFirst()
                .orElse("diagnostic_step_failed");
        } else if (!mappedStepIds.isEmpty() && evidenceSteps.size() == mappedStepIds.size()) {
            status = "completed";
        } else {
            status = "missing";
            boolean mappedButRemaining = mappedStepIds.stream().anyMatch(remainingStepIds::contains);
            reason = planBudgetSaturated && (mappedStepIds.isEmpty() || mappedButRemaining)
                ? "execution_budget_exhausted"
                : mappedStepIds.isEmpty() ? "no_matching_step" : "not_executed";
        }

        ExplicitAssessment explicit = explicitAssessment(check.dimension(), evidenceSteps);
        return new CheckResult(
            check.checkId(),
            check.capability(),
            normalizedDimension(check.dimension()),
            !Boolean.FALSE.equals(check.required()),
            check.priority(),
            new ArrayList<>(mappedStepIds),
            status,
            reason,
            evidenceRefs,
            explicit.score(),
            explicit.confidence()
        );
    }

    private static boolean stepMatchesCheck(InterpretationPlan.Step step,
                                            InterpretationPlan.DiagnosticCheck check) {
        if (step == null || step.input() == null) {
            return false;
        }
        String checkId = firstText(step.input(), "diagnosticCheckId", "diagnostic_check_id", "checkId", "check_id");
        if (sameToken(check.checkId(), checkId)) {
            return true;
        }
        String capability = firstText(step.input(), "diagnosticCapability", "diagnostic_capability", "capability");
        if (sameToken(check.capability(), capability)) {
            return true;
        }
        Object toolCall = firstPresent(step.input(), "toolCall", "tool_call");
        if (toolCall instanceof Map<?, ?> map) {
            return sameToken(check.capability(), firstText(map, "action", "capability"));
        }
        return false;
    }

    private static ExplicitAssessment explicitAssessment(
        String dimension,
        List<InterpretationPlanRuntime.StepExecution> evidenceSteps
    ) {
        List<Double> scores = new ArrayList<>();
        List<Double> confidences = new ArrayList<>();
        for (InterpretationPlanRuntime.StepExecution execution : evidenceSteps) {
            if (execution == null || !execution.success() || !(execution.output() instanceof Map<?, ?> output)) {
                continue;
            }
            Map<?, ?> assessment = findAssessment(output, 0);
            if (assessment.isEmpty()) {
                continue;
            }
            Object dimensionValue = firstPresent(assessment, dimension, normalizedDimension(dimension));
            Map<?, ?> dimensionAssessment = dimensionValue instanceof Map<?, ?> map ? map : assessment;
            Double score = number(firstPresent(dimensionAssessment, "score", "healthScore", "health_score"));
            Double confidence = number(firstPresent(dimensionAssessment, "confidence", "confidenceScore", "confidence_score"));
            if (score != null) {
                scores.add(clamp(score, 0.0D, 100.0D));
            }
            if (confidence != null) {
                confidences.add(clamp(confidence, 0.0D, 1.0D));
            }
        }
        return new ExplicitAssessment(average(scores), average(confidences));
    }

    private static Map<?, ?> findAssessment(Map<?, ?> values, int depth) {
        if (values == null || values.isEmpty() || depth > 5) {
            return Map.of();
        }
        Map<?, ?> direct = firstMap(values, "diagnosticAssessment", "diagnostic_assessment", "assessment");
        if (!direct.isEmpty()) {
            return direct;
        }
        for (String envelope : List.of(
            "data", "result", "payload", "operation", "structuredContent", "structured_content", "output"
        )) {
            Object nested = values.get(envelope);
            if (nested instanceof Map<?, ?> map) {
                Map<?, ?> found = findAssessment(map, depth + 1);
                if (!found.isEmpty()) {
                    return found;
                }
            }
        }
        return Map.of();
    }

    private static Assessment assessment(List<CheckResult> results, Coverage coverage) {
        Map<String, DimensionAssessment> dimensions = new LinkedHashMap<>();
        Map<String, List<CheckResult>> byDimension = new LinkedHashMap<>();
        for (CheckResult result : results) {
            byDimension.computeIfAbsent(normalizedDimension(result.dimension()), ignored -> new ArrayList<>()).add(result);
        }
        for (Map.Entry<String, List<CheckResult>> entry : byDimension.entrySet()) {
            List<CheckResult> checks = entry.getValue();
            int required = (int) checks.stream().filter(CheckResult::required).count();
            int completed = (int) checks.stream().filter(CheckResult::required)
                .filter(check -> "completed".equals(check.status())).count();
            List<Double> scores = checks.stream().map(CheckResult::score).filter(value -> value != null).toList();
            List<Double> confidences = checks.stream().map(CheckResult::confidence).filter(value -> value != null).toList();
            Double score = average(scores);
            Double confidence = average(confidences);
            String status = score == null
                ? "NOT_ASSESSED"
                : completed < required ? "PARTIAL_EVIDENCE" : "ASSESSED";
            dimensions.put(entry.getKey(), new DimensionAssessment(
                score,
                confidence,
                required,
                completed,
                status
            ));
        }
        List<Double> assessedScores = dimensions.values().stream()
            .map(DimensionAssessment::score)
            .filter(value -> value != null)
            .toList();
        List<Double> assessedConfidences = dimensions.values().stream()
            .map(DimensionAssessment::confidence)
            .filter(value -> value != null)
            .toList();
        boolean complete = coverage.missing() == 0 && coverage.failed() == 0;
        Double overallScore = complete && assessedScores.size() == dimensions.size()
            ? average(assessedScores)
            : null;
        Double assessedConfidence = average(assessedConfidences);
        Double overallConfidence = assessedConfidence == null
            ? null
            : round(assessedConfidence * coverage.ratio());
        return new Assessment(
            dimensions,
            overallScore,
            overallConfidence,
            overallScore == null ? "INSUFFICIENT_EVIDENCE" : "ASSESSED"
        );
    }

    private static String evidenceRef(int iteration, InterpretationPlanRuntime.StepExecution execution) {
        return "iteration:" + iteration
            + ":step:" + execution.stepId()
            + ":tool:" + (hasText(execution.toolName()) ? execution.toolName() : execution.actionType());
    }

    private static String normalizedDimension(String dimension) {
        return hasText(dimension) ? dimension.trim().toLowerCase(Locale.ROOT) : "general";
    }

    private static boolean sameToken(String first, String second) {
        return hasText(first) && hasText(second) && canonical(first).equals(canonical(second));
    }

    private static String canonical(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private static String firstText(Map<?, ?> values, String... keys) {
        Object value = firstPresent(values, (Object[]) keys);
        return hasText(value) ? String.valueOf(value).trim() : null;
    }

    private static Object firstPresent(Map<?, ?> values, Object... keys) {
        if (values == null || keys == null) {
            return null;
        }
        for (Object key : keys) {
            if (key != null && values.containsKey(key) && values.get(key) != null) {
                return values.get(key);
            }
        }
        return null;
    }

    private static Map<?, ?> firstMap(Map<?, ?> values, String... keys) {
        Object value = firstPresent(values, (Object[]) keys);
        return value instanceof Map<?, ?> map ? map : Map.of();
    }

    private static Double number(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (!hasText(value)) {
            return null;
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static Double average(List<Double> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        return round(values.stream().mapToDouble(Double::doubleValue).average().orElse(0.0D));
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static double round(double value) {
        return Math.round(value * 1000.0D) / 1000.0D;
    }

    private static boolean hasText(Object value) {
        return value != null && !String.valueOf(value).isBlank();
    }

    private static List<InterpretationPlanRuntime.StepExecution> safeExecutions(
        List<InterpretationPlanRuntime.StepExecution> executions
    ) {
        return executions == null ? List.of() : executions;
    }

    public record CheckResult(
        @JsonProperty("check_id")
        String checkId,
        String capability,
        String dimension,
        boolean required,
        Integer priority,
        @JsonProperty("step_ids")
        List<Integer> stepIds,
        String status,
        String reason,
        @JsonProperty("evidence_refs")
        List<String> evidenceRefs,
        Double score,
        Double confidence
    ) {
    }

    public record Coverage(
        int required,
        int completed,
        int failed,
        int missing,
        double ratio
    ) {
    }

    public record Assessment(
        Map<String, DimensionAssessment> dimensions,
        @JsonProperty("overall_score")
        Double overallScore,
        @JsonProperty("overall_confidence")
        Double overallConfidence,
        @JsonProperty("overall_status")
        String overallStatus
    ) {
    }

    public record DimensionAssessment(
        Double score,
        Double confidence,
        int required,
        int completed,
        String status
    ) {
    }

    private record ExplicitAssessment(Double score, Double confidence) {
    }
}
