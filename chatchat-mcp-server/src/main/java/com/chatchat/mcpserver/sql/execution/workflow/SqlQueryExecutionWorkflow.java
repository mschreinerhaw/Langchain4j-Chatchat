package com.chatchat.mcpserver.sql.execution.workflow;

import com.chatchat.common.kernel.KernelDataScope;
import com.chatchat.common.runtime.workflow.AbstractStagedExecutionWorkflow;
import com.chatchat.common.tool.ToolOutput;
import com.chatchat.mcpserver.database.definition.DatabaseQueryConfig;
import com.chatchat.mcpserver.database.definition.DatabaseQueryConfigService;
import com.chatchat.mcpserver.database.execution.DatabaseQueryInvokeService;
import com.chatchat.mcpserver.routing.target.ExecutionTargetRouter;
import com.chatchat.mcpserver.sql.execution.SqlQueryExecuteService;
import com.chatchat.mcpserver.sql.execution.SqlQueryResult;
import com.chatchat.mcpserver.sql.execution.SqlScriptExecuteService;
import com.chatchat.mcpserver.sql.execution.SqlScriptResult;
import com.chatchat.mcpserver.sql.parsing.SqlStatementExtractor;
import com.chatchat.mcpserver.sql.template.SqlTemplateConfig;
import com.chatchat.mcpserver.sql.template.SqlTemplateService;
import com.chatchat.mcpserver.template.AgentRuntimeTemplateDsl;
import com.chatchat.mcpserver.tool.StandardToolExecutionResultFactory;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Executes the governed SQL gateway as an explicit staged capability workflow. */
@Component
public class SqlQueryExecutionWorkflow extends AbstractStagedExecutionWorkflow<
    Map<String, Object>, SqlQueryExecutionWorkflow.Analysis, SqlQueryExecutionWorkflow.Plan,
    SqlQueryExecutionWorkflow.Execution, McpSchema.CallToolResult> {

    public static final String WORKFLOW_ID = "execute.sql-query.v1";

    private final SqlTemplateService sqlTemplateService;
    private final SqlQueryExecuteService queryExecuteService;
    private final SqlScriptExecuteService scriptExecuteService;
    private final DatabaseQueryConfigService databaseQueryConfigService;
    private final DatabaseQueryInvokeService databaseQueryInvokeService;
    private final ExecutionTargetRouter executionTargetRouter;
    private final StandardToolExecutionResultFactory resultFactory;

    public SqlQueryExecutionWorkflow(SqlTemplateService sqlTemplateService,
                                     SqlQueryExecuteService queryExecuteService,
                                     SqlScriptExecuteService scriptExecuteService,
                                     DatabaseQueryConfigService databaseQueryConfigService,
                                     DatabaseQueryInvokeService databaseQueryInvokeService,
                                     ExecutionTargetRouter executionTargetRouter,
                                     StandardToolExecutionResultFactory resultFactory) {
        this.sqlTemplateService = sqlTemplateService;
        this.queryExecuteService = queryExecuteService;
        this.scriptExecuteService = scriptExecuteService;
        this.databaseQueryConfigService = databaseQueryConfigService;
        this.databaseQueryInvokeService = databaseQueryInvokeService;
        this.executionTargetRouter = executionTargetRouter;
        this.resultFactory = resultFactory;
    }

    @Override public String workflowId() { return WORKFLOW_ID; }

    @Override
    protected void validateInput(Map<String, Object> input, KernelDataScope scope) {
        if (input == null) throw new IllegalArgumentException("sql_query_execute input is required");
    }

    @Override
    protected Analysis analyze(Map<String, Object> input, KernelDataScope scope) {
        validateTemplateArgumentContract(input);
        validateExecutableSelector(input);
        DatabaseQueryConfig businessQuery = businessDatabaseQueryTemplate(input);
        return new Analysis(input, businessQuery == null ? "ROUTED_SQL" : "BUSINESS_QUERY", businessQuery);
    }

    @Override
    protected Plan plan(Map<String, Object> input, Analysis analysis, KernelDataScope scope) {
        List<String> steps = "BUSINESS_QUERY".equals(analysis.mode())
            ? List.of("VALIDATE_ARGUMENT_CONTRACT", "RESOLVE_BUSINESS_TEMPLATE",
                "BIND_TEMPLATE_PARAMETERS", "EXECUTE_DATABASE_QUERY_WORKFLOW",
                "VERIFY_RESULT", "ASSEMBLE_EVIDENCE")
            : List.of("VALIDATE_ARGUMENT_CONTRACT", "ROUTE_LOGICAL_DATASOURCE",
                "CLASSIFY_SQL_CARDINALITY", "ENFORCE_READ_ONLY_POLICY",
                "EXECUTE_QUERY_OR_SCRIPT", "VERIFY_RESULT", "ASSEMBLE_EVIDENCE");
        return new Plan(analysis.mode(), steps);
    }

    @Override
    protected Execution executePlan(Map<String, Object> input, Analysis analysis, Plan plan,
                                     KernelDataScope scope) {
        if (analysis.businessQuery() != null) {
            Map<String, Object> arguments = databaseQueryArguments(analysis.arguments());
            ToolOutput output = databaseQueryInvokeService.invoke(analysis.businessQuery(), arguments);
            Object structured = resultFactory.fromDatabaseQuery(analysis.businessQuery(), arguments, output);
            boolean success = output != null && output.isSuccess();
            String summary = success ? summarizeDatabaseQueryData(output.getData())
                : output == null ? "database_query returned no output" : output.getErrorMessage();
            return new Execution("BUSINESS_QUERY", success, summary, structured);
        }

        Map<String, Object> routed = executionTargetRouter.routeSqlQuery(analysis.arguments());
        if (shouldExecuteAsScript(routed)) {
            SqlScriptResult result = scriptExecuteService.execute(toScriptArguments(routed));
            return new Execution("SQL_SCRIPT", result.success(),
                result.success() ? "SQL script completed" : result.errorMessage(),
                resultFactory.fromSqlScript(result));
        }
        SqlQueryResult result = queryExecuteService.execute(routed);
        return new Execution("SQL_QUERY", result.success(),
            result.success() ? "SQL query completed" : result.errorMessage(),
            resultFactory.fromSql(result));
    }

    @Override
    protected void verify(Map<String, Object> input, Analysis analysis, Plan plan,
                          Execution execution, KernelDataScope scope) {
        if (execution == null || execution.structuredContent() == null) {
            throw new IllegalStateException("sql_query_execute produced no structured result");
        }
    }

    @Override
    protected McpSchema.CallToolResult assemble(Map<String, Object> input, Analysis analysis, Plan plan,
                                                 Execution execution, KernelDataScope scope) {
        Object structured = withWorkflowTrace(execution.structuredContent(), execution.mode(),
            plan.steps(), execution.success());
        return McpSchema.CallToolResult.builder()
            .addTextContent(execution.summary() == null ? "" : execution.summary())
            .structuredContent(structured)
            .isError(!execution.success())
            .build();
    }

    /** Returns the concurrency-governance level before execution starts. */
    public String runtimeLevel(Map<String, Object> arguments) {
        Map<String, Object> input = arguments == null ? Map.of() : arguments;
        String script = firstText(text(input, "script"), text(input, "sql"));
        if (script == null) {
            DatabaseQueryConfig query = businessDatabaseQueryTemplate(input);
            script = query == null ? null : query.getSqlTemplate();
        }
        if (script == null || script.isBlank()) return "sql";
        try {
            return scriptExecuteService.extractStatements(script).size() > 1 ? "sql_script" : "sql";
        } catch (Exception ignored) {
            return script.contains(";") ? "sql_script" : "sql";
        }
    }

    private boolean shouldExecuteAsScript(Map<String, Object> arguments) {
        String sql = text(arguments, "sql");
        String script = text(arguments, "script");
        if (script != null) return true;
        if (sql == null) {
            String template = templateId(arguments);
            String body = sqlTemplateBody(template);
            return body != null && (AgentRuntimeTemplateDsl.looksLikeDsl(body)
                || SqlStatementExtractor.splitStatements(body).size() > 1);
        }
        return SqlStatementExtractor.splitStatements(sql).size() > 1;
    }

    private String sqlTemplateBody(String templateCode) {
        if (templateCode == null) return null;
        return sqlTemplateService.listEnabled().stream()
            .filter(template -> template != null && template.getCode() != null
                && template.getCode().equalsIgnoreCase(templateCode))
            .map(SqlTemplateConfig::getSqlTemplate)
            .findFirst().orElse(null);
    }

    private Map<String, Object> toScriptArguments(Map<String, Object> arguments) {
        Map<String, Object> values = new LinkedHashMap<>(arguments == null ? Map.of() : arguments);
        values.put("script", firstText(text(values, "script"), text(values, "sql")));
        values.remove("sql");
        if (values.containsKey("maxRows") && !values.containsKey("maxRowsPerStatement")) {
            values.put("maxRowsPerStatement", values.get("maxRows"));
        }
        return values;
    }

    private DatabaseQueryConfig businessDatabaseQueryTemplate(Map<String, Object> arguments) {
        String template = templateId(arguments);
        if (template == null) return null;
        return databaseQueryConfigService.listEnabled().stream()
            .filter(config -> config != null && config.getToolName() != null
                && config.getToolName().equalsIgnoreCase(template))
            .findFirst().orElse(null);
    }

    private void validateTemplateArgumentContract(Map<String, Object> arguments) {
        List<String> aliases = List.of("template", "templateId", "template_id");
        Set<String> values = new LinkedHashSet<>();
        for (String alias : aliases) {
            Object value = arguments.get(alias);
            if (value == null) continue;
            if (!(value instanceof CharSequence) || String.valueOf(value).isBlank()) {
                throw new IllegalArgumentException("TEMPLATE_ARGUMENT_CONTRACT_FAILED: " + alias
                    + " must be a non-empty scalar string; template objects and arrays are not executable ids");
            }
            String candidate = String.valueOf(value).trim();
            if (candidate.startsWith("{") || candidate.startsWith("[")) {
                throw new IllegalArgumentException("TEMPLATE_ARGUMENT_CONTRACT_FAILED: " + alias
                    + " must contain only the scalar template id");
            }
            values.add(candidate);
        }
        if (values.size() > 1) {
            throw new IllegalArgumentException("TEMPLATE_ARGUMENT_CONTRACT_FAILED: template aliases must identify "
                + "the same template: " + values);
        }
        Object parameters = arguments.get("parameters");
        if (parameters != null && !(parameters instanceof Map<?, ?>)) {
            throw new IllegalArgumentException("TEMPLATE_ARGUMENT_CONTRACT_FAILED: parameters must be an object "
                + "containing execution values only");
        }
        if (parameters instanceof Map<?, ?> map && (map.containsKey("properties") || map.containsKey("$schema"))
            && map.containsKey("type")) {
            throw new IllegalArgumentException("TEMPLATE_ARGUMENT_CONTRACT_FAILED: parameterSchema is read-only "
                + "metadata and cannot be used as execution parameters");
        }
        for (String key : List.of("executionContext", "mcpExecutionContext")) {
            Object context = arguments.get(key);
            if (context != null && !(context instanceof Map<?, ?>)) {
                throw new IllegalArgumentException("TEMPLATE_ARGUMENT_CONTRACT_FAILED: " + key + " must be an object");
            }
        }
    }

    private void validateExecutableSelector(Map<String, Object> arguments) {
        String template = templateId(arguments);
        String sql = firstText(text(arguments, "sql"), text(arguments, "script"));
        if (template == null && sql == null) {
            throw new IllegalArgumentException(
                "SQL_EXECUTION_SOURCE_REQUIRED: provide a template/templateId returned by template discovery "
                    + "or an explicit read-only sql/script");
        }
        if (template != null && sql != null) {
            throw new IllegalArgumentException(
                "SQL_EXECUTION_SOURCE_CONFLICT: use either template/templateId or sql/script, not both");
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> databaseQueryArguments(Map<String, Object> arguments) {
        Object parameters = arguments.get("parameters");
        if (parameters instanceof Map<?, ?> map) return new LinkedHashMap<>((Map<String, Object>) map);
        Map<String, Object> values = new LinkedHashMap<>(arguments);
        List.of("template", "templateId", "template_id", "executionContext", "mcpExecutionContext")
            .forEach(values::remove);
        return values;
    }

    private Object withWorkflowTrace(Object structured, String mode, List<String> steps, boolean success) {
        if (!(structured instanceof Map<?, ?> source)) return structured;
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, value) -> result.put(String.valueOf(key), value));
        result.put("executionWorkflow", Map.of(
            "workflowId", WORKFLOW_ID, "mode", mode, "steps", steps, "verified", success));
        return result;
    }

    private String summarizeDatabaseQueryData(Object data) {
        if (data == null) return "database_query executed successfully";
        if (!(data instanceof Map<?, ?> map)) return String.valueOf(data);
        Map<String, Object> summary = new LinkedHashMap<>();
        for (String key : List.of("mode", "schemaVersion", "tool", "execution", "resultSetCount")) {
            summary.put(key, map.get(key));
        }
        summary.put("structuredContent",
            "Complete workflow steps, result semantics and row data are available in structuredContent.data");
        try {
            return com.chatchat.agents.protocol.ModelProtocolJson.compact(summary);
        } catch (RuntimeException ignored) {
            return "database_query workflow executed; complete data is available in structuredContent.data";
        }
    }

    private String templateId(Map<String, Object> arguments) {
        return firstText(text(arguments, "template"),
            firstText(text(arguments, "templateId"), text(arguments, "template_id")));
    }

    private String text(Map<String, Object> source, String key) {
        if (source == null || source.get(key) == null) return null;
        String value = String.valueOf(source.get(key)).trim();
        return value.isBlank() ? null : value;
    }

    private String firstText(String first, String second) {
        return first == null || first.isBlank() ? second : first;
    }

    public record Analysis(Map<String, Object> arguments, String mode, DatabaseQueryConfig businessQuery) {
        public Analysis {
            arguments = arguments == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(arguments));
        }
    }

    public record Plan(String mode, List<String> steps) {
        public Plan { steps = steps == null ? List.of() : List.copyOf(steps); }
    }

    public record Execution(String mode, boolean success, String summary, Object structuredContent) { }
}
