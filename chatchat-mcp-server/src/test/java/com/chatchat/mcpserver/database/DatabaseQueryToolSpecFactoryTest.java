package com.chatchat.mcpserver.database;

import com.chatchat.mcpserver.tool.AgentRuntimeGovernanceFactory;
import com.chatchat.mcpserver.tool.McpToolConcurrencyManager;
import com.chatchat.mcpserver.tool.StandardToolExecutionResultFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.server.McpServerFeatures;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DatabaseQueryToolSpecFactoryTest {

    @Test
    void toolDescriptionIncludesBusinessGroupContextForModelSelection() {
        DatabaseQueryConfig config = new DatabaseQueryConfig();
        config.setId("query-1");
        config.setToolName("query_fund_nav_check");
        config.setTitle("Fund NAV check");
        config.setDescription("Read fund NAV reconciliation rows.");
        config.setBusinessGroup("fund_nav");
        config.setBusinessGroupName("Fund NAV reconciliation");
        config.setBusinessGroupDescription("Business queries for cross-channel fund NAV consistency analysis");

        AgentRuntimeGovernanceFactory governanceFactory = mock(AgentRuntimeGovernanceFactory.class);
        McpToolConcurrencyManager concurrencyManager = mock(McpToolConcurrencyManager.class);
        when(governanceFactory.metaForDatabaseQuery(any())).thenReturn(Map.of());
        when(concurrencyManager.limitMeta(anyString(), anyString())).thenReturn(Map.of());
        DatabaseQueryToolSpecFactory factory = new DatabaseQueryToolSpecFactory(
            mock(DatabaseQueryInvokeService.class),
            new ObjectMapper(),
            governanceFactory,
            concurrencyManager,
            mock(StandardToolExecutionResultFactory.class)
        );

        McpServerFeatures.SyncToolSpecification spec = factory.toToolSpecification(config);

        assertThat(spec.tool().description())
            .contains("Read fund NAV reconciliation rows.")
            .contains("Fund NAV reconciliation")
            .contains("fund_nav")
            .contains("cross-channel fund NAV consistency analysis");
    }
}
