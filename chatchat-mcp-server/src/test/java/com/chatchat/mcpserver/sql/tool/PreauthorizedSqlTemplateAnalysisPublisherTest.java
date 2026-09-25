package com.chatchat.mcpserver.sql.tool;

import com.chatchat.mcpserver.sql.datasource.SqlDatasourceConfig;
import com.chatchat.mcpserver.sql.datasource.SqlDatasourceConfigService;
import com.chatchat.mcpserver.sql.execution.SqlSafetyService;
import com.chatchat.mcpserver.sql.execution.workflow.SqlQueryExecutionWorkflow;
import com.chatchat.mcpserver.sql.template.SqlTemplateConfig;
import com.chatchat.mcpserver.sql.template.SqlTemplateService;
import com.chatchat.mcpserver.database.definition.DatabaseQueryConfigService;
import com.chatchat.mcpserver.tool.AgentRuntimeGovernanceFactory;
import com.chatchat.mcpserver.tool.McpToolConcurrencyManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.server.McpSyncServer;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PreauthorizedSqlTemplateAnalysisPublisherTest {
    private final SqlDatasourceConfigService datasources = mock(SqlDatasourceConfigService.class);
    private final SqlTemplateService templates = mock(SqlTemplateService.class);
    private final PreauthorizedSqlTemplateAnalysisPublisher publisher =
        new PreauthorizedSqlTemplateAnalysisPublisher(mock(McpSyncServer.class), datasources, templates,
            mock(DatabaseQueryConfigService.class), mock(SqlQueryExecutionWorkflow.class), new SqlSafetyService(),
            new AgentRuntimeGovernanceFactory(new ObjectMapper()), mock(McpToolConcurrencyManager.class),
            new ObjectMapper());

    @Test void acceptsOnlyExplicitlyAllowlistedReadOnlyTemplateAndSanitizesArguments() {
        allowlisted("SELECT amount FROM sales WHERE year = {{year}}", "LOW");

        Map<String, Object> approved = publisher.validate(Map.of("templateId", "SALES_TOTAL",
            "parameters", Map.of("year", 2025),
            "assetDomain", "sales", "domain", "sales",
            "executionContext", Map.of("assetName", "sales", "env", "PROD")));

        assertThat(approved).containsEntry("template", "SALES_TOTAL")
            .containsEntry("maxRows", 100).containsEntry("timeoutSeconds", 30);
        assertThat(approved).doesNotContainKeys("sql", "script", "datasourceId");
    }

    @Test void refusesRawSqlUnlistedTemplateAndUnsafeBody() {
        allowlisted("SELECT amount FROM sales", "LOW");
        Map<String, Object> request = new LinkedHashMap<>(Map.of("templateId", "SALES_TOTAL",
            "assetDomain", "sales", "domain", "sales",
            "executionContext", Map.of("assetName", "sales", "env", "PROD")));
        request.put("sql", "SELECT * FROM sales");
        assertThatThrownBy(() -> publisher.validate(request)).isInstanceOf(IllegalArgumentException.class);
        request.remove("sql");
        request.put("templateId", "NOT_ALLOWED");
        assertThatThrownBy(() -> publisher.validate(request)).hasMessageContaining("not explicitly authorized");

        allowlisted("DELETE FROM sales", "LOW");
        request.put("templateId", "SALES_TOTAL");
        assertThatThrownBy(() -> publisher.validate(request)).hasMessageContaining("forbidden keyword");
    }

    @Test void refusesHighRiskAndMultiStatementTemplates() {
        Map<String, Object> request = Map.of("templateId", "SALES_TOTAL",
            "assetDomain", "sales", "domain", "sales",
            "executionContext", Map.of("assetName", "sales", "env", "PROD"));
        allowlisted("SELECT amount FROM sales", "HIGH");
        assertThatThrownBy(() -> publisher.validate(request)).hasMessageContaining("low/medium-risk");
        allowlisted("SELECT amount FROM sales; SELECT id FROM sales", "LOW");
        assertThatThrownBy(() -> publisher.validate(request)).hasMessageContaining("low/medium-risk");
    }

    private void allowlisted(String sql, String risk) {
        SqlDatasourceConfig asset = new SqlDatasourceConfig();
        asset.setId("asset-1");
        asset.setName("sales");
        asset.setEnvironment("PROD");
        asset.setDatabaseType("postgresql");
        asset.setAllowedTemplatesJson("[\"SALES_TOTAL\"]");
        when(datasources.listEnabled()).thenReturn(List.of(asset));
        SqlTemplateConfig template = new SqlTemplateConfig();
        template.setCode("SALES_TOTAL");
        template.setSqlTemplate(sql);
        template.setRiskLevel(risk);
        template.setDatabaseType("postgresql");
        when(templates.listEnabled()).thenReturn(List.of(template));
    }
}
