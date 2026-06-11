package com.chatchat.chat.interaction.service;

import com.chatchat.chat.interaction.model.InteractionContext;
import com.chatchat.chat.interaction.model.InteractionMode;
import com.chatchat.chat.interaction.model.InteractionRequest;
import com.chatchat.chat.interaction.model.InteractionResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Enterprise interaction orchestration entry.
 *
 * Responsibilities:
 * - Resolve interaction mode
 * - Maintain request/conversation context
 * - Route execution to mode-specific handlers
 * - Apply unified response envelope metadata
 */
@Slf4j
@Service
public class InteractionOrchestrationService {

    private static final int DEFAULT_HISTORY_WINDOW = 8;
    private final Map<InteractionMode, InteractionModeHandler> handlers;
    private final ConversationMemoryService memoryService;

    /**
     * Creates a new InteractionOrchestrationService instance.
     *
     * @param modeHandlers the mode handlers value
     * @param memoryService the memory service value
     */
    public InteractionOrchestrationService(List<InteractionModeHandler> modeHandlers,
                                           ConversationMemoryService memoryService) {
        this.handlers = modeHandlers.stream()
            .collect(Collectors.toMap(InteractionModeHandler::mode, Function.identity()));
        this.memoryService = memoryService;
    }

    /**
     * Performs the chat operation.
     *
     * @param request the request value
     * @return the operation result
     */
    public InteractionResponse chat(InteractionRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Request cannot be null");
        }
        if (request.getQuery() == null || request.getQuery().isBlank()) {
            throw new IllegalArgumentException("Query cannot be empty");
        }

        InteractionMode mode = InteractionMode.from(request.getMode());
        InteractionModeHandler handler = handlers.get(mode);
        if (handler == null) {
            throw new IllegalArgumentException("No handler configured for mode: " + mode.code());
        }

        String requestId = UUID.randomUUID().toString();
        String conversationId = memoryService.ensureConversationId(request.getConversationId(), request.getUserId());
        int historyWindow = request.getHistoryWindow() == null ? DEFAULT_HISTORY_WINDOW : request.getHistoryWindow();
        long startedAt = System.currentTimeMillis();

        InteractionContext context = InteractionContext.builder()
            .requestId(requestId)
            .conversationId(conversationId)
            .mode(mode)
            .startedAtMs(startedAt)
            .history(memoryService.recent(conversationId, historyWindow))
            .build();

        memoryService.append(conversationId, "user", request.getQuery());
        InteractionResponse response = handler.handle(request, context);

        if (response == null) {
            response = InteractionResponse.builder()
                .answer("No response generated")
                .build();
        }

        if (response.getAnswer() != null && !response.getAnswer().isBlank()) {
            memoryService.append(conversationId, "assistant", response.getAnswer());
        }

        response.setConversationId(conversationId);
        response.setRequestId(requestId);
        response.setMode(mode.code());
        response.setTimestamp(System.currentTimeMillis());
        response.setLatencyMs(System.currentTimeMillis() - startedAt);

        log.info("[{}] Interaction completed. mode={}, conversationId={}",
            requestId, mode.code(), conversationId);
        return response;
    }
}
