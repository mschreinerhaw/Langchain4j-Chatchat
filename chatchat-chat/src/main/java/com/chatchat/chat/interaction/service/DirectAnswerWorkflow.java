package com.chatchat.chat.interaction.service;

import com.chatchat.agents.model.ConfigurableChatModelFactory;
import com.chatchat.chat.interaction.model.*;
import com.chatchat.chat.skills.model.SkillDefinition;
import com.chatchat.common.runtime.capability.WorkflowOutcome;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Map;

/** Tool-free conversation when no capabilities were selected or semantic routing selected a direct answer. */
@Component
@lombok.extern.slf4j.Slf4j
public class DirectAnswerWorkflow {
    private final ChatModel defaultModel;
    private final ConfigurableChatModelFactory models;
    private final ObjectMapper mapper;

    public DirectAnswerWorkflow(ChatModel defaultModel, ConfigurableChatModelFactory models, ObjectMapper mapper) {
        this.defaultModel = defaultModel;
        this.models = models;
        this.mapper = mapper;
    }

    public InteractionResponse execute(InteractionRequest request, InteractionContext context, SkillDefinition agent) {
        try {
            String modelName = agent.modelName() == null || agent.modelName().isBlank() ? request.getModelName() : agent.modelName();
            var history = context.history() == null ? List.of() : context.history().stream()
                .skip(Math.max(0, context.history().size() - 8)).toList();
            String input = mapper.writeValueAsString(Map.of("question", request.getQuery(), "history", history,
                "conversationSummary", context.conversationSummary() == null ? "" : context.conversationSummary()));
            String prompt = (agent.systemPrompt() == null ? "" : agent.systemPrompt()) + """

                Answer the user's conversational or general-knowledge question directly using the conversation context.
                No tools or external evidence have been acquired in this workflow. Do not claim to have queried assets,
                executed templates, retrieved documents or verified current data.
                Provide a useful answer from general knowledge and supplied context even when no MCP tools are selected.
                Do not require tool selection or Agent configuration as a prerequisite for answering.
                If the requested facts need unavailable external evidence, explain the limitation without inventing facts.
                Conversation data:
                """ + input;
            InteractionExecution.checkCancellation(request);
            prompt += com.chatchat.agents.runtime.context.SkillAnalysisContext.prompt(context.skillAnalysisContext(), "REPORT");
            String answer = (modelName == null || modelName.isBlank() ? defaultModel : models.create(modelName)).chat(prompt);
            InteractionExecution.checkCancellation(request);
            if (answer == null || answer.isBlank()) throw new IllegalStateException("Empty direct answer");
            return response(answer, WorkflowOutcome.Type.READY_TO_ANSWER, "DIRECT_ANSWER_COMPLETED", true);
        } catch (java.util.concurrent.CancellationException cancelled) {
            throw cancelled;
        } catch (Exception failure) {
            InteractionExecution.propagateCancellation(request, failure);
            log.warn("Direct answer failed requestId={} failureType={}", context.requestId(), failure.getClass().getSimpleName(), failure);
            return response("暂时无法生成回答，请稍后重试。", WorkflowOutcome.Type.FAILED, "DIRECT_ANSWER_FAILED", false);
        }
    }

    private InteractionResponse response(String answer, WorkflowOutcome.Type type, String reason, boolean complete) {
        return InteractionResponse.builder().answer(answer).toolTraces(List.of())
            .metadata(Map.of("handler", "DirectAnswerWorkflow", "toolPlanningSkipped", true,
                WorkflowOutcome.METADATA_KEY, new WorkflowOutcome(type, reason, List.of(), List.of(), complete))).build();
    }
}
