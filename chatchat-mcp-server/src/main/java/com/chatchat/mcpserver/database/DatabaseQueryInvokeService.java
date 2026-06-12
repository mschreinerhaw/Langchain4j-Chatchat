package com.chatchat.mcpserver.database;

import com.chatchat.agents.tool.ToolRegistry;
import com.chatchat.common.tool.ToolInput;
import com.chatchat.common.tool.ToolLogSummarizer;
import com.chatchat.common.tool.ToolOutput;
import com.chatchat.mcpserver.sql.SqlDatasourceConfig;
import com.chatchat.mcpserver.sql.SqlDatasourceConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class DatabaseQueryInvokeService {

    private static final String TOOL_NAME = "database_query";

    private final ToolRegistry toolRegistry;
    private final SqlDatasourceConfigService datasourceConfigService;

    /**
     * Performs the invoke operation.
     *
     * @param config the config value
     * @param arguments the arguments value
     * @return the operation result
     */
    public ToolOutput invoke(DatabaseQueryConfig config, Map<String, Object> arguments) {
        log.info("Database query invoke started databaseQueryId={} tool={} maxRows={} sql={} args={}",
            config.getId(),
            config.getToolName(),
            config.getMaxRows(),
            config.getSqlTemplate(),
            ToolLogSummarizer.summarize(arguments));
        return invoke(toParameters(config, arguments == null ? Map.of() : arguments));
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
            log.warn("Database query invoke failed tool={} durationMs={} error={}",
                TOOL_NAME,
                Math.max(0L, System.currentTimeMillis() - startedAt),
                "database_query tool is not registered");
            return ToolOutput.failure("database_query tool is not registered");
        }
        ToolInput input = ToolInput.builder()
            .requestId(UUID.randomUUID().toString())
            .userId("admin")
            .parameters(parameters)
            .build();
        ToolOutput output = toolRegistry.executeEnhancedTool(TOOL_NAME, input);
        long durationMs = Math.max(0L, System.currentTimeMillis() - startedAt);
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
        if (config.getDatasourceId() != null && !config.getDatasourceId().isBlank()) {
            SqlDatasourceConfig datasource = datasourceConfigService.getEnabled(config.getDatasourceId());
            putIfPresent(parameters, "jdbc_url", datasource.getJdbcUrl());
            putIfPresent(parameters, "driver_class", datasource.getDriverClass());
            putIfPresent(parameters, "username", datasource.getUsername());
            putIfPresent(parameters, "password", datasource.getPassword());
            parameters.put("datasource_id", datasource.getId());
            parameters.put("datasource_name", datasource.getName());
            parameters.put("reload_drivers", false);
        } else {
            putIfPresent(parameters, "jdbc_url", config.getJdbcUrl());
            putIfPresent(parameters, "driver_class", config.getDriverClass());
            putIfPresent(parameters, "username", config.getUsername());
            putIfPresent(parameters, "password", config.getPassword());
            parameters.put("reload_drivers", config.isReloadDrivers());
        }
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
