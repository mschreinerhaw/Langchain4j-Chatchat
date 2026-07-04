package com.chatchat.mcpserver.database;

import com.chatchat.agents.tool.ToolRegistry;
import com.chatchat.common.tool.ToolInput;
import com.chatchat.common.tool.ToolOutput;
import com.chatchat.mcpserver.audit.InvocationAuditService;
import com.chatchat.mcpserver.sql.SqlDatasourceConfig;
import com.chatchat.mcpserver.sql.SqlDatasourceConfigService;
import com.chatchat.mcpserver.sql.SqlScriptExecuteService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DatabaseQueryInvokeServiceTest {

    private final ToolRegistry toolRegistry = mock(ToolRegistry.class);
    private final SqlDatasourceConfigService datasourceConfigService = mock(SqlDatasourceConfigService.class);
    private final SqlScriptExecuteService scriptExecuteService = mock(SqlScriptExecuteService.class);
    private final InvocationAuditService auditService = mock(InvocationAuditService.class);
    private final DatabaseQueryInvokeService service = new DatabaseQueryInvokeService(
        toolRegistry,
        datasourceConfigService,
        scriptExecuteService,
        new ObjectMapper(),
        auditService
    );

    @Test
    void passesDatasourceDatabaseTypeToDatabaseQueryTool() {
        when(toolRegistry.hasTool("database_query")).thenReturn(true);
        when(datasourceConfigService.getEnabled("asset-dm")).thenReturn(dmDatasource());
        when(toolRegistry.executeEnhancedTool(org.mockito.ArgumentMatchers.eq("database_query"),
            org.mockito.ArgumentMatchers.any(ToolInput.class))).thenReturn(ToolOutput.success(Map.of("ok", true)));
        DatabaseQueryConfig config = new DatabaseQueryConfig();
        config.setId("query-dm");
        config.setToolName("db_query_dm");
        config.setTitle("DM query");
        config.setDatasourceId("asset-dm");
        config.setSqlTemplate("SELECT 1");
        config.setMaxRows(1);
        config.setTimeoutSeconds(90);

        ToolOutput output = service.invoke(config, Map.of());

        ArgumentCaptor<ToolInput> inputCaptor = ArgumentCaptor.forClass(ToolInput.class);
        verify(toolRegistry).executeEnhancedTool(org.mockito.ArgumentMatchers.eq("database_query"), inputCaptor.capture());
        Map<String, Object> parameters = inputCaptor.getValue().getParameters();
        assertThat(output.isSuccess()).isTrue();
        assertThat(parameters).containsEntry("database_type", "dm");
        assertThat(parameters).containsEntry("driver_class", "dm.jdbc.driver.DmDriver");
        assertThat(parameters).containsEntry("jdbc_url", "jdbc:dm://192.168.195.221:5236");
        assertThat(parameters).containsEntry("datasource_id", "asset-dm");
        assertThat(parameters).containsEntry("timeoutSeconds", 90);
        assertThat(parameters).containsEntry("timeout_seconds", 90);
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
