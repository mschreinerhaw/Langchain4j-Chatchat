package com.chatchat.chat.conversation.store;

import com.chatchat.chat.conversation.model.ChatMessageDetail;

import com.chatchat.chat.conversation.model.Conversation;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface ChatMessageDetailStore {

    /**
     * Stores the put.
     *
     * @param detail the detail value
     * @return the operation result
     */
    String put(ChatMessageDetail detail);

    /**
     * Returns the get.
     *
     * @param key the key value
     * @return the get
     */
    Optional<ChatMessageDetail> get(String key);

    /**
     * Loads multiple details while isolating a broken legacy record from the
     * rest of the conversation.
     */
    default Map<String, ChatMessageDetail> getAll(List<String> keys) {
        Map<String, ChatMessageDetail> details = new LinkedHashMap<>();
        if (keys == null) {
            return details;
        }
        for (String key : keys) {
            if (key == null || key.isBlank()) {
                continue;
            }
            try {
                get(key).ifPresent(detail -> details.put(key, detail));
            } catch (RuntimeException ignored) {
                // A corrupt legacy message must not make the whole conversation unreadable.
            }
        }
        return details;
    }

    /**
     * Deletes the delete.
     *
     * @param key the key value
     */
    void delete(String key);
}
