package com.chatchat.mcpserver.database.admin;

import com.chatchat.mcpserver.database.definition.DatabaseQueryConfigService;
import com.chatchat.mcpserver.database.definition.DatabaseQueryConfig;
import com.chatchat.mcpserver.database.execution.DatabaseQueryInvokeService;
import com.chatchat.mcpserver.database.execution.DatabaseQuerySourceAdapterService;
import com.chatchat.mcpserver.database.publication.DatabaseQueryPublicationService;

import com.chatchat.agents.tool.ToolRegistry;
import com.chatchat.common.response.ApiResponse;
import com.chatchat.common.tool.ToolOutput;
import com.chatchat.mcpserver.sql.calendar.DynamicDateParamService;
import com.chatchat.mcpserver.sql.datasource.SqlDatasourceConfig;
import com.chatchat.mcpserver.sql.datasource.SqlDatasourceConfigService;
import com.chatchat.tools.builtin.DynamicJdbcDriverLoader;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.test.util.ReflectionTestUtils;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;

class DatabaseQueryAdminControllerTest {

    private final ToolRegistry toolRegistry = mock(ToolRegistry.class);
    private final DatabaseQueryConfigService configService = mock(DatabaseQueryConfigService.class);
    private final DatabaseQueryInvokeService invokeService = mock(DatabaseQueryInvokeService.class);
    private final DatabaseQueryPublicationService publicationService = mock(DatabaseQueryPublicationService.class);
    private final SqlDatasourceConfigService datasourceConfigService = mock(SqlDatasourceConfigService.class);
    private final DynamicDateParamService dynamicDateParamService = new DynamicDateParamService(mock(DynamicJdbcDriverLoader.class));
    private final DatabaseQueryAdminController controller = new DatabaseQueryAdminController(
        toolRegistry,
        configService,
        invokeService,
        publicationService,
        new ObjectMapper(),
        datasourceConfigService,
        dynamicDateParamService
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
                null,
                Map.of(),
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
        assertThat(parameters).containsKey("params");
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

    @Test
    void builtinFinancialDraftUsesGovernedPreviewWithoutJdbcAssetOrToolLookup() {
        DatabaseQuerySourceAdapterService adapters = mock(DatabaseQuerySourceAdapterService.class);
        ReflectionTestUtils.setField(controller, "sourceAdapters", adapters);
        when(invokeService.invokePreview(any(DatabaseQueryConfig.class), anyMap()))
            .thenReturn(ToolOutput.success(Map.of("rowCount", 1)));
        Map<String, Object> parameters = Map.of("curve", "国债收益率曲线");
        var request = new DatabaseQueryAdminController.DatabaseQueryTestRequest(
            "SELECT * FROM bond_yield_curve_daily WHERE curve_name = :curve", List.of(),
            Map.of("type", "object"), parameters, 12, 9, "builtin_financial_market",
            null, null, null, null, false);

        ApiResponse<ToolOutput> response = controller.test(request);

        ArgumentCaptor<DatabaseQueryConfig> draft = ArgumentCaptor.forClass(DatabaseQueryConfig.class);
        verify(invokeService).invokePreview(draft.capture(), org.mockito.ArgumentMatchers.eq(parameters));
        assertThat(response.getCode()).isEqualTo(200);
        assertThat(draft.getValue().getDatasourceId()).isEqualTo("builtin_financial_market");
        assertThat(draft.getValue().getSqlTemplate()).isEqualTo(request.sql());
        assertThat(draft.getValue().getInputSchemaJson()).contains("object");
        assertThat(draft.getValue().getMaxRows()).isEqualTo(12);
        assertThat(draft.getValue().getTimeoutSeconds()).isEqualTo(9);
        verifyNoInteractions(adapters, datasourceConfigService, toolRegistry);
    }

    @Test
    void builtinFinancialDraftRetainsPreviewDefaultsWithoutSqlSteps() {
        when(invokeService.invokePreview(any(DatabaseQueryConfig.class), anyMap()))
            .thenReturn(ToolOutput.success(Map.of()));
        var request = new DatabaseQueryAdminController.DatabaseQueryTestRequest(
            "SELECT observation_date FROM bond_yield_curve_daily", null, Map.of(), Map.of(),
            null, null, "builtin_financial_market", null, null, null, null, false);

        controller.test(request);

        ArgumentCaptor<DatabaseQueryConfig> draft = ArgumentCaptor.forClass(DatabaseQueryConfig.class);
        verify(invokeService).invokePreview(draft.capture(), anyMap());
        assertThat(draft.getValue().getMaxRows()).isEqualTo(50);
        assertThat(draft.getValue().getTimeoutSeconds()).isEqualTo(30);
        verifyNoInteractions(datasourceConfigService, toolRegistry);
    }

    @Test
    void templateParametersAreBoundBeforeDynamicDateResolution() {
        when(toolRegistry.hasTool("database_query")).thenReturn(true);
        when(datasourceConfigService.getEnabled("asset-dm")).thenReturn(dmDatasource());
        when(invokeService.invoke(anyMap())).thenReturn(ToolOutput.success(Map.of()));
        var request = new DatabaseQueryAdminController.DatabaseQueryTestRequest(
            "SELECT {{today}} AS provided_date", null, Map.of(), Map.of("today", "20260102"),
            1, 30, "asset-dm", null, null, null, null, false);

        controller.test(request);

        ArgumentCaptor<Map<String, Object>> parameters = ArgumentCaptor.forClass(Map.class);
        verify(invokeService).invoke(parameters.capture());
        assertThat(parameters.getValue()).containsEntry("sql", "SELECT :today AS provided_date");
        assertThat((Map<String, Object>) parameters.getValue().get("params")).containsEntry("today", "20260102");
    }
}
