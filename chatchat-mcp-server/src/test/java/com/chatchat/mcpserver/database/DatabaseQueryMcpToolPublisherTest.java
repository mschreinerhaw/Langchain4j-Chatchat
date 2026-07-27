package com.chatchat.mcpserver.database;

import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DatabaseQueryMcpToolPublisherTest {

    @Test
    void refreshPublishesEveryEnabledDatabaseQueryAsSpecializedMcpTool() {
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
            new DatabaseQueryMcpNamingPolicy()
        );

        publisher.refresh();

        verify(mcpSyncServer).addTool(validationSpec);
        verify(mcpSyncServer).addTool(marketSpec);
        verify(mcpSyncServer).notifyToolsListChanged();
    }

    private DatabaseQueryConfig config(String toolName, String category) {
        DatabaseQueryConfig config = new DatabaseQueryConfig();
        config.setToolName(toolName);
        config.setCapabilityCategory(category);
        config.setEnabled(true);
        return config;
    }
}
