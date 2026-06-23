package com.chatchat.mcpserver.database;

import com.chatchat.agents.protocol.ModelProtocolJson;
import com.chatchat.agents.tool.ToolRegistry;
import com.chatchat.common.response.ApiResponse;
import com.chatchat.common.tool.ToolOutput;
import com.chatchat.mcpserver.sql.SqlDatasourceConfig;
import com.chatchat.mcpserver.sql.SqlDatasourceConfigService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/database-query")
public class DatabaseQueryAdminController {

    private static final String TOOL_NAME = "database_query";

    private final ToolRegistry toolRegistry;
    private final DatabaseQueryConfigService configService;
    private final DatabaseQueryInvokeService invokeService;
    private final DatabaseQueryMcpToolPublisher publisher;
    private final ObjectMapper objectMapper;
    private final SqlDatasourceConfigService datasourceConfigService;

    @Value("${spring.datasource.url:}")
    private String applicationJdbcUrl;

    /**
     * Lists the list.
     *
     * @return the list list
     */
    @GetMapping
    public ApiResponse<List<DatabaseQueryView>> list() {
        return ApiResponse.success(configService.listAll().stream().map(this::toView).toList());
    }

    /**
     * Creates the create.
     *
     * @param request the request value
     * @return the created create
     */
    @PostMapping
    public ApiResponse<DatabaseQueryView> create(@RequestBody DatabaseQueryUpsertRequest request) {
        DatabaseQueryConfig saved = configService.create(fromRequest(request));
        publisher.refresh();
        return ApiResponse.success(toView(saved), "Database query registered");
    }

    /**
     * Updates the update.
     *
     * @param id the id value
     * @param request the request value
     * @return the updated update
     */
    @PutMapping("/{id}")
    public ApiResponse<DatabaseQueryView> update(@PathVariable("id") String id,
                                                 @RequestBody DatabaseQueryUpsertRequest request) {
        DatabaseQueryConfig saved = configService.update(id, fromRequest(request));
        publisher.refresh();
        return ApiResponse.success(toView(saved), "Database query updated");
    }

