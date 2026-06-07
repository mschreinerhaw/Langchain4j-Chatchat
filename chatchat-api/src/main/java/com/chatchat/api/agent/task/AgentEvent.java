package com.chatchat.api.agent.task;

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
    private String type;
    private String status;
    private String payload;

    @Builder.Default
    private long createTime = System.currentTimeMillis();
}
