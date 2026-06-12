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
@ConfigurationProperties(prefix = "chatchat.tool-runtime")
public class ToolRuntimeProperties {

    private boolean enforceAllowedTools = true;
    private boolean enforceAuthentication = true;
    private int defaultMaxCallsPerMinute = 0;
    private int circuitBreakerFailureThreshold = 3;
    private int circuitBreakerOpenSeconds = 60;
    private int topToolLimit = 6;
    private String defaultRuntimeLevel = "readonly";
    private Map<String, String> levelPolicy = new LinkedHashMap<>();
}