    /**
     * Deletes the delete.
     *
     * @param id the id value
     * @return the operation result
     */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable("id") String id) {
        configService.delete(id);
        publisher.refresh();
        return ApiResponse.success(null, "Database query deleted");
    }

    /**
     * Performs the batch delete operation.
     *
     * @param request the request value
     * @return the operation result
     */
    @PostMapping("/batch-delete")
    public ApiResponse<Map<String, Object>> batchDelete(@RequestBody BatchDeleteRequest request) {
        int deleted = configService.deleteAll(request.ids());
        publisher.refresh();
        return ApiResponse.success(Map.of("deleted", deleted), "Database queries deleted");
    }

    /**
     * Sets the enabled.
     *
     * @param id the id value
     * @param enabled the enabled value
     * @return the operation result
     */
    @PostMapping("/{id}/enabled")
    public ApiResponse<DatabaseQueryView> setEnabled(@PathVariable("id") String id,
                                                     @RequestParam("enabled") boolean enabled) {
        DatabaseQueryConfig saved = configService.setEnabled(id, enabled);
        publisher.refresh();
        return ApiResponse.success(toView(saved), "Database query status updated");
    }

    /**
     * Performs the test saved operation.
     *
     * @param id the id value
     * @param arguments the arguments value
     * @return the operation result
     */
    @PostMapping("/{id}/test")
    public ApiResponse<ToolOutput> testSaved(@PathVariable("id") String id,
                                             @RequestBody(required = false) Map<String, Object> arguments) {
        return ApiResponse.success(invokeService.invoke(configService.getById(id), arguments));
    }

    /**
     * Performs the test operation.
     *
     * @param request the request value
     * @return the operation result
     */
    @PostMapping("/test")
    public ApiResponse<ToolOutput> test(@RequestBody DatabaseQueryTestRequest request) {
        if (!toolRegistry.hasTool(TOOL_NAME)) {
            return ApiResponse.internalError("database_query tool is not registered");
        }
        return ApiResponse.success(invokeService.invoke(toParameters(request)));
    }

    /**
     * Creates the value from request.
     *
     * @param request the request value
     * @return the operation result
     */
    private DatabaseQueryConfig fromRequest(DatabaseQueryUpsertRequest request) {
        DatabaseQueryConfig config = new DatabaseQueryConfig();
        config.setToolName(request.toolName());
        config.setTitle(request.title());
        config.setDatasourceId(request.datasourceId());
        config.setDescription(request.description());
        config.setSqlTemplate(request.sqlTemplate());
        config.setInputSchemaJson(writeJson(request.inputSchema()));
        config.setGovernanceJson(writeJson(request.governance()));
        config.setMaxRows(request.maxRows() == null ? 50 : request.maxRows());
        config.setJdbcUrl(request.jdbcUrl());
        config.setDriverClass(request.driverClass());
        config.setUsername(request.username());
        config.setPassword(request.password());
        config.setReloadDrivers(request.reloadDrivers() != null && request.reloadDrivers());
        config.setEnabled(request.enabled() == null || request.enabled());
        return config;
    }

    /**
     * Converts the value to view.
     *
     * @param config the config value
     * @return the converted view
     */
    private DatabaseQueryView toView(DatabaseQueryConfig config) {
        return new DatabaseQueryView(
            config.getId(),
            config.getToolName(),
            config.getTitle(),
            config.getDatasourceId(),
            config.getDescription(),
            config.getSqlTemplate(),
            readJsonMap(config.getInputSchemaJson()),
            readJsonMap(config.getGovernanceJson()),
            config.getMaxRows(),
            config.getJdbcUrl(),
            config.getDriverClass(),
            config.getUsername(),
            config.getPassword(),
            config.isReloadDrivers(),
            config.isEnabled(),
            config.getCreatedAt() == null ? null : config.getCreatedAt().toEpochMilli(),
            config.getUpdatedAt() == null ? null : config.getUpdatedAt().toEpochMilli()
        );
    }

    /**
     * Converts the value to parameters.
     *
     * @param request the request value
     * @return the converted parameters
     */
    private Map<String, Object> toParameters(DatabaseQueryTestRequest request) {
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("sql", request.sql());
        parameters.put("params", request.params() == null ? Map.of() : request.params());
        parameters.put("max_rows", request.maxRows());
        if (request.datasourceId() != null && !request.datasourceId().isBlank()) {
            SqlDatasourceConfig datasource = datasourceConfigService.getEnabled(request.datasourceId());
            parameters.put("jdbc_url", datasource.getJdbcUrl());
            putIfPresent(parameters, "driver_class", datasource.getDriverClass());
            putIfPresent(parameters, "username", datasource.getUsername());
            putIfPresent(parameters, "password", datasource.getPassword());
            parameters.put("datasource_id", datasource.getId());
            parameters.put("datasource_name", datasource.getName());
            parameters.put("reload_drivers", false);
        } else {
            parameters.put("jdbc_url", requireJdbcUrl(request.jdbcUrl()));
            putIfPresent(parameters, "driver_class", request.driverClass());
            putIfPresent(parameters, "username", request.username());
            putIfPresent(parameters, "password", request.password());
            parameters.put("reload_drivers", request.reloadDrivers() != null && request.reloadDrivers());
        }
        return parameters;
    }

    /**
     * Writes the json.
     *
     * @param map the map value
     * @return the operation result
     */
    private String writeJson(Map<String, Object> map) {
        if (map == null || map.isEmpty()) {
            return null;
        }
        try {
            return ModelProtocolJson.compact(map);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("inputSchema is invalid");
        }
    }

    /**
     * Reads the json map.
     *
     * @param json the json value
     * @return the operation result
     */
    private Map<String, Object> readJsonMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception ex) {
            return Map.of();
        }
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
     * Performs the require jdbc url operation.
     *
     * @param value the value value
     * @return the operation result
     */
    private String requireJdbcUrl(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("jdbcUrl is required; local configuration database queries are forbidden");
        }
        String jdbcUrl = value.trim();
        if (isApplicationJdbcUrl(jdbcUrl)) {
            throw new IllegalArgumentException("local configuration database queries are forbidden");
        }
        return jdbcUrl;
    }

    /**
     * Returns whether is application jdbc url.
     *
     * @param jdbcUrl the jdbc url value
     * @return whether the condition is satisfied
     */
    private boolean isApplicationJdbcUrl(String jdbcUrl) {
        return applicationJdbcUrl != null
            && !applicationJdbcUrl.isBlank()
            && applicationJdbcUrl.trim().equalsIgnoreCase(jdbcUrl.trim());
    }

    public record DatabaseQueryTestRequest(
        String sql,
        Map<String, Object> params,
        Integer maxRows,
        String datasourceId,
        String jdbcUrl,
        String driverClass,
        String username,
        String password,
        Boolean reloadDrivers
    ) {
    }

    public record DatabaseQueryUpsertRequest(
        String toolName,
        String title,
        String datasourceId,
        String description,
        String sqlTemplate,
        Map<String, Object> inputSchema,
        Map<String, Object> governance,
        Integer maxRows,
        String jdbcUrl,
        String driverClass,
        String username,
        String password,
        Boolean reloadDrivers,
        Boolean enabled
    ) {
    }

    public record BatchDeleteRequest(List<String> ids) {
    }

    public record DatabaseQueryView(
        String id,
        String toolName,
        String title,
        String datasourceId,
        String description,
        String sqlTemplate,
        Map<String, Object> inputSchema,
        Map<String, Object> governance,
        int maxRows,
        String jdbcUrl,
        String driverClass,
        String username,
        String password,
        boolean reloadDrivers,
        boolean enabled,
        Long createdAt,
        Long updatedAt
    ) {
    }
}
