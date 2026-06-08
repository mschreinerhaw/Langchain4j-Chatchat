package com.chatchat.api.controller;

import com.chatchat.knowledgebase.rag.RAGService;
import com.chatchat.common.constants.AppConstants;
import com.chatchat.common.response.ApiResponse;
import com.chatchat.knowledgebase.service.KnowledgeBaseService;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.model.chat.ChatModel;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * REST API controller for chat and RAG operations
 *
 * Provides endpoints for:
 * - Sending chat messages with RAG context
 * - Streaming chat responses
 * - Retrieving RAG statistics
 */
@Slf4j
@RestController
@RequestMapping(AppConstants.API_V1 + "/chat")
@RequiredArgsConstructor
@Tag(name = "Chat", description = "Chat and RAG APIs")
public class ChatController {

    private final RAGService ragService;
    private final KnowledgeBaseService knowledgeBaseService;
    private final ObjectProvider<ChatModel> chatLanguageModelProvider;

    /**
     * Send a chat message with RAG context
     *
     * @param request Chat request containing message, knowledge base ID, etc.
     * @return ChatResponse wrapped in ApiResponse
     */
    @PostMapping("/message")
    @Operation(summary = "Send a chat message")
    @ApiResponses(value = {
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "Message processed successfully",
            content = @Content(schema = @Schema(implementation = ApiResponse.class))
        ),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "400",
            description = "Invalid request parameters"
        ),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "500",
            description = "Internal server error"
        )
    })
    public ApiResponse<ChatResponse> sendMessage(@RequestBody ChatRequest request) {
        String requestId = UUID.randomUUID().toString();
        log.info("[{}] Received chat message: {}", requestId, request.message());

        try {
            // Validate request
            if (request.message() == null || request.message().trim().isEmpty()) {
                log.warn("[{}] Invalid message: empty", requestId);
                return ApiResponse.badRequest("Message cannot be empty");
            }

            if (request.knowledgeBaseId() == null || request.knowledgeBaseId().trim().isEmpty()) {
                log.warn("[{}] Invalid knowledge base ID: empty", requestId);
                return ApiResponse.badRequest("Knowledge base ID is required");
            }

            // Retrieve context from knowledge base
            List<Document> contextDocs = ragService.retrieveContext(
                request.knowledgeBaseId(),
                request.message(),
                5
            );

            log.debug("[{}] Retrieved {} context documents", requestId, contextDocs.size());

            // Build contextual prompt
            String systemPrompt = request.systemPrompt() != null ?
                request.systemPrompt() :
                "You are a helpful AI assistant.";

            String prompt = ragService.buildContextualPrompt(
                request.message(),
                contextDocs,
                systemPrompt
            );

            log.debug("[{}] Built contextual prompt", requestId);

            // Generate response
            String response = generateResponse(prompt, request, requestId);

            // Create chat response
            ChatResponse chatResponse = new ChatResponse(
                request.conversationId() != null ?
                    request.conversationId() :
                    UUID.randomUUID().toString(),
                response,
                contextDocs.size(),
                System.currentTimeMillis(),
                requestId
            );

            log.info("[{}] Successfully processed chat message", requestId);

            return ApiResponse.success(chatResponse, "Message processed successfully");

        } catch (IllegalArgumentException e) {
            log.warn("[{}] Validation error: {}", requestId, e.getMessage());
            return ApiResponse.badRequest(e.getMessage());
        } catch (Exception e) {
            log.error("[{}] Error processing chat message", requestId, e);
            return ApiResponse.internalError("Error processing message: " + e.getMessage());
        }
    }

    /**
     * Stream chat response using Server-Sent Events
     *
     * @param request Chat request
     * @return Streaming response
     */
    @PostMapping(value = "/stream", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Stream chat response")
    public ApiResponse<String> streamChat(@RequestBody ChatRequest request) {
        String requestId = UUID.randomUUID().toString();
        log.info("[{}] Streaming chat message: {}", requestId, request.message());

        try {
            // Validate request
            if (request.message() == null || request.message().trim().isEmpty()) {
                return ApiResponse.badRequest("Message cannot be empty");
            }

            if (request.knowledgeBaseId() == null || request.knowledgeBaseId().trim().isEmpty()) {
                return ApiResponse.badRequest("Knowledge base ID is required");
            }

            // In production, this would use SSE or WebSocket for streaming
            log.info("[{}] Streaming initiated for message: {}", requestId, request.message());

            return ApiResponse.success(
                "Streaming initialized",
                "Streaming initiated for request: " + requestId
            );
        } catch (Exception e) {
            log.error("[{}] Error in stream chat", requestId, e);
            return ApiResponse.internalError("Error streaming message: " + e.getMessage());
        }
    }

    /**
     * Get RAG statistics for a knowledge base
     *
     * @param knowledgeBaseId Knowledge base identifier
     * @return RAG statistics wrapped in ApiResponse
     */
    @GetMapping("/stats/{knowledgeBaseId}")
    @Operation(summary = "Get RAG statistics")
    public ApiResponse<RAGService.RetrievalStats> getStats(@PathVariable("knowledgeBaseId") String knowledgeBaseId) {
        String requestId = UUID.randomUUID().toString();
        log.info("[{}] Getting RAG stats for KB: {}", requestId, knowledgeBaseId);

        try {
            if (knowledgeBaseId == null || knowledgeBaseId.trim().isEmpty()) {
                return ApiResponse.badRequest("Knowledge base ID is required");
            }

            RAGService.RetrievalStats stats = ragService.getStats(knowledgeBaseId);

            log.info("[{}] Retrieved stats for KB: {}", requestId, knowledgeBaseId);

            return ApiResponse.success(stats, "Statistics retrieved successfully");
        } catch (Exception e) {
            log.error("[{}] Error retrieving RAG stats", requestId, e);
            return ApiResponse.internalError("Error retrieving statistics: " + e.getMessage());
        }
    }

    /**
     * Health check endpoint
     */
    @GetMapping("/health")
    @Operation(summary = "Health check")
    public ApiResponse<String> health() {
        return ApiResponse.success("OK", "Chat service is healthy");
    }

    /**
     * Generate response based on prompt and context.
     */
    private String generateResponse(String prompt, ChatRequest request, String requestId) {
        ChatModel model = chatLanguageModelProvider.getIfAvailable();
        if (model == null) {
            log.warn("[{}] No ChatModel bean available, using fallback response", requestId);
            return "当前未配置可用模型，无法生成真实回答。请检查 chatchat.models 配置。";
        }

        log.debug("[{}] Generating response for prompt length: {}", requestId, prompt.length());
        return model.chat(prompt);
    }

    /**
     * Chat request model
     */
    public record ChatRequest(
        String conversationId,
        String message,
        String knowledgeBaseId,
        String systemPrompt
    ) {}

    /**
     * Chat response model
     */
    public record ChatResponse(
        String conversationId,
        String response,
        int contextDocumentsUsed,
        long timestamp,
        String requestId
    ) {}
}
