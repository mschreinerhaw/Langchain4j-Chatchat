package com.chatchat.chat.conversation;

import java.util.Optional;

public interface ChatMessageTextStore {

    boolean isEnabled();

    void put(String documentId, ChatMessageDetail detail);

    Optional<ChatMessageDetail> get(String documentId);

    void putText(String documentId,
                 String kind,
                 String tenantId,
                 String sessionId,
                 String entityId,
                 String text);

    Optional<String> getText(String documentId);

    void delete(String documentId);
}
