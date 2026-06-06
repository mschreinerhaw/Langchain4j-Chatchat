package com.chatchat.mcpserver.api;

import io.modelcontextprotocol.server.McpSyncServer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
@RequiredArgsConstructor
public class ApiMcpToolPublisher {

    private final McpSyncServer mcpSyncServer;
    private final ApiServiceConfigService configService;
    private final ApiToolSpecFactory toolSpecFactory;
    private final Set<String> managedToolNames = ConcurrentHashMap.newKeySet();

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        refresh();
    }

    public synchronized void refresh() {
        managedToolNames.forEach(toolName -> {
            try {
                mcpSyncServer.removeTool(toolName);
            } catch (Exception ex) {
                log.debug("Failed to remove old API MCP tool {}: {}", toolName, ex.getMessage());
            }
        });
        managedToolNames.clear();

        for (ApiServiceConfig config : configService.listEnabled()) {
            try {
                mcpSyncServer.addTool(toolSpecFactory.toToolSpecification(config));
                managedToolNames.add(config.getToolName());
            } catch (Exception ex) {
                log.warn("Skip API MCP tool {}: {}", config.getToolName(), ex.getMessage());
            }
        }

        mcpSyncServer.notifyToolsListChanged();
        log.info("API MCP tools refreshed, registered {}", managedToolNames.size());
    }
}
