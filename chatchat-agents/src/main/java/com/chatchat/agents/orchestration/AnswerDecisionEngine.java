package com.chatchat.agents.orchestration;

import com.chatchat.agents.runtime.AgentAnswerReview;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Owns the single final-answer write decision for agent runs.
 */
class AnswerDecisionEngine {

    static final String CONTRACT_VERSION = "answer_decision_v1";
    static final String NO_REWRITE = "no_rewrite";
    static final String REVIEWER_REWRITE = "reviewer_rewrite";
    static final String DETERMINISTIC_EVIDENCE_REWRITE = "deterministic_evidence_rewrite";
    static final String DOCUMENT_EVIDENCE_REWRITE = "document_evidence_rewrite";
    static final String QUALITY_SELECTED_ANSWER = "quality_selected_answer";
    static final String EMPTY_ANSWER = "empty_answer";
    static final String QUALITY_AGGREGATION_VERSION = "answer_quality_aggregation_v1";

    private static final double WEIGHT_GROUNDING = 0.30D;
    private static final double WEIGHT_ACCURACY = 0.25D;
    private static final double WEIGHT_COMPLETENESS = 0.20D;
    private static final double WEIGHT_CITATION = 0.15D;
    private static final double WEIGHT_USEFULNESS = 0.10D;
    private static final double TIE_EPSILON = 0.000001D;

