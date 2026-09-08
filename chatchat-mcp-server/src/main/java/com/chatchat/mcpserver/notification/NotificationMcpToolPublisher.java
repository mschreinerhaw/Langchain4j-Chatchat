package com.chatchat.mcpserver.notification;

import io.modelcontextprotocol.server.McpSyncServer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationMcpToolPublisher implements com.chatchat.mcpserver.tool.McpToolContributor {

    private final McpSyncServer mcpSyncServer;
    private final NotificationChannelConfigService configService;
    private final NotificationToolSpecFactory toolSpecFactory;
    private final Set<String> managedToolNames = ConcurrentHashMap.newKeySet();

    @Value("${chatchat.mcp.notifications.enabled:true}")
    private boolean notificationsEnabled = true;

    public synchronized void refresh() {
        com.chatchat.mcpserver.tool.McpToolPublicationPipeline.PublicationResult result = refreshPublication();
        managedToolNames.clear();
        managedToolNames.addAll(result.publishedTools());
        log.info("Notification MCP tools refreshed, registered {}", managedToolNames.size());
    }

    @Override public String contributorId() { return "notifications"; }
    @Override public McpSyncServer publicationServer() { return mcpSyncServer; }
    @Override public List<com.chatchat.mcpserver.tool.ToolPublication> contribute() {
        if (!notificationsEnabled) return List.of();
        return configService.listEnabled().stream().map(toolSpecFactory::toToolSpecification)
            .map(com.chatchat.mcpserver.tool.ToolPublication::from).toList();
    }
    @Override public Set<String> retiredToolNames() { return Set.copyOf(managedToolNames); }
}
