package com.chatchat.integration.mcp.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Runtime properties for stdio MCP proxy.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "chatchat.mcp.stdio-proxy")
public class McpStdioProxyProperties {

    /**
     * Enable stdio proxy feature.
     */
    private boolean enabled = true;

    /**
     * Maximum concurrent stdio sessions.
     */
    private int maxSessions = 32;

    /**
     * Request timeout in milliseconds.
     */
    private long requestTimeoutMs = 20000;

    /**
     * Startup timeout in milliseconds.
     */
    private long startupTimeoutMs = 15000;

    /**
     * Session idle ttl in milliseconds.
     */
    private long idleTtlMs = 300000;

    /**
     * Cleanup interval in milliseconds.
     */
    private long cleanupIntervalMs = 30000;

    /**
     * Allowed command list. Empty means allow all.
     */
    private List<String> commandAllowList = new ArrayList<>();
}
