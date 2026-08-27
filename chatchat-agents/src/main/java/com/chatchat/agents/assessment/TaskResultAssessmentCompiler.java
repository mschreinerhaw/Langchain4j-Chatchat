package com.chatchat.agents.assessment;

import com.chatchat.agents.evidence.answer.AnswerAssemblyMode;
import com.chatchat.agents.evidence.answer.AnswerAssemblyPolicy;
import com.chatchat.agents.runtime.plan.DiagnosticRun;
import com.chatchat.agents.runtime.plan.DiagnosticRunStateMachine;
import com.chatchat.common.interaction.InteractionToolTrace;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Deterministically compiles the common assessment protocol from existing Runtime
 * metadata. It contains no domain field lists, task keywords, or prompt heuristics.
 */
public final class TaskResultAssessmentCompiler {

    public static final String METADATA_KEY = "taskResultAssessment";
    public static final String POLICY_KEY = "taskResultPolicy";
    private final McpResultEvidencePolicy mcpResultPolicy = new McpResultEvidencePolicy();

    public TaskResultAssessment compile(Map<String, Object> metadata,
                                        List<InteractionToolTrace> traces,
                                        AnswerAssemblyPolicy assemblyPolicy) {
        Map<String, Object> values = metadata == null ? Map.of() : metadata;
        List<InteractionToolTrace> safeTraces = traces == null ? List.of() : traces;
        Policy policy = Policy.from(values.get(POLICY_KEY));
        McpResultEvidencePolicy.Assessment mcpAssessment =
            mcpResultPolicy.assess(safeTraces);
        boolean mcpResultAvailable = mcpAssessment.resultAvailable()
            || truthy(values.get("mcpResultAnswerAllowed"));

        int successfulTools = (int) safeTraces.stream().filter(trace -> trace != null && trace.isSuccess()).count();
        int failedTools = (int) safeTraces.stream().filter(trace -> trace != null && !trace.isSuccess()).count();
        List<String> executionReasons = new ArrayList<>();
        TaskResultAssessment.ExecutionStatus executionStatus =
            executionStatus(values, successfulTools, failedTools, executionReasons);

        EvidenceFacts facts = evidenceFacts(values, assemblyPolicy, successfulTools);
        TaskResultAssessment.EvidenceStatus evidenceStatus =
            evidenceStatus(facts, assemblyPolicy, mcpResultAvailable);
        boolean usableEvidence = evidenceStatus == TaskResultAssessment.EvidenceStatus.COMPLETE
            || evidenceStatus == TaskResultAssessment.EvidenceStatus.PARTIAL
            || evidenceStatus == TaskResultAssessment.EvidenceStatus.CONFLICTED;
        boolean complete = executionStatus == TaskResultAssessment.ExecutionStatus.SUCCESS
            && evidenceStatus != TaskResultAssessment.EvidenceStatus.PARTIAL
            && evidenceStatus != TaskResultAssessment.EvidenceStatus.INSUFFICIENT
            && evidenceStatus != TaskResultAssessment.EvidenceStatus.CONFLICTED
            && facts.missing().isEmpty();

        TaskResultAssessment.FulfillmentStatus fulfillmentStatus;
        if (complete) {
            fulfillmentStatus = TaskResultAssessment.FulfillmentStatus.COMPLETE;
        } else if (usableEvidence || successfulTools > 0 || !facts.supported().isEmpty()) {
            fulfillmentStatus = TaskResultAssessment.FulfillmentStatus.PARTIAL;
        } else {
            fulfillmentStatus = TaskResultAssessment.FulfillmentStatus.UNFULFILLED;
        }

        boolean strictRefusal = assemblyPolicy != null
            && assemblyPolicy.mode() == AnswerAssemblyMode.REFUSE
            && !mcpResultAvailable;
        boolean retryRecommended = (policy.retryOnPartial || facts.evidenceRequired())
            && fulfillmentStatus != TaskResultAssessment.FulfillmentStatus.COMPLETE;
        TaskResultAssessment.DeliveryDecision deliveryDecision;
        if (strictRefusal) {
            deliveryDecision = TaskResultAssessment.DeliveryDecision.REFUSE;
        } else if (fulfillmentStatus == TaskResultAssessment.FulfillmentStatus.COMPLETE) {
            deliveryDecision = TaskResultAssessment.DeliveryDecision.FULL_ARTIFACT;
        } else if (mcpResultAvailable) {
            deliveryDecision = policy.strictEvidenceOnly
                ? TaskResultAssessment.DeliveryDecision.FACTS_ONLY
                : TaskResultAssessment.DeliveryDecision.PARTIAL_ARTIFACT;
        } else if (usableEvidence && policy.partialDeliveryAllowed) {
            deliveryDecision = policy.strictEvidenceOnly
                ? TaskResultAssessment.DeliveryDecision.FACTS_ONLY
                : TaskResultAssessment.DeliveryDecision.PARTIAL_ARTIFACT;
        } else if (retryRecommended || executionStatus == TaskResultAssessment.ExecutionStatus.BLOCKED) {
            deliveryDecision = TaskResultAssessment.DeliveryDecision.RETRY;
        } else {
            deliveryDecision = TaskResultAssessment.DeliveryDecision.REFUSE;
        }
        TaskResultAssessment.ClaimPolicy claimPolicy =
            deliveryDecision == TaskResultAssessment.DeliveryDecision.PARTIAL_ARTIFACT
                && policy.proposalContentAllowed
                ? TaskResultAssessment.ClaimPolicy.SUPPORTED_FACTS_PLUS_LABELED_PROPOSALS
                : TaskResultAssessment.ClaimPolicy.SUPPORTED_FACTS_ONLY;
        List<String> limitations = new ArrayList<>(facts.missing());
        limitations.addAll(facts.conflicts());
        TaskResultAssessment.EvidenceAvailability availability =
            evidenceAvailability(mcpAssessment, mcpResultAvailable, evidenceStatus);
        TaskResultAssessment.AnalysisCapability analysisCapability =
            evidenceStatus == TaskResultAssessment.EvidenceStatus.COMPLETE
                ? TaskResultAssessment.AnalysisCapability.FULL
                : mcpResultAvailable || usableEvidence
                    ? TaskResultAssessment.AnalysisCapability.PARTIAL
                    : TaskResultAssessment.AnalysisCapability.NONE;
        boolean answerAllowed = mcpResultAvailable
            || deliveryDecision == TaskResultAssessment.DeliveryDecision.FULL_ARTIFACT
            || deliveryDecision == TaskResultAssessment.DeliveryDecision.PARTIAL_ARTIFACT
            || deliveryDecision == TaskResultAssessment.DeliveryDecision.FACTS_ONLY;
        String blockingReason = answerAllowed
            ? null
            : deliveryDecision == TaskResultAssessment.DeliveryDecision.RETRY
                ? "no_available_query_result_retry_required"
                : "no_available_evidence";

        return new TaskResultAssessment(
            TaskResultAssessment.CONTRACT_VERSION,
            new TaskResultAssessment.Execution(
                executionStatus, successfulTools, failedTools, executionReasons),
            new TaskResultAssessment.Evidence(
                evidenceStatus, availability, analysisCapability, answerAllowed, blockingReason,
                facts.coverage(), facts.quality(), facts.freshness(),
                facts.supported(), facts.missing(), facts.conflicts(), facts.reasons()),
            new TaskResultAssessment.Fulfillment(
                fulfillmentStatus, facts.supported(), facts.missing(), retryRecommended),
            new TaskResultAssessment.Delivery(
                deliveryDecision,
                answerAllowed,
                deliveryDecision == TaskResultAssessment.DeliveryDecision.PARTIAL_ARTIFACT
                    || deliveryDecision == TaskResultAssessment.DeliveryDecision.FACTS_ONLY,
                claimPolicy,
                limitations)
        );
    }

