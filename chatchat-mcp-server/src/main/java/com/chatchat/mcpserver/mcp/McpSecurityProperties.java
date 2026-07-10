package com.chatchat.mcpserver.mcp;

import com.chatchat.common.security.InternalCredentialProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "chatchat.mcp.security")
public class McpSecurityProperties {

    private boolean requireMcpToken = true;
    private String invocationToken = "";
    private String encryptedInvocationToken = "";

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

    public String getEncryptedInvocationToken() {
        return encryptedInvocationToken;
    }

    public void setEncryptedInvocationToken(String encryptedInvocationToken) {
        this.encryptedInvocationToken = encryptedInvocationToken;
    }

    public boolean matchesInvocationToken(String token) {
        return matchesInvocationToken(token, null);
    }

    public boolean matchesInvocationToken(String token, InternalCredentialProperties internalCredentialProperties) {
        String configuredToken = resolvedInvocationToken(internalCredentialProperties);
        return token != null
            && !token.isBlank()
            && configuredToken != null
            && !configuredToken.isBlank()
            && configuredToken.trim().equals(token.trim());
    }

    public String resolvedInvocationToken(InternalCredentialProperties internalCredentialProperties) {
        if (internalCredentialProperties == null) {
            return invocationToken == null ? "" : invocationToken.trim();
        }
        String resolved = internalCredentialProperties.resolveSecret(encryptedInvocationToken, invocationToken);
        return resolved.isBlank() ? internalCredentialProperties.resolvedSecret() : resolved;
    }
}
