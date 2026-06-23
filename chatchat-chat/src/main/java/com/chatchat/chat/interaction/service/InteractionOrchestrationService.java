package com.chatchat.chat.interaction.service;

import com.chatchat.chat.interaction.model.InteractionContext;
import com.chatchat.chat.interaction.model.InteractionMode;
import com.chatchat.chat.interaction.model.InteractionRequest;
import com.chatchat.chat.interaction.model.InteractionResponse;
import com.chatchat.chat.image.ImageUnderstandingService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
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

    private final Map<InteractionMode, InteractionModeHandler> handlers;
    private final ConversationMemoryService memoryService;
    private final ImageUnderstandingService imageUnderstandingService;
    private final ConversationContextProperties contextProperties;

    /**
     * Creates a new InteractionOrchestrationService instance.
     *
     * @param modeHandlers the mode handlers value
     * @param memoryService the memory service value
     */
    @Autowired
    public InteractionOrchestrationService(List<InteractionModeHandler> modeHandlers,
                                           ConversationMemoryService memoryService,
                                           ImageUnderstandingService imageUnderstandingService,
                                           ConversationContextProperties contextProperties) {
        this.handlers = modeHandlers.stream()
            .collect(Collectors.toMap(InteractionModeHandler::mode, Function.identity()));
        this.memoryService = memoryService;
        this.imageUnderstandingService = imageUnderstandingService;
        this.contextProperties = contextProperties == null ? new ConversationContextProperties() : contextProperties;
    }

    public InteractionOrchestrationService(List<InteractionModeHandler> modeHandlers,
                                           ConversationMemoryService memoryService,
                                           ImageUnderstandingService imageUnderstandingService) {
        this(modeHandlers, memoryService, imageUnderstandingService, new ConversationContextProperties());
    }

    /**
     * Creates a new InteractionOrchestrationService instance.
     *
     * @param modeHandlers the mode handlers value
     * @param memoryService the memory service value
     */
    public InteractionOrchestrationService(List<InteractionModeHandler> modeHandlers,
                                           ConversationMemoryService memoryService) {
        this(modeHandlers, memoryService, null, new ConversationContextProperties());
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
        int historyWindow = request.getHistoryWindow() == null
            ? contextProperties.getRecentMessageLimit()
            : request.getHistoryWindow();
        long startedAt = System.currentTimeMillis();

        InteractionContext context = InteractionContext.builder()
            .requestId(requestId)
            .conversationId(conversationId)
            .mode(mode)
            .startedAtMs(startedAt)
            .conversationSummary(memoryService.summary(conversationId).map(summary -> summary.summary()).orElse(""))
            .history(memoryService.recent(conversationId, historyWindow))
            .build();

        request.setQuery(appendImageContext(request.getQuery(), buildImageContext(request)));
        memoryService.append(conversationId, "user", request.getQuery());
        InteractionResponse response = handler.handle(request, context);

        if (response == null) {
            response = InteractionResponse.builder()
                .answer("No response generated")
                .build();
        }

        if (response.getAnswer() != null && !response.getAnswer().isBlank()) {
            memoryService.append(
                conversationId,
                "assistant",
                response.getAnswer(),
                response.getSources(),
                response.getToolTraces(),
                memoryService.responseMemoryContext(response)
            );
            memoryService.maybeRefreshSummary(conversationId);
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

    private String appendImageContext(String query, String imageContext) {
        if (imageContext == null || imageContext.isBlank()) {
            return query;
        }
        StringBuilder builder = new StringBuilder();
        builder.append(query == null ? "" : query.trim());
        builder.append("\n\n");
        builder.append(imageContext);
        return builder.toString();
    }

    private String buildImageContext(InteractionRequest request) {
        if (imageUnderstandingService == null) {
            return "";
        }
        return imageUnderstandingService.buildContext(request.getImageAnalysisIds());
    }
}
