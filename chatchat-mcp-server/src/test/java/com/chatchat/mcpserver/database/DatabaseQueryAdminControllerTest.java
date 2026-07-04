package com.chatchat.mcpserver.database;

import com.chatchat.agents.tool.ToolRegistry;
import com.chatchat.common.response.ApiResponse;
import com.chatchat.common.tool.ToolOutput;
import com.chatchat.mcpserver.search.McpTemplateLuceneIndexService;
import com.chatchat.mcpserver.sql.SqlDatasourceConfig;
import com.chatchat.mcpserver.sql.SqlDatasourceConfigService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DatabaseQueryAdminControllerTest {

    private final ToolRegistry toolRegistry = mock(ToolRegistry.class);
    private final DatabaseQueryConfigService configService = mock(DatabaseQueryConfigService.class);
    private final DatabaseQueryInvokeService invokeService = mock(DatabaseQueryInvokeService.class);
    private final DatabaseQueryMcpToolPublisher publisher = mock(DatabaseQueryMcpToolPublisher.class);
    private final McpTemplateLuceneIndexService templateIndexService = mock(McpTemplateLuceneIndexService.class);
    private final SqlDatasourceConfigService datasourceConfigService = mock(SqlDatasourceConfigService.class);
    private final DatabaseQueryAdminController controller = new DatabaseQueryAdminController(
        toolRegistry,
        configService,
        invokeService,
        publisher,
        templateIndexService,
        new ObjectMapper(),
        datasourceConfigService
    );

    @Test
    void testRequestPassesDatasourceDatabaseTypeToInvoker() {
        when(toolRegistry.hasTool("database_query")).thenReturn(true);
        when(datasourceConfigService.getEnabled("asset-dm")).thenReturn(dmDatasource());
        when(invokeService.invoke(org.mockito.ArgumentMatchers.anyMap()))
            .thenReturn(ToolOutput.success(Map.of("ok", true)));
        DatabaseQueryAdminController.DatabaseQueryTestRequest request =
            new DatabaseQueryAdminController.DatabaseQueryTestRequest(
                "SELECT 1",
                Map.of(),
                1,
                75,
                "asset-dm",
                null,
                null,
                null,
                null,
                false
            );

        ApiResponse<ToolOutput> response = controller.test(request);

        ArgumentCaptor<Map<String, Object>> parametersCaptor = ArgumentCaptor.forClass(Map.class);
        verify(invokeService).invoke(parametersCaptor.capture());
        Map<String, Object> parameters = parametersCaptor.getValue();
        assertThat(response.getCode()).isEqualTo(200);
        assertThat(parameters).containsEntry("database_type", "dm");
        assertThat(parameters).containsEntry("driver_class", "dm.jdbc.driver.DmDriver");
        assertThat(parameters).containsEntry("jdbc_url", "jdbc:dm://192.168.195.221:5236");
        assertThat(parameters).containsEntry("datasource_id", "asset-dm");
        assertThat(parameters).containsEntry("timeoutSeconds", 75);
        assertThat(parameters).containsEntry("timeout_seconds", 75);
    }

    private SqlDatasourceConfig dmDatasource() {
        SqlDatasourceConfig config = new SqlDatasourceConfig();
        config.setId("asset-dm");
        config.setName("DM datasource");
        config.setEnabled(true);
        config.setJdbcUrl("jdbc:dm://192.168.195.221:5236");
        config.setDriverClass("dm.jdbc.driver.DmDriver");
        config.setDatabaseType("dm");
        config.setUsername("GDP");
        config.setPassword("secret");
        return config;
    }
}
