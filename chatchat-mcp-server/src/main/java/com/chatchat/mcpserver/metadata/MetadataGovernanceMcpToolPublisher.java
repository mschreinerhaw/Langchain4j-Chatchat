package com.chatchat.mcpserver.metadata;

import io.modelcontextprotocol.server.McpSyncServer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class MetadataGovernanceMcpToolPublisher {

    public static final String RETIRED_ANNOTATE_TOOL = "enterprise_metadata_annotate_ddl";
    public static final String RETIRED_COMPARE_TOOL = "enterprise_metadata_compare";

    private final McpSyncServer mcpSyncServer;
    private final EnterpriseMetadataProperties properties;

    @Order(Ordered.LOWEST_PRECEDENCE)
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        if (properties.isEnabled()) refresh();
    }

    public synchronized void refresh() {
        remove(RETIRED_ANNOTATE_TOOL);
        remove(RETIRED_COMPARE_TOOL);
        mcpSyncServer.notifyToolsListChanged();
        log.info("Retired enterprise metadata MCP tools removed tools=[{}, {}]",
            RETIRED_ANNOTATE_TOOL, RETIRED_COMPARE_TOOL);
    }

    private void remove(String toolName) {
        try {
            mcpSyncServer.removeTool(toolName);
        } catch (Exception ex) {
            log.debug("Metadata governance MCP tool {} was not registered: {}", toolName, ex.getMessage());
        }
    }

}
