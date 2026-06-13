package com.chatchat.chat.interaction.service;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "chatchat.chat.context")
public class ConversationContextProperties {

    private int recentMessageLimit = 8;
    private int summaryTriggerMessages = 12;
    private int summaryKeepRecentMessages = 6;
    private int summaryMaxChars = 1600;
    private boolean summaryEnabled = true;
}
