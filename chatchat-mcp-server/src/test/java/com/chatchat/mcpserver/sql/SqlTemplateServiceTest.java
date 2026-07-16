package com.chatchat.mcpserver.sql;

import com.chatchat.mcpserver.template.TemplateParameterValidator;
import com.chatchat.tools.builtin.DynamicJdbcDriverLoader;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SqlTemplateServiceTest {

    private final SqlTemplateConfigRepository repository = mock(SqlTemplateConfigRepository.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void doesNotSeedDefaultTemplatesUnlessExplicitlyEnabled() {
        SqlTemplateSeedProperties properties = new SqlTemplateSeedProperties();
        SqlTemplateService service = service(properties);
        when(repository.findByCode(anyString())).thenReturn(Optional.empty());
        when(repository.findByEnabledTrueOrderByCodeAsc()).thenReturn(List.of());

        service.listEnabled();

        verify(repository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void enabledDefaultSeedCreatesOnlyMaintenanceTemplates() {
        SqlTemplateSeedProperties properties = new SqlTemplateSeedProperties();
        properties.setSeedDefaultsEnabled(true);
        SqlTemplateService service = service(properties);
        when(repository.findByCode(anyString())).thenReturn(Optional.empty());
        when(repository.findByEnabledTrueOrderByCodeAsc()).thenReturn(List.of());

        service.listEnabled();

        ArgumentCaptor<SqlTemplateConfig> captor = ArgumentCaptor.forClass(SqlTemplateConfig.class);
        verify(repository, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
        List<SqlTemplateConfig> saved = captor.getAllValues();
        assertThat(saved).extracting(SqlTemplateConfig::getCode)
            .contains(
                "MYSQL_SHOW_PROCESSLIST",
                "MYSQL_SHOW_STATUS",
                "MYSQL_INNODB_STATUS",
                "MYSQL_INNODB_TRX",
                "MYSQL_DATABASE_SIZE",
                "MYSQL_INSTANCE_VARIABLES",
                "MYSQL_CONNECTION_STATUS",
                "MYSQL_TOP_TABLES_SIZE",
                "MYSQL_STATEMENT_DIGEST_TOP",
                "ORACLE_SESSION_OVERVIEW",
                "ORACLE_INSTANCE_STATUS",
                "ORACLE_LOCKS",
                "ORACLE_SYSTEM_EVENTS",
                "ORACLE_TABLESPACE_USAGE",
                "ORACLE_TABLESPACE_SIZE",
                "ORACLE_DATABASE_OVERVIEW",
                "ORACLE_ACTIVE_SESSIONS",
                "ORACLE_TOP_SQL_ELAPSED",
                "ORACLE_WAIT_CLASS_SUMMARY",
                "POSTGRES_ACTIVITY",
                "POSTGRES_DATABASE_SIZE",
                "POSTGRES_TABLE_SIZE_RANKING",
                "POSTGRES_LOCKS",
                "POSTGRES_LONG_TRANSACTIONS",
                "POSTGRES_DATABASE_STATS",
                "POSTGRES_WAIT_ACTIVITY",
                "POSTGRES_BLOCKING_CHAINS",
                "POSTGRES_BGWRITER_STATS",
                "SQLSERVER_SESSIONS",
                "SQLSERVER_REQUESTS",
                "SQLSERVER_DATABASE_SIZE",
                "SQLSERVER_LOCKS",
                "SQLSERVER_IO_STATS",
                "SQLSERVER_INSTANCE_OVERVIEW",
                "SQLSERVER_WAIT_STATS",
                "SQLSERVER_DATABASE_STATS",
                "SQLSERVER_MEMORY_OVERVIEW",
                "DM_SESSIONS",
                "DM_INSTANCE_STATUS",
                "DM_LOCKS",
                "DM_SQL_HISTORY",
                "DM_TABLESPACE_SIZE",
                "DM_ACTIVE_SESSIONS",
                "DM_TOP_SQL_HISTORY",
                "DM_LOCK_WAIT_OVERVIEW",
                "DM_TABLESPACE_USAGE",
                "TDSQL_SHOW_PROCESSLIST",
                "TDSQL_SHOW_STATUS",
                "TDSQL_INNODB_STATUS",
                "TDSQL_INNODB_TRX",
                "TDSQL_DATABASE_SIZE",
                "TDSQL_INSTANCE_VARIABLES",
                "TDSQL_CONNECTION_STATUS",
                "TDSQL_TOP_TABLES_SIZE",
                "TDSQL_STATEMENT_DIGEST_TOP",
                "TIDB_PROCESSLIST",
                "TIDB_CLUSTER_INFO",
                "TIDB_TRANSACTIONS",
                "TIDB_STATEMENTS_SUMMARY",
                "TIDB_DATABASE_SIZE",
                "TIDB_NODE_LOAD",
                "TIDB_CLUSTER_HARDWARE",
                "TIDB_RECENT_SLOW_QUERIES",
                "TIDB_DATA_LOCK_WAITS",
                "TIDB_TABLE_STORAGE_STATS",
                "TIDB_REGION_STATUS",
                "TIDB_ANALYZE_STATUS",
                "TIDB_STATEMENTS_ERRORS",
                "KINGBASE_ACTIVITY",
                "KINGBASE_DATABASE_STATS",
                "KINGBASE_LOCKS",
                "KINGBASE_LONG_QUERIES",
                "KINGBASE_DATABASE_SIZE",
                "KINGBASE_WAIT_ACTIVITY",
                "KINGBASE_BLOCKING_OVERVIEW",
                "KINGBASE_LONG_TRANSACTIONS",
                "KINGBASE_CACHE_HIT_RATIO",
                "KINGBASE_TOP_TABLES_SIZE",
                "KINGBASE_TABLE_ACTIVITY",
                "KINGBASE_INDEX_USAGE",
                "KINGBASE_BGWRITER_STATS",
                "OCEANBASE_PROCESSLIST",
                "OCEANBASE_SERVERS",
                "OCEANBASE_TENANTS",
                "OCEANBASE_SQL_AUDIT",
                "OCEANBASE_DATABASE_SIZE",
                "OCEANBASE_SERVER_OVERVIEW",
                "OCEANBASE_TENANT_OVERVIEW",
                "OCEANBASE_TOP_SQL_AUDIT",
                "OCEANBASE_TOP_TABLES_SIZE"
            )
            .doesNotContain(
                "CHECK_TABLE_COUNT",
                "CHECK_RECENT_DATA",
                "TASK_RESULT",
                "CHECK_TASK_RESULT",
                "MYSQL_SCHEMA_TABLE_OVERVIEW",
                "MYSQL_TABLE_LOCATION",
                "MYSQL_TABLE_METADATA",
                "ORACLE_TABLE_METADATA",
                "POSTGRES_TABLE_METADATA",
                "SQLSERVER_TABLE_METADATA"
            );
        assertThat(saved).hasSize(90);
        assertThat(saved)
            .filteredOn(template -> template.getCode().startsWith("MYSQL_"))
            .hasSize(9)
            .allSatisfy(template -> {
                assertThat(template.getDatabaseType()).isEqualTo("mysql");
                assertThat(template.getRiskLevel()).isEqualTo("LOW");
                assertThat(template.getCategory()).startsWith("maintenance_");
            });
        assertThat(saved)
            .filteredOn(template -> template.getCode().startsWith("ORACLE_"))
            .hasSize(10)
            .allSatisfy(template -> assertThat(template.getDatabaseType()).isEqualTo("oracle"));
        assertThat(saved)
            .filteredOn(template -> "ORACLE_TABLESPACE_USAGE".equals(template.getCode()))
            .singleElement()
            .satisfies(template -> {
                assertThat(template.getSqlTemplate())
                    .containsIgnoringCase("dba_data_files")
                    .containsIgnoringCase("dba_free_space")
                    .containsIgnoringCase("used_mb")
                    .containsIgnoringCase("free_mb")
                    .containsIgnoringCase("used_pct");
                assertThat(template.getIntentSignalsJson()).contains("tablespace usage", "free space", "utilization");
            });
        assertThat(saved)
            .filteredOn(template -> template.getCode().startsWith("POSTGRES_"))
            .hasSize(9)
            .allSatisfy(template -> assertThat(template.getDatabaseType()).isEqualTo("postgresql"));
        assertThat(saved)
            .filteredOn(template -> template.getCode().startsWith("SQLSERVER_"))
            .hasSize(9)
            .allSatisfy(template -> assertThat(template.getDatabaseType()).isEqualTo("sqlserver"));
        assertThat(saved)
            .filteredOn(template -> template.getCode().startsWith("SQLSERVER_"))
            .allSatisfy(template -> assertThat(template.getSqlTemplate().toLowerCase())
                .doesNotContain("select *"));
        assertThat(saved)
            .filteredOn(template -> template.getCode().startsWith("DM_"))
            .hasSize(9)
            .allSatisfy(template -> assertThat(template.getDatabaseType()).isEqualTo("dm"));
        assertThat(saved)
            .filteredOn(template -> template.getCode().startsWith("TDSQL_"))
            .hasSize(9)
            .allSatisfy(template -> assertThat(template.getDatabaseType()).isEqualTo("tdsql"));
        assertThat(saved)
            .filteredOn(template -> template.getCode().startsWith("TIDB_"))
            .hasSize(13)
            .allSatisfy(template -> assertThat(template.getDatabaseType()).isEqualTo("tidb"));
        assertThat(saved)
            .filteredOn(template -> template.getCode().startsWith("KINGBASE_"))
            .hasSize(13)
            .allSatisfy(template -> assertThat(template.getDatabaseType()).isEqualTo("kingbase"));
        assertThat(saved)
            .filteredOn(template -> template.getCode().startsWith("OCEANBASE_"))
            .hasSize(9)
            .allSatisfy(template -> assertThat(template.getDatabaseType()).isEqualTo("oceanbase"));
        assertThat(saved).extracting(SqlTemplateConfig::getSqlTemplate)
            .noneSatisfy(sql -> assertThat(sql).containsIgnoringCase("{{table}}"))
            .noneSatisfy(sql -> assertThat(sql).containsIgnoringCase("LIMIT 100"))
            .noneSatisfy(sql -> assertThat(sql).containsIgnoringCase("task_result"))
            .noneSatisfy(sql -> assertThat(sql).containsIgnoringCase("customer_asset"))
            .noneSatisfy(sql -> assertThat(sql).containsIgnoringCase("exec "));
        assertThat(saved)
            .filteredOn(template -> template.getCode().endsWith("_TABLE_METADATA"))
            .isEmpty();
    }

    @Test
    void infersDomesticDatabaseTypesFromJdbcAndDriverHints() {
        assertThat(SqlDatasourceConfigService.normalizeDatabaseType(
            null,
            "jdbc:mysql://tdsql-cluster.example:3306/app",
            "com.mysql.cj.jdbc.Driver"
        )).isEqualTo("tdsql");
        assertThat(SqlDatasourceConfigService.normalizeDatabaseType(
            null,
            "jdbc:oceanbase://ob.example:2881/app",
            "com.oceanbase.jdbc.Driver"
        )).isEqualTo("oceanbase");
        assertThat(SqlDatasourceConfigService.normalizeDatabaseType(
            null,
            "jdbc:mysql://tidb-cluster.example:4000/app",
            "com.mysql.cj.jdbc.Driver"
        )).isEqualTo("tidb");
        assertThat(SqlDatasourceConfigService.normalizeDatabaseType(
            null,
            "jdbc:kingbase8://127.0.0.1:54321/app",
            "com.kingbase8.Driver"
        )).isEqualTo("kingbase");
        assertThat(SqlDatasourceConfigService.normalizeDatabaseType(
            null,
            "jdbc:dm://127.0.0.1:5236",
            "dm.jdbc.driver.DmDriver"
        )).isEqualTo("dm");
    }

    @Test
    void enabledDefaultSeedDeletesRetiredGenericTemplates() {
        SqlTemplateSeedProperties properties = new SqlTemplateSeedProperties();
        properties.setSeedDefaultsEnabled(true);
        SqlTemplateService service = service(properties);
        SqlTemplateConfig tableCount = template("CHECK_TABLE_COUNT");
        SqlTemplateConfig recentData = template("CHECK_RECENT_DATA");
        SqlTemplateConfig taskResult = template("TASK_RESULT");
        SqlTemplateConfig mysqlMetadata = template("MYSQL_TABLE_METADATA");
        when(repository.findByCode(anyString())).thenReturn(Optional.empty());
        when(repository.findByCode("CHECK_TABLE_COUNT")).thenReturn(Optional.of(tableCount));
        when(repository.findByCode("CHECK_RECENT_DATA")).thenReturn(Optional.of(recentData));
        when(repository.findByCode("TASK_RESULT")).thenReturn(Optional.of(taskResult));
        when(repository.findByCode("MYSQL_TABLE_METADATA")).thenReturn(Optional.of(mysqlMetadata));
        when(repository.findByEnabledTrueOrderByCodeAsc()).thenReturn(List.of());

        service.listEnabled();

        verify(repository).delete(tableCount);
        verify(repository).delete(recentData);
        verify(repository).delete(taskResult);
        verify(repository).delete(mysqlMetadata);
    }

    @Test
    void refreshesExistingDefaultSqlTemplateDefinitionButPreservesDisabledState() {
        SqlTemplateSeedProperties properties = new SqlTemplateSeedProperties();
        properties.setSeedDefaultsEnabled(true);
        SqlTemplateService service = service(properties);
        SqlTemplateConfig existing = template("MYSQL_SHOW_STATUS");
        existing.setTitle("Old status title");
        existing.setDescription("Old status description");
        existing.setSqlTemplate("SELECT 1");
        existing.setParameterSchemaJson("{}");
        existing.setRiskLevel("HIGH");
        existing.setCategory("custom");
        existing.setDatabaseType("generic");
        existing.setDatasourceId("user-bound-datasource");
        existing.setRoutingLabelsJson("[]");
        existing.setIntentSignalsJson("[]");
        existing.setEnabled(false);
        when(repository.findByCode(anyString())).thenReturn(Optional.empty());
        when(repository.findByCode("MYSQL_SHOW_STATUS")).thenReturn(Optional.of(existing));
        when(repository.findByEnabledTrueOrderByCodeAsc()).thenReturn(List.of());

        service.listEnabled();

        ArgumentCaptor<SqlTemplateConfig> captor = ArgumentCaptor.forClass(SqlTemplateConfig.class);
        verify(repository, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
        assertThat(captor.getAllValues()).anySatisfy(saved -> {
            if ("MYSQL_SHOW_STATUS".equals(saved.getCode())) {
                assertThat(saved.getTitle()).isEqualTo("MySQL status variables");
                assertThat(saved.getDescription()).contains("status counters");
                assertThat(saved.getSqlTemplate()).isEqualTo("SHOW STATUS");
                assertThat(saved.getRiskLevel()).isEqualTo("LOW");
                assertThat(saved.getCategory()).isEqualTo("maintenance_instance");
                assertThat(saved.getDatabaseType()).isEqualTo("mysql");
                assertThat(saved.getDatasourceId()).isNull();
                assertThat(saved.getRoutingLabelsJson()).contains("mysql", "maintenance", "instance");
                assertThat(saved.getIntentSignalsJson()).contains("performance_issue");
                assertThat(saved.isEnabled()).isFalse();
            }
        });
    }

    @Test
    void refreshesExistingDefaultSqlTemplateEvenWhenDefaultSeedIsDisabled() {
        SqlTemplateSeedProperties properties = new SqlTemplateSeedProperties();
        SqlTemplateService service = service(properties);
        SqlTemplateConfig existing = template("MYSQL_SHOW_STATUS");
        existing.setTitle("旧状态标题");
        existing.setDescription("旧状态描述");
        existing.setSqlTemplate("SELECT 1");
        existing.setParameterSchemaJson("{}");
        existing.setRiskLevel("HIGH");
        existing.setCategory("custom");
        existing.setDatabaseType("generic");
        existing.setDatasourceId("user-bound-datasource");
        existing.setRoutingLabelsJson("[]");
        existing.setIntentSignalsJson("[]");
        existing.setEnabled(true);
        when(repository.findByCode(anyString())).thenReturn(Optional.empty());
        when(repository.findByCode("MYSQL_SHOW_STATUS")).thenReturn(Optional.of(existing));
        when(repository.findByEnabledTrueOrderByCodeAsc()).thenReturn(List.of(existing));

        service.listEnabled();

        ArgumentCaptor<SqlTemplateConfig> captor = ArgumentCaptor.forClass(SqlTemplateConfig.class);
        verify(repository, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
        assertThat(captor.getAllValues()).anySatisfy(saved -> {
            if ("MYSQL_SHOW_STATUS".equals(saved.getCode())) {
                assertThat(saved.getTitle()).isEqualTo("MySQL status variables");
                assertThat(saved.getDescription()).contains("status counters");
                assertThat(saved.getSqlTemplate()).isEqualTo("SHOW STATUS");
                assertThat(saved.getRiskLevel()).isEqualTo("LOW");
                assertThat(saved.getCategory()).isEqualTo("maintenance_instance");
                assertThat(saved.getDatabaseType()).isEqualTo("mysql");
                assertThat(saved.getDatasourceId()).isNull();
                assertThat(saved.isEnabled()).isTrue();
            }
        });
    }

    @Test
    void tableMetadataTemplateQuotesAndValidatesTableNameParameter() {
        SqlTemplateSeedProperties properties = new SqlTemplateSeedProperties();
        SqlTemplateService service = service(properties);
        SqlTemplateConfig metadata = template("MYSQL_TABLE_METADATA");
        metadata.setSqlTemplate("SELECT column_name FROM information_schema.columns WHERE table_name = {{tableName}}");
        metadata.setParameterSchemaJson("""
            {
              "type": "object",
              "properties": {
                "tableName": {
                  "type": "string",
                  "pattern": "[A-Za-z_][A-Za-z0-9_]*"
                }
              },
              "required": ["tableName"]
            }
            """);
        when(repository.findByCode("MYSQL_TABLE_METADATA")).thenReturn(Optional.of(metadata));

        String sql = service.render("MYSQL_TABLE_METADATA", Map.of("tableName", "orders"));

        assertThat(sql).contains("table_name = 'orders'");
        assertThatThrownBy(() -> service.render("MYSQL_TABLE_METADATA", Map.of("tableName", "orders;drop")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("parameter tableName contains SQL syntax");
    }

    @Test
    void rejectsRawSqlParameterWhenTemplateDoesNotDeclareIt() {
        SqlTemplateSeedProperties properties = new SqlTemplateSeedProperties();
        SqlTemplateService service = service(properties);
        SqlTemplateConfig innodbTrx = template("MYSQL_INNODB_TRX");
        innodbTrx.setSqlTemplate("SELECT * FROM information_schema.INNODB_TRX");
        innodbTrx.setParameterSchemaJson("""
            {
              "type": "object",
              "properties": {},
              "required": []
            }
            """);
        when(repository.findByCode("MYSQL_INNODB_TRX")).thenReturn(Optional.of(innodbTrx));

        assertThatThrownBy(() -> service.render(
            "MYSQL_INNODB_TRX",
            Map.of("sql", "SHOW CREATE TABLE rdsm_ad.t_ad_dict_entr_supn")
        ))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("SQL template MYSQL_INNODB_TRX does not accept parameter sql");
    }

    @Test
    void rejectsSqlSyntaxHiddenInsideTypedTemplateParameter() {
        SqlTemplateSeedProperties properties = new SqlTemplateSeedProperties();
        SqlTemplateService service = service(properties);
        SqlTemplateConfig metadata = template("MYSQL_TABLE_METADATA");
        metadata.setSqlTemplate("SELECT column_name FROM information_schema.columns WHERE table_name = {{tableName}}");
        metadata.setParameterSchemaJson("""
            {
              "type": "object",
              "properties": {
                "tableName": {"type": "string"}
              },
              "required": ["tableName"]
            }
            """);
        when(repository.findByCode("MYSQL_TABLE_METADATA")).thenReturn(Optional.of(metadata));

        assertThatThrownBy(() -> service.render(
            "MYSQL_TABLE_METADATA",
            Map.of("tableName", "SHOW CREATE TABLE rdsm_ad.t_ad_dict_entr_supn")
        ))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("parameter tableName contains SQL syntax");
    }

    @Test
    void rejectsDynamicSqlFragmentParameterEvenWhenSchemaDeclaresIt() {
        SqlTemplateSeedProperties properties = new SqlTemplateSeedProperties();
        SqlTemplateService service = service(properties);
        SqlTemplateConfig template = template("MYSQL_CUSTOM_FILTER");
        template.setSqlTemplate("SELECT * FROM t WHERE {{whereClause}}");
        template.setParameterSchemaJson("""
            {
              "type": "object",
              "properties": {
                "whereClause": {"type": "string"}
              },
              "required": ["whereClause"]
            }
            """);
        when(repository.findByCode("MYSQL_CUSTOM_FILTER")).thenReturn(Optional.of(template));

        assertThatThrownBy(() -> service.render(
            "MYSQL_CUSTOM_FILTER",
            Map.of("whereClause", "status = 'A' ORDER BY id")
        ))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("does not allow SQL fragment parameter whereClause");
    }

    private SqlTemplateConfig template(String code) {
        SqlTemplateConfig config = new SqlTemplateConfig();
        config.setId(code.toLowerCase());
        config.setCode(code);
        config.setTitle(code);
        config.setSqlTemplate("SELECT 1");
        return config;
    }

    private SqlTemplateService service(SqlTemplateSeedProperties properties) {
        return new SqlTemplateService(
            repository,
            objectMapper,
            new TemplateParameterValidator(objectMapper),
            properties,
            new DynamicDateParamService(
                mock(DynamicJdbcDriverLoader.class),
                Clock.fixed(Instant.parse("2026-07-07T00:00:00Z"), ZoneId.of("Asia/Shanghai"))
            )
        );
    }
}
