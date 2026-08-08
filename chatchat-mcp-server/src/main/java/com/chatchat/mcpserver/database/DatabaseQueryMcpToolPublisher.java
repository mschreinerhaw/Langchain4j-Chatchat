package com.chatchat.mcpserver.database;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.server.McpSyncServer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
@RequiredArgsConstructor
public class DatabaseQueryMcpToolPublisher {

    static final String UNIFIED_EXECUTION_MODE = "template_via_execution_gateway";
    static final String MARKET_DATA_CATEGORY = "market_data";

    private final McpSyncServer mcpSyncServer;
    private final DatabaseQueryConfigService configService;
    private final DatabaseQueryToolSpecFactory toolSpecFactory;
    private final DatabaseQueryMcpNamingPolicy namingPolicy;
    private final ObjectMapper objectMapper;
    private final Set<String> managedToolNames = ConcurrentHashMap.newKeySet();

    @Order(Ordered.LOWEST_PRECEDENCE)
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        refresh();
    }

    public synchronized void refresh() {
        managedToolNames.forEach(this::remove);
        managedToolNames.clear();

        int published = 0;
        int indexedOnly = 0;
        for (DatabaseQueryConfig config : configService.listEnabled()) {
            if (!publishAsDedicatedTool(config)) {
                indexedOnly++;
                continue;
            }
            try {
                com.chatchat.mcpserver.tool.McpToolPublicationReviewer.addReviewedTool(
                    mcpSyncServer, toolSpecFactory.toToolSpecification(config));
                managedToolNames.add(namingPolicy.toolName(config));
                published++;
            } catch (Exception ex) {
                log.error("Database query specialized MCP tool publication failed tool={} category={}: {}",
                    config.getToolName(), config.getCapabilityCategory(), ex.getMessage(), ex);
            }
        }
        mcpSyncServer.notifyToolsListChanged();
        log.info("Database query MCP publication refreshed published={} templateIndexOnly={} tools={}",
            published, indexedOnly, managedToolNames.stream().sorted().toList());
    }

    boolean publishAsDedicatedTool(DatabaseQueryConfig config) {
        if (config == null) {
            return false;
        }
        String configuredMode = configuredPublicationMode(config.getGovernanceJson());
        if (UNIFIED_EXECUTION_MODE.equals(configuredMode)) {
            return false;
        }
        if (configuredMode != null) {
            return true;
        }
        return !MARKET_DATA_CATEGORY.equals(normalize(config.getCapabilityCategory()));
    }

    private String configuredPublicationMode(String governanceJson) {
        if (governanceJson == null || governanceJson.isBlank()) {
            return null;
        }
        try {
            JsonNode governance = objectMapper.readTree(governanceJson);
            for (String field : new String[]{"publicationMode", "publishMode"}) {
                JsonNode value = governance == null ? null : governance.get(field);
                if (value != null && value.isTextual() && !value.asText().isBlank()) {
                    return normalize(value.asText());
                }
            }
        } catch (Exception ex) {
            log.warn("Database query governance publication mode is invalid; keeping dedicated publication for compatibility: {}",
                ex.getMessage());
        }
        return null;
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim().toLowerCase(Locale.ROOT);
    }

    private void remove(String toolName) {
        try {
            mcpSyncServer.removeTool(toolName);
        } catch (Exception ex) {
            log.debug("Database query MCP tool {} was not registered: {}", toolName, ex.getMessage());
        }
    }
}
