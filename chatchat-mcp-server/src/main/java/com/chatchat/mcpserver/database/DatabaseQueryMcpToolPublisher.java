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
        managedToolNames.forEach(this::remove);
        managedToolNames.clear();

        mcpSyncServer.notifyToolsListChanged();
        log.info("Database query per-template MCP publishing disabled; use business_query_template_search and follow its execution binding (sql_script_execute for DAG/multi-SQL templates)");
    }

    private void remove(String toolName) {
        try {
            mcpSyncServer.removeTool(toolName);
        } catch (Exception ex) {
            log.debug("Database query MCP tool {} was not registered: {}", toolName, ex.getMessage());
        }
    }
}
