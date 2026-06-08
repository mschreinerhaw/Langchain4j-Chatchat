package com.chatchat.mcpserver.database;

import io.modelcontextprotocol.server.McpSyncServer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
@RequiredArgsConstructor
public class DatabaseQueryMcpToolPublisher {

    private final McpSyncServer mcpSyncServer;
    private final DatabaseQueryConfigService configService;
    private final DatabaseQueryToolSpecFactory toolSpecFactory;
    private final Set<String> managedToolNames = ConcurrentHashMap.newKeySet();

    @Order(Ordered.LOWEST_PRECEDENCE)
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        refresh();
    }

    public synchronized void refresh() {
        managedToolNames.forEach(toolName -> {
            try {
                mcpSyncServer.removeTool(toolName);
            } catch (Exception ex) {
                log.debug("Failed to remove old database query MCP tool {}: {}", toolName, ex.getMessage());
            }
        });
        managedToolNames.clear();

        for (DatabaseQueryConfig config : configService.listEnabled()) {
            try {
                mcpSyncServer.addTool(toolSpecFactory.toToolSpecification(config));
                managedToolNames.add(config.getToolName());
            } catch (Exception ex) {
                log.warn("Skip database query MCP tool {}: {}", config.getToolName(), ex.getMessage());
            }
        }

        mcpSyncServer.notifyToolsListChanged();
        log.info("Database query MCP tools refreshed, registered {}", managedToolNames.size());
    }
}