    AnswerDecision decide(AnswerDecisionRequest request) {
        String candidate = request == null ? "" : blankToEmpty(request.candidateAnswer());
        AgentAnswerReview review = request == null ? null : request.review();
        EvidenceSignal evidence = request == null ? EvidenceSignal.empty() : request.evidenceSignal();
        AnswerQualityEvaluator.QualityReport quality = request == null ? null : request.qualityReport();
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("answerDecisionContractVersion", CONTRACT_VERSION);
        if (!candidate.isBlank()) {
            metadata.put("candidateAnswerPreview", shortText(candidate, 1000));
        }

        if (evidence != null && evidence.confirmationRequired()) {
            return decision(candidate, NO_REWRITE, "confirmation_required", "none", metadata);
        }

        if (evidence != null && evidence.lockedAnswer() != null) {
            DeterministicLockedAnswer locked = evidence.lockedAnswer();
            metadata.put("deterministicAnswerAvailable", true);
            metadata.put("deterministicAnswerContractVersion", evidence.executionContract());
            putIfPresent(metadata, "deterministicAnswerContractHash", locked.contractHash());
            putIfPresent(metadata, "deterministicAnswerGraphViewHash", locked.graphViewHash());
            if (evidence.shouldReplaceWithGroundedEvidence()) {
                metadata.put("deterministicAnswerFallbackApplied", true);
                return decision(
                    locked.answer(),
                    DETERMINISTIC_EVIDENCE_REWRITE,
                    "deterministic_lock_replaces_unusable_candidate",
                    "evidence_guard",
                    metadata
                );
            }
            metadata.put("deterministicAnswerUsedAsEvidence", true);
        }

        if (review != null
            && AgentAnswerReview.REVISED.equals(review.status())
            && review.answer() != null
            && !review.answer().isBlank()) {
            metadata.put("answerReviewRewriteSuggested", true);
            metadata.put("answerReviewSuggestedAnswerPreview", shortText(review.answer(), 1000));
        }

        if (quality != null) {
            attachQualityMetadata(metadata, quality);
            QualitySelection selection = selectByQuality(quality, evidence);
            attachQualityAggregationMetadata(metadata, selection);
            if (quality.available() && selection.selectedCandidate() != null) {
                AnswerQualityEvaluator.AnswerCandidate selected = selection.selectedCandidate();
                if (AnswerQualityEvaluator.CANDIDATE.equals(selected.source())) {
                    if (metadata.containsKey("answerReviewRewriteSuggested")) {
                        metadata.put("answerReviewRewriteApplied", false);
                        metadata.put("answerReviewRewriteSkippedReason", "quality_aggregation_selected_original_candidate");
                    }
                    return decision(
                        selected.answer(),
                        NO_REWRITE,
                        "deterministic_quality_aggregation_selected_candidate",
                        "none",
                        metadata
                    );
                }
                if (AnswerQualityEvaluator.REVIEWER_SUGGESTION.equals(selected.source())) {
                    metadata.put("answerReviewRewriteApplied", true);
                } else if (AnswerQualityEvaluator.DETERMINISTIC_EVIDENCE.equals(selected.source())) {
                    metadata.put("deterministicAnswerFallbackApplied", true);
                } else if (AnswerQualityEvaluator.DOCUMENT_EVIDENCE.equals(selected.source())) {
                    metadata.put("evidenceForcedAnswer", true);
                    metadata.put("evidenceForcedReason", firstNonBlank(
                        evidence == null ? null : evidence.replacementReason(),
                        candidate.isBlank() ? "empty_answer_with_document_evidence" : "no_match_fallback_with_document_evidence"
                    ));
                    metadata.put("evidenceForcedCitations", evidence == null || evidence.documentEvidence() == null
                        ? List.of()
                        : evidence.documentEvidence().stream()
                        .map(GroundedDocumentEvidence::citation)
                        .filter(value -> value != null && !value.isBlank())
                        .toList());
                }
                return decision(
                    selected.answer(),
                    QUALITY_SELECTED_ANSWER,
                    firstNonBlank(selection.reason(), "deterministic_quality_aggregation_selected_highest_score"),
                    "quality_aggregator",
                    metadata
                );
            }
        }

        if (evidence != null
            && evidence.groundedDocumentAnswer() != null
            && !evidence.groundedDocumentAnswer().isBlank()
            && evidence.shouldReplaceWithGroundedEvidence()) {
            metadata.put("evidenceForcedAnswer", true);
            metadata.put("evidenceForcedReason", firstNonBlank(
                evidence.replacementReason(),
                candidate.isBlank() ? "empty_answer_with_document_evidence" : "no_match_fallback_with_document_evidence"
            ));
            metadata.put("evidenceForcedCitations", evidence.documentEvidence().stream()
                .map(GroundedDocumentEvidence::citation)
                .filter(value -> value != null && !value.isBlank())
                .toList());
            return decision(
                evidence.groundedDocumentAnswer(),
                DOCUMENT_EVIDENCE_REWRITE,
                stringValue(metadata.get("evidenceForcedReason")),
                "evidence_guard",
                metadata
            );
        }

        if (review != null
            && AgentAnswerReview.REVISED.equals(review.status())
            && review.answer() != null
            && !review.answer().isBlank()) {
            if (reviewerRewriteAllowed(request.metadata())) {
                metadata.put("answerReviewRewriteApplied", true);
                return decision(
                    review.answer(),
                    REVIEWER_REWRITE,
                    "reviewer_rewrite_explicitly_enabled",
                    "reviewer",
                    metadata
                );
            }
            metadata.put("answerReviewRewriteApplied", false);
            metadata.put("answerReviewRewriteSkippedReason", "reviewer_rewrite_disabled");
        }

        if (!candidate.isBlank()) {
            return decision(candidate, NO_REWRITE, "candidate_answer_retained", "none", metadata);
        }

        metadata.put("emptyAnswer", true);
        metadata.put("emptyAnswerReason", review == null ? "answer_review_unavailable" : review.feedback());
        return decision("", EMPTY_ANSWER, "empty_candidate_answer", "none", metadata);
    }

    private AnswerDecision decision(String finalAnswer,
                                    String action,
                                    String reason,
                                    String rewriteSource,
                                    Map<String, Object> metadata) {
        Map<String, Object> values = new LinkedHashMap<>(metadata == null ? Map.of() : metadata);
        values.put("answerDecision", action);
        values.put("answerDecisionReason", reason);
        values.put("answerRewriteSource", rewriteSource);
        if (finalAnswer != null && !finalAnswer.isBlank()) {
            values.put("finalAnswerPreview", shortText(finalAnswer, 1000));
        }
        return new AnswerDecision(
            finalAnswer == null ? "" : finalAnswer,
            action,
            reason,
            rewriteSource,
            Map.copyOf(values)
        );
    }

