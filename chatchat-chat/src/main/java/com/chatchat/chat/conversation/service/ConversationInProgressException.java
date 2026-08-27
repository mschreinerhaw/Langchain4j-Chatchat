package com.chatchat.chat.conversation.service;

import com.chatchat.chat.conversation.model.Conversation;

/**
 * Raised when a destructive operation targets a conversation that is still running.
 */
public class ConversationInProgressException extends RuntimeException {

    public ConversationInProgressException(String conversationId) {
        super("Conversation is still in progress and cannot be deleted: " + conversationId);
    }
}
