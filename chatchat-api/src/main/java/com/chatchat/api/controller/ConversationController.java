package com.chatchat.api.controller;

import com.chatchat.api.conversation.Conversation;
import com.chatchat.api.rag.RAGService;
import com.chatchat.common.constants.AppConstants;
import com.chatchat.common.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * REST API controller for conversation management
 */
@RestController
@RequestMapping(AppConstants.API_V1 + "/conversations")
@RequiredArgsConstructor
@Tag(name = "Conversations", description = "Conversation management APIs")
public class ConversationController {

    private final RAGService ragService;

    // In-memory conversation store
    private final Map<String, Conversation> conversations = new HashMap<>();

    /**
     * Create a new conversation
     */
    @PostMapping
    @Operation(summary = "Create a new conversation")
    public ApiResponse<Conversation> createConversation(@RequestBody CreateConversationRequest request) {
        String conversationId = UUID.randomUUID().toString();

        Conversation conversation = Conversation.builder()
            .id(conversationId)
            .userId(request.userId())
            .title(request.title())
            .createdAt(LocalDateTime.now())
            .updatedAt(LocalDateTime.now())
            .messages(new ArrayList<>())
            .build();

        conversations.put(conversationId, conversation);

        return ApiResponse.success(conversation, "Conversation created successfully");
    }

    /**
     * Get conversation by ID
     */
    @GetMapping("/{conversationId}")
    @Operation(summary = "Get conversation details")
    public ApiResponse<Conversation> getConversation(@PathVariable("conversationId") String conversationId) {
        Conversation conversation = conversations.get(conversationId);

        if (conversation == null) {
            return ApiResponse.notFound("Conversation not found: " + conversationId);
        }

        return ApiResponse.success(conversation);
    }

    /**
     * List conversations for a user
     */
    @GetMapping("/user/{userId}")
    @Operation(summary = "List user conversations")
    public ApiResponse<List<Conversation>> listUserConversations(@PathVariable("userId") String userId) {
        List<Conversation> userConversations = conversations.values().stream()
            .filter(c -> c.getUserId().equals(userId))
            .toList();

        return ApiResponse.success(userConversations);
    }

    /**
     * Delete conversation
     */
    @DeleteMapping("/{conversationId}")
    @Operation(summary = "Delete a conversation")
    public ApiResponse<Void> deleteConversation(@PathVariable("conversationId") String conversationId) {
        conversations.remove(conversationId);
        return ApiResponse.success(null, "Conversation deleted successfully");
    }

    /**
     * Request model for creating conversation
     */
    public record CreateConversationRequest(
        String userId,
        String title
    ) {}
}
