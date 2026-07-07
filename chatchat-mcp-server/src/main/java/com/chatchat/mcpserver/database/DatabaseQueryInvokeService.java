package com.chatchat.mcpserver.database;

import com.chatchat.agents.tool.ToolRegistry;
import com.chatchat.common.tool.ToolInput;
import com.chatchat.common.tool.ToolLogSummarizer;
import com.chatchat.common.tool.ToolOutput;
import com.chatchat.mcpserver.audit.InvocationAuditService;
import com.chatchat.mcpserver.cache.DatabaseQueryCacheService;
import com.chatchat.mcpserver.sql.DynamicDateParamService;
import com.chatchat.mcpserver.sql.SqlDatasourceConfig;
import com.chatchat.mcpserver.sql.SqlDatasourceConfigService;
import com.chatchat.mcpserver.sql.SqlScriptExecuteService;
import com.chatchat.mcpserver.template.AgentRuntimeTemplateDsl;
import com.chatchat.mcpserver.template.AgentRuntimeTemplateDsl.TemplatePlan;
import com.chatchat.mcpserver.template.AgentRuntimeTemplateDsl.TemplateStep;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class DatabaseQueryInvokeService {

    private static final String TOOL_NAME = "database_query";

    private final ToolRegistry toolRegistry;
    private final SqlDatasourceConfigService datasourceConfigService;
    private final SqlScriptExecuteService scriptExecuteService;
    private final DynamicDateParamService dynamicDateParamService;
    private final ObjectMapper objectMapper;
    private final InvocationAuditService auditService;
    private final DatabaseQueryCacheService cacheService;

    /**
     * Performs the invoke operation.
     *
     * @param config the config value
     * @param arguments the arguments value
     * @return the operation result
     */
    public ToolOutput invoke(DatabaseQueryConfig config, Map<String, Object> arguments) {
        long startedAt = System.currentTimeMillis();
        Map<String, Object> auditArgs = arguments == null ? Map.of() : arguments;
        ToolOutput output;
        try {
            assertExecutionCapability(config);
            log.info("Database query invoke started databaseQueryId={} tool={} maxRows={} sql={} args={}",
                config.getId(),
                config.getToolName(),
                config.getMaxRows(),
                config.getSqlTemplate(),
                ToolLogSummarizer.summarize(arguments));
            Map<String, Object> parameters = toParameters(config, auditArgs);
            var cached = cacheService.get(config, parameters);
            if (cached.isPresent()) {
                output = cached.get();
                output.setExecutionTimeMs(Math.max(0L, System.currentTimeMillis() - startedAt));
                log.info("Database query invoke cache hit databaseQueryId={} tool={} durationMs={}",
                    config.getId(),
                    config.getToolName(),
                    Math.max(0L, System.currentTimeMillis() - startedAt));
            } else {
                output = invoke(parameters);
                if (output.getMetadata() != null) {
                    output.getMetadata().putIfAbsent("cacheHit", false);
                }
                cacheService.put(config, parameters, output);
            }
        } catch (Exception ex) {
            output = ToolOutput.failure(ex.getMessage());
            output.setExecutionTimeMs(Math.max(0L, System.currentTimeMillis() - startedAt));
        }
        long durationMs = output == null || output.getExecutionTimeMs() == null
            ? Math.max(0L, System.currentTimeMillis() - startedAt)
            : output.getExecutionTimeMs();
        auditService.recordDatabaseQueryCall(config, auditArgs, output, durationMs);
        return output;
    }

    private void assertExecutionCapability(DatabaseQueryConfig config) {
        if (config.getCapabilitiesJson() == null || config.getCapabilitiesJson().isBlank()) {
            log.warn("MCP database query has no protocol capabilities configured; allowing legacy execution: databaseQueryId={}, tool={}",
                config.getId(), config.getToolName());
            return;
        }
        Set<String> capabilities;
        try {
            capabilities = objectMapper.readValue(config.getCapabilitiesJson(), new TypeReference<List<String>>() {}).stream()
                .filter(value -> value != null && !value.isBlank())
                .map(value -> value.trim().toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());
        } catch (Exception ex) {
            throw new IllegalArgumentException("Database query capabilities config is invalid");
        }
        if (capabilities.stream().noneMatch(value -> value.equals("database_query")
            || value.equals("sql_query_execute")
            || value.equals("sql_exec")
            || value.equals("sql")
            || value.equals("jdbc"))) {
            throw new IllegalArgumentException("Database query does not declare SQL execution capability: "
                + config.getToolName());
        }
    }

    /**
     * Performs the invoke operation.
     *
     * @param parameters the parameters value
     * @return the operation result
     */
    public ToolOutput invoke(Map<String, Object> parameters) {
        long startedAt = System.currentTimeMillis();
        if (!toolRegistry.hasTool(TOOL_NAME)) {
            long durationMs = Math.max(0L, System.currentTimeMillis() - startedAt);
            log.warn("Database query invoke failed tool={} durationMs={} error={}",
                TOOL_NAME,
                durationMs,
                "database_query tool is not registered");
            ToolOutput output = ToolOutput.failure("database_query tool is not registered");
            output.setExecutionTimeMs(durationMs);
            return output;
        }
        String sql = text(parameters, "sql");
        TemplatePlan plan = sqlPlan(sql);
        List<String> statements = plan.steps().stream().map(TemplateStep::command).toList();
        if (statements.size() > 1) {
            return invokeScript(parameters, plan, startedAt);
        }
        if (statements.size() == 1) {
            parameters = new LinkedHashMap<>(parameters);
            parameters.put("sql", statements.get(0));
        }
        ToolInput input = ToolInput.builder()
            .requestId(UUID.randomUUID().toString())
            .userId("admin")
            .parameters(parameters)
            .build();
        ToolOutput output = toolRegistry.executeEnhancedTool(TOOL_NAME, input);
        long durationMs = Math.max(0L, System.currentTimeMillis() - startedAt);
        if (output == null) {
            output = ToolOutput.failure("database_query tool returned no output");
        }
        output.setExecutionTimeMs(durationMs);
        if (output.isSuccess()) {
            log.info("Database query invoke succeeded tool={} durationMs={} result={}",
                TOOL_NAME,
                durationMs,
                ToolLogSummarizer.summarize(output.getData()));
        } else {
            log.warn("Database query invoke failed tool={} durationMs={} error={} result={}",
                TOOL_NAME,
                durationMs,
                output.getErrorMessage(),
                ToolLogSummarizer.summarize(output.getData()));
        }
        return output;
    }

    private ToolOutput invokeScript(Map<String, Object> parameters, TemplatePlan plan, long startedAt) {
        List<Map<String, Object>> results = new java.util.ArrayList<>();
        boolean success = true;
        String errorMessage = null;
        List<TemplateStep> statements = plan.steps();
        for (int index = 0; index < statements.size(); index++) {
            TemplateStep current = statements.get(index);
            Map<String, Object> stepParameters = new LinkedHashMap<>(parameters);
            stepParameters.put("sql", current.command());
            ToolOutput step = invokeSingleStatement(stepParameters);
            Map<String, Object> data = mapValue(step.getData());
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("statementIndex", index + 1);
            result.put("stepCode", current.stepCode());
            result.put("stepName", current.stepName());
            result.put("stepType", current.stepType());
            result.put("required", current.required());
            result.put("analysisHint", current.analysisHint());
            result.put("success", step.isSuccess());
            result.put("sql", data.getOrDefault("sql", current.command()));
            result.put("columns", data.getOrDefault("columns", List.of()));
            result.put("rows", data.getOrDefault("rows", List.of()));
            result.put("rowCount", data.getOrDefault("rowCount", 0));
            result.put("maxRows", data.getOrDefault("maxRows", parameters.getOrDefault("max_rows", parameters.get("maxRows"))));
            result.put("possiblyTruncated", data.getOrDefault("possiblyTruncated", false));
            result.put("errorMessage", step.getErrorMessage());
            results.add(result);
            if (!step.isSuccess()) {
                if (current.required()) {
                    success = false;
                    errorMessage = step.getErrorMessage();
                }
                if (!plan.continueOnError()) {
                    break;
                }
            }
        }
        long durationMs = Math.max(0L, System.currentTimeMillis() - startedAt);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("mode", plan.dsl() ? "agent_runtime_template_dsl" : "sql_script");
        data.put("templateDsl", AgentRuntimeTemplateDsl.metadata(plan));
        data.put("analysisPolicy", plan.analysisPolicy());
        data.put("executionMode", plan.executionMode());
        data.put("continueOnError", plan.continueOnError());
        data.put("statementCount", statements.size());
        data.put("resultSetCount", results.size());
        data.put("results", results);
        data.put("possiblyPartial", results.size() < statements.size());
        ToolOutput output = success
            ? ToolOutput.success(data, "Database SQL script completed successfully")
            : ToolOutput.failure(errorMessage == null ? "Database SQL script failed" : errorMessage);
        output.setData(data);
        output.setExecutionTimeMs(durationMs);
        output.getMetadata().put("executionMode", data.get("mode"));
        output.getMetadata().put("statementCount", statements.size());
        return output;
    }

    private ToolOutput invokeSingleStatement(Map<String, Object> parameters) {
        ToolInput input = ToolInput.builder()
            .requestId(UUID.randomUUID().toString())
            .userId("admin")
            .parameters(parameters)
            .build();
        ToolOutput output = toolRegistry.executeEnhancedTool(TOOL_NAME, input);
        return output == null ? ToolOutput.failure("database_query tool returned no output") : output;
    }

    /**
     * Converts the value to parameters.
     *
     * @param config the config value
     * @param arguments the arguments value
     * @return the converted parameters
     */
    private Map<String, Object> toParameters(DatabaseQueryConfig config, Map<String, Object> arguments) {
        if (config.getDatasourceId() == null || config.getDatasourceId().isBlank()) {
            throw new IllegalArgumentException("database query requires an enabled datasource asset");
        }
        SqlDatasourceConfig datasource = datasourceConfigService.getEnabled(config.getDatasourceId());
        Map<String, Object> resolvedArguments = dynamicDateParamService.enrichParameters(
            arguments,
            datasource,
            config.getSqlTemplate()
        );
        String resolvedSql = dynamicDateParamService.resolveSqlPlaceholders(config.getSqlTemplate(), datasource);
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("sql", resolvedSql);
        parameters.put("params", resolvedArguments);
        parameters.put("max_rows", config.getMaxRows());
        parameters.put("timeoutSeconds", config.getTimeoutSeconds());
        parameters.put("timeout_seconds", config.getTimeoutSeconds());
        putIfPresent(parameters, "jdbc_url", datasource.getJdbcUrl());
        putIfPresent(parameters, "driver_class", datasource.getDriverClass());
        putIfPresent(parameters, "database_type", datasource.getDatabaseType());
        putIfPresent(parameters, "username", datasource.getUsername());
        putIfPresent(parameters, "password", datasource.getPassword());
        parameters.put("datasource_id", datasource.getId());
        parameters.put("datasource_name", datasource.getName());
        parameters.put("reload_drivers", false);
        return parameters;
    }

    private TemplatePlan sqlPlan(String sql) {
        TemplatePlan dsl = AgentRuntimeTemplateDsl.parse(sql, "database_query", "DB_SQL", "SQL");
        if (dsl != null) {
            return dsl;
        }
        List<String> statements = scriptExecuteService.extractStatements(sql);
        List<TemplateStep> steps = new java.util.ArrayList<>();
        for (int index = 0; index < statements.size(); index++) {
            steps.add(AgentRuntimeTemplateDsl.singleStep(index + 1, "SQL", statements.get(index)));
        }
        return new TemplatePlan(
            "database_query",
            "database_query",
            "DB_SQL",
            null,
            "SEQUENTIAL",
            false,
            "LOW",
            null,
            Map.of(),
            steps,
            false
        );
    }

    /**
     * Stores the if present.
     *
     * @param parameters the parameters value
     * @param key the key value
     * @param value the value value
     */
    private void putIfPresent(Map<String, Object> parameters, String key, String value) {
        if (value != null && !value.isBlank()) {
            parameters.put(key, value.trim());
        }
    }

    private String text(Map<String, Object> values, String key) {
        Object value = values == null ? null : values.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mapValue(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    /**
     * Performs the argument keys operation.
     *
     * @param arguments the arguments value
     * @return the operation result
     */
    private java.util.List<String> argumentKeys(Map<String, Object> arguments) {
        if (arguments == null || arguments.isEmpty()) {
            return java.util.List.of();
        }
        return arguments.keySet().stream()
            .filter(key -> key != null && !key.isBlank())
            .sorted()
            .toList();
    }
}