    public String promptInstructions(TaskResultAssessment assessment) {
        if (assessment == null || assessment.delivery() == null) {
            return "";
        }
        return switch (assessment.delivery().decision()) {
            case FULL_ARTIFACT ->
                "Task result policy: deliver the complete evidence-supported artifact.";
            case PARTIAL_ARTIFACT -> """
                Task result policy: deliver a useful partial artifact before listing limitations.
                A successful non-empty MCP query result MUST be analyzed; incomplete fields or metrics cannot justify refusal.
                Clearly separate evidence-backed facts from additions labeled “设计建议（非企业标准证据）”.
                Missing evidence reduces completeness; by itself it is not a reason to replace the deliverable with “无法生成”.
                Never present a design suggestion as a retrieved enterprise standard or verified fact.""";
            case FACTS_ONLY -> """
                Task result policy: deliver only the supported facts or supported partial artifact.
                List missing aspects separately and do not add proposals that are not grounded in evidence.""";
            case RETRY ->
                "Task result policy: explain the current usable result and the concrete retry needed; do not claim completion.";
            case REFUSE ->
                "Task result policy: the evidence contract prohibits an artifact. State the evidence boundary and do not bypass it.";
        };
    }

    private TaskResultAssessment.ExecutionStatus executionStatus(Map<String, Object> metadata,
                                                                 int successful,
                                                                 int failed,
                                                                 List<String> reasons) {
        if (truthy(metadata.get("confirmationRequired"))) {
            reasons.add("confirmation_required");
            return TaskResultAssessment.ExecutionStatus.BLOCKED;
        }
        String stopReason = text(metadata.get("stopReason"));
        if (containsAny(stopReason, "failed", "error", "fatal")) {
            reasons.add(stopReason);
            return successful > 0
                ? TaskResultAssessment.ExecutionStatus.PARTIAL_SUCCESS
                : TaskResultAssessment.ExecutionStatus.FAILED;
        }
        if (failed > 0) {
            reasons.add("one_or_more_tools_failed");
            return successful > 0
                ? TaskResultAssessment.ExecutionStatus.PARTIAL_SUCCESS
                : TaskResultAssessment.ExecutionStatus.FAILED;
        }
        return TaskResultAssessment.ExecutionStatus.SUCCESS;
    }

