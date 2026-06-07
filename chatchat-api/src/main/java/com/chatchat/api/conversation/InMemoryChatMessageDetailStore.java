package com.chatchat.api.conversation;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Component
@ConditionalOnProperty(prefix = "chatchat.chat.detail-store", name = "type", havingValue = "memory")
public class InMemoryChatMessageDetailStore implements ChatMessageDetailStore {

    private final Map<String, ChatMessageDetail> store = new ConcurrentHashMap<>();

    @Override
    public String put(ChatMessageDetail detail) {
        Instant createdAt = detail.getCreatedAt() == null ? Instant.now() : detail.getCreatedAt();
        detail.setCreatedAt(createdAt);
        String key = ChatMessageKeyBuilder.build(
            detail.getTenantId(),
            detail.getSessionId(),
            createdAt,
            detail.getMessageId()
        );
        store.put(key, detail);
        return key;
    }

    @Override
    public Optional<ChatMessageDetail> get(String key) {
        return Optional.ofNullable(store.get(key));
    }

    @Override
    public void delete(String key) {
        store.remove(key);
    }
}
