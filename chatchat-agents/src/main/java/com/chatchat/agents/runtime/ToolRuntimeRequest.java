package com.chatchat.agents.runtime;

import com.chatchat.common.tool.ToolInput;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolRuntimeRequest {

    private String toolName;
    private String runtimeMode;
    private String requestId;
    private String conversationId;
    private String tenantId;
    private String userId;

    @Builder.Default
    private List<String> allowedTools = new ArrayList<>();

    private ToolInput toolInput;

    @Builder.Default
    private Map<String, Object> attributes = Map.of();
}
