package com.chatchat.integration.mcp.catalog;

import com.chatchat.common.mcp.catalog.McpToolCatalogQueryPort;
import com.chatchat.integration.mcp.service.McpServiceConfigService;
import com.chatchat.integration.mcp.service.McpToolRegistryBridge;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/** Integration projection adapter for read-only MCP catalog consumers. */
@Service
@RequiredArgsConstructor
public class DefaultMcpToolCatalogQueryAdapter implements McpToolCatalogQueryPort {
    private final McpServiceConfigService configService;
    private final McpToolRegistryBridge registryBridge;

    @Override
    public List<ServiceSummary> enabledServices() {
        return configService.listEnabled().stream()
            .map(service -> new ServiceSummary(service.getId(), service.getName(), service.isEnabled(),
                service.getUpdatedAt()))
            .toList();
    }

    @Override
    public List<RegisteredTool> registeredTools() {
        return registryBridge.listRegisteredTools().stream()
            .map(tool -> new RegisteredTool(tool.localToolName(), tool.serviceId(), tool.serviceName(),
                tool.remoteToolName(), tool.description(), tool.backendServiceType(), tool.category(),
                tool.categories(), tool.tags(), tool.applicability()))
            .toList();
    }
}
