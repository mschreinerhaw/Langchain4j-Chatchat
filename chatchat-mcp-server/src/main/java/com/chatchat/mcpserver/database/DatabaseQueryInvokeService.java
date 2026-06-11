package com.chatchat.mcpserver.database;

import com.chatchat.agents.tool.ToolRegistry;
import com.chatchat.common.tool.ToolInput;
import com.chatchat.common.tool.ToolLogSummarizer;
import com.chatchat.common.tool.ToolOutput;
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

    public ToolOutput invoke(DatabaseQueryConfig config, Map<String, Object> arguments) {
        log.info("Database query invoke started databaseQueryId={} tool={} maxRows={} sql={} args={}",
            config.getId(),
            config.getToolName(),
            config.getMaxRows(),
            config.getSqlTemplate(),
            ToolLogSummarizer.summarize(arguments));
        return invoke(toParameters(config, arguments == null ? Map.of() : arguments));
    }

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

    private Map<String, Object> toParameters(DatabaseQueryConfig config, Map<String, Object> arguments) {
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("sql", config.getSqlTemplate());
        parameters.put("params", arguments);
        parameters.put("max_rows", config.getMaxRows());
        putIfPresent(parameters, "jdbc_url", config.getJdbcUrl());
        putIfPresent(parameters, "driver_class", config.getDriverClass());
        putIfPresent(parameters, "username", config.getUsername());
        putIfPresent(parameters, "password", config.getPassword());
        parameters.put("reload_drivers", config.isReloadDrivers());
        return parameters;
    }

    private void putIfPresent(Map<String, Object> parameters, String key, String value) {
        if (value != null && !value.isBlank()) {
            parameters.put(key, value.trim());
        }
    }

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
