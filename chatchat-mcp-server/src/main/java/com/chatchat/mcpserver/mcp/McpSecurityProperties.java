package com.chatchat.mcpserver.mcp;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "chatchat.mcp.security")
public class McpSecurityProperties {

    private boolean requireMcpToken = false;

    public boolean isRequireMcpToken() {
        return requireMcpToken;
    }

    public void setRequireMcpToken(boolean requireMcpToken) {
        this.requireMcpToken = requireMcpToken;
    }
}
