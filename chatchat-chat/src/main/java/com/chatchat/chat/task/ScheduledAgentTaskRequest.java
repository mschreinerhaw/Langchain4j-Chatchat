package com.chatchat.chat.task;

import lombok.Data;

import java.time.Instant;

@Data
public class ScheduledAgentTaskRequest {

    private String tenantId;
    private String userId;
    private String agentId;
    private String name;
    private Boolean enabled;
    private String triggerType;
    private String cron;
    private String cronExpr;
    private Long intervalSeconds;
    private Long delaySeconds;
    private Instant nextFireTime;
    private Instant expiredAt;
    private Integer maxRetries;
    private Long retryDelaySeconds;
    private String question;
    private Boolean notifyEnabled;
    private Boolean tradingDayOnly;
    private AgentTaskSubmitRequest payload;
    private String payloadJson;
}
