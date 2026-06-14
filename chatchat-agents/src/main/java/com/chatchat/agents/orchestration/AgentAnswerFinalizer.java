package com.chatchat.agents.orchestration;

import com.chatchat.agents.runtime.AgentAnswerReview;
import com.chatchat.agents.runtime.AgentAnswerReviewer;
import com.chatchat.common.interaction.InteractionToolTrace;
import dev.langchain4j.model.chat.ChatModel;
import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * Builds final agent answers and preserves the legacy execution result contract.
 */
@Slf4j
class AgentAnswerFinalizer {

    private final AgentAnswerReviewer answerReviewer;
    private final AgentRuntimeGuard runtimeGuard;

    AgentAnswerFinalizer(AgentAnswerReviewer answerReviewer, AgentRuntimeGuard runtimeGuard) {
        this.answerReviewer = answerReviewer;
        this.runtimeGuard = runtimeGuard;
    }

    AgentOrchestrator.AgentExecutionResult finishExecution(String answer,
                                                           List<InteractionToolTrace> traces,
                                                           Map<String, Object> metadata,
                                                           List<String> observations) {
        Map<String, Object> values = metadata == null ? new LinkedHashMap<>() : metadata;
        values.put("runtimeContractVersion", "agent_runtime_v1");
        values.put("observations", observations == null ? List.of() : List.copyOf(observations));
        values.put("toolTraceCount", traces == null ? 0 : traces.size());
        return new AgentOrchestrator.AgentExecutionResult(
            answer,
            traces == null ? List.of() : List.copyOf(traces),
            values
        );
    }

    boolean markToolBudgetExceeded(String requestedToolName,
                                   int maxToolCalls,
                                   List<InteractionToolTrace> traces,
                                   Map<String, Object> metadata,
                                   List<String> observations) {
        if (maxToolCalls == Integer.MAX_VALUE || traces == null || traces.size() < maxToolCalls) {
            return false;
        }
        metadata.put("stopReason", "tool_budget_exceeded");
        metadata.put("toolBudgetExceeded", true);
        metadata.put("maxToolCalls", maxToolCalls);
        metadata.put("requestedToolAfterBudget", requestedToolName);
        observations.add("Agent run stopped before executing " + requestedToolName
            + " because the max tool calls budget was reached.");
        return true;
    }

    AgentOrchestrator.AgentExecutionResult finishBudgetedSummary(ChatModel activeChatModel,
                                                                 String query,
                                                                 String systemPrompt,
                                                                 List<InteractionToolTrace> traces,
                                                                 Map<String, Object> metadata,
                                                                 List<String> observations,
                                                                 BooleanSupplier cancellationCheck) {
        runtimeGuard.checkCancelled(cancellationCheck);
        String finalAnswer = summarizeWithObservations(activeChatModel, query, systemPrompt, observations, metadata);
        runtimeGuard.checkCancelled(cancellationCheck);
        AgentAnswerReview review = reviewAnswer(activeChatModel, query, systemPrompt, observations, finalAnswer, metadata);
        runtimeGuard.checkCancelled(cancellationCheck);
        recordAnswerReview(metadata, review);
        metadata.put("stopReason", "tool_budget_exceeded");
        return finishExecution(reviewedAnswer(review, metadata), traces, metadata, observations);
    }

    AgentOrchestrator.AgentExecutionResult finishReviewedSummary(ChatModel activeChatModel,
                                                                 String query,
                                                                 String systemPrompt,
                                                                 List<InteractionToolTrace> traces,
                                                                 Map<String, Object> metadata,
                                                                 List<String> observations,
                                                                 BooleanSupplier cancellationCheck,
                                                                 String stopReason) {
        runtimeGuard.checkCancelled(cancellationCheck);
        String finalAnswer = summarizeWithObservations(activeChatModel, query, systemPrompt, observations, metadata);
        runtimeGuard.checkCancelled(cancellationCheck);
        AgentAnswerReview review = reviewAnswer(activeChatModel, query, systemPrompt, observations, finalAnswer, metadata);
        runtimeGuard.checkCancelled(cancellationCheck);
        recordAnswerReview(metadata, review);
        metadata.put("stopReason", stopReason);
        return finishExecution(reviewedAnswer(review, metadata), traces, metadata, observations);
    }

    AgentOrchestrator.AgentExecutionResult finishReviewedAnswer(ChatModel activeChatModel,
                                                                String query,
                                                                String systemPrompt,
                                                                List<InteractionToolTrace> traces,
                                                                Map<String, Object> metadata,
                                                                List<String> observations,
                                                                String answer,
                                                                BooleanSupplier cancellationCheck,
                                                                String stopReason) {
        String finalAnswer = safeAnswer(activeChatModel, answer, query, observations, systemPrompt, metadata);
        runtimeGuard.checkCancelled(cancellationCheck);
        AgentAnswerReview review = reviewAnswer(activeChatModel, query, systemPrompt, observations, finalAnswer, metadata);
        runtimeGuard.checkCancelled(cancellationCheck);
        recordAnswerReview(metadata, review);
        metadata.put("stopReason", stopReason);
        return finishExecution(reviewedAnswer(review, metadata), traces, metadata, observations);
    }

