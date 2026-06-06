package com.chatchat.api.application.interaction.service;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Lightweight in-memory conversation memory to support interaction orchestration.
 */
@Service
public class ConversationMemoryService {

    private static final int MAX_MESSAGES_PER_CONVERSATION = 100;
    private final Map<String, Deque<MessageSnapshot>> store = new ConcurrentHashMap<>();

    public String ensureConversationId(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            String generated = UUID.randomUUID().toString();
            store.putIfAbsent(generated, new ConcurrentLinkedDeque<>());
            return generated;
        }
        store.putIfAbsent(conversationId, new ConcurrentLinkedDeque<>());
        return conversationId;
    }

    public void append(String conversationId, String role, String content) {
        if (content == null || content.isBlank()) {
            return;
        }
        Deque<MessageSnapshot> messages = store.computeIfAbsent(conversationId, key -> new ConcurrentLinkedDeque<>());
        messages.addLast(new MessageSnapshot(role, content, System.currentTimeMillis()));
        while (messages.size() > MAX_MESSAGES_PER_CONVERSATION) {
            messages.pollFirst();
        }
    }

    public List<MessageSnapshot> recent(String conversationId, int limit) {
        Deque<MessageSnapshot> messages = store.get(conversationId);
        if (messages == null || messages.isEmpty() || limit <= 0) {
            return List.of();
        }
        List<MessageSnapshot> all = new ArrayList<>(messages);
        int from = Math.max(0, all.size() - limit);
        return all.subList(from, all.size());
    }

    public record MessageSnapshot(String role, String content, long timestamp) {
    }
}

