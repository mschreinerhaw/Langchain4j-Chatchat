package com.chatchat.chat.conversation;

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

    /**
     * Stores the put.
     *
     * @param detail the detail value
     * @return the operation result
     */
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

    /**
     * Returns the get.
     *
     * @param key the key value
     * @return the get
     */
    @Override
    public Optional<ChatMessageDetail> get(String key) {
        return Optional.ofNullable(store.get(key));
    }

    /**
     * Deletes the delete.
     *
     * @param key the key value
     */
    @Override
    public void delete(String key) {
        store.remove(key);
    }
}
