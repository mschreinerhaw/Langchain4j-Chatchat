package com.chatchat.api.conversation;

import java.util.Optional;

public interface ChatMessageDetailStore {

    String put(ChatMessageDetail detail);

    Optional<ChatMessageDetail> get(String key);

    void delete(String key);
}
