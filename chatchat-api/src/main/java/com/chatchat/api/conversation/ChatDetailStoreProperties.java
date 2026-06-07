package com.chatchat.api.conversation;

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
}
