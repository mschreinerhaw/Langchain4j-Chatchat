package com.chatchat.agents.orchestration;

import com.chatchat.agents.runtime.AgentAnswerReview;
import com.chatchat.agents.runtime.AgentAnswerReviewer;
import com.chatchat.common.interaction.InteractionToolTrace;
import dev.langchain4j.model.chat.ChatModel;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * Builds final agent answers and preserves the legacy execution result contract.
 */
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
        String finalAnswer = summarizeWithObservations(activeChatModel, query, systemPrompt, observations);
        runtimeGuard.checkCancelled(cancellationCheck);
        AgentAnswerReview review = answerReviewer.review(activeChatModel, query, systemPrompt, observations, finalAnswer);
        runtimeGuard.checkCancelled(cancellationCheck);
        recordAnswerReview(metadata, review);
        metadata.put("stopReason", "tool_budget_exceeded");
        return finishExecution(review.answer(), traces, metadata, observations);
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
        String finalAnswer = summarizeWithObservations(activeChatModel, query, systemPrompt, observations);
        runtimeGuard.checkCancelled(cancellationCheck);
        AgentAnswerReview review = answerReviewer.review(activeChatModel, query, systemPrompt, observations, finalAnswer);
        runtimeGuard.checkCancelled(cancellationCheck);
        recordAnswerReview(metadata, review);
        metadata.put("stopReason", stopReason);
        return finishExecution(review.answer(), traces, metadata, observations);
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
        String finalAnswer = safeAnswer(activeChatModel, answer, query, observations, systemPrompt);
        runtimeGuard.checkCancelled(cancellationCheck);
        AgentAnswerReview review = answerReviewer.review(activeChatModel, query, systemPrompt, observations, finalAnswer);
        runtimeGuard.checkCancelled(cancellationCheck);
        recordAnswerReview(metadata, review);
        metadata.put("stopReason", stopReason);
        return finishExecution(review.answer(), traces, metadata, observations);
    }

    private String safeAnswer(ChatModel activeChatModel,
                              String answer,
                              String query,
                              List<String> observations,
                              String systemPrompt) {
        if (answer != null && !answer.isBlank()) {
            return answer;
        }
        return summarizeWithObservations(activeChatModel, query, systemPrompt, observations);
    }

    private String summarizeWithObservations(ChatModel activeChatModel,
                                             String query,
                                             String systemPrompt,
                                             List<String> observations) {
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
        return activeChatModel.chat(prompt.toString());
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
}
