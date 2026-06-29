package com.chatchat.mcpserver.sql;

import com.chatchat.mcpserver.template.TemplateParameterValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

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
        SqlTemplateService service = new SqlTemplateService(
            repository,
            objectMapper,
            new TemplateParameterValidator(objectMapper),
            properties
        );
        when(repository.findByEnabledTrueOrderByCodeAsc()).thenReturn(List.of());

        service.listEnabled();

        verify(repository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void enabledDefaultSeedCreatesOnlyMaintenanceTemplates() {
        SqlTemplateSeedProperties properties = new SqlTemplateSeedProperties();
        properties.setSeedDefaultsEnabled(true);
        SqlTemplateService service = new SqlTemplateService(
            repository,
            objectMapper,
            new TemplateParameterValidator(objectMapper),
            properties
        );
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
                "MYSQL_SCHEMA_TABLE_OVERVIEW",
                "MYSQL_TABLE_LOCATION",
                "MYSQL_TABLE_METADATA",
                "ORACLE_SESSION_OVERVIEW",
                "ORACLE_INSTANCE_STATUS",
                "ORACLE_LOCKS",
                "ORACLE_SYSTEM_EVENTS",
                "ORACLE_TABLESPACE_SIZE",
                "ORACLE_TABLE_METADATA",
                "POSTGRES_ACTIVITY",
                "POSTGRES_DATABASE_SIZE",
                "POSTGRES_TABLE_SIZE_RANKING",
                "POSTGRES_LOCKS",
                "POSTGRES_LONG_TRANSACTIONS",
                "POSTGRES_TABLE_METADATA",
                "SQLSERVER_SESSIONS",
                "SQLSERVER_REQUESTS",
                "SQLSERVER_DATABASE_SIZE",
                "SQLSERVER_LOCKS",
                "SQLSERVER_IO_STATS",
                "SQLSERVER_TABLE_METADATA"
            )
            .doesNotContain("CHECK_TABLE_COUNT", "CHECK_RECENT_DATA", "TASK_RESULT", "CHECK_TASK_RESULT");
        assertThat(saved).hasSize(26);
        assertThat(saved)
            .filteredOn(template -> template.getCode().startsWith("MYSQL_"))
            .hasSize(8)
            .allSatisfy(template -> {
                assertThat(template.getDatabaseType()).isEqualTo("mysql");
                assertThat(template.getRiskLevel()).isEqualTo("LOW");
                assertThat(template.getCategory()).startsWith("maintenance_");
            });
        assertThat(saved)
            .filteredOn(template -> template.getCode().startsWith("ORACLE_"))
            .hasSize(6)
            .allSatisfy(template -> assertThat(template.getDatabaseType()).isEqualTo("oracle"));
        assertThat(saved)
            .filteredOn(template -> template.getCode().startsWith("POSTGRES_"))
            .hasSize(6)
            .allSatisfy(template -> assertThat(template.getDatabaseType()).isEqualTo("postgresql"));
        assertThat(saved)
            .filteredOn(template -> template.getCode().startsWith("SQLSERVER_"))
            .hasSize(6)
            .allSatisfy(template -> assertThat(template.getDatabaseType()).isEqualTo("sqlserver"));
        assertThat(saved).extracting(SqlTemplateConfig::getSqlTemplate)
            .noneSatisfy(sql -> assertThat(sql).containsIgnoringCase("{{table}}"))
            .noneSatisfy(sql -> assertThat(sql).containsIgnoringCase("LIMIT 100"))
            .noneSatisfy(sql -> assertThat(sql).containsIgnoringCase("task_result"))
            .noneSatisfy(sql -> assertThat(sql).containsIgnoringCase("customer_asset"))
            .noneSatisfy(sql -> assertThat(sql).containsIgnoringCase("exec "));
        assertThat(saved)
            .filteredOn(template -> template.getCode().endsWith("_TABLE_METADATA"))
            .hasSize(4)
            .allSatisfy(template -> {
                assertThat(template.getCategory()).isEqualTo("maintenance_metadata");
                assertThat(template.getParameterSchemaJson()).contains("tableName");
                assertThat(template.getSqlTemplate()).contains("{{tableName}}");
            });
    }

    @Test
    void enabledDefaultSeedDeletesRetiredGenericTemplates() {
        SqlTemplateSeedProperties properties = new SqlTemplateSeedProperties();
        properties.setSeedDefaultsEnabled(true);
        SqlTemplateService service = new SqlTemplateService(
            repository,
            objectMapper,
            new TemplateParameterValidator(objectMapper),
            properties
        );
        SqlTemplateConfig tableCount = template("CHECK_TABLE_COUNT");
        SqlTemplateConfig recentData = template("CHECK_RECENT_DATA");
        SqlTemplateConfig taskResult = template("TASK_RESULT");
        when(repository.findByCode(anyString())).thenReturn(Optional.empty());
        when(repository.findByCode("CHECK_TABLE_COUNT")).thenReturn(Optional.of(tableCount));
        when(repository.findByCode("CHECK_RECENT_DATA")).thenReturn(Optional.of(recentData));
        when(repository.findByCode("TASK_RESULT")).thenReturn(Optional.of(taskResult));
        when(repository.findByEnabledTrueOrderByCodeAsc()).thenReturn(List.of());

        service.listEnabled();

        verify(repository).delete(tableCount);
        verify(repository).delete(recentData);
        verify(repository).delete(taskResult);
    }

    @Test
    void tableMetadataTemplateQuotesAndValidatesTableNameParameter() {
        SqlTemplateSeedProperties properties = new SqlTemplateSeedProperties();
        SqlTemplateService service = new SqlTemplateService(
            repository,
            objectMapper,
            new TemplateParameterValidator(objectMapper),
            properties
        );
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
            .hasMessageContaining("does not match required pattern");
    }

    private SqlTemplateConfig template(String code) {
        SqlTemplateConfig config = new SqlTemplateConfig();
        config.setId(code.toLowerCase());
        config.setCode(code);
        config.setTitle(code);
        config.setSqlTemplate("SELECT 1");
        return config;
    }
}