    private EvidenceFacts evidenceFacts(Map<String, Object> metadata,
                                        AnswerAssemblyPolicy assemblyPolicy,
                                        int successfulTools) {
        Set<String> supported = new LinkedHashSet<>();
        Set<String> missing = new LinkedHashSet<>();
        Set<String> conflicts = new LinkedHashSet<>();
        Set<String> reasons = new LinkedHashSet<>();
        Double coverage = null;
        Double quality = null;
        Double freshness = null;
        boolean explicitlySufficient = truthy(metadata.get("interpretationPlanEvidenceSufficient"));
        Object latest = latest(metadata.get("interpretationPlanEvidenceHistory"));
        if (latest != null) {
            explicitlySufficient |= truthy(property(latest, "sufficient"));
            addStrings(supported, property(latest, "evidenceUsed"));
            addStrings(missing, property(latest, "missingEvidence"));
            addStrings(missing, property(latest, "remainingMissing"));
            addStrings(conflicts, property(latest, "conflicts"));
            quality = number(property(latest, "confidence"));
            collectEvaluations(latest, supported, missing, reasons);
        }
        addStrings(missing, metadata.get("interpretationPlanRemainingMissing"));

        Object diagnostic = first(metadata.get("diagnosticRun"), property(latest, "diagnosticRun"));
        if (diagnostic instanceof DiagnosticRun run) {
            coverage = run.coverage() == null ? null : run.coverage().ratio();
            if (run.outcome() == DiagnosticRunStateMachine.Outcome.SUCCESS) {
                explicitlySufficient = true;
            }
            if (run.confidenceEngine() != null) {
                addStrings(missing, run.confidenceEngine().missingEvidence().stream()
                    .map(DiagnosticRun.MissingEvidence::checkId).toList());
            }
        } else if (diagnostic != null) {
            coverage = number(first(property(diagnostic, "evidenceCoverage"),
                property(property(diagnostic, "coverage"), "ratio")));
            addStrings(missing, property(diagnostic, "missingEvidence"));
        }

        Object diagnosticQuality = metadata.get("diagnosticEvidenceQuality");
        if (diagnosticQuality != null) {
            coverage = firstNumber(coverage, number(property(diagnosticQuality, "coverage")));
            quality = firstNumber(quality, dimensionValue(property(diagnosticQuality, "quality")));
            freshness = dimensionValue(property(diagnosticQuality, "freshness"));
            addStrings(missing, property(diagnosticQuality, "missingMetrics"));
            addStrings(missing, property(diagnosticQuality, "missingContext"));
            addStrings(reasons, property(diagnosticQuality, "reasons"));
        }
        if (assemblyPolicy != null) {
            addStrings(missing, assemblyPolicy.missingInfo());
        }
        boolean assessed = latest != null || diagnostic != null || diagnosticQuality != null
            || assemblyPolicy != null || !missing.isEmpty() || !supported.isEmpty();
        boolean evidenceRequired = evidenceRequired(metadata.get("taskContract"));
        return new EvidenceFacts(
            coverage, quality, freshness, List.copyOf(supported), List.copyOf(missing),
            List.copyOf(conflicts), List.copyOf(reasons), explicitlySufficient, assessed,
            evidenceRequired, successfulTools);
    }

