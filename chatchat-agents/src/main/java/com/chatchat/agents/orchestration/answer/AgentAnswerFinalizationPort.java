package com.chatchat.agents.orchestration.answer;

import com.chatchat.agents.orchestration.analysis.model.AnalysisSummaryResult;


import com.chatchat.agents.orchestration.AgentOrchestrator;
import com.chatchat.agents.runtime.governance.GovernanceIsolationScope;
import com.chatchat.common.runtime.summary.analysis.spi.DataAnalysisSummaryProtocol;
import com.chatchat.common.interaction.InteractionToolTrace;
import dev.langchain4j.model.chat.ChatModel;

import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

/** Orchestrator-facing answer completion boundary. */
public interface AgentAnswerFinalizationPort extends AgentToolBudgetPort {

    void setAnalysisSummaryProtocol(
        DataAnalysisSummaryProtocol<AnalysisSummaryResult, GovernanceIsolationScope> analysisSummaryProtocol);

    AgentOrchestrator.AgentExecutionResult finishExecution(String answer,
        List<InteractionToolTrace> traces, Map<String, Object> metadata, List<String> observations);

    AgentOrchestrator.AgentExecutionResult finishBudgetedSummary(ChatModel activeChatModel,
        String query, String systemPrompt, List<InteractionToolTrace> traces,
        Map<String, Object> metadata, List<String> observations, BooleanSupplier cancellationCheck);

    AgentOrchestrator.AgentExecutionResult finishReviewedSummary(ChatModel activeChatModel,
        String query, String systemPrompt, List<InteractionToolTrace> traces,
        Map<String, Object> metadata, List<String> observations, BooleanSupplier cancellationCheck,
        String stopReason);

    AgentOrchestrator.AgentExecutionResult finishReviewedAnswer(ChatModel activeChatModel,
        String query, String systemPrompt, List<InteractionToolTrace> traces,
        Map<String, Object> metadata, List<String> observations, String answer,
        BooleanSupplier cancellationCheck, String stopReason);

    AgentOrchestrator.AgentExecutionResult finishProducedAnswerAfterCancellation(String query,
        List<InteractionToolTrace> traces, Map<String, Object> metadata, List<String> observations,
        String answer, String reason);
}
