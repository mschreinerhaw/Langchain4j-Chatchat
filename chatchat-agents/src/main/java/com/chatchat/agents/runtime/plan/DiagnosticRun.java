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
 * returned by tools. A step shared by multiple checks requires check-specific execution evidence;
 * step success alone cannot inflate coverage. It never derives a health score from template names
 * or domain keywords.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DiagnosticRun(
    @JsonProperty("profile_id")
    String profileId,
    @JsonProperty("target_kind")
    String targetKind,
    List<CheckResult> checks,
    Coverage coverage,
    Assessment assessment,
    @JsonProperty("confidence_engine")
    ConfidenceEngine confidenceEngine,
    DiagnosticRunStateMachine.State state,
    DiagnosticRunStateMachine.Outcome outcome,
    @JsonProperty("failure_code")
    DiagnosticRunStateMachine.FailureCode failureCode,
    @JsonProperty("recovery_action")
    DiagnosticRunStateMachine.RecoveryAction recoveryAction
) {
    public DiagnosticRun(String profileId,
                         String targetKind,
                         List<CheckResult> checks,
                         Coverage coverage,
                         Assessment assessment) {
        this(profileId, targetKind, checks, coverage, assessment, null, null, null, null, null);
    }

    public DiagnosticRun(String profileId,
                         String targetKind,
                         List<CheckResult> checks,
                         Coverage coverage,
                         Assessment assessment,
                         ConfidenceEngine confidenceEngine) {
        this(profileId, targetKind, checks, coverage, assessment, confidenceEngine,
            null, null, null, null);
    }

    public static DiagnosticRun evaluate(InterpretationPlan plan,
                                         List<InterpretationPlanRuntime.StepExecution> executions,
                                         Set<Integer> remainingStepIds,
                                         int evidenceIteration) {
        return evaluate(plan, executions, remainingStepIds, evidenceIteration, null, false);
    }

    public static DiagnosticRun evaluate(InterpretationPlan plan,
                                         List<InterpretationPlanRuntime.StepExecution> executions,
                                         Set<Integer> remainingStepIds,
                                         int evidenceIteration,
                                         String runtimeStatus,
                                         boolean runtimeSuccess) {
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
        Map<Integer, Integer> mappedCheckCounts = mappedCheckCounts(profile.checks(), plannedSteps);
        List<CheckResult> results = profile.checks().stream()
            .filter(check -> check != null && hasText(check.checkId()))
            .sorted(Comparator.comparingInt(check -> check.priority() == null ? Integer.MAX_VALUE : check.priority()))
            .map(check -> evaluateCheck(
                check,
                plannedSteps,
                executedSteps,
                remainingStepIds == null ? Set.of() : remainingStepIds,
                planBudgetSaturated,
                Math.max(1, evidenceIteration),
                mappedCheckCounts
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
        ConfidenceEngine confidenceEngine = confidenceEngine(
            profile, results, coverage, Math.max(1, evidenceIteration));
        boolean hasRemainingSteps = remainingStepIds != null && !remainingStepIds.isEmpty();
        DiagnosticRunStateMachine.Snapshot snapshot = runtimeStatus == null
            ? DiagnosticRunStateMachine.resolveEvidenceOnly(
                completed, failed, missing, hasRemainingSteps, confidenceEngine.remainingRetries())
            : DiagnosticRunStateMachine.resolve(
                runtimeStatus, runtimeSuccess, completed, failed, missing,
                hasRemainingSteps, confidenceEngine.remainingRetries());
        return new DiagnosticRun(
            profile.profileId(),
            profile.targetKind(),
            results,
            coverage,
            assessment(results, coverage),
            confidenceEngine,
            snapshot.state(),
            snapshot.outcome(),
            snapshot.failureCode(),
            snapshot.recoveryAction()
        );
    }

    private static CheckResult evaluateCheck(
        InterpretationPlan.DiagnosticCheck check,
        Map<Integer, InterpretationPlan.Step> plannedSteps,
        Map<Integer, InterpretationPlanRuntime.StepExecution> executedSteps,
        Set<Integer> remainingStepIds,
        boolean planBudgetSaturated,
        int evidenceIteration,
        Map<Integer, Integer> mappedCheckCounts
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

        List<InterpretationPlanRuntime.StepExecution> executedMappedSteps = mappedStepIds.stream()
            .map(executedSteps::get)
            .filter(execution -> execution != null)
            .toList();
        List<InterpretationPlanRuntime.StepExecution> evidenceSteps = executedMappedSteps.stream()
            .filter(execution -> mappedCheckCounts.getOrDefault(execution.stepId(), 0) <= 1
                || checkEvidenceState(execution, check) != CheckEvidenceState.UNKNOWN)
            .toList();
        List<String> evidenceRefs = evidenceSteps.stream()
            .map(execution -> evidenceRef(evidenceIteration, execution))
            .toList();

        String status;
        String reason = null;
        if (executedMappedSteps.stream().anyMatch(execution -> !execution.success())
            || evidenceSteps.stream().anyMatch(execution -> checkEvidenceState(execution, check) == CheckEvidenceState.FAILED)) {
            status = "failed";
            reason = executedMappedSteps.stream()
                .filter(execution -> !execution.success())
                .map(InterpretationPlanRuntime.StepExecution::errorMessage)
                .filter(DiagnosticRun::hasText)
                .findFirst()
                .orElse("diagnostic_check_failed");
        } else if (!mappedStepIds.isEmpty()
            && evidenceSteps.size() == mappedStepIds.size()
            && evidenceSteps.stream().allMatch(execution ->
                mappedCheckCounts.getOrDefault(execution.stepId(), 0) <= 1
                    || checkEvidenceState(execution, check) == CheckEvidenceState.SUCCESS)) {
            status = "completed";
        } else {
            status = "missing";
            boolean mappedButRemaining = mappedStepIds.stream().anyMatch(remainingStepIds::contains);
            reason = planBudgetSaturated && (mappedStepIds.isEmpty() || mappedButRemaining)
                ? "execution_budget_exhausted"
                : mappedStepIds.isEmpty() ? "no_matching_step"
                : executedMappedSteps.size() == mappedStepIds.size()
                    ? evidenceSteps.stream().anyMatch(execution ->
                        checkEvidenceState(execution, check) == CheckEvidenceState.NOT_EXECUTED)
                        ? "not_executed"
                        : "no_check_specific_evidence"
                    : "not_executed";
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
            explicit.confidence(),
            validWeight(check.weight())
        );
    }

    private static ConfidenceEngine confidenceEngine(
        InterpretationPlan.DiagnosticProfile profile,
        List<CheckResult> results,
        Coverage coverage,
        int evidenceIteration
    ) {
        InterpretationPlan.DiagnosticCompletionPolicy policy = profile.completionPolicy();
        int retryBudget = policy == null || policy.retryBudget() == null
            ? 2 : Math.max(0, policy.retryBudget());
        int maxAttempts = policy == null || policy.maxAttempts() == null
            ? retryBudget + 1 : Math.max(1, policy.maxAttempts());
        double highThreshold = policy == null || policy.highConfidenceThreshold() == null
            ? 0.8D : clamp(policy.highConfidenceThreshold(), 0.0D, 1.0D);
        double partialThreshold = policy == null || policy.partialEvidenceThreshold() == null
            ? 0.6D : clamp(policy.partialEvidenceThreshold(), 0.0D, highThreshold);

        List<CheckResult> requiredChecks = results.stream().filter(CheckResult::required).toList();
        double totalWeight = requiredChecks.stream().mapToDouble(CheckResult::weight).sum();
        double completedWeight = requiredChecks.stream()
            .filter(check -> "completed".equals(check.status()))
            .mapToDouble(CheckResult::weight)
            .sum();
        double weightedCoverage = totalWeight <= 0.0D ? 1.0D : round(completedWeight / totalWeight);
        String evidenceLevel = weightedCoverage >= 1.0D
            ? "FULL_EVIDENCE"
            : weightedCoverage >= highThreshold
                ? "HIGH_CONFIDENCE"
                : weightedCoverage >= partialThreshold
                    ? "PARTIAL_EVIDENCE"
                    : "INSUFFICIENT";
        boolean partialConclusionAllowed = weightedCoverage >= partialThreshold;
        int attemptsUsed = Math.min(maxAttempts, Math.max(1, evidenceIteration));
        int remainingRetries = Math.max(0, Math.min(retryBudget, maxAttempts - attemptsUsed));
        double averageWeight = requiredChecks.isEmpty() ? 0.0D : totalWeight / requiredChecks.size();
        List<MissingEvidence> missingEvidence = requiredChecks.stream()
            .filter(check -> !"completed".equals(check.status()))
            .map(check -> new MissingEvidence(
                check.checkId(),
                check.capability(),
                check.reason(),
                check.weight(),
                check.weight() >= averageWeight ? "HIGH" : "NORMAL",
                remainingRetries > 0
            ))
            .toList();
        String completionStatus = missingEvidence.isEmpty()
            ? "COMPLETE"
            : remainingRetries > 0
                ? "RETRY_MISSING_EVIDENCE"
                : partialConclusionAllowed ? "PARTIAL_FINAL" : "INSUFFICIENT_FINAL";
        return new ConfidenceEngine(
            coverage.ratio(),
            weightedCoverage,
            evidenceLevel,
            partialConclusionAllowed,
            "evidence_based",
            attemptsUsed,
            remainingRetries,
            completionStatus,
            missingEvidence
        );
    }

    private static double validWeight(Double weight) {
        return weight == null || !Double.isFinite(weight) || weight <= 0.0D ? 1.0D : weight;
    }

    private static Map<Integer, Integer> mappedCheckCounts(
        List<InterpretationPlan.DiagnosticCheck> checks,
        Map<Integer, InterpretationPlan.Step> plannedSteps
    ) {
        Map<Integer, Integer> counts = new LinkedHashMap<>();
        for (InterpretationPlan.DiagnosticCheck check : checks == null ? List.<InterpretationPlan.DiagnosticCheck>of() : checks) {
            if (check == null) {
                continue;
            }
            Set<Integer> ids = new LinkedHashSet<>();
            if (check.stepIds() != null) {
                check.stepIds().stream().filter(plannedSteps::containsKey).forEach(ids::add);
            }
            if (ids.isEmpty()) {
                plannedSteps.values().stream()
                    .filter(step -> stepMatchesCheck(step, check))
                    .map(InterpretationPlan.Step::id)
                    .forEach(ids::add);
            }
            ids.forEach(id -> counts.merge(id, 1, Integer::sum));
        }
        return counts;
    }

    private static CheckEvidenceState checkEvidenceState(
        InterpretationPlanRuntime.StepExecution execution,
        InterpretationPlan.DiagnosticCheck check
    ) {
        if (execution == null || !execution.success()) {
            return CheckEvidenceState.FAILED;
        }
        if (matchesCheckIdentity(execution.metadata() == null ? null : execution.metadata().get("resolvedInput"), check)
            || matchesCheckIdentity(execution.output(), check)) {
            return CheckEvidenceState.SUCCESS;
        }
        Object results = property(execution.output(), "results");
        if (results instanceof Iterable<?> children) {
            for (Object child : children) {
                if (!matchesCheckIdentity(child, check)) {
                    continue;
                }
                String childStatus = textProperty(child, "status");
                String evidenceUsable = textProperty(child, "evidenceUsable");
                if ("NOT_EXECUTED".equalsIgnoreCase(childStatus)
                    || "SKIPPED".equalsIgnoreCase(childStatus)
                    || DiagnosticRunStateMachine.FailureCode.TIME_BUDGET_EXHAUSTED.wireValue()
                        .equalsIgnoreCase(childStatus)
                    || "RESULT_MISSING".equalsIgnoreCase(childStatus)
                    || "false".equalsIgnoreCase(evidenceUsable)) {
                    return CheckEvidenceState.NOT_EXECUTED;
                }
                return childStatus == null
                    || "SUCCESS".equalsIgnoreCase(childStatus)
                    || "COMPLETED".equalsIgnoreCase(childStatus)
                    ? CheckEvidenceState.SUCCESS
                    : CheckEvidenceState.FAILED;
            }
        }
        return CheckEvidenceState.UNKNOWN;
    }

    private static boolean matchesCheckIdentity(Object value, InterpretationPlan.DiagnosticCheck check) {
        if (value == null || check == null) {
            return false;
        }
        String identity = firstObjectText(value,
            "diagnosticCheckId", "diagnostic_check_id", "checkId", "check_id", "callId", "call_id");
        if (sameToken(check.checkId(), identity)) {
            return true;
        }
        String capability = firstObjectText(value,
            "diagnosticCapability", "diagnostic_capability", "capability");
        if (sameToken(check.capability(), capability)) {
            return true;
        }
        String template = firstObjectText(value,
            "templateCode", "template_code", "templateId", "template_id", "template");
        return semanticTemplateMatch(template, check.checkId())
            || semanticTemplateMatch(template, check.capability());
    }

    private static boolean semanticTemplateMatch(String template, String semanticCheck) {
        String templateToken = canonical(template);
        String checkToken = canonical(semanticCheck);
        return templateToken.length() >= 6
            && checkToken.length() >= 6
            && (templateToken.endsWith(checkToken) || checkToken.endsWith(templateToken));
    }

    private static String firstObjectText(Object value, String... keys) {
        for (String key : keys) {
            String text = textProperty(value, key);
            if (hasText(text)) {
                return text;
            }
        }
        return null;
    }

    private static String textProperty(Object value, String key) {
        Object property = property(value, key);
        return property == null ? null : String.valueOf(property).trim();
    }

    private static Object property(Object value, String key) {
        if (value instanceof Map<?, ?> map) {
            return map.get(key);
        }
        if (value == null || key == null || key.isBlank()) {
            return null;
        }
        try {
            return value.getClass().getMethod(key).invoke(value);
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
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
        Double confidence,
        double weight
    ) {
        public CheckResult(String checkId,
                           String capability,
                           String dimension,
                           boolean required,
                           Integer priority,
                           List<Integer> stepIds,
                           String status,
                           String reason,
                           List<String> evidenceRefs,
                           Double score,
                           Double confidence) {
            this(checkId, capability, dimension, required, priority, stepIds, status, reason,
                evidenceRefs, score, confidence, 1.0D);
        }

        @JsonProperty("execution_state")
        public String executionState() {
            if ("completed".equals(status)) {
                return "SUCCESS";
            }
            if ("failed".equals(status)) {
                String normalizedReason = reason == null ? "" : reason.toLowerCase(Locale.ROOT);
                return normalizedReason.contains("blocked") ? "BLOCKED" : "FAILED";
            }
            if ("no_matching_step".equals(reason)) {
                return "PLANNED";
            }
            return "NOT_EXECUTED";
        }
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

    public record ConfidenceEngine(
        @JsonProperty("evidence_coverage")
        double evidenceCoverage,
        @JsonProperty("weighted_coverage")
        double weightedCoverage,
        @JsonProperty("evidence_level")
        String evidenceLevel,
        @JsonProperty("partial_conclusion_allowed")
        boolean partialConclusionAllowed,
        @JsonProperty("reasoning_mode")
        String reasoningMode,
        @JsonProperty("attempts_used")
        int attemptsUsed,
        @JsonProperty("remaining_retries")
        int remainingRetries,
        @JsonProperty("completion_status")
        String completionStatus,
        @JsonProperty("missing_evidence")
        List<MissingEvidence> missingEvidence
    ) {
    }

    public record MissingEvidence(
        @JsonProperty("check_id")
        String checkId,
        String capability,
        String reason,
        double weight,
        String priority,
        @JsonProperty("retry_eligible")
        boolean retryEligible
    ) {
    }

    private record ExplicitAssessment(Double score, Double confidence) {
    }

    private enum CheckEvidenceState {
        SUCCESS,
        FAILED,
        NOT_EXECUTED,
        UNKNOWN
    }
}