    private TaskResultAssessment.EvidenceStatus evidenceStatus(
        EvidenceFacts facts,
        AnswerAssemblyPolicy policy,
        boolean mcpResultAvailable
    ) {
        if (mcpResultAvailable && !facts.conflicts().isEmpty()) {
            return TaskResultAssessment.EvidenceStatus.CONFLICTED;
        }
        if (mcpResultAvailable && (policy == null
            || policy.mode() == AnswerAssemblyMode.REFUSE
            || !facts.explicitlySufficient()
            || !facts.missing().isEmpty())) {
            return TaskResultAssessment.EvidenceStatus.PARTIAL;
        }
        if (policy != null && policy.mode() == AnswerAssemblyMode.REFUSE) {
            return TaskResultAssessment.EvidenceStatus.INSUFFICIENT;
        }
        if (!facts.conflicts().isEmpty()) {
            return TaskResultAssessment.EvidenceStatus.CONFLICTED;
        }
        if (facts.explicitlySufficient() && facts.missing().isEmpty()) {
            return TaskResultAssessment.EvidenceStatus.COMPLETE;
        }
        if (!facts.missing().isEmpty() && (!facts.supported().isEmpty() || facts.successfulTools() > 0)) {
            return TaskResultAssessment.EvidenceStatus.PARTIAL;
        }
        if (!facts.missing().isEmpty()) {
            return TaskResultAssessment.EvidenceStatus.INSUFFICIENT;
        }
        if (facts.evidenceRequired() && !facts.assessed() && facts.successfulTools() == 0) {
            return TaskResultAssessment.EvidenceStatus.INSUFFICIENT;
        }
        if (facts.assessed() || facts.successfulTools() > 0) {
            return facts.explicitlySufficient()
                ? TaskResultAssessment.EvidenceStatus.COMPLETE
                : TaskResultAssessment.EvidenceStatus.PARTIAL;
        }
        return TaskResultAssessment.EvidenceStatus.NONE;
    }

    private TaskResultAssessment.EvidenceAvailability evidenceAvailability(
        McpResultEvidencePolicy.Assessment assessment,
        boolean mcpResultAvailable,
        TaskResultAssessment.EvidenceStatus evidenceStatus
    ) {
        if (mcpResultAvailable
            || evidenceStatus == TaskResultAssessment.EvidenceStatus.COMPLETE
            || evidenceStatus == TaskResultAssessment.EvidenceStatus.PARTIAL
            || evidenceStatus == TaskResultAssessment.EvidenceStatus.CONFLICTED) {
            return TaskResultAssessment.EvidenceAvailability.AVAILABLE;
        }
        if (assessment.availability() == McpResultEvidencePolicy.Availability.EMPTY) {
            return TaskResultAssessment.EvidenceAvailability.EMPTY;
        }
        return TaskResultAssessment.EvidenceAvailability.UNAVAILABLE;
    }