    private boolean reviewerRewriteAllowed(Map<String, Object> metadata) {
        if (metadata == null) {
            return false;
        }
        Object value = metadata.get("allowReviewerRewrite");
        if (value instanceof Boolean bool) {
            return bool;
        }
        return value != null && Boolean.parseBoolean(String.valueOf(value));
    }

    private void attachQualityMetadata(Map<String, Object> metadata, AnswerQualityEvaluator.QualityReport quality) {
        metadata.put("answerQualityContractVersion", quality.contractVersion());
        metadata.put("answerQualityAvailable", quality.available());
        putIfPresent(metadata, "answerQualityLlmSelectedId", quality.llmSelectedId());
        metadata.put("answerQualityReason", quality.reason());
        metadata.put("answerQualityCandidates", quality.candidateMaps());
        metadata.put("answerQualityScores", quality.scoreMaps());
        if (quality.rawPreview() != null && !quality.rawPreview().isBlank()) {
            metadata.put("answerQualityRawPreview", quality.rawPreview());
        }
    }

    private QualitySelection selectByQuality(AnswerQualityEvaluator.QualityReport quality, EvidenceSignal evidence) {
        if (quality == null || !quality.available() || quality.candidates() == null || quality.candidates().isEmpty()) {
            return new QualitySelection(null, "quality_unavailable", List.of());
        }
        Map<String, AnswerQualityEvaluator.CandidateScore> scoresById = new LinkedHashMap<>();
        if (quality.scores() != null) {
            for (AnswerQualityEvaluator.CandidateScore score : quality.scores()) {
                if (score != null && score.id() != null) {
                    scoresById.put(score.id(), score);
                }
            }
        }
        boolean evidenceRequired = evidenceRequired(evidence);
        List<QualityCandidateDecision> decisions = new ArrayList<>();
        QualityCandidateDecision selected = null;
        for (AnswerQualityEvaluator.AnswerCandidate candidate : quality.candidates()) {
            if (candidate == null) {
                continue;
            }
            AnswerQualityEvaluator.CandidateScore score = scoresById.getOrDefault(candidate.id(), defaultScore(candidate.id()));
            List<String> filterReasons = hardFilterReasons(candidate, score, evidenceRequired);
            double aggregateScore = aggregateScore(score);
            QualityCandidateDecision decision = new QualityCandidateDecision(
                candidate,
                score,
                aggregateScore,
                filterReasons.isEmpty(),
                filterReasons
            );
            decisions.add(decision);
            if (decision.passedHardFilter() && betterThan(decision, selected)) {
                selected = decision;
            }
        }
        if (selected == null) {
            return new QualitySelection(null, "all_quality_candidates_failed_hard_filter", List.copyOf(decisions));
        }
        return new QualitySelection(
            selected.candidate(),
            "deterministic_quality_aggregation_selected_highest_score",
            List.copyOf(decisions)
        );
    }

    private void attachQualityAggregationMetadata(Map<String, Object> metadata, QualitySelection selection) {
        metadata.put("answerQualityAggregationVersion", QUALITY_AGGREGATION_VERSION);
        metadata.put("answerQualityAggregationWeights", qualityWeights());
        metadata.put("answerQualityAggregateScores", selection == null ? List.of() : selection.decisionMaps());
        if (selection != null && selection.selectedCandidate() != null) {
            AnswerQualityEvaluator.AnswerCandidate selected = selection.selectedCandidate();
            QualityCandidateDecision selectedDecision = selection.decisionFor(selected.id());
            metadata.put("answerQualitySelectedId", selected.id());
            metadata.put("answerQualitySelectedSource", selected.source());
            metadata.put("answerQualitySelectedPreview", shortText(selected.answer(), 1000));
            metadata.put("answerQualityAggregationReason", selection.reason());
            if (selectedDecision != null) {
                metadata.put("answerQualitySelectedAggregateScore", selectedDecision.aggregateScore());
            }
            metadata.put("answerDecisionTrace", answerDecisionTrace(selection));
        } else {
            metadata.put("answerQualitySelectionSkippedReason", selection == null ? "quality_unavailable" : selection.reason());
        }
    }

