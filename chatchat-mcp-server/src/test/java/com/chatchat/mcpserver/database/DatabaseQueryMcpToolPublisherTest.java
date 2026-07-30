package com.chatchat.mcpserver.database;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DatabaseQueryMcpToolPublisherTest {

    @Test
    void refreshKeepsMarketDataInTemplateIndexAndPublishesOtherCategories() {
        McpSyncServer mcpSyncServer = mock(McpSyncServer.class);
        DatabaseQueryConfigService configService = mock(DatabaseQueryConfigService.class);
        DatabaseQueryToolSpecFactory toolSpecFactory = mock(DatabaseQueryToolSpecFactory.class);
        DatabaseQueryConfig validation = config("validate_customer_asset", "data_validation");
        DatabaseQueryConfig market = config("query_bond_yield", "market_data");
        McpServerFeatures.SyncToolSpecification validationSpec =
            mock(McpServerFeatures.SyncToolSpecification.class);
        McpServerFeatures.SyncToolSpecification marketSpec =
            mock(McpServerFeatures.SyncToolSpecification.class);
        when(configService.listEnabled()).thenReturn(List.of(validation, market));
        when(toolSpecFactory.toToolSpecification(validation)).thenReturn(validationSpec);
        when(toolSpecFactory.toToolSpecification(market)).thenReturn(marketSpec);
        DatabaseQueryMcpToolPublisher publisher = new DatabaseQueryMcpToolPublisher(
            mcpSyncServer,
            configService,
            toolSpecFactory,
            new DatabaseQueryMcpNamingPolicy(),
            new ObjectMapper()
        );

        publisher.refresh();

        verify(mcpSyncServer).addTool(validationSpec);
        verify(mcpSyncServer, never()).addTool(marketSpec);
        verify(toolSpecFactory, never()).toToolSpecification(market);
        verify(mcpSyncServer).notifyToolsListChanged();
    }

    @Test
    void explicitGatewayModeKeepsAnyCategoryIndexOnly() {
        DatabaseQueryMcpToolPublisher publisher = new DatabaseQueryMcpToolPublisher(
            mock(McpSyncServer.class),
            mock(DatabaseQueryConfigService.class),
            mock(DatabaseQueryToolSpecFactory.class),
            new DatabaseQueryMcpNamingPolicy(),
            new ObjectMapper()
        );
        DatabaseQueryConfig config = config("validate_customer_asset", "data_validation");
        config.setGovernanceJson("""
            {"publicationMode":"template_via_execution_gateway"}
            """);

        org.assertj.core.api.Assertions.assertThat(publisher.publishAsDedicatedTool(config)).isFalse();
    }

    private DatabaseQueryConfig config(String toolName, String category) {
        DatabaseQueryConfig config = new DatabaseQueryConfig();
        config.setToolName(toolName);
        config.setCapabilityCategory(category);
        config.setEnabled(true);
        return config;
    }
}
