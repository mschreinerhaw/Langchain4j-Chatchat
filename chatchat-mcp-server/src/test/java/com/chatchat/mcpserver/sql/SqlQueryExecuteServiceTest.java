package com.chatchat.mcpserver.sql;

import com.chatchat.mcpserver.audit.InvocationAuditService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SqlQueryExecuteServiceTest {

    private final SqlDatasourceConfigService datasourceConfigService = mock(SqlDatasourceConfigService.class);
    private final SqlTemplateService templateService = mock(SqlTemplateService.class);
    private final MetadataResolverService metadataResolverService = mock(MetadataResolverService.class);
    private final InvocationAuditService auditService = mock(InvocationAuditService.class);
    private final SqlQueryExecuteService service = new SqlQueryExecuteService(
        datasourceConfigService,
        new SqlSafetyService(),
        templateService,
        metadataResolverService,
        auditService,
        new ObjectMapper()
    );

    @Test
    void queryResultIncludesColumnMetadataCommentsAndMaskingGovernance() throws Exception {
        String jdbcUrl = "jdbc:h2:mem:sql_query_result;DB_CLOSE_DELAY=-1";
        try (var connection = DriverManager.getConnection(jdbcUrl, "sa", "");
             Statement statement = connection.createStatement()) {
            statement.execute("create table test_customer (id int primary key, name varchar(64), phone varchar(32))");
            statement.execute("comment on column test_customer.id is 'Customer ID'");
            statement.execute("comment on column test_customer.name is 'Customer Name'");
            statement.execute("comment on column test_customer.phone is 'Phone number'");
            statement.execute("insert into test_customer (id, name, phone) values (1, 'Alice', '13800000000')");
        }

        SqlDatasourceConfig datasource = new SqlDatasourceConfig();
        datasource.setId("ds-1");
        datasource.setName("customer-db");
        datasource.setToolName("sql_customer");
        datasource.setJdbcUrl(jdbcUrl);
        datasource.setUsername("sa");
        datasource.setPassword("");
        datasource.setDefaultMaxRows(10);
        datasource.setDefaultTimeoutSeconds(5);
        when(datasourceConfigService.getEnabled("ds-1")).thenReturn(datasource);

        SqlQueryResult result = service.execute(Map.of(
            "datasourceId", "ds-1",
            "sql", "select id, name, phone from test_customer",
            "maxRows", 10
        ));

        assertThat(result.success()).isTrue();
        assertThat(result.columns()).containsExactly("ID", "NAME", "PHONE");
        assertThat(result.rows()).hasSize(1);
        assertThat(result.rows().get(0)).containsEntry("PHONE", "***");
        assertThat(result.columnMetadata()).hasSize(3);
        assertThat(result.columnMetadata().get(0)).containsEntry("comment", "Customer ID");
        assertThat(result.columnMetadata().get(1)).containsEntry("comment", "Customer Name");
        assertThat(result.columnMetadata().get(2)).containsEntry("comment", "Phone number");
        assertThat(result.columnMetadata().get(2)).containsEntry("masked", true);
    }

    @Test
    void rejectsNonSqlTemplateIdAliasInsteadOfRunningParameterSql() {
        SqlDatasourceConfig datasource = new SqlDatasourceConfig();
        datasource.setId("ds-1");
        datasource.setName("customer-db");
        datasource.setToolName("sql_customer");
        datasource.setJdbcUrl("jdbc:h2:mem:unused");
        datasource.setDefaultMaxRows(10);
        datasource.setDefaultTimeoutSeconds(5);
        when(datasourceConfigService.getEnabled("ds-1")).thenReturn(datasource);
        when(templateService.render(eq("CHECK_CPU"), anyMap(), eq(datasource), anyMap()))
            .thenThrow(new IllegalArgumentException("SQL template not found or disabled: CHECK_CPU"));

        SqlQueryResult result = service.execute(Map.of(
            "datasourceId", "ds-1",
            "templateId", "CHECK_CPU",
            "parameters", Map.of("sql", "SELECT version()")
        ));

        assertThat(result.success()).isFalse();
        assertThat(result.errorMessage()).contains("SQL template not found or disabled: CHECK_CPU");
        assertThat(result.sql()).isNull();
    }

    @Test
    void templateExecutionEnrichesSchemaNameFromJdbcUrl() {
        SqlDatasourceConfig datasource = new SqlDatasourceConfig();
        datasource.setId("ds-1");
        datasource.setName("customer-db");
        datasource.setToolName("sql_customer");
        datasource.setJdbcUrl("jdbc:mysql://127.0.0.1:3306/bd?useSSL=false");
        datasource.setDefaultMaxRows(10);
        datasource.setDefaultTimeoutSeconds(5);
        when(datasourceConfigService.getEnabled("ds-1")).thenReturn(datasource);
        when(metadataResolverService.resolveTable(eq(datasource), eq("t_khtz_label"), eq(null)))
            .thenReturn(new TableResolution(
                "ds-1",
                "mysql",
                "t_khtz_label",
                null,
                "bd",
                "t_khtz_label",
                "resolved",
                1.0,
                List.of(new TableLocation("ds-1", "bd", "bd", "t_khtz_label", "BASE TABLE", null, 1.0)),
                1,
                false,
                null
            ));
        when(templateService.render(eq("MYSQL_TABLE_METADATA"), anyMap(), eq(datasource), anyMap()))
            .thenThrow(new IllegalArgumentException("stop after render"));

        SqlQueryResult result = service.execute(Map.of(
            "datasourceId", "ds-1",
            "templateId", "MYSQL_TABLE_METADATA",
            "parameters", Map.of("tableName", "t_khtz_label")
        ));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> parameters = ArgumentCaptor.forClass(Map.class);
        verify(templateService).render(eq("MYSQL_TABLE_METADATA"), parameters.capture(), eq(datasource), anyMap());
        assertThat(parameters.getValue())
            .containsEntry("tableName", "t_khtz_label")
            .containsEntry("schemaName", "bd")
            .containsEntry("databaseName", "bd");
        assertThat(result.diagnostics()).containsEntry("failureStage", "prepare");
        Map<?, ?> diagnosticParameters = (Map<?, ?>) result.diagnostics().get("templateParameters");
        Map<?, ?> diagnosticDatasource = (Map<?, ?>) result.diagnostics().get("datasource");
        assertThat(diagnosticParameters.get("schemaName")).isEqualTo("bd");
        assertThat(diagnosticDatasource.get("databaseType")).isEqualTo("mysql");
        assertThat(diagnosticDatasource.get("jdbcDatabase")).isEqualTo("bd");
    }

    @Test
    void tableMetadataResolutionRunsBeforeJdbcDefaultSchemaFallback() {
        SqlDatasourceConfig datasource = new SqlDatasourceConfig();
        datasource.setId("ds-1");
        datasource.setName("customer-db");
        datasource.setToolName("sql_customer");
        datasource.setJdbcUrl("jdbc:mysql://127.0.0.1:3306/tpcds?useSSL=false");
        datasource.setDefaultMaxRows(10);
        datasource.setDefaultTimeoutSeconds(5);
        when(datasourceConfigService.getEnabled("ds-1")).thenReturn(datasource);
        when(metadataResolverService.resolveTable(eq(datasource), eq("lbappdeploydetail"), eq(null)))
            .thenReturn(new TableResolution(
                "ds-1",
                "mysql",
                "lbappdeploydetail",
                null,
                "rdsm_ad",
                "lbappdeploydetail",
                "resolved",
                0.95,
                List.of(new TableLocation("ds-1", "rdsm_ad", "rdsm_ad", "lbappdeploydetail", "BASE TABLE", null, 0.95)),
                1,
                true,
                null
            ));
        when(templateService.render(eq("MYSQL_TABLE_METADATA"), anyMap(), eq(datasource), anyMap()))
            .thenThrow(new IllegalArgumentException("stop after render"));

        SqlQueryResult result = service.execute(Map.of(
            "datasourceId", "ds-1",
            "templateId", "MYSQL_TABLE_METADATA",
            "parameters", Map.of("tableName", "lbappdeploydetail")
        ));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> parameters = ArgumentCaptor.forClass(Map.class);
        verify(templateService).render(eq("MYSQL_TABLE_METADATA"), parameters.capture(), eq(datasource), anyMap());
        assertThat(parameters.getValue())
            .containsEntry("tableName", "lbappdeploydetail")
            .containsEntry("schemaName", "rdsm_ad")
            .containsEntry("databaseName", "rdsm_ad");
        assertThat(result.diagnostics()).containsEntry("failureStage", "prepare");
    }

    @Test
    void selfHealsEmptyMetadataResultByRetryingAlternateSchema() throws Exception {
        String jdbcUrl = "jdbc:h2:mem:sql_query_self_healing;DB_CLOSE_DELAY=-1";
        try (var connection = DriverManager.getConnection(jdbcUrl, "sa", "");
             Statement statement = connection.createStatement()) {
            statement.execute("create schema tenant_b");
            statement.execute("create table tenant_b.customer_label (id int primary key, name varchar(64))");
        }

        SqlDatasourceConfig datasource = new SqlDatasourceConfig();
        datasource.setId("ds-1");
        datasource.setName("customer-db");
        datasource.setToolName("sql_customer");
        datasource.setJdbcUrl(jdbcUrl);
        datasource.setUsername("sa");
        datasource.setPassword("");
        datasource.setDefaultMaxRows(10);
        datasource.setDefaultTimeoutSeconds(5);
        when(datasourceConfigService.getEnabled("ds-1")).thenReturn(datasource);
        when(metadataResolverService.resolveTable(eq(datasource), eq("customer_label"), eq("PUBLIC")))
            .thenReturn(new TableResolution(
                "ds-1",
                "postgresql",
                "customer_label",
                "PUBLIC",
                "PUBLIC",
                "CUSTOMER_LABEL",
                "resolved",
                0.8,
                List.of(
                    new TableLocation("ds-1", "PUBLIC", "PUBLIC", "CUSTOMER_LABEL", "BASE TABLE", null, 0.8),
                    new TableLocation("ds-1", "TENANT_B", "TENANT_B", "CUSTOMER_LABEL", "BASE TABLE", null, 0.7)
                ),
                1,
                false,
                null
            ));
        when(templateService.render(eq("MYSQL_TABLE_METADATA"), anyMap(), eq(datasource), anyMap()))
            .thenAnswer(invocation -> {
                Map<?, ?> parameters = invocation.getArgument(1);
                return "SELECT column_name FROM information_schema.columns WHERE table_schema = '"
                    + parameters.get("schemaName")
                    + "' AND table_name = '"
                    + parameters.get("tableName")
                    + "' ORDER BY ordinal_position";
            });

        SqlQueryResult result = service.execute(Map.of(
            "datasourceId", "ds-1",
            "templateId", "MYSQL_TABLE_METADATA",
            "parameters", Map.of(
                "tableName", "customer_label",
                "schemaName", "PUBLIC"
            )
        ));

        assertThat(result.success()).isTrue();
        assertThat(result.rowCount()).isEqualTo(2);
        assertThat(result.normalizedSql()).contains("TENANT_B");
        assertThat(result.diagnostics()).containsKey("selfHealing");
        verify(metadataResolverService).recordUsage(any(TableLocation.class));
    }
}
