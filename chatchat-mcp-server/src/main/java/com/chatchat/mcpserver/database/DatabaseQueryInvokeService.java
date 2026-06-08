package com.chatchat.mcpserver.database;

import com.chatchat.agents.tool.ToolRegistry;
import com.chatchat.common.tool.ToolInput;
import com.chatchat.common.tool.ToolOutput;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class DatabaseQueryInvokeService {

    private static final String TOOL_NAME = "database_query";

    private final ToolRegistry toolRegistry;

    public ToolOutput invoke(DatabaseQueryConfig config, Map<String, Object> arguments) {
        return invoke(toParameters(config, arguments == null ? Map.of() : arguments));
    }

    public ToolOutput invoke(Map<String, Object> parameters) {
        if (!toolRegistry.hasTool(TOOL_NAME)) {
            return ToolOutput.failure("database_query tool is not registered");
        }
        ToolInput input = ToolInput.builder()
            .requestId(UUID.randomUUID().toString())
            .userId("admin")
            .parameters(parameters)
            .build();
        return toolRegistry.executeEnhancedTool(TOOL_NAME, input);
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
}
