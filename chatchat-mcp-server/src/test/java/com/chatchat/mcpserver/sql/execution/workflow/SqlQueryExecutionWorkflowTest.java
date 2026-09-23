package com.chatchat.mcpserver.sql.execution.workflow;

import com.chatchat.mcpserver.database.definition.DatabaseQueryConfigService;
import com.chatchat.mcpserver.database.execution.DatabaseQueryInvokeService;
import com.chatchat.mcpserver.routing.target.ExecutionTargetRouter;
import com.chatchat.mcpserver.sql.execution.SqlQueryExecuteService;
import com.chatchat.mcpserver.sql.execution.SqlQueryResult;
import com.chatchat.mcpserver.sql.execution.SqlScriptExecuteService;
import com.chatchat.mcpserver.sql.template.SqlTemplateConfig;
import com.chatchat.mcpserver.sql.template.SqlTemplateService;
import com.chatchat.mcpserver.template.TemplateParameterValidator;
import com.chatchat.mcpserver.template.workflow.TemplateParameterWorkflow;
import com.chatchat.mcpserver.tool.StandardToolExecutionResultFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SqlQueryExecutionWorkflowTest {

    @Test
    @SuppressWarnings("unchecked")
    void resolvesRegisteredSqlTemplateParametersBeforeTargetRouting() {
        SqlTemplateService templates = mock(SqlTemplateService.class);
        SqlTemplateConfig template = new SqlTemplateConfig();
        template.setCode("ACCOUNT_QUERY");
        template.setSqlTemplate("select * from account where id = {{accountId}}");
        template.setParameterSchemaJson("""
            {"type":"object","properties":{"accountId":{"type":"integer"}},"required":["accountId"]}
            """);
        when(templates.listEnabled()).thenReturn(List.of(template));

        DatabaseQueryConfigService businessQueries = mock(DatabaseQueryConfigService.class);
        when(businessQueries.listEnabled()).thenReturn(List.of());
        ExecutionTargetRouter router = mock(ExecutionTargetRouter.class);
        when(router.routeSqlQuery(anyMap())).thenAnswer(invocation -> {
            Map<String, Object> routed = new LinkedHashMap<>(invocation.getArgument(0));
            routed.put("sql", "select 1");
            return routed;
        });
        SqlQueryExecuteService queryExecutor = mock(SqlQueryExecuteService.class);
        SqlQueryResult queryResult = mock(SqlQueryResult.class);
        when(queryResult.success()).thenReturn(true);
        when(queryExecutor.execute(anyMap())).thenReturn(queryResult);
        StandardToolExecutionResultFactory results = mock(StandardToolExecutionResultFactory.class);
        when(results.fromSql(queryResult)).thenReturn(Map.of("success", true));

        ObjectMapper objectMapper = new ObjectMapper();
        TemplateParameterWorkflow parameters = new TemplateParameterWorkflow(
            new TemplateParameterValidator(objectMapper), objectMapper);
        SqlQueryExecutionWorkflow workflow = new SqlQueryExecutionWorkflow(
            templates, queryExecutor, mock(SqlScriptExecuteService.class), businessQueries,
            mock(DatabaseQueryInvokeService.class), router, results, parameters);

        McpSchema.CallToolResult result = workflow.execute(Map.of(
            "templateId", "ACCOUNT_QUERY",
            "accountId", "42",
            "executionContext", Map.of("assetName", "accounts", "env", "DEV")));

        ArgumentCaptor<Map<String, Object>> routedInput = ArgumentCaptor.forClass(Map.class);
        verify(router).routeSqlQuery(routedInput.capture());
        assertThat((Map<String, Object>) routedInput.getValue().get("parameters"))
            .containsEntry("accountId", 42);
        assertThat(result.structuredContent().toString())
            .contains("resolve.template-parameters.v1", "REQUEST_FIELD", "SQL_QUERY");
    }
}