    private Map<String, Object> answerDecisionTrace(QualitySelection selection) {
        Map<String, Object> trace = new LinkedHashMap<>();
        trace.put("contractVersion", "answer_decision_trace_v1");
        trace.put("aggregationVersion", QUALITY_AGGREGATION_VERSION);
        trace.put("winnerId", selection.selectedCandidate() == null ? null : selection.selectedCandidate().id());
        trace.put("winnerSource", selection.selectedCandidate() == null ? null : selection.selectedCandidate().source());
        trace.put("reason", selection.reason());
        trace.put("weights", qualityWeights());
        trace.put("candidates", selection.decisionMaps());
        return trace;
    }

    private Map<String, Object> qualityWeights() {
        Map<String, Object> weights = new LinkedHashMap<>();
        weights.put("grounding", WEIGHT_GROUNDING);
        weights.put("accuracy", WEIGHT_ACCURACY);
        weights.put("completeness", WEIGHT_COMPLETENESS);
        weights.put("citation", WEIGHT_CITATION);
        weights.put("usefulness", WEIGHT_USEFULNESS);
        return weights;
    }

    private List<String> hardFilterReasons(AnswerQualityEvaluator.AnswerCandidate candidate,
                                           AnswerQualityEvaluator.CandidateScore score,
                                           boolean evidenceRequired) {
        List<String> reasons = new ArrayList<>();
        if (candidate.answer() == null || candidate.answer().isBlank()) {
            reasons.add("empty_answer");
        }
        if (score.contradictsObservation()) {
            reasons.add("contradicts_observation");
        }
        if (score.usesFailedToolEvidence()) {
            reasons.add("uses_failed_tool_evidence");
        }
        if (score.missingRequiredCitation()) {
            reasons.add("missing_required_citation");
        }
        if (score.schemaViolation()) {
            reasons.add("schema_violation");
        }
        if (score.unsafe()) {
            reasons.add("unsafe");
        }
        if (evidenceRequired && score.grounding() < 0.35D) {
            reasons.add("low_grounding_for_required_evidence");
        }
        if (evidenceRequired && score.citation() < 0.35D) {
            reasons.add("low_citation_for_required_evidence");
        }
        return List.copyOf(reasons);
    }

    private boolean betterThan(QualityCandidateDecision candidate, QualityCandidateDecision selected) {
        if (selected == null) {
            return true;
        }
        int scoreComparison = Double.compare(candidate.aggregateScore(), selected.aggregateScore());
        if (Math.abs(candidate.aggregateScore() - selected.aggregateScore()) > TIE_EPSILON) {
            return scoreComparison > 0;
        }
        int priorityComparison = Integer.compare(
            sourcePriority(candidate.candidate().source()),
            sourcePriority(selected.candidate().source())
        );
        if (priorityComparison != 0) {
            return priorityComparison > 0;
        }
        return false;
    }

    private int sourcePriority(String source) {
        if (AnswerQualityEvaluator.DETERMINISTIC_EVIDENCE.equals(source)) {
            return 40;
        }
        if (AnswerQualityEvaluator.DOCUMENT_EVIDENCE.equals(source)) {
            return 30;
        }
        if (AnswerQualityEvaluator.CANDIDATE.equals(source)) {
            return 20;
        }
        if (AnswerQualityEvaluator.REVIEWER_SUGGESTION.equals(source)) {
            return 10;
        }
        return 0;
    }

