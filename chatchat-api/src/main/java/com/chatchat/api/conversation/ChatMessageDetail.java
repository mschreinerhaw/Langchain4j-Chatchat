package com.chatchat.api.conversation;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatMessageDetail {

    private String messageId;
    private String sessionId;
    private String tenantId;
    private String userId;
    private String role;
    private String content;
    private String model;
    private Integer tokens;
    private Instant createdAt;
    private List<String> toolsUsed;
    private String sourceKnowledgeBase;
}
