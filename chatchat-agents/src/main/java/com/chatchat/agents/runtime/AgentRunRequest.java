package com.chatchat.agents.runtime;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentRunRequest {

    public static final long DEFAULT_TIMEOUT_MS = 300_000L;

    private String runId;
    private String query;
    private String tenantId;

    @Builder.Default
    private List<String> availableTools = new ArrayList<>();

    private String systemPrompt;
    private String modelName;

    @Builder.Default
    private List<String> boundDocumentIds = new ArrayList<>();

    @Builder.Default
    private List<String> boundDocumentTags = new ArrayList<>();

    private String skillId;
    private String requestId;
    private String conversationId;
    private String userId;

    @Builder.Default
    private int webSearchResultLimit = 10;

    @Builder.Default
    private List<String> requiredToolNames = new ArrayList<>();

    private boolean requireBoundToolCall;

    private Integer maxSteps;
    private Integer maxToolCalls;

    @Builder.Default
    private Long timeoutMs = DEFAULT_TIMEOUT_MS;

    @Builder.Default
    private Map<String, Object> attributes = new LinkedHashMap<>();
}
