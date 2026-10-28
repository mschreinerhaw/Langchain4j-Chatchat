package com.chatchat.agents.orchestration.answer;

import com.chatchat.agents.protocol.ModelProtocolJson;
import com.chatchat.agents.runtime.answer.AgentAnswerReview;
import com.chatchat.agents.runtime.answer.AgentAnswerReviewer;
import dev.langchain4j.model.chat.ChatModel;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Coordinates reviewer preflight, bounded model execution and safe fallback. */
@Slf4j
final class AnswerReviewCoordinator {

    private final AgentAnswerReviewer reviewer;
    private final AnswerEvidenceLedgerCompiler evidenceLedgerCompiler;

    AnswerReviewCoordinator(AgentAnswerReviewer reviewer,
                            AnswerEvidenceLedgerCompiler evidenceLedgerCompiler) {
        this.reviewer = reviewer;
        this.evidenceLedgerCompiler = evidenceLedgerCompiler;
    }

    AgentAnswerReview review(ChatModel activeChatModel,
                             String query,
                             String systemPrompt,
                             List<String> observations,
                             String finalAnswer,
                             Map<String, Object> metadata) {
        String runId = stringValue(metadata == null ? null : metadata.get("agentRunId"));
        List<String> reviewObservations = new ArrayList<>(
            modelAnalysisReviewObservations(observations, metadata));
        AnswerEvidenceLedgerCompiler.Result preflight = evidenceLedgerCompiler.compile(
            finalAnswer, metadata, reviewObservations, List.of());
        if ("FAIL".equals(preflight.status()) || "PARTIAL".equals(preflight.status())) {
            reviewObservations.add(preflight.reviewerContext());
        }
        if (metadata != null) {
            metadata.put("answerEvidencePreflight", preflight.claimLedger());
            metadata.put("answerEvidencePreflightStatus", preflight.status());
            metadata.put("answerEvidencePreflightCoverage", preflight.coverage());
        }
        if (finalAnswer == null || finalAnswer.isBlank() || activeChatModel == null) {
            log.info("agentModelSkipped phase=review runId={} reason={} answerChars={} observationCount={}",
                nonBlank(runId), activeChatModel == null ? "chat_model_unavailable" : "empty_answer",
                finalAnswer == null ? 0 : finalAnswer.length(), reviewObservations.size());
            return reviewer.review(activeChatModel, query, systemPrompt, reviewObservations, finalAnswer);
        }
        long startedAt = System.currentTimeMillis();
        log.info("agentModelRequest phase=review runId={} modelClass={} answerChars={} observationCount={}",
            nonBlank(runId), activeChatModel.getClass().getName(), finalAnswer.length(), reviewObservations.size());
        AgentAnswerReview review;
        try {
            review = reviewer.review(activeChatModel, query, systemPrompt,
                List.copyOf(reviewObservations), finalAnswer);
        } catch (Exception ex) {
            put(metadata, "answerReviewFailed", true);
            put(metadata, "answerReviewError", ex.getMessage());
            put(metadata, "answerReviewFallback", "accepted_current_answer");
            log.warn("agentModelFailed phase=review runId={} error={}", nonBlank(runId), ex.getMessage());
            return accepted(finalAnswer,
                "Answer reviewer failed; accepted current evidence-grounded answer.");
        }
        log.info("agentModelResponse phase=review runId={} durationMs={} status={} answerChars={}",
            nonBlank(runId), System.currentTimeMillis() - startedAt,
            review == null ? null : review.status(),
            review == null || review.answer() == null ? 0 : review.answer().length());
        log.info("agentModelOutput phase=review runId={} status={} answer=\n{}",
            nonBlank(runId), review == null ? null : review.status(),
            ModelProtocolJson.prettyJsonForLog(review == null ? null : review.answer()));
        if (review != null && AgentAnswerReview.REJECTED.equals(review.status())) {
            log.warn("agentModelReviewRejected runId={} feedback={}", nonBlank(runId), review.feedback());
        }
        return validateRevisedReport(review, finalAnswer, preflight, reviewObservations, metadata, runId);
    }

