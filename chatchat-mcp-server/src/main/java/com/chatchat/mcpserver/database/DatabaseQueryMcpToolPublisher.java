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
        remove("database_query_execute");
        managedToolNames.forEach(this::remove);
        managedToolNames.clear();

        for (DatabaseQueryConfig config : configService.listEnabled()) {
            if (config != null && config.getToolName() != null && !config.getToolName().isBlank()) {
                remove(config.getToolName());
            }
        }

        mcpSyncServer.notifyToolsListChanged();
        log.info("Database query per-template MCP publishing disabled; use business_query_template_search + sql_query_execute");
    }

    private void remove(String toolName) {
        try {
            mcpSyncServer.removeTool(toolName);
        } catch (Exception ex) {
            log.debug("Database query MCP tool {} was not registered: {}", toolName, ex.getMessage());
        }
    }
}
