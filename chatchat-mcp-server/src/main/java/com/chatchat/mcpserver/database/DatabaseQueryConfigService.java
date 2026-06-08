package com.chatchat.mcpserver.database;

import com.chatchat.agents.tool.ToolRegistry;
import com.chatchat.mcpserver.api.ApiServiceConfigRepository;
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

    @Value("${spring.datasource.url:}")
    private String applicationJdbcUrl;

    @Transactional(readOnly = true)
    public List<DatabaseQueryConfig> listAll() {
        return repository.findAllByOrderByToolNameAsc();
    }

    @Transactional(readOnly = true)
    public List<DatabaseQueryConfig> listEnabled() {
        return repository.findByEnabledTrueOrderByToolNameAsc().stream()
            .filter(config -> blankToNull(config.getJdbcUrl()) != null)
            .filter(config -> !isApplicationJdbcUrl(config.getJdbcUrl()))
            .toList();
    }

    @Transactional(readOnly = true)
    public DatabaseQueryConfig getById(String id) {
        return repository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Database query config not found: " + id));
    }

    @Transactional
    public DatabaseQueryConfig create(DatabaseQueryConfig draft) {
        validate(draft, null);
        return repository.save(draft);
    }

    @Transactional
    public DatabaseQueryConfig update(String id, DatabaseQueryConfig draft) {
        DatabaseQueryConfig current = getById(id);
        current.setToolName(draft.getToolName());
        current.setTitle(draft.getTitle());
        current.setDescription(draft.getDescription());
        current.setSqlTemplate(draft.getSqlTemplate());
        current.setInputSchemaJson(draft.getInputSchemaJson());
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

    @Transactional
    public DatabaseQueryConfig setEnabled(String id, boolean enabled) {
        DatabaseQueryConfig current = getById(id);
        if (enabled && blankToNull(current.getJdbcUrl()) == null) {
            throw new IllegalArgumentException("jdbcUrl is required; local configuration database queries are forbidden");
        }
        if (enabled && isApplicationJdbcUrl(current.getJdbcUrl())) {
            throw new IllegalArgumentException("local configuration database queries are forbidden");
        }
        current.setEnabled(enabled);
        return repository.save(current);
    }

    @Transactional
    public void delete(String id) {
        repository.deleteById(id);
    }

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
        config.setDescription(blankToNull(config.getDescription()));
        config.setSqlTemplate(normalizeRequired(config.getSqlTemplate(), "sql"));
        config.setInputSchemaJson(normalizeJsonObject(config.getInputSchemaJson()));
        config.setMaxRows(config.getMaxRows() <= 0 ? 50 : Math.min(500, config.getMaxRows()));
        String jdbcUrl = normalizeRequired(config.getJdbcUrl(), "jdbcUrl");
        if (isApplicationJdbcUrl(jdbcUrl)) {
            throw new IllegalArgumentException("local configuration database queries are forbidden");
        }
        config.setJdbcUrl(jdbcUrl);
        config.setDriverClass(blankToNull(config.getDriverClass()));
        config.setUsername(blankToNull(config.getUsername()));
        config.setPassword(blankToNull(config.getPassword()));
    }

    private String normalizeJsonObject(String json) {
        String value = blankToNull(json);
        if (value == null) {
            return null;
        }
        try {
            Map<String, Object> map = objectMapper.readValue(value, new TypeReference<>() {});
            return objectMapper.writeValueAsString(map);
        } catch (Exception ex) {
            throw new IllegalArgumentException("inputSchema must be a valid JSON object");
        }
    }

    private String normalizeRequired(String value, String fieldName) {
        String normalized = blankToNull(value);
        if (normalized == null) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
        return normalized;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private boolean isApplicationJdbcUrl(String jdbcUrl) {
        return applicationJdbcUrl != null
            && !applicationJdbcUrl.isBlank()
            && applicationJdbcUrl.trim().equalsIgnoreCase(jdbcUrl.trim());
    }
}
