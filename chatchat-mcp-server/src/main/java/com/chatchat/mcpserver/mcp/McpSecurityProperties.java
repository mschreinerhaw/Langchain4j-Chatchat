package com.chatchat.mcpserver.mcp;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "chatchat.mcp.security")
public class McpSecurityProperties {

    private boolean requireMcpToken = true;
    private String invocationToken = "";

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

    public String getInvocationToken() {
        return invocationToken;
    }

    public void setInvocationToken(String invocationToken) {
        this.invocationToken = invocationToken;
    }

    public boolean matchesInvocationToken(String token) {
        return token != null
            && !token.isBlank()
            && invocationToken != null
            && !invocationToken.isBlank()
            && invocationToken.trim().equals(token.trim());
    }
}
