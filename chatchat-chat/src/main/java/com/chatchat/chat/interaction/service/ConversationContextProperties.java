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
    private int maxUserQueryChars = 64_000;
    private int maxSystemPromptChars = 32_000;
    private int maxIdentifierChars = 256;
    private int maxAvailableTools = 256;
    private int maxImageAnalysisIds = 64;
    private int maxHistoryWindow = 100;
    private int maxResults = 1_000;
    private int maxToolInputDepth = 20;
    private int maxToolInputNodes = 10_000;
    private int maxToolInputTextChars = 256_000;
}
