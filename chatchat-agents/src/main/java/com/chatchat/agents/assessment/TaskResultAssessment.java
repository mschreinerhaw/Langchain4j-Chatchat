package com.chatchat.agents.assessment;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runtime-wide assessment of what ran, what is supported, how much of the task was
 * fulfilled, and what may safely be delivered.
 *
 * <p>The dimensions are intentionally independent. In particular, a successful tool
 * call does not imply complete evidence, and partial evidence does not automatically
 * prohibit a useful partial artifact.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TaskResultAssessment(
    String contractVersion,
    Execution execution,
    Evidence evidence,
    Fulfillment fulfillment,
    Delivery delivery
) {
    public static final String CONTRACT_VERSION = "task_result_assessment_v1";

    public TaskResultAssessment {
        contractVersion = CONTRACT_VERSION;
    }

    public enum ExecutionStatus {
        SUCCESS, PARTIAL_SUCCESS, FAILED, BLOCKED
    }

    public enum EvidenceStatus {
        COMPLETE, PARTIAL, INSUFFICIENT, NONE, CONFLICTED
    }

    public enum EvidenceAvailability {
        AVAILABLE, EMPTY, UNAVAILABLE
    }

    public enum AnalysisCapability {
        FULL, PARTIAL, NONE
    }

    public enum FulfillmentStatus {
        COMPLETE, PARTIAL, UNFULFILLED
    }

    public enum DeliveryDecision {
        FULL_ARTIFACT, PARTIAL_ARTIFACT, FACTS_ONLY, RETRY, REFUSE
    }

    public enum ClaimPolicy {
        SUPPORTED_FACTS_ONLY,
        SUPPORTED_FACTS_PLUS_LABELED_PROPOSALS
    }

    public record Execution(
        ExecutionStatus status,
        int successfulTools,
        int failedTools,
        List<String> reasons
    ) {
        public Execution {
            status = status == null ? ExecutionStatus.SUCCESS : status;
            successfulTools = Math.max(0, successfulTools);
            failedTools = Math.max(0, failedTools);
            reasons = immutable(reasons);
        }
    }

    public record Evidence(
        EvidenceStatus status,
        EvidenceAvailability availability,
        AnalysisCapability analysisCapability,
        boolean answerAllowed,
        String blockingReason,
        Double coverage,
        Double quality,
        Double freshness,
        List<String> supportedAspects,
        List<String> missingAspects,
        List<String> conflicts,
        List<String> reasons
    ) {
        public Evidence {
            status = status == null ? EvidenceStatus.NONE : status;
            availability = availability == null ? EvidenceAvailability.UNAVAILABLE : availability;
            analysisCapability = analysisCapability == null ? AnalysisCapability.NONE : analysisCapability;
            blockingReason = blockingReason == null || blockingReason.isBlank() ? null : blockingReason;
            coverage = score(coverage);
            quality = score(quality);
            freshness = score(freshness);
            supportedAspects = immutable(supportedAspects);
            missingAspects = immutable(missingAspects);
            conflicts = immutable(conflicts);
            reasons = immutable(reasons);
        }
    }

    public record Fulfillment(
        FulfillmentStatus status,
        List<String> completedRequirements,
        List<String> unmetRequirements,
        boolean retryRecommended
    ) {
        public Fulfillment {
            status = status == null ? FulfillmentStatus.UNFULFILLED : status;
            completedRequirements = immutable(completedRequirements);
            unmetRequirements = immutable(unmetRequirements);
        }
    }

    public record Delivery(
        DeliveryDecision decision,
        boolean allowed,
        boolean partial,
        ClaimPolicy claimPolicy,
        List<String> limitations
    ) {
        public Delivery {
            decision = decision == null ? DeliveryDecision.REFUSE : decision;
            claimPolicy = claimPolicy == null ? ClaimPolicy.SUPPORTED_FACTS_ONLY : claimPolicy;
            limitations = immutable(limitations);
        }
    }

    public Map<String, Object> toMap() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("contractVersion", contractVersion);
        value.put("execution", execution);
        value.put("evidence", evidence);
        value.put("fulfillment", fulfillment);
        value.put("delivery", delivery);
        return value;
    }

    private static List<String> immutable(List<String> values) {
        return values == null ? List.of() : values.stream()
            .filter(value -> value != null && !value.isBlank())
            .distinct()
            .toList();
    }

    private static Double score(Double value) {
        if (value == null || !Double.isFinite(value)) {
            return null;
        }
        return Math.max(0.0D, Math.min(1.0D, value));
    }
}
