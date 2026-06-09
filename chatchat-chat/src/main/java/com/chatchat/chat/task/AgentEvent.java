package com.chatchat.chat.task;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentEvent {

    @Builder.Default
    private String eventId = UUID.randomUUID().toString();

    private String taskId;
    private String tenantId;
    private String userId;
    private String agentId;
    private String sessionId;
    private String parentEventId;
    private Long sequence;
    private String toolName;
    private String type;
    private String status;
    private String payload;
    private Long latencyMs;
    private String errorCode;
    private Integer retryCount;

    @Builder.Default
    private long createTime = System.currentTimeMillis();
}
