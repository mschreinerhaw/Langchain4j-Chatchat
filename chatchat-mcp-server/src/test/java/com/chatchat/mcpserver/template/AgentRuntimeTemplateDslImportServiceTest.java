package com.chatchat.mcpserver.template;

import com.chatchat.mcpserver.database.DatabaseQueryConfigService;
import com.chatchat.mcpserver.database.DatabaseQueryMcpToolPublisher;
import com.chatchat.mcpserver.ops.CommandTemplateConfig;
import com.chatchat.mcpserver.ops.CommandTemplateService;
import com.chatchat.mcpserver.ops.OpsMcpToolPublisher;
import com.chatchat.mcpserver.search.McpTemplateLuceneIndexService;
import com.chatchat.mcpserver.sql.SqlMcpToolPublisher;
import com.chatchat.mcpserver.sql.SqlTemplateService;
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
}
