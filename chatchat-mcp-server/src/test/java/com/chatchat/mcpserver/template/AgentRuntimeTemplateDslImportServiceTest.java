package com.chatchat.mcpserver.template;

import com.chatchat.mcpserver.database.definition.DatabaseQueryConfig;
import com.chatchat.mcpserver.database.definition.DatabaseQueryConfigService;
import com.chatchat.mcpserver.database.publication.DatabaseQueryMcpToolPublisher;
import com.chatchat.mcpserver.ops.command.CommandTemplateConfig;
import com.chatchat.mcpserver.ops.command.CommandTemplateService;
import com.chatchat.mcpserver.ops.tool.OpsMcpToolPublisher;
import com.chatchat.mcpserver.search.index.McpTemplateLuceneIndexService;
import com.chatchat.mcpserver.sql.tool.SqlMcpToolPublisher;
import com.chatchat.mcpserver.sql.template.SqlTemplateConfig;
import com.chatchat.mcpserver.sql.template.SqlTemplateService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentRuntimeTemplateDslImportServiceTest {
    @Test
    void importsAdaptedQueriesIntoExistingRegistryWithoutLosingWorkflowOptions() throws Exception {
        when(databaseQueryConfigService.listAll()).thenReturn(List.of());
        when(databaseQueryConfigService.create(any())).thenAnswer(call -> {
            DatabaseQueryConfig config = call.getArgument(0); config.setId("query-search"); return config;
        });
        String dsl = """
            {"templateCode":"SEARCH_REPORTS","templateName":"检索报告","templateType":"DATABASE_QUERY",
             "datasourceId":"http:search-asset","description":"报告检索","implementationSteps":"检索并分析报告",
             "sqlSteps":[{"sqlCode":"SEARCH","sqlName":"检索报告","sqlDescription":"报告结果",
               "sqlContent":"{\\"query\\":{\\"match_all\\":{}}}","queryOptions":{"index":"reports"},
               "executionOrder":1,"workflowEnabled":true,"dependencies":[],"returnToModel":false,
               "resultSemantic":{"businessEntity":"报告"},
               "parameterMappings":[{"parameter":"vector","sourceType":"STATIC","defaultValue":[0.1,0.2]}]}]}
            """;
        var request = new AgentRuntimeTemplateDslImportService.ImportRequest(dsl, "DATABASE_QUERY", null, null);
        assertThat(service.validate(request).valid()).isTrue();
        assertThat(service.importTemplate(request).targetRegistry()).isEqualTo("database_query_template");
        var captor = ArgumentCaptor.forClass(DatabaseQueryConfig.class);
        verify(databaseQueryConfigService).create(captor.capture());
        var saved = new ObjectMapper().readTree(captor.getValue().getSqlStepsJson());
        assertThat(saved.at("/0/queryOptions/index").asText()).isEqualTo("reports");
        assertThat(saved.at("/0/workflowEnabled").asBoolean()).isTrue();
        assertThat(saved.at("/0/returnToModel").asBoolean()).isFalse();
        assertThat(saved.at("/0/parameterMappings/0/defaultValue").isArray()).isTrue();
        assertThat(saved.at("/0/resultSemantic/businessEntity").asText()).isEqualTo("报告");
    }

    private final CommandTemplateService commandTemplateService = mock(CommandTemplateService.class);
    private final SqlTemplateService sqlTemplateService = mock(SqlTemplateService.class);
    private final DatabaseQueryConfigService databaseQueryConfigService = mock(DatabaseQueryConfigService.class);
    private final OpsMcpToolPublisher opsPublisher = mock(OpsMcpToolPublisher.class);
    private final SqlMcpToolPublisher sqlPublisher = mock(SqlMcpToolPublisher.class);
    private final DatabaseQueryMcpToolPublisher databaseQueryPublisher = mock(DatabaseQueryMcpToolPublisher.class);
    private final McpTemplateLuceneIndexService templateIndexService = mock(McpTemplateLuceneIndexService.class);
    private final AgentRuntimeTemplateDslImportService service = new AgentRuntimeTemplateDslImportService(
        new ObjectMapper(),
        commandTemplateService,
        sqlTemplateService,
        databaseQueryConfigService,
        opsPublisher,
        sqlPublisher,
        databaseQueryPublisher,
        templateIndexService
    );

    @Test
    void importsLinuxDslIntoCommandTemplateRegistry() {
        when(commandTemplateService.listAll()).thenReturn(List.of());
        when(commandTemplateService.save(any(CommandTemplateConfig.class))).thenAnswer(invocation -> {
            CommandTemplateConfig config = invocation.getArgument(0);
            config.setId("template-1");
            return config;
        });
        String dsl = """
            {
              "templateCode": "LINUX_HOST_STATUS",
              "templateName": "Linux host status",
              "templateType": "LINUX_CMD",
              "description": "Collect host status",
              "riskLevel": "LOW",
              "steps": [
                {
                  "stepCode": "UPTIME",
                  "stepName": "Uptime",
                  "stepType": "SHELL",
                  "command": "uptime",
                  "analysisHint": "Check load average."
                }
              ]
            }
            """;

        AgentRuntimeTemplateDslImportService.ImportResult result = service.importTemplate(
            new AgentRuntimeTemplateDslImportService.ImportRequest(dsl, "LINUX_CMD", null, null)
        );

        assertThat(result.targetRegistry()).isEqualTo("linux_command_template");
        assertThat(result.savedId()).isEqualTo("template-1");
        ArgumentCaptor<CommandTemplateConfig> captor = ArgumentCaptor.forClass(CommandTemplateConfig.class);
        verify(commandTemplateService).save(captor.capture());
        assertThat(captor.getValue().getCode()).isEqualTo("LINUX_HOST_STATUS");
        assertThat(captor.getValue().getCommandTemplate()).contains("\"steps\"");
        assertThat(captor.getValue().getIntentSignalsJson()).contains("UPTIME", "Check load average.");
        verify(opsPublisher).refresh();
        verify(templateIndexService).upsertCommandTemplates(any());
    }

    @Test
    void rejectsSqlDslWithShellStepType() {
        String dsl = """
            {
              "templateCode": "BAD_SQL_DSL",
              "templateType": "DB_SQL",
              "steps": [
                {
                  "stepCode": "HOST",
                  "stepType": "SHELL",
                  "command": "hostname"
                }
              ]
            }
            """;

        AgentRuntimeTemplateDslImportService.ValidationResult result = service.validate(
            new AgentRuntimeTemplateDslImportService.ImportRequest(dsl, "DB_SQL", null, null)
        );

        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anyMatch(error -> error.contains("stepType SHELL is not allowed"));
    }

    @Test
    void importsBatchDslIntoMatchingRegistries() {
        when(commandTemplateService.listAll()).thenReturn(List.of());
        when(sqlTemplateService.listAll()).thenReturn(List.of());
        when(commandTemplateService.save(any(CommandTemplateConfig.class))).thenAnswer(invocation -> {
            CommandTemplateConfig config = invocation.getArgument(0);
            config.setId("linux-template-1");
            return config;
        });
        when(sqlTemplateService.save(any(SqlTemplateConfig.class))).thenAnswer(invocation -> {
            SqlTemplateConfig config = invocation.getArgument(0);
            config.setId("sql-template-1");
            return config;
        });
        String dsl = """
            [
              {
                "templateCode": "LINUX_UPTIME",
                "templateName": "Linux uptime",
                "templateType": "LINUX_CMD",
                "steps": [
                  {
                    "stepCode": "UPTIME",
                    "stepType": "SHELL",
                    "command": "uptime"
                  }
                ]
              },
              {
                "templateCode": "MYSQL_STATUS",
                "templateName": "MySQL status",
                "templateType": "DB_SQL",
                "targetType": "mysql",
                "steps": [
                  {
                    "stepCode": "STATUS",
                    "stepType": "SQL",
                    "command": "SHOW STATUS"
                  }
                ]
              }
            ]
            """;

        AgentRuntimeTemplateDslImportService.ValidationResult validation = service.validate(
            new AgentRuntimeTemplateDslImportService.ImportRequest(dsl, "LINUX_CMD", null, null)
        );
        AgentRuntimeTemplateDslImportService.ImportResult result = service.importTemplate(
            new AgentRuntimeTemplateDslImportService.ImportRequest(dsl, "LINUX_CMD", null, null)
        );

        assertThat(validation.valid()).isTrue();
        assertThat(validation.targetRegistry()).isEqualTo("batch");
        assertThat(validation.normalized()).containsEntry("batch", true);
        assertThat(result.targetRegistry()).isEqualTo("batch");
        assertThat(result.normalized()).containsEntry("importedCount", 2);
        verify(commandTemplateService).save(any(CommandTemplateConfig.class));
        verify(sqlTemplateService).save(any(SqlTemplateConfig.class));
        verify(opsPublisher).refresh();
        verify(sqlPublisher).refresh();
    }

    @Test
    void importsBatchDatabaseQuerySqlStepsIntoDatabaseQueryRegistry() {
        when(databaseQueryConfigService.listAll()).thenReturn(List.of());
        when(databaseQueryConfigService.create(any(DatabaseQueryConfig.class))).thenAnswer(invocation -> {
            DatabaseQueryConfig config = invocation.getArgument(0);
            config.setId("database-query-1");
            return config;
        });
        String dsl = """
            [
              {
                "templateCode": "CUSTOMER_ANALYSIS",
                "templateName": "Customer analysis",
                "templateType": "DATABASE_QUERY",
                "datasourceId": "ds-1",
                "description": "Analyze customer order data",
                "implementationSteps": "Run summary first, then detail rows.",
                "sqlSteps": [
                  {
                    "sqlCode": "SUMMARY",
                    "sqlName": "Summary",
                    "sqlDescription": "Summary result set",
                    "sqlContent": "select count(*) cnt from orders",
                    "executionOrder": 1
                  },
                  {
                    "sqlCode": "DETAIL",
                    "sqlName": "Detail rows",
                    "sqlDescription": "Detail result set",
                    "sqlContent": "select * from orders where status = :status",
                    "executionOrder": 2,
                    "parameters": {
                      "status": "ACTIVE"
                    }
                  }
                ]
              }
            ]
            """;

        AgentRuntimeTemplateDslImportService.ValidationResult validation = service.validate(
            new AgentRuntimeTemplateDslImportService.ImportRequest(dsl, "DATABASE_QUERY", null, null)
        );
        AgentRuntimeTemplateDslImportService.ImportResult result = service.importTemplate(
            new AgentRuntimeTemplateDslImportService.ImportRequest(dsl, "DATABASE_QUERY", null, null)
        );

        assertThat(validation.valid()).isTrue();
        assertThat(validation.targetRegistry()).isEqualTo("batch");
        assertThat(result.targetRegistry()).isEqualTo("batch");
        assertThat(result.normalized()).containsEntry("importedCount", 1);
        ArgumentCaptor<DatabaseQueryConfig> captor = ArgumentCaptor.forClass(DatabaseQueryConfig.class);
        verify(databaseQueryConfigService).create(captor.capture());
        assertThat(captor.getValue().getToolName()).isEqualTo("CUSTOMER_ANALYSIS");
        assertThat(captor.getValue().getDatasourceId()).isEqualTo("ds-1");
        assertThat(captor.getValue().getImplementationSteps()).contains("Run summary first");
        assertThat(captor.getValue().getSqlStepsJson())
            .contains("SUMMARY", "DETAIL", "Summary result set", "select * from orders where status = :status");
        verify(databaseQueryPublisher).refresh();
        verify(templateIndexService).upsertDatabaseQueryTemplates(any());
    }
}
