package com.chatchat.api.controller;

import com.chatchat.chat.conversation.Conversation;
import com.chatchat.chat.conversation.ConversationService;
import com.chatchat.api.security.ApiAuthenticationFilter;
import com.chatchat.common.constants.AppConstants;
import com.chatchat.common.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
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
    public ApiResponse<Conversation> createConversation(@RequestBody CreateConversationRequest request,
                                                        HttpServletRequest servletRequest) {
        Conversation conversation = conversationService.createConversation(
            resolveTenantId(servletRequest, request.tenantId()),
            request.userId(),
            request.title()
        );
        return ApiResponse.success(conversation, "Conversation created successfully");
    }

    /**
     * Get conversation by ID
     */
    @GetMapping("/{conversationId}")
    @Operation(summary = "Get conversation details")
    public ApiResponse<Conversation> getConversation(@PathVariable("conversationId") String conversationId,
                                                     @RequestParam(value = "tenantId", required = false) String tenantId,
                                                     HttpServletRequest servletRequest) {
        return conversationService.getConversation(resolveTenantId(servletRequest, tenantId), conversationId)
            .map(ApiResponse::success)
            .orElseGet(() -> ApiResponse.notFound("Conversation not found: " + conversationId));
    }

    /**
     * List conversations for a user
     */
    @GetMapping("/user/{userId}")
    @Operation(summary = "List user conversations")
    public ApiResponse<List<Conversation>> listUserConversations(@PathVariable("userId") String userId,
                                                                 @RequestParam(value = "tenantId", required = false) String tenantId,
                                                                 HttpServletRequest servletRequest) {
        return ApiResponse.success(conversationService.listUserConversations(resolveTenantId(servletRequest, tenantId), userId));
    }

    /**
     * Delete conversation
     */
    @DeleteMapping("/{conversationId}")
    @Operation(summary = "Delete a conversation")
    public ApiResponse<Void> deleteConversation(@PathVariable("conversationId") String conversationId,
                                                @RequestParam(value = "tenantId", required = false) String tenantId,
                                                HttpServletRequest servletRequest) {
        conversationService.deleteConversation(resolveTenantId(servletRequest, tenantId), conversationId);
        return ApiResponse.success(null, "Conversation deleted successfully");
    }

    private String resolveTenantId(HttpServletRequest request, String requestedTenantId) {
        String currentTenantId = requestAttribute(request, ApiAuthenticationFilter.CURRENT_TENANT_ID);
        if (currentTenantId != null && !currentTenantId.isBlank()) {
            return currentTenantId.trim();
        }
        return requestedTenantId == null || requestedTenantId.isBlank() ? "default" : requestedTenantId.trim();
    }

    private String requestAttribute(HttpServletRequest request, String name) {
        Object value = request == null ? null : request.getAttribute(name);
        return value == null ? null : String.valueOf(value);
    }

    /**
     * Request model for creating conversation
     */
    public record CreateConversationRequest(
        String tenantId,
        String userId,
        String title
    ) {
        public CreateConversationRequest(String userId, String title) {
            this(null, userId, title);
        }
    }
}
