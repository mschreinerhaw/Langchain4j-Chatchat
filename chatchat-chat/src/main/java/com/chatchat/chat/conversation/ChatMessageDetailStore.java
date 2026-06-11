package com.chatchat.chat.conversation;

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
     * Deletes the delete.
     *
     * @param key the key value
     */
    void delete(String key);
}
