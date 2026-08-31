package com.chatchat.common.runtime.summary.analysis;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Source-neutral, serializable contract shared by planner, evidence coverage,
 * gap retrieval and analysis stages. It describes business capabilities rather
 * than SQL, tool names or domain-specific fields.
 */
public record AnalysisLoopContract(
    String schemaVersion,
    String primaryGoal,
    List<QuestionCoverage> questions,
    List<GapRequest> gapRequests,
    boolean coreCoverageComplete,
    String gapFingerprint
) {
    public static final String SCHEMA_VERSION = "analysis_loop_contract.v1";

    public AnalysisLoopContract {
        schemaVersion = SCHEMA_VERSION;
        primaryGoal = text(primaryGoal, "Complete the requested business analysis");
        questions = questions == null ? List.of() : List.copyOf(questions);
        gapRequests = gapRequests == null ? List.of() : List.copyOf(gapRequests);
        coreCoverageComplete = questions.stream()
            .filter(question -> question.criticality() == Criticality.CORE)
            .allMatch(question -> question.status() == CoverageStatus.SUPPORTED);
        gapFingerprint = fingerprint(gapRequests);
    }

    public static AnalysisLoopContract of(String primaryGoal,
                                          List<QuestionCoverage> questions,
                                          List<GapRequest> gapRequests) {
        return new AnalysisLoopContract(SCHEMA_VERSION, primaryGoal, questions,
            gapRequests, false, null);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schemaVersion", schemaVersion);
        result.put("primaryGoal", primaryGoal);
        result.put("questions", questions.stream().map(QuestionCoverage::toMap).toList());
        result.put("gapRequests", gapRequests.stream().map(GapRequest::toMap).toList());
        result.put("coreCoverageComplete", coreCoverageComplete);
        result.put("gapFingerprint", gapFingerprint);
        return Collections.unmodifiableMap(result);
    }

    public enum Criticality { CORE, SUPPORTING }

    public enum CoverageStatus { SUPPORTED, PARTIAL, UNSUPPORTED }

    public record QuestionCoverage(
        String questionId,
        String businessQuestion,
        Criticality criticality,
        CoverageStatus status,
        List<String> requiredCapabilities,
        List<String> evidenceReferences,
        List<String> missingEvidence
    ) {
        public QuestionCoverage {
            questionId = text(questionId, "question");
            businessQuestion = text(businessQuestion, questionId);
            criticality = criticality == null ? Criticality.CORE : criticality;
            status = status == null ? CoverageStatus.UNSUPPORTED : status;
            requiredCapabilities = copy(requiredCapabilities);
            evidenceReferences = copy(evidenceReferences);
            missingEvidence = copy(missingEvidence);
        }

        public Map<String, Object> toMap() {
            return Map.of(
                "questionId", questionId,
                "businessQuestion", businessQuestion,
                "criticality", criticality.name(),
                "status", status.name(),
                "requiredCapabilities", requiredCapabilities,
                "evidenceReferences", evidenceReferences,
                "missingEvidence", missingEvidence
            );
        }
    }

    public record GapRequest(
        String questionId,
        String retrievalGoal,
        List<String> requiredCapabilities,
        String timeHorizon,
        String grain,
        Criticality priority,
        String reason
    ) {
        public GapRequest {
            questionId = text(questionId, "question");
            retrievalGoal = text(retrievalGoal, "Retrieve evidence for the unsupported business question");
            requiredCapabilities = copy(requiredCapabilities);
            timeHorizon = text(timeHorizon, "UNSPECIFIED");
            grain = text(grain, "UNSPECIFIED");
            priority = priority == null ? Criticality.CORE : priority;
            reason = text(reason, "Required evidence is missing");
        }

        public Map<String, Object> toMap() {
            return Map.of(
                "questionId", questionId,
                "retrievalGoal", retrievalGoal,
                "requiredCapabilities", requiredCapabilities,
                "timeHorizon", timeHorizon,
                "grain", grain,
                "priority", priority.name(),
                "reason", reason
            );
        }
    }

    private static List<String> copy(List<String> values) {
        return values == null ? List.of() : values.stream()
            .filter(value -> value != null && !value.isBlank()).distinct().toList();
    }

    private static String text(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static String fingerprint(List<GapRequest> requests) {
        String canonical = (requests == null ? List.<GapRequest>of() : requests).stream()
            .map(request -> String.join("|", request.questionId(), request.retrievalGoal(),
                String.join(",", request.requiredCapabilities()), request.timeHorizon(),
                request.grain(), request.priority().name(), request.reason()))
            .sorted().reduce("", (left, right) -> left + "\n" + right);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
