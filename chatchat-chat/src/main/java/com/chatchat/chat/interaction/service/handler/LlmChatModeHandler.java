package com.chatchat.chat.interaction.service.handler;

import com.chatchat.chat.interaction.model.InteractionContext;
import com.chatchat.chat.interaction.model.InteractionMode;
import com.chatchat.chat.interaction.model.InteractionRequest;
import com.chatchat.chat.interaction.model.InteractionResponse;
import com.chatchat.chat.interaction.service.ConversationMemoryService;
import com.chatchat.chat.interaction.service.InteractionModeHandler;
import dev.langchain4j.model.chat.ChatModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.stream.Collectors;

/**
 * Default LLM chat interaction handler.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class LlmChatModeHandler implements InteractionModeHandler {

    private final ChatModel chatLanguageModel;

    /**
     * Performs the mode operation.
     *
     * @return the operation result
     */
    @Override
    public InteractionMode mode() {
        return InteractionMode.LLM_CHAT;
    }

    /**
     * Handles the handle.
     *
     * @param request the request value
     * @param context the context value
     * @return the operation result
     */
    @Override
    public InteractionResponse handle(InteractionRequest request, InteractionContext context) {
        String prompt = buildPrompt(request, context);
        long startedAt = System.currentTimeMillis();
        log.info("llmChatModelRequest requestId={} conversationId={} promptChars={} historyUsed={}",
            context.requestId(),
            context.conversationId(),
            prompt.length(),
            context.history().size());
        String answer = chatLanguageModel.chat(prompt);
        log.info("llmChatModelOutput requestId={} conversationId={} durationMs={} answerChars={} answer=\n{}",
            context.requestId(),
            context.conversationId(),
            System.currentTimeMillis() - startedAt,
            answer == null ? 0 : answer.length(),
            answer == null ? "" : answer);

        return InteractionResponse.builder()
            .answer(answer)
            .metadata(java.util.Map.of(
                "historyUsed", context.history().size(),
                "summaryUsed", context.conversationSummary() != null && !context.conversationSummary().isBlank(),
                "handler", "LlmChatModeHandler"
            ))
            .build();
    }

    /**
     * Builds the prompt.
     *
     * @param request the request value
     * @param context the context value
     * @return the built prompt
     */
    private String buildPrompt(InteractionRequest request, InteractionContext context) {
        StringBuilder builder = new StringBuilder();
        if (request.getSystemPrompt() != null && !request.getSystemPrompt().isBlank()) {
            builder.append("System: ").append(request.getSystemPrompt()).append("\n\n");
        }
        if (context.conversationSummary() != null && !context.conversationSummary().isBlank()) {
            builder.append("Conversation Summary:\n")
                .append(context.conversationSummary().trim())
                .append("\n\n");
        }
        if (!context.history().isEmpty()) {
            String history = context.history().stream()
                .map(this::formatHistoryMessage)
                .collect(Collectors.joining("\n"));
            builder.append("Conversation History:\n").append(history).append("\n\n");
        }
        builder.append("User: ").append(request.getQuery());
        return builder.toString();
    }

    private String formatHistoryMessage(ConversationMemoryService.MessageSnapshot message) {
        String role = message.role() == null || message.role().isBlank() ? "unknown" : message.role().trim();
        String line = role + ": " + message.content();
        String memory = message.compactContext();
        return memory.isBlank() ? line : line + "\n  context: " + memory;
    }
}
