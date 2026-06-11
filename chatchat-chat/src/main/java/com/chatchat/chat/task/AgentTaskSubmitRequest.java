package com.chatchat.chat.task;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AgentTaskSubmitRequest {

    private String tenantId;
    private String resumeTaskId;
    private String userId;
    private String agentId;
    private String sessionId;
    private String query;
    private String mode;
    private String systemPrompt;
    private String modelName;
    private String skillId;
    private Integer maxResults;
    private Integer historyWindow;
    private Boolean stream;
    private List<String> availableTools = new ArrayList<>();
    private Map<String, Object> toolInput = new HashMap<>();
}
