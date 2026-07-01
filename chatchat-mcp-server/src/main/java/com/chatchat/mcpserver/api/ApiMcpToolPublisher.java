package com.chatchat.mcpserver.api;

import io.modelcontextprotocol.server.McpSyncServer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
@RequiredArgsConstructor
public class ApiMcpToolPublisher {

    private final McpSyncServer mcpSyncServer;
    private final Set<String> managedToolNames = ConcurrentHashMap.newKeySet();

    /**
     * Performs the on application ready operation.
     */
    @Order(Ordered.LOWEST_PRECEDENCE)
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        refresh();
    }

    /**
     * Performs the refresh operation.
     */
    public synchronized void refresh() {
        managedToolNames.forEach(toolName -> {
            try {
                mcpSyncServer.removeTool(toolName);
            } catch (Exception ex) {
                log.debug("Failed to remove old API MCP tool {}: {}", toolName, ex.getMessage());
            }
        });
        managedToolNames.clear();

        mcpSyncServer.notifyToolsListChanged();
        log.info("API MCP per-service tool publishing disabled; use api_template_query");
    }
}
