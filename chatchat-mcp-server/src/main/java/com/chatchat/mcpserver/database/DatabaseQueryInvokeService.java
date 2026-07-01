package com.chatchat.mcpserver.database;

import com.chatchat.agents.tool.ToolRegistry;
import com.chatchat.common.tool.ToolInput;
import com.chatchat.common.tool.ToolLogSummarizer;
import com.chatchat.common.tool.ToolOutput;
import com.chatchat.mcpserver.audit.InvocationAuditService;
import com.chatchat.mcpserver.sql.SqlDatasourceConfig;
import com.chatchat.mcpserver.sql.SqlDatasourceConfigService;
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
    private final ObjectMapper objectMapper;
    private final InvocationAuditService auditService;

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
            output = invoke(toParameters(config, auditArgs));
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

    /**
     * Converts the value to parameters.
     *
     * @param config the config value
     * @param arguments the arguments value
     * @return the converted parameters
     */
    private Map<String, Object> toParameters(DatabaseQueryConfig config, Map<String, Object> arguments) {
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("sql", config.getSqlTemplate());
        parameters.put("params", arguments);
        parameters.put("max_rows", config.getMaxRows());
        if (config.getDatasourceId() == null || config.getDatasourceId().isBlank()) {
            throw new IllegalArgumentException("database query requires an enabled datasource asset");
        }
        SqlDatasourceConfig datasource = datasourceConfigService.getEnabled(config.getDatasourceId());
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
