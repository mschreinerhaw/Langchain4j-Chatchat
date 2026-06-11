package com.chatchat.agents.runtime;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "mcp-policy")
public class McpPolicyProperties {

    private boolean enabled = true;

    private Map<String, String> riskPolicy = new LinkedHashMap<>(Map.of(
        "low", ToolRuntimeAction.AUTO_EXECUTE.code(),
        "medium", ToolRuntimeAction.ASK_BEFORE_EXECUTE.code(),
        "high", ToolRuntimeAction.ASK_BEFORE_EXECUTE.code(),
        "forbidden", ToolRuntimeAction.DENY.code()
    ));

    private Map<String, String> toolPolicy = new LinkedHashMap<>();

    private Map<String, Map<String, String>> parameterPolicy = new LinkedHashMap<>();
}