    private String safeAnswer(ChatModel activeChatModel,
                              String answer,
                              String query,
                              List<String> observations,
                              String systemPrompt,
                              Map<String, Object> metadata) {
        if (answer != null && !answer.isBlank()) {
            return answer;
        }
        return summarizeWithObservations(activeChatModel, query, systemPrompt, observations, metadata);
    }

    private String summarizeWithObservations(ChatModel activeChatModel,
                                             String query,
                                             String systemPrompt,
                                             List<String> observations,
                                             Map<String, Object> metadata) {
        StringBuilder prompt = new StringBuilder();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            prompt.append("System instruction: ").append(systemPrompt).append("\n\n");
        }
        prompt.append("Use the observations below and answer the user question in Chinese.\n");
        prompt.append("If any tool observation reports failure, explicitly state that this source was unavailable and do not treat it as evidence.\n");
        prompt.append("When both internal document and web search observations are available, separate internal document evidence from web verification evidence and explain conflicts instead of merging them silently.\n");
        prompt.append("If observations include web citation labels such as [缃戦〉1], append the matching label immediately after every sentence that relies on that web source.\n");
        prompt.append("Do not invent citations or cite URLs that are not listed in the observations.\n");
        prompt.append("If an Evidence trust policy asks for more evidence, avoid strong claims and say that trusted evidence is insufficient.\n");
        if (observations == null || observations.isEmpty()) {
            prompt.append("No external tool observation is available.\n");
        } else {
            observations.forEach(ob -> prompt.append("- ").append(ob).append("\n"));
        }
        prompt.append("\nUser question: ").append(query);
        String promptText = prompt.toString();
        String runId = stringValue(metadata == null ? null : metadata.get("agentRunId"));
        long startedAt = System.currentTimeMillis();
        log.info("agentModelRequest phase=summary runId={} modelClass={} promptChars={} observationCount={}",
            firstNonBlank(runId, ""),
            activeChatModel == null ? null : activeChatModel.getClass().getName(),
            promptText.length(),
            observations == null ? 0 : observations.size());
        String answer = activeChatModel.chat(promptText);
        log.info("agentModelResponse phase=summary runId={} durationMs={} responseChars={}",
            firstNonBlank(runId, ""),
            System.currentTimeMillis() - startedAt,
            answer == null ? 0 : answer.length());
        return answer;
    }

    private AgentAnswerReview reviewAnswer(ChatModel activeChatModel,
                                           String query,
                                           String systemPrompt,
                                           List<String> observations,
                                           String finalAnswer,
                                           Map<String, Object> metadata) {
        String runId = stringValue(metadata == null ? null : metadata.get("agentRunId"));
        if (finalAnswer == null || finalAnswer.isBlank() || activeChatModel == null) {
            log.info("agentModelSkipped phase=review runId={} reason={} answerChars={} observationCount={}",
                firstNonBlank(runId, ""),
                activeChatModel == null ? "chat_model_unavailable" : "empty_answer",
                finalAnswer == null ? 0 : finalAnswer.length(),
                observations == null ? 0 : observations.size());
            return answerReviewer.review(activeChatModel, query, systemPrompt, observations, finalAnswer);
        }
        long startedAt = System.currentTimeMillis();
        log.info("agentModelRequest phase=review runId={} modelClass={} answerChars={} observationCount={}",
            firstNonBlank(runId, ""),
            activeChatModel == null ? null : activeChatModel.getClass().getName(),
            finalAnswer == null ? 0 : finalAnswer.length(),
            observations == null ? 0 : observations.size());
        AgentAnswerReview review = answerReviewer.review(activeChatModel, query, systemPrompt, observations, finalAnswer);
        log.info("agentModelResponse phase=review runId={} durationMs={} status={} answerChars={}",
            firstNonBlank(runId, ""),
            System.currentTimeMillis() - startedAt,
            review == null ? null : review.status(),
            review == null || review.answer() == null ? 0 : review.answer().length());
        if (review != null && AgentAnswerReview.REJECTED.equals(review.status())) {
            log.warn("agentModelReviewRejected runId={} feedback={}",
                firstNonBlank(runId, ""),
                review.feedback());
        }
        return review;
    }

    private void recordAnswerReview(Map<String, Object> metadata, AgentAnswerReview review) {
        if (metadata == null || review == null) {
            return;
        }
        metadata.put("answerReviewStatus", review.status());
        if (review.feedback() != null && !review.feedback().isBlank()) {
            metadata.put("answerReviewFeedback", review.feedback());
        }
    }

    private String reviewedAnswer(AgentAnswerReview review, Map<String, Object> metadata) {
        String answer = review == null ? "" : review.answer();
        if (answer != null && !answer.isBlank()) {
            return answer;
        }
        if (metadata != null) {
            metadata.put("emptyAnswer", true);
            metadata.put("emptyAnswerReason", review == null ? "answer_review_unavailable" : review.feedback());
        }
        return "";
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String firstNonBlank(String first, String second) {
        return first == null || first.isBlank() ? second : first;
    }
}