    private AgentAnswerReview validateRevisedReport(AgentAnswerReview review,
                                                    String originalAnswer,
                                                    AnswerEvidenceLedgerCompiler.Result originalPreflight,
                                                    List<String> observations,
                                                    Map<String, Object> metadata,
                                                    String runId) {
        if (review == null || !AgentAnswerReview.REVISED.equals(review.status())
            || review.answer() == null || review.answer().isBlank()
            || metadata == null || metadata.get("analysisReportContract") == null) {
            return review;
        }
        String revised = review.answer();
        AnswerEvidenceLedgerCompiler.Result revisedPreflight = evidenceLedgerCompiler.compile(
            revised, metadata, observations, List.of());
        double retentionRatio = originalAnswer == null || originalAnswer.isBlank()
            ? 1D : (double) revised.length() / originalAnswer.length();
        boolean destructiveRewrite = retentionRatio < 0.75D;
        boolean evidenceRegressed = revisedPreflight.unknownReferences() > originalPreflight.unknownReferences()
            || revisedPreflight.criticalUnboundClaims() > originalPreflight.criticalUnboundClaims()
            || statusRank(revisedPreflight.status()) < statusRank(originalPreflight.status())
            || revisedPreflight.coverage() + 0.000001D < originalPreflight.coverage();
        metadata.put("answerReviewRevisedEvidencePreflight", revisedPreflight.claimLedger());
        metadata.put("answerReviewRevisedEvidencePreflightStatus", revisedPreflight.status());
        metadata.put("answerReviewRevisedEvidencePreflightCoverage", revisedPreflight.coverage());
        metadata.put("answerReviewContentRetentionRatio", Math.round(retentionRatio * 10_000D) / 10_000D);
        if (!destructiveRewrite && !evidenceRegressed) {
            return review;
        }
        metadata.put("answerReviewFallback", "accepted_current_answer");
        metadata.put("answerReviewRewriteRejected", true);
        metadata.put("answerReviewRewriteRejectedReason",
            destructiveRewrite ? "destructive_report_shortening" : "evidence_grounding_regression");
        log.warn("agentModelReviewRewriteRejected runId={} reason={} originalChars={} revisedChars={} "
                + "originalEvidenceStatus={} revisedEvidenceStatus={}",
            nonBlank(runId), metadata.get("answerReviewRewriteRejectedReason"),
            originalAnswer == null ? 0 : originalAnswer.length(), revised.length(),
            originalPreflight.status(), revisedPreflight.status());
        return accepted(originalAnswer,
            "Reviewer rewrite was advisory only because it shortened the governed report or regressed evidence grounding. "
                + firstNonBlank(review.feedback(), ""));
    }

    private int statusRank(String status) {
        if ("PASS".equals(status) || "NOT_APPLICABLE".equals(status)) return 3;
        if ("PARTIAL".equals(status)) return 2;
        if ("FAIL".equals(status)) return 1;
        return 0;
    }

    private List<String> modelAnalysisReviewObservations(List<String> observations,
                                                         Map<String, Object> metadata) {
        List<String> values = new ArrayList<>(observations == null ? List.of() : observations);
        if (metadata == null) {
            return List.copyOf(values);
        }
        Object context = metadata.remove("modelAnalysisReviewContext");
        if (context == null || String.valueOf(context).isBlank()) {
            return List.copyOf(values);
        }
        String evidence = String.valueOf(context);
        values.add("model_analysis_repair_v1 complete executed evidence:\n" + evidence);
        metadata.put("modelAnalysisReviewContextApplied", true);
        metadata.put("modelAnalysisReviewEvidenceChars", evidence.length());
        return List.copyOf(values);
    }

    private AgentAnswerReview accepted(String answer, String feedback) {
        return new AgentAnswerReview(AgentAnswerReview.ACCEPTED, answer == null ? "" : answer, feedback);
    }

    private void put(Map<String, Object> metadata, String key, Object value) {
        if (metadata != null) {
            metadata.put(key, value);
        }
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String nonBlank(String value) {
        return value == null ? "" : value;
    }

    private String firstNonBlank(String first, String second) {
        return first == null || first.isBlank() ? second : first;
    }
}
