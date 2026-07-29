package com.chatchat.chat.conversation;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface ChatMessageTextStore {

    boolean isEnabled();

    void put(String documentId, ChatMessageDetail detail);

    Optional<ChatMessageDetail> get(String documentId);

    default Map<String, ChatMessageDetail> getAll(List<String> documentIds) {
        Map<String, ChatMessageDetail> details = new LinkedHashMap<>();
        if (documentIds == null) {
            return details;
        }
        for (String documentId : documentIds) {
            if (documentId == null || documentId.isBlank()) {
                continue;
            }
            try {
                get(documentId).ifPresent(detail -> details.put(documentId, detail));
            } catch (RuntimeException ignored) {
                // Preserve readable messages when one external document is invalid.
            }
        }
        return details;
    }

    void putText(String documentId,
                 String kind,
                 String tenantId,
                 String sessionId,
                 String entityId,
                 String text);

    Optional<String> getText(String documentId);

    void delete(String documentId);
}
