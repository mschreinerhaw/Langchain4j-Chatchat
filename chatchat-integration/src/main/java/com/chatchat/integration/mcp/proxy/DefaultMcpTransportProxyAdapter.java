package com.chatchat.integration.mcp.proxy;

import com.chatchat.common.mcp.proxy.McpTransportProxyPort;
import com.chatchat.integration.mcp.entity.McpServiceConfig;
import com.chatchat.integration.mcp.service.McpServiceConfigService;
import com.chatchat.integration.mcp.service.McpStdioProxyService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Map;

/** Resolves persisted transport configuration and owns stdio proxy lifecycle. */
@Service
@RequiredArgsConstructor
public class DefaultMcpTransportProxyAdapter implements McpTransportProxyPort {
    private static final String PROTOCOL_STDIO_PROXY = "mcp_stdio_proxy";

    private final McpServiceConfigService configService;
    private final McpStdioProxyService stdioProxyService;

    @Override
    public Map<String, Object> forward(String serviceId, Map<String, Object> request) {
        McpServiceConfig config = configService.getById(serviceId);
        if (!PROTOCOL_STDIO_PROXY.equalsIgnoreCase(config.getProtocol())) {
            throw new IllegalArgumentException("service protocol is not mcp_stdio_proxy");
        }
        return stdioProxyService.forwardJsonRpc(config, request);
    }

    @Override
    public void closeSession(String serviceId) {
        stdioProxyService.closeSession(serviceId);
    }
}
