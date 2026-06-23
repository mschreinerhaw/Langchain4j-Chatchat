package com.chatchat.mcpserver.database;

import com.chatchat.agents.protocol.ModelProtocolJson;

import com.chatchat.agents.tool.ToolRegistry;
import com.chatchat.mcpserver.api.ApiServiceConfigRepository;
import com.chatchat.mcpserver.sql.SqlDatasourceConfigService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class DatabaseQueryConfigService {

    private static final Pattern TOOL_NAME_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{1,128}$");
    private static final Set<String> RESERVED_TOOL_NAMES = Set.of("database_query");

    private final DatabaseQueryConfigRepository repository;
    private final ApiServiceConfigRepository apiServiceConfigRepository;
    private final ToolRegistry toolRegistry;
    private final ObjectMapper objectMapper;
    private final SqlDatasourceConfigService datasourceConfigService;

    @Value("${spring.datasource.url:}")
    private String applicationJdbcUrl;

    /**
     * Lists the all.
     *
     * @return the all list
     */
    @Transactional(readOnly = true)
    public List<DatabaseQueryConfig> listAll() {
        return repository.findAllByOrderByToolNameAsc();
    }

    /**
     * Lists the enabled.
     *
     * @return the enabled list
     */
    @Transactional(readOnly = true)
    public List<DatabaseQueryConfig> listEnabled() {
        return repository.findByEnabledTrueOrderByToolNameAsc().stream()
            .filter(this::hasUsableDatasource)
            .toList();
    }

    /**
     * Returns the by id.
     *
     * @param id the id value
     * @return the by id
     */
    @Transactional(readOnly = true)
    public DatabaseQueryConfig getById(String id) {
        return repository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Database query config not found: " + id));
    }

    /**
     * Creates the create.
     *
     * @param draft the draft value
     * @return the created create
     */
    @Transactional
    public DatabaseQueryConfig create(DatabaseQueryConfig draft) {
        validate(draft, null);
        return repository.save(draft);
    }

    /**
     * Updates the update.
     *
     * @param id the id value
     * @param draft the draft value
     * @return the updated update
     */
    @Transactional
    public DatabaseQueryConfig update(String id, DatabaseQueryConfig draft) {
        DatabaseQueryConfig current = getById(id);
        current.setToolName(draft.getToolName());
        current.setTitle(draft.getTitle());
        current.setDatasourceId(draft.getDatasourceId());
        current.setDescription(draft.getDescription());
        current.setSqlTemplate(draft.getSqlTemplate());
        current.setInputSchemaJson(draft.getInputSchemaJson());
        current.setGovernanceJson(draft.getGovernanceJson());
        current.setMaxRows(draft.getMaxRows());
        current.setJdbcUrl(draft.getJdbcUrl());
        current.setDriverClass(draft.getDriverClass());
        current.setUsername(draft.getUsername());
        current.setPassword(draft.getPassword());
        current.setReloadDrivers(draft.isReloadDrivers());
        current.setEnabled(draft.isEnabled());
        validate(current, id);
        return repository.save(current);
    }

    /**
     * Sets the enabled.
     *
     * @param id the id value
     * @param enabled the enabled value
     * @return the operation result
     */
    @Transactional
    public DatabaseQueryConfig setEnabled(String id, boolean enabled) {
        DatabaseQueryConfig current = getById(id);
        if (enabled && !hasUsableDatasource(current)) {
            throw new IllegalArgumentException("database query requires an enabled datasource asset or external jdbcUrl");
        }
        current.setEnabled(enabled);
        return repository.save(current);
    }

    /**
     * Deletes the delete.
     *
     * @param id the id value
     */
    @Transactional
    public void delete(String id) {
        repository.deleteById(id);
    }

    /**
     * Deletes the all.
     *
     * @param ids the ids value
     * @return the operation result
     */
    @Transactional
    public int deleteAll(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        List<String> normalizedIds = ids.stream()
            .filter(id -> id != null && !id.isBlank())
            .map(String::trim)
            .distinct()
            .toList();
        if (normalizedIds.isEmpty()) {
            return 0;
        }
        List<DatabaseQueryConfig> existing = repository.findAllById(normalizedIds);
        repository.deleteAll(existing);
        return existing.size();
    }

    /**
     * Validates the validate.
     *
     * @param config the config value
     * @param currentId the current id value
     */
    private void validate(DatabaseQueryConfig config, String currentId) {
        String toolName = normalizeRequired(config.getToolName(), "toolName");
        if (!TOOL_NAME_PATTERN.matcher(toolName).matches()) {
            throw new IllegalArgumentException("toolName can only contain letters, numbers, '_' and '-'");
        }
        if (RESERVED_TOOL_NAMES.contains(toolName.toLowerCase(Locale.ROOT)) || toolRegistry.hasTool(toolName)) {
            throw new IllegalArgumentException("toolName conflicts with existing tool: " + toolName);
        }
        if (apiServiceConfigRepository.findByToolNameIgnoreCase(toolName).isPresent()) {
            throw new IllegalArgumentException("toolName conflicts with API service: " + toolName);
        }
        repository.findByToolNameIgnoreCase(toolName)
            .filter(existing -> currentId == null || !existing.getId().equals(currentId))
            .ifPresent(existing -> {
                throw new IllegalArgumentException("toolName already exists: " + toolName);
            });
        config.setToolName(toolName);
        config.setTitle(blankToNull(config.getTitle()) == null ? toolName : config.getTitle().trim());
        config.setDatasourceId(blankToNull(config.getDatasourceId()));
        config.setDescription(blankToNull(config.getDescription()));
        config.setSqlTemplate(normalizeRequired(config.getSqlTemplate(), "sql"));
        config.setInputSchemaJson(normalizeJsonObject(config.getInputSchemaJson()));
        config.setGovernanceJson(normalizeJsonObject(config.getGovernanceJson(), "governance"));
        config.setMaxRows(config.getMaxRows() <= 0 ? 50 : Math.min(500, config.getMaxRows()));
        if (config.getDatasourceId() != null) {
            datasourceConfigService.getEnabled(config.getDatasourceId());
            config.setJdbcUrl(blankToNull(config.getJdbcUrl()));
        } else {
            String jdbcUrl = normalizeRequired(config.getJdbcUrl(), "jdbcUrl");
            if (isApplicationJdbcUrl(jdbcUrl)) {
                throw new IllegalArgumentException("local configuration database queries are forbidden");
            }
            config.setJdbcUrl(jdbcUrl);
        }
        config.setDriverClass(blankToNull(config.getDriverClass()));
        config.setUsername(blankToNull(config.getUsername()));
        config.setPassword(blankToNull(config.getPassword()));
    }

    /**
     * Normalizes the json object.
     *
     * @param json the json value
     * @return the operation result
     */
    private String normalizeJsonObject(String json) {
        return normalizeJsonObject(json, "inputSchema");
    }

    /**
     * Normalizes the json object.
     *
     * @param json the json value
     * @param fieldName the field name value
     * @return the operation result
     */
    private String normalizeJsonObject(String json, String fieldName) {
        String value = blankToNull(json);
        if (value == null) {
            return null;
        }
        try {
            Map<String, Object> map = objectMapper.readValue(value, new TypeReference<>() {});
            return ModelProtocolJson.compact(map);
        } catch (Exception ex) {
            throw new IllegalArgumentException(fieldName + " must be a valid JSON object");
        }
    }

    /**
     * Normalizes the required.
     *
     * @param value the value value
     * @param fieldName the field name value
     * @return the operation result
     */
    private String normalizeRequired(String value, String fieldName) {
        String normalized = blankToNull(value);
        if (normalized == null) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
        return normalized;
    }

    /**
     * Performs the blank to null operation.
     *
     * @param value the value value
     * @return the operation result
     */
    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
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

    private boolean hasUsableDatasource(DatabaseQueryConfig config) {
        if (blankToNull(config.getDatasourceId()) != null) {
            try {
                datasourceConfigService.getEnabled(config.getDatasourceId());
                return true;
            } catch (Exception ignored) {
                return false;
            }
        }
        return blankToNull(config.getJdbcUrl()) != null && !isApplicationJdbcUrl(config.getJdbcUrl());
    }
}
