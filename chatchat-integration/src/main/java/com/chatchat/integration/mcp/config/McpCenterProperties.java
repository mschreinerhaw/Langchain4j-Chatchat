package com.chatchat.integration.mcp.config;

import com.chatchat.common.security.InternalCredentialProperties;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "chatchat.mcp.center")
public class McpCenterProperties {

    private boolean enabled = true;

    private String baseUrl;

    private String adminUsername;

    private String adminPassword;

    private String encryptedAdminPassword;

    private String adminLoginPath = "/api/v1/admin/auth/login";

    private String serviceListPath = "/api/v1/mcp-services";

    private String mcpEndpoint = "/mcp";

    private String standaloneServiceId = "chatchat-mcp-server";

    private String standaloneServiceName = "ChatChat MCP Server";

    private String invocationToken;

    private String encryptedInvocationToken;

    private int timeoutMs = 0;

    private boolean importStandaloneServer = true;

    public String resolvedAdminUsername(InternalCredentialProperties internalCredentialProperties) {
        String configured = text(adminUsername);
        if (!configured.isBlank()) {
            return configured;
        }
        return internalCredentialProperties == null ? "" : internalCredentialProperties.resolvedUsername();
    }

    public String resolvedAdminPassword(InternalCredentialProperties internalCredentialProperties) {
        if (internalCredentialProperties == null) {
            return text(adminPassword);
        }
        String resolved = internalCredentialProperties.resolveSecret(encryptedAdminPassword, adminPassword);
        return resolved.isBlank() ? internalCredentialProperties.resolvedSecret() : resolved;
    }

    public String resolvedInvocationToken(InternalCredentialProperties internalCredentialProperties) {
        if (internalCredentialProperties == null) {
            return text(invocationToken);
        }
        String resolved = internalCredentialProperties.resolveSecret(encryptedInvocationToken, invocationToken);
        return resolved.isBlank() ? internalCredentialProperties.resolvedSecret() : resolved;
    }

    private String text(String value) {
        return value == null || value.isBlank() ? "" : value.trim();
    }
}
