package com.chatchat.chat.conversation.store;

import com.chatchat.chat.conversation.model.Conversation;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "chatchat.chat.detail-store")
public class ChatDetailStoreProperties {

    private String type = "rocksdb";
    private String path = "./data/chat-rocksdb";
    private boolean createIfMissing = true;
    private int maxStringLength = 100_000_000;
    private ExternalText externalText = new ExternalText();

    @Getter
    @Setter
    public static class ExternalText {
        private boolean enabled = false;
        private String indexName = "chatchat_chat_details";
        private boolean migrateLegacyOnRead = true;
    }
}