    private double aggregateScore(AnswerQualityEvaluator.CandidateScore score) {
        return bounded(
            score.grounding() * WEIGHT_GROUNDING
                + score.accuracy() * WEIGHT_ACCURACY
                + score.completeness() * WEIGHT_COMPLETENESS
                + score.citation() * WEIGHT_CITATION
                + score.usefulness() * WEIGHT_USEFULNESS
        );
    }

    private boolean evidenceRequired(EvidenceSignal evidence) {
        return evidence != null
            && (evidence.lockedAnswer() != null
            || (evidence.documentEvidence() != null && !evidence.documentEvidence().isEmpty()));
    }

    private AnswerQualityEvaluator.CandidateScore defaultScore(String id) {
        return new AnswerQualityEvaluator.CandidateScore(
            id,
            0.0D,
            0.0D,
            0.0D,
            0.0D,
            0.0D,
            0.0D,
            false,
            false,
            false,
            false,
            false,
            List.of()
        );
    }

    private double bounded(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.0D;
        }
        return Math.max(0.0D, Math.min(1.0D, value));
    }

    private void putIfPresent(Map<String, Object> metadata, String key, String value) {
        if (value != null && !value.isBlank()) {
            metadata.put(key, value);
        }
    }

    private String shortText(String value, int maxChars) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String normalized = value.replaceAll("\\s+", " ").trim();
        int limit = Math.max(80, maxChars);
        return normalized.length() <= limit ? normalized : normalized.substring(0, limit);
    }

    private String blankToEmpty(String value) {
        return value == null ? "" : value;
    }

    private String firstNonBlank(String first, String second) {
        return first == null || first.isBlank() ? second : first;
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    record AnswerDecisionRequest(
        String candidateAnswer,
        AgentAnswerReview review,
        EvidenceSignal evidenceSignal,
        AnswerQualityEvaluator.QualityReport qualityReport,
        Map<String, Object> metadata
    ) {
    }

    record AnswerDecision(
        String finalAnswer,
        String action,
        String reason,
        String rewriteSource,
        Map<String, Object> metadata
    ) {
    }

    record EvidenceSignal(
        boolean confirmationRequired,
        String executionContract,
        DeterministicLockedAnswer lockedAnswer,
        List<GroundedDocumentEvidence> documentEvidence,
        String groundedDocumentAnswer,
        boolean shouldReplaceWithGroundedEvidence,
        String replacementReason
    ) {
        static EvidenceSignal empty() {
            return new EvidenceSignal(false, null, null, List.of(), null, false, null);
        }
    }

    record DeterministicLockedAnswer(
        String answer,
        String contractHash,
        String graphViewHash
    ) {
    }

    record GroundedDocumentEvidence(
        String citation,
        String source,
        String section,
        String content
    ) {
    }

    record QualitySelection(
        AnswerQualityEvaluator.AnswerCandidate selectedCandidate,
        String reason,
        List<QualityCandidateDecision> decisions
    ) {
        List<Map<String, Object>> decisionMaps() {
            return decisions == null ? List.of() : decisions.stream().map(QualityCandidateDecision::toMap).toList();
        }

        QualityCandidateDecision decisionFor(String id) {
            if (id == null || decisions == null) {
                return null;
            }
            return decisions.stream()
                .filter(decision -> decision.candidate() != null && id.equals(decision.candidate().id()))
                .findFirst()
                .orElse(null);
        }
    }

    record QualityCandidateDecision(
        AnswerQualityEvaluator.AnswerCandidate candidate,
        AnswerQualityEvaluator.CandidateScore score,
        double aggregateScore,
        boolean passedHardFilter,
        List<String> hardFilterReasons
    ) {
        Map<String, Object> toMap() {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("id", candidate.id());
            values.put("source", candidate.source());
            values.put("aggregateScore", aggregateScore);
            values.put("passedHardFilter", passedHardFilter);
            values.put("hardFilterReasons", hardFilterReasons == null ? List.of() : hardFilterReasons);
            values.put("score", score == null ? Map.of() : score.toMap());
            return values;
        }
    }
}
