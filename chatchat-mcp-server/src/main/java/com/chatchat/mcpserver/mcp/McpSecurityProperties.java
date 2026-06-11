package com.chatchat.mcpserver.mcp;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "chatchat.mcp.security")
public class McpSecurityProperties {

    private boolean requireMcpToken = false;

    /**
     * Returns whether is require mcp token.
     *
     * @return whether the condition is satisfied
     */
    public boolean isRequireMcpToken() {
        return requireMcpToken;
    }

    /**
     * Sets the require mcp token.
     *
     * @param requireMcpToken the require mcp token value
     */
    public void setRequireMcpToken(boolean requireMcpToken) {
        this.requireMcpToken = requireMcpToken;
    }
}
