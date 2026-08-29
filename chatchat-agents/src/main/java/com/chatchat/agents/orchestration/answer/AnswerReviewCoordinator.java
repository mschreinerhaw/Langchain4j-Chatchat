package com.chatchat.agents.orchestration.answer;

import com.chatchat.agents.protocol.ModelProtocolJson;
import com.chatchat.agents.runtime.answer.AgentAnswerReview;
import com.chatchat.agents.runtime.answer.AgentAnswerReviewer;
import dev.langchain4j.model.chat.ChatModel;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Coordinates reviewer preflight, bounded model execution and safe fallback. */
@Slf4j
final class AnswerReviewCoordinator {

    private final AgentAnswerReviewer reviewer;
    private final AnswerEvidenceLedgerCompiler evidenceLedgerCompiler;
    private final long defaultTimeoutMs;

    AnswerReviewCoordinator(AgentAnswerReviewer reviewer,
                            AnswerEvidenceLedgerCompiler evidenceLedgerCompiler,
                            long defaultTimeoutMs) {
        this.reviewer = reviewer;
        this.evidenceLedgerCompiler = evidenceLedgerCompiler;
        this.defaultTimeoutMs = defaultTimeoutMs;
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
        long timeoutMs = configuredTimeoutMs("chatchat.agent.answer.review.timeout.ms", defaultTimeoutMs);
        AgentAnswerReview review;
        try {
            review = runWithTimeout(runId, timeoutMs,
                () -> reviewer.review(activeChatModel, query, systemPrompt,
                    List.copyOf(reviewObservations), finalAnswer));
        } catch (TimeoutException ex) {
            put(metadata, "answerReviewTimedOut", true);
            put(metadata, "answerReviewTimeoutMs", timeoutMs);
            put(metadata, "answerReviewFallback", "accepted_current_answer");
            log.warn("agentModelTimeout phase=review runId={} timeoutMs={} answerChars={} observationCount={}",
                nonBlank(runId), timeoutMs, finalAnswer.length(), observations == null ? 0 : observations.size());
            return accepted(finalAnswer,
                "Answer reviewer timed out after " + timeoutMs + "ms; accepted current evidence-grounded answer.");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            put(metadata, "answerReviewInterrupted", true);
            put(metadata, "answerReviewFallback", "accepted_current_answer");
            log.warn("agentModelInterrupted phase=review runId={} answerChars={}", nonBlank(runId), finalAnswer.length());
            return accepted(finalAnswer,
                "Answer reviewer was interrupted; accepted current evidence-grounded answer.");
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
        return review;
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

    private <T> T runWithTimeout(String runId, long timeoutMs, Callable<T> task) throws Exception {
        if (timeoutMs <= 0) {
            return task.call();
        }
        ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "agent-review-" + nonBlank(runId));
            thread.setDaemon(true);
            return thread;
        });
        Future<T> future = executor.submit(task);
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException | InterruptedException ex) {
            future.cancel(true);
            throw ex;
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof Exception exception) {
                throw exception;
            }
            throw new IllegalStateException(cause);
        } finally {
            executor.shutdownNow();
        }
    }

    private long configuredTimeoutMs(String property, long fallback) {
        String value = System.getProperty(property);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            long parsed = Long.parseLong(value.trim());
            return parsed < 0 ? fallback : parsed;
        } catch (NumberFormatException ignored) {
            return fallback;
        }
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
}
