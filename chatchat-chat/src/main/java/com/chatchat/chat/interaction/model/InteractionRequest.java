package com.chatchat.chat.interaction.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Unified request model for enterprise interaction orchestration.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InteractionRequest {

    private String conversationId;
    private String userId;
    private String mode;
    private String query;
    private String knowledgeBaseId;
    private String systemPrompt;
    private String modelName;
    private String skillId;
    private String toolName;

    @Builder.Default
    private Map<String, Object> toolInput = new HashMap<>();

    @Builder.Default
    private List<String> availableTools = new ArrayList<>();

    private Integer maxResults;
    private Integer historyWindow;
    private Boolean stream;
}
