package com.chatchat.api.controller;

import com.chatchat.chat.conversation.Conversation;
import com.chatchat.chat.conversation.ConversationService;
import com.chatchat.common.constants.AppConstants;
import com.chatchat.common.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST API controller for conversation management
 */
@RestController
@RequestMapping(AppConstants.API_V1 + "/conversations")
@RequiredArgsConstructor
@Tag(name = "Conversations", description = "Conversation management APIs")
public class ConversationController {

    private final ConversationService conversationService;

    /**
     * Create a new conversation
     */
    @PostMapping
    @Operation(summary = "Create a new conversation")
    public ApiResponse<Conversation> createConversation(@RequestBody CreateConversationRequest request) {
        Conversation conversation = conversationService.createConversation(request.userId(), request.title());
        return ApiResponse.success(conversation, "Conversation created successfully");
    }

    /**
     * Get conversation by ID
     */
    @GetMapping("/{conversationId}")
    @Operation(summary = "Get conversation details")
    public ApiResponse<Conversation> getConversation(@PathVariable("conversationId") String conversationId) {
        return conversationService.getConversation(conversationId)
            .map(ApiResponse::success)
            .orElseGet(() -> ApiResponse.notFound("Conversation not found: " + conversationId));
    }

    /**
     * List conversations for a user
     */
    @GetMapping("/user/{userId}")
    @Operation(summary = "List user conversations")
    public ApiResponse<List<Conversation>> listUserConversations(@PathVariable("userId") String userId) {
        return ApiResponse.success(conversationService.listUserConversations(userId));
    }

    /**
     * Delete conversation
     */
    @DeleteMapping("/{conversationId}")
    @Operation(summary = "Delete a conversation")
    public ApiResponse<Void> deleteConversation(@PathVariable("conversationId") String conversationId) {
        conversationService.deleteConversation(conversationId);
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
