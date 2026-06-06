package com.chatchat.api.application.interaction.service.handler;

import com.chatchat.api.application.interaction.model.InteractionContext;
import com.chatchat.api.application.interaction.model.InteractionMode;
import com.chatchat.api.application.interaction.model.InteractionRequest;
import com.chatchat.api.application.interaction.model.InteractionResponse;
import com.chatchat.api.application.interaction.service.ConversationMemoryService;
import com.chatchat.api.application.interaction.service.InteractionModeHandler;
import dev.langchain4j.model.chat.ChatModel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.stream.Collectors;

/**
 * Default LLM chat interaction handler.
 */
@Component
@RequiredArgsConstructor
public class LlmChatModeHandler implements InteractionModeHandler {

    private final ChatModel chatLanguageModel;

    @Override
    public InteractionMode mode() {
        return InteractionMode.LLM_CHAT;
    }

    @Override
    public InteractionResponse handle(InteractionRequest request, InteractionContext context) {
        String prompt = buildPrompt(request, context);
        String answer = chatLanguageModel.chat(prompt);

        return InteractionResponse.builder()
            .answer(answer)
            .metadata(java.util.Map.of(
                "historyUsed", context.history().size(),
                "handler", "LlmChatModeHandler"
            ))
            .build();
    }

    private String buildPrompt(InteractionRequest request, InteractionContext context) {
        StringBuilder builder = new StringBuilder();
        if (request.getSystemPrompt() != null && !request.getSystemPrompt().isBlank()) {
            builder.append("System: ").append(request.getSystemPrompt()).append("\n\n");
        }
        if (!context.history().isEmpty()) {
            String history = context.history().stream()
                .map(message -> message.role() + ": " + message.content())
                .collect(Collectors.joining("\n"));
            builder.append("Conversation History:\n").append(history).append("\n\n");
        }
        builder.append("User: ").append(request.getQuery());
        return builder.toString();
    }
}