    private void collectEvaluations(Object value,
                                    Set<String> supported,
                                    Set<String> missing,
                                    Set<String> reasons) {
        if (value instanceof Map<?, ?> map) {
            addStrings(supported, map.get("supportedAspects"));
            addStrings(missing, map.get("missingAspects"));
            String reason = text(map.get("reason"));
            if (reason != null && map.containsKey("answerability")) {
                reasons.add(reason);
            }
            map.values().forEach(child -> collectEvaluations(child, supported, missing, reasons));
        } else if (value instanceof Iterable<?> iterable) {
            iterable.forEach(child -> collectEvaluations(child, supported, missing, reasons));
        }
    }

    private static Object latest(Object value) {
        if (!(value instanceof List<?> list) || list.isEmpty()) {
            return null;
        }
        return list.get(list.size() - 1);
    }

    private static Object property(Object value, String name) {
        if (value instanceof Map<?, ?> map) {
            return map.get(name);
        }
        if (value == null) {
            return null;
        }
        try {
            Method method = value.getClass().getMethod(name);
            return method.invoke(value);
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    private static Object first(Object first, Object second) {
        return first == null ? second : first;
    }

    private static Double dimensionValue(Object value) {
        return number(property(value, "value"));
    }

    private static Double firstNumber(Double first, Double second) {
        return first == null ? second : first;
    }

    private static Double number(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return value == null ? null : Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static void addStrings(Set<String> target, Object value) {
        if (value instanceof Iterable<?> iterable) {
            iterable.forEach(item -> addStrings(target, item));
            return;
        }
        if (value instanceof Map<?, ?> map) {
            Object description = firstPresent(map,
                "aspect", "field", "metric", "checkId", "capability",
                "description", "basis", "reason", "evidence_id", "evidenceId");
            if (description != null) {
                addStrings(target, description);
            }
            return;
        }
        if (value instanceof Boolean) {
            return;
        }
        String text = text(value);
        if (text != null) {
            target.add(text);
        }
    }

    private static Object firstPresent(Map<?, ?> map, String... keys) {
        for (String key : keys) {
            Object value = map.get(key);
            if (value != null && text(value) != null) {
                return value;
            }
        }
        return null;
    }

    private static String text(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() || "null".equalsIgnoreCase(text) ? null : text;
    }

    private static boolean truthy(Object value) {
        return Boolean.TRUE.equals(value) || "true".equalsIgnoreCase(String.valueOf(value));
    }

    private static boolean containsAny(String value, String... tokens) {
        if (value == null) {
            return false;
        }
        String normalized = value.toLowerCase(Locale.ROOT);
        for (String token : tokens) {
            if (normalized.contains(token)) {
                return true;
            }
        }
        return false;
    }

    private static boolean evidenceRequired(Object contract) {
        Object requirement = property(contract, "evidenceRequirement");
        if (requirement == null) {
            requirement = property(contract, "evidence_requirement");
        }
        String value = text(requirement);
        return "REQUIRED".equalsIgnoreCase(value) || "STRICT".equalsIgnoreCase(value);
    }

    private record EvidenceFacts(
        Double coverage,
        Double quality,
        Double freshness,
        List<String> supported,
        List<String> missing,
        List<String> conflicts,
        List<String> reasons,
        boolean explicitlySufficient,
        boolean assessed,
        boolean evidenceRequired,
        int successfulTools
    ) {
    }

    private record Policy(
        boolean partialDeliveryAllowed,
        boolean proposalContentAllowed,
        boolean strictEvidenceOnly,
        boolean retryOnPartial
    ) {
        private static Policy from(Object value) {
            if (!(value instanceof Map<?, ?> map)) {
                return new Policy(true, true, false, false);
            }
            return new Policy(
                booleanValue(map.get("partialDeliveryAllowed"), true),
                booleanValue(map.get("proposalContentAllowed"), true),
                booleanValue(map.get("strictEvidenceOnly"), false),
                booleanValue(map.get("retryOnPartial"), false)
            );
        }

        private static boolean booleanValue(Object value, boolean fallback) {
            return value == null ? fallback : truthy(value);
        }
    }
}
