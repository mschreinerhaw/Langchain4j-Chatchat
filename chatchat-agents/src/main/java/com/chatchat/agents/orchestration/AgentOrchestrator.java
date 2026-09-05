package com.chatchat.agents.orchestration;

import com.chatchat.agents.orchestration.analysis.model.AnalysisDatasetSummary;
import com.chatchat.agents.orchestration.analysis.model.AnalysisSummaryResult;
import com.chatchat.agents.orchestration.analysis.model.AnalysisTask;
import com.chatchat.agents.orchestration.analysis.model.AnalysisTaskResult;


import com.chatchat.agents.orchestration.evidence.EvidenceTrustEvaluator;
import com.chatchat.agents.runtime.answer.AgentAnswerReviewer;
import com.chatchat.agents.runtime.config.AgentRuntimeProperties;
import com.chatchat.agents.runtime.observation.AgentObservationPipeline;
import com.chatchat.agents.runtime.plan.persistence.InterpretationPlanStore;
import com.chatchat.agents.runtime.plan.InterpretationPlan;
import com.chatchat.agents.runtime.plan.InterpretationPlanRuntime;
import com.chatchat.agents.runtime.store.AgentRunStore;
import com.chatchat.agents.runtime.tool.ToolRuntimeService;
import com.chatchat.agents.runtime.governance.GovernanceIsolationScope;
import com.chatchat.agents.tool.ToolRegistry;
import com.chatchat.common.config.ModelsConfig;
import com.chatchat.common.interaction.InteractionToolTrace;
import com.chatchat.common.tool.ToolOutput;
import com.chatchat.common.runtime.summary.analysis.spi.DataAnalysisSummaryProtocol;
import com.chatchat.common.runtime.summary.spi.ModelSummaryDispatcher;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * Stable public facade for Agent orchestration.
 *
 * <p>Planning, evidence, analysis, workflow recovery and tool execution are implemented by
 * focused components assembled by the internal engine. This type only owns the public API and
 * dependency-injection boundary.</p>
 */
@Service
public class AgentOrchestrator extends AgentOrchestrationEngine {

    public AgentOrchestrator(ChatModel chatModel, ToolRegistry toolRegistry,
                             ToolRuntimeService toolRuntimeService, ObjectMapper objectMapper,
                             ModelsConfig modelsConfig) {
        super(chatModel, toolRegistry, toolRuntimeService, objectMapper, modelsConfig);
    }

    public AgentOrchestrator(ChatModel chatModel, ToolRegistry toolRegistry,
                             ToolRuntimeService toolRuntimeService, ObjectMapper objectMapper,
                             ModelsConfig modelsConfig, EvidenceTrustEvaluator evidenceTrustEvaluator) {
        super(chatModel, toolRegistry, toolRuntimeService, objectMapper, modelsConfig,
            evidenceTrustEvaluator);
    }

    public AgentOrchestrator(ChatModel chatModel, ToolRegistry toolRegistry,
                             ToolRuntimeService toolRuntimeService, ObjectMapper objectMapper,
                             ModelsConfig modelsConfig, EvidenceTrustEvaluator evidenceTrustEvaluator,
                             AgentRunStore runStore) {
        super(chatModel, toolRegistry, toolRuntimeService, objectMapper, modelsConfig,
            evidenceTrustEvaluator, runStore);
    }

    public AgentOrchestrator(ChatModel chatModel, ToolRegistry toolRegistry,
                             ToolRuntimeService toolRuntimeService, ObjectMapper objectMapper,
                             ModelsConfig modelsConfig, EvidenceTrustEvaluator evidenceTrustEvaluator,
                             AgentRunStore runStore, AgentObservationPipeline observationPipeline) {
        super(chatModel, toolRegistry, toolRuntimeService, objectMapper, modelsConfig,
            evidenceTrustEvaluator, runStore, observationPipeline);
    }

    public AgentOrchestrator(ChatModel chatModel, ToolRegistry toolRegistry,
                             ToolRuntimeService toolRuntimeService, ObjectMapper objectMapper,
                             ModelsConfig modelsConfig, EvidenceTrustEvaluator evidenceTrustEvaluator,
                             AgentRunStore runStore, AgentObservationPipeline observationPipeline,
                             AgentAnswerReviewer answerReviewer) {
        super(chatModel, toolRegistry, toolRuntimeService, objectMapper, modelsConfig,
            evidenceTrustEvaluator, runStore, observationPipeline, answerReviewer);
    }

