package com.chatchat.integration.mcp.config;

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

    private String adminLoginPath = "/api/v1/admin/auth/login";

    private String serviceListPath = "/api/v1/mcp-services";

    private String mcpEndpoint = "/mcp";

    private String standaloneServiceId = "chatchat-mcp-server";

    private String standaloneServiceName = "ChatChat MCP Server";

    private String invocationToken;

    private int timeoutMs = 0;

    private boolean importStandaloneServer = true;
}
