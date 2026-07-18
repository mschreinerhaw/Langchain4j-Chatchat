package com.chatchat.mcpserver.notification;

import io.modelcontextprotocol.server.McpSyncServer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
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
public class NotificationMcpToolPublisher {

    private final McpSyncServer mcpSyncServer;
    private final NotificationChannelConfigService configService;
    private final NotificationToolSpecFactory toolSpecFactory;
    private final Set<String> managedToolNames = ConcurrentHashMap.newKeySet();

    @Value("${chatchat.mcp.notifications.enabled:true}")
    private boolean notificationsEnabled = true;

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
                log.debug("Failed to remove old notification MCP tool {}: {}", toolName, ex.getMessage());
            }
        });
        managedToolNames.clear();

        if (!notificationsEnabled) {
            mcpSyncServer.notifyToolsListChanged();
            log.info("Notification/alert MCP tool publishing is disabled");
            return;
        }

        for (NotificationChannelConfig config : configService.listEnabled()) {
            try {
                mcpSyncServer.addTool(toolSpecFactory.toToolSpecification(config));
                managedToolNames.add(config.getToolName());
            } catch (Exception ex) {
                log.warn("Skip notification MCP tool {}: {}", config.getToolName(), ex.getMessage());
            }
        }
        mcpSyncServer.notifyToolsListChanged();
        log.info("Notification MCP tools refreshed, registered {}", managedToolNames.size());
    }
}
