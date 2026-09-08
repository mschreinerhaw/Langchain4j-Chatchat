package com.chatchat.mcpserver.metadata.tool;

import com.chatchat.mcpserver.metadata.config.EnterpriseMetadataProperties;

import io.modelcontextprotocol.server.McpSyncServer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class MetadataGovernanceMcpToolPublisher implements com.chatchat.mcpserver.tool.McpToolContributor {

    public static final String RETIRED_ANNOTATE_TOOL = "enterprise_metadata_annotate_ddl";
    public static final String RETIRED_COMPARE_TOOL = "enterprise_metadata_compare";

    private final McpSyncServer mcpSyncServer;
    private final EnterpriseMetadataProperties properties;

    @Order(Ordered.LOWEST_PRECEDENCE)
    public synchronized void refresh() {
        refreshPublication();
        log.info("Retired enterprise metadata MCP tools removed tools=[{}, {}]",
            RETIRED_ANNOTATE_TOOL, RETIRED_COMPARE_TOOL);
    }

    @Override public String contributorId() { return "metadata_governance_legacy"; }
    @Override public McpSyncServer publicationServer() { return mcpSyncServer; }
    @Override public java.util.List<com.chatchat.mcpserver.tool.ToolPublication> contribute() {
        return java.util.List.of();
    }
    @Override public java.util.Set<String> retiredToolNames() {
        return java.util.Set.of(RETIRED_ANNOTATE_TOOL, RETIRED_COMPARE_TOOL);
    }

}
