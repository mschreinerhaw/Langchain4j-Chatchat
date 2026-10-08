package com.chatchat.mcpserver.datacapability;

import com.chatchat.mcpserver.datacapability.definition.*;
import com.chatchat.mcpserver.datacapability.execution.CapabilityExecutionService;
import com.chatchat.mcpserver.datacapability.publication.CapabilityMcpPublisher;
import com.chatchat.mcpserver.license.*;
import com.chatchat.mcpserver.tool.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.server.McpSyncServer;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CapabilityMcpPublisherTest {
    @Test void publishesOnlyEnabledMcpCapabilitiesAndRetiresUnpublishedTools() {
        var capabilities = mock(CapabilityService.class);
        var server = mock(McpSyncServer.class);
        var license = mock(McpLicenseService.class);
        var publisher = new CapabilityMcpPublisher(server, capabilities, mock(CapabilityExecutionService.class),
            mock(McpToolConcurrencyManager.class), license, new ObjectMapper());
        when(license.allowsModule("databaseMcp")).thenReturn(true);
        when(server.listTools()).thenReturn(List.of());
        when(capabilities.list(null)).thenReturn(List.of(definition("published_query", true, true),
            definition("disabled_query", false, true), definition("api_only_query", true, false)));
        var contributed = publisher.contribute();
        assertThat(contributed).extracting(ToolPublication::toolName).containsExactly("data_query_published_query");
        McpToolPublicationReviewer.review(contributed.get(0));
        publisher.refreshPublication();
        verify(server).addTool(org.mockito.ArgumentMatchers.any());
        when(capabilities.list(null)).thenReturn(List.of()); publisher.refreshPublication();
        verify(server).removeTool("data_query_published_query");
        when(license.allowsModule("databaseMcp")).thenReturn(false);
        assertThat(publisher.contribute()).isEmpty();
        var catalog = new McpAdminMenuCatalog(new ObjectMapper());
        assertThat(catalog.moduleForTool("data_query_published_query").orElseThrow().key()).isEqualTo("databaseMcp");
        assertThat(catalog.menuForPath("/api/v1/data-capabilities/calendar/days").orElseThrow().key()).isEqualTo("databaseMcp");
    }
    private CapabilityDefinition definition(String code, boolean enabled, boolean mcp) {
        return new CapabilityDefinition(code, "查询", "查询数据", CapabilityType.TRADING_CALENDAR, null, null,
            "isTradingDay", null, null, Map.of("market", "SSE"), 30, 100, enabled, true, mcp);
    }
}
