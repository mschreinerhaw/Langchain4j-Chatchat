package com.chatchat.integration.mcp.service;

import com.chatchat.common.security.InternalCredentialProperties;
import com.chatchat.integration.mcp.config.McpCenterProperties;
import com.chatchat.integration.mcp.model.McpToolInvokeResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class McpGatewayClientTest {

    @Test
    void preservesExpiredLicenseErrorReturnedByMcpServer() {
        McpGatewayClient client = new McpGatewayClient(
            new ObjectMapper(),
            new McpCenterProperties(),
            new InternalCredentialProperties(),
            mock(McpStdioProxyService.class)
        );

        McpToolInvokeResult result = client.failureResult("""
            MCP HTTP status 403: {"success":false,"error":"MCP_LICENSE_EXPIRED",
            "message":"License 已过期，新的 MCP 工具调用已停止，请联系供应商续期",
            "licenseStatus":"EXPIRED","retryable":false,"action":"STOP"}
            """);

        assertThat(result.success()).isFalse();
        assertThat(result.errorCode()).isEqualTo("MCP_LICENSE_EXPIRED");
        assertThat(result.errorMessage()).isEqualTo("License 已过期，新的 MCP 工具调用已停止，请联系供应商续期");
        assertThat(result.retryable()).isFalse();
        assertThat(result.action()).isEqualTo("STOP");
    }
}
