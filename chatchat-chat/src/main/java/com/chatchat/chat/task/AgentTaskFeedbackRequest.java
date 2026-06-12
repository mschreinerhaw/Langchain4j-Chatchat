package com.chatchat.chat.task;

import lombok.Data;

@Data
public class AgentTaskFeedbackRequest {

    private String tenantId;
    private String userId;
    private Boolean useful;
    private Boolean adopted;
    private Boolean resolved;
    private String comment;
    private String reasonCategory;
}
