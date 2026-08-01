package com.chatchat.chat.task;

import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class AgentTaskFeedbackRequest {

    @Size(max = 128)
    private String tenantId;
    @Size(max = 128)
    private String userId;
    private Boolean useful;
    private Boolean adopted;
    private Boolean resolved;
    @Size(max = 4000)
    private String comment;
    @Size(max = 64)
    private String reasonCategory;
}