    public AgentOrchestrator(ChatModel chatModel, ToolRegistry toolRegistry,
                             ToolRuntimeService toolRuntimeService, ObjectMapper objectMapper,
                             ModelsConfig modelsConfig, EvidenceTrustEvaluator evidenceTrustEvaluator,
                             AgentRunStore runStore, AgentObservationPipeline observationPipeline,
                             AgentAnswerReviewer answerReviewer,
                             InterpretationPlanStore interpretationPlanStore) {
        super(chatModel, toolRegistry, toolRuntimeService, objectMapper, modelsConfig,
            evidenceTrustEvaluator, runStore, observationPipeline, answerReviewer,
            interpretationPlanStore);
    }

    @Autowired
    public AgentOrchestrator(ChatModel chatModel, ToolRegistry toolRegistry,
                             ToolRuntimeService toolRuntimeService, ObjectMapper objectMapper,
                             ModelsConfig modelsConfig, EvidenceTrustEvaluator evidenceTrustEvaluator,
                             AgentRunStore runStore, AgentObservationPipeline observationPipeline,
                             AgentAnswerReviewer answerReviewer,
                             InterpretationPlanStore interpretationPlanStore,
                             AgentRuntimeProperties agentRuntimeProperties) {
        super(chatModel, toolRegistry, toolRuntimeService, objectMapper, modelsConfig,
            evidenceTrustEvaluator, runStore, observationPipeline, answerReviewer,
            interpretationPlanStore, agentRuntimeProperties);
    }

    // Declared compatibility seams retained while their callers migrate to the domain services.
    @Override
    public void setAnalysisSummaryProtocol(
        DataAnalysisSummaryProtocol<AnalysisSummaryResult, GovernanceIsolationScope> protocol
    ) {
        super.setAnalysisSummaryProtocol(protocol);
    }

    @Override
    public void setModelSummaryDispatcher(
        ModelSummaryDispatcher<AnalysisTask, AnalysisDatasetSummary, AnalysisTaskResult> dispatcher
    ) {
        super.setModelSummaryDispatcher(dispatcher);
    }

    @Override
    protected Map<String, Object> workflowAttemptAttributes(Map<String, Object> attributes, int attempt) {
        return super.workflowAttemptAttributes(attributes, attempt);
    }

    @Override
    protected int maxRewriteTimes(InterpretationPlan plan) {
        return super.maxRewriteTimes(plan);
    }

    @Override
    protected Map<String, Object> mandatoryWorkflowResultReview(String toolName, ToolOutput output) {
        return super.mandatoryWorkflowResultReview(toolName, output);
    }

    @Override
    protected Map<String, Object> mandatoryWorkflowPredecessorReview(
        String fallbackTool, List<InteractionToolTrace> predecessorTraces) {
        return super.mandatoryWorkflowPredecessorReview(fallbackTool, predecessorTraces);
    }

    @Override
    protected AgentExecutionResult finishInterpretationPlanWorkflowBlockedIfPending(
        List<InteractionToolTrace> traces, Map<String, Object> metadata, List<String> observations,
        String stopReason, String reason) {
        return super.finishInterpretationPlanWorkflowBlockedIfPending(
            traces, metadata, observations, stopReason, reason);
    }

    @Override
    protected Map<String, Object> analyzeInterpretationPlanEvidence(
        ChatModel activeChatModel, String query, String systemPrompt, InterpretationPlan plan,
        InterpretationPlanRuntime.ExecutionResult result, int iteration,
        List<Map<String, Object>> previousEvidence, Map<String, Object> runtimeAttributes,
        Map<String, Object> metadata, BooleanSupplier cancellationCheck) {
        return super.analyzeInterpretationPlanEvidence(activeChatModel, query, systemPrompt, plan,
            result, iteration, previousEvidence, runtimeAttributes, metadata, cancellationCheck);
    }

    @Override
    protected InterpretationPlanRuntime.ExecutionResult rejectUnsatisfiedInterpretationPlanResult(
        String stage, InterpretationPlanRuntime.ExecutionResult result, List<String> observations,
        Map<String, Object> metadata) {
        return super.rejectUnsatisfiedInterpretationPlanResult(stage, result, observations, metadata);
    }

    @Override
    protected Object authoritativeWorkflowDagForContinuation(
        Object rawDag, InterpretationPlan rewrittenPlan, Set<String> completedTools) {
        return super.authoritativeWorkflowDagForContinuation(rawDag, rewrittenPlan, completedTools);
    }

    @Override
    protected String buildToolResultReviewPrompt(
        String query, String systemPrompt, InterpretationPlanRuntime.StepReviewRequest request) {
        return super.buildToolResultReviewPrompt(query, systemPrompt, request);
    }

    public record ToolCallExecution(
        InteractionToolTrace trace,
        String observation,
        ToolOutput output
    ) {
    }

    public record AgentExecutionResult(
        String answer,
        List<InteractionToolTrace> toolTraces,
        Map<String, Object> metadata
    ) {
    }
}
