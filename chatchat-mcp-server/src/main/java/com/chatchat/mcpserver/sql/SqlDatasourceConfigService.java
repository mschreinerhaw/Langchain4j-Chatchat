package com.chatchat.mcpserver.sql;

import com.chatchat.agents.protocol.ModelProtocolJson;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class SqlDatasourceConfigService {

    private static final Pattern TOOL_NAME_PATTERN = Pattern.compile("^db_query_[A-Za-z0-9_]{2,120}$");

    private final SqlDatasourceConfigRepository repository;
    private final ObjectMapper objectMapper;

    @Value("${spring.datasource.url:}")
    private String applicationJdbcUrl;

    public List<SqlDatasourceConfig> listAll() {
        return repository.findAll().stream()
            .sorted(Comparator.comparing(SqlDatasourceConfig::getName))
            .toList();
    }

    public List<SqlDatasourceConfig> listEnabled() {
        return repository.findByEnabledTrueOrderByNameAsc().stream()
            .filter(config -> !isApplicationJdbcUrl(config.getJdbcUrl()))
            .toList();
    }

    @Transactional
    public SqlDatasourceConfig create(SqlDatasourceConfig config) {
        normalize(config, null);
        return repository.save(config);
    }

    @Transactional
    public SqlDatasourceConfig update(String id, SqlDatasourceConfig request) {
        SqlDatasourceConfig config = getById(id);
        config.setName(firstText(request.getName(), config.getName()));
        config.setToolName(firstText(request.getToolName(), config.getToolName()));
        config.setTitle(firstText(request.getTitle(), config.getTitle()));
        config.setDescription(blankToNull(request.getDescription()));
        config.setJdbcUrl(firstText(request.getJdbcUrl(), config.getJdbcUrl()));
        config.setDriverClass(blankToNull(request.getDriverClass()));
        config.setUsername(blankToNull(request.getUsername()));
        config.setPassword(blankToNull(request.getPassword()));
        config.setEnabled(request.isEnabled());
        config.setEnvironment(firstText(request.getEnvironment(), config.getEnvironment()));
        config.setRuntimeAction("confirm_required");
        config.setDefaultTimeoutSeconds(request.getDefaultTimeoutSeconds());
        config.setDefaultMaxRows(request.getDefaultMaxRows());
        config.setSensitiveTablesJson(normalizeJsonArray(request.getSensitiveTablesJson(), "sensitiveTables"));
        config.setSensitiveFieldsJson(normalizeJsonArray(request.getSensitiveFieldsJson(), "sensitiveFields"));
        config.setAllowedTablesJson(normalizeJsonArray(request.getAllowedTablesJson(), "allowedTables"));
        config.setGovernanceJson(normalizeJsonObject(request.getGovernanceJson(), "governance"));
        normalize(config, id);
        return repository.save(config);
    }

    public SqlDatasourceConfig getEnabled(String id) {
        SqlDatasourceConfig config = getById(id);
        if (!config.isEnabled()) {
            throw new IllegalArgumentException("SQL datasource is disabled: " + id);
        }
        if (isApplicationJdbcUrl(config.getJdbcUrl())) {
            throw new IllegalArgumentException("Local configuration datasource is forbidden");
        }
        return config;
    }

    public SqlDatasourceConfig getById(String id) {
        return repository.findById(requireText(id, "datasourceId is required"))
            .orElseThrow(() -> new IllegalArgumentException("SQL datasource not found: " + id));
    }

    private void normalize(SqlDatasourceConfig config, String currentId) {
        config.setName(firstText(config.getName(), config.getJdbcUrl()));
        config.setToolName(normalizeToolName(firstText(config.getToolName(), defaultToolName(config))));
        repository.findByToolNameIgnoreCase(config.getToolName())
            .filter(existing -> currentId == null || !existing.getId().equals(currentId))
            .ifPresent(existing -> {
                throw new IllegalArgumentException("SQL datasource toolName already exists: " + config.getToolName());
            });
        config.setTitle(firstText(config.getTitle(), config.getName()));
        config.setDescription(firstText(config.getDescription(),
            "用途：查询 " + config.getName() + "，只允许 SELECT/SHOW/DESCRIBE/EXPLAIN。禁止任何写入、DDL、权限、删除、更新操作。"));
        config.setJdbcUrl(requireText(config.getJdbcUrl(), "jdbcUrl is required"));
        if (isApplicationJdbcUrl(config.getJdbcUrl())) {
            throw new IllegalArgumentException("Local configuration datasource is forbidden");
        }
        config.setEnvironment(normalizeEnvironment(config.getEnvironment()));
        config.setRuntimeAction("confirm_required");
        config.setDefaultTimeoutSeconds(Math.max(1, Math.min(config.getDefaultTimeoutSeconds(), 60)));
        config.setDefaultMaxRows(Math.max(1, Math.min(config.getDefaultMaxRows(), 5000)));
        config.setSensitiveTablesJson(normalizeJsonArray(config.getSensitiveTablesJson(), "sensitiveTables"));
        config.setSensitiveFieldsJson(normalizeJsonArray(config.getSensitiveFieldsJson(), "sensitiveFields"));
        config.setAllowedTablesJson(normalizeJsonArray(config.getAllowedTablesJson(), "allowedTables"));
        config.setGovernanceJson(normalizeJsonObject(config.getGovernanceJson(), "governance"));
    }

    private String normalizeToolName(String value) {
        String toolName = requireText(value, "toolName is required").trim().toLowerCase(Locale.ROOT);
        if (!TOOL_NAME_PATTERN.matcher(toolName).matches()) {
            throw new IllegalArgumentException("toolName must follow db_query_{dbType}_{domain}_{env}");
        }
        return toolName;
    }

    private String defaultToolName(SqlDatasourceConfig config) {
        String env = firstText(config.getEnvironment(), "dev").toLowerCase(Locale.ROOT);
        String base = firstText(config.getName(), "database")
            .toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9]+", "_")
            .replaceAll("_+", "_")
            .replaceAll("^_|_$", "");
        if (base.isBlank()) {
            base = "database";
        }
        return "db_query_" + base + "_" + env;
    }

    private String normalizeEnvironment(String value) {
        String normalized = firstText(value, "DEV").toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "DEV", "TEST", "UAT", "PROD" -> normalized;
            default -> "DEV";
        };
    }

    private String normalizeJsonArray(String json, String field) {
        String value = blankToNull(json);
        if (value == null) {
            return null;
        }
        try {
            List<String> items = objectMapper.readValue(value, new TypeReference<>() {});
            return ModelProtocolJson.compact(items);
        } catch (Exception ex) {
            throw new IllegalArgumentException(field + " must be a JSON string array");
        }
    }

    private String normalizeJsonObject(String json, String field) {
        String value = blankToNull(json);
        if (value == null) {
            return null;
        }
        try {
            Map<String, Object> parsed = objectMapper.readValue(value, new TypeReference<>() {});
            return ModelProtocolJson.compact(parsed);
        } catch (Exception ex) {
            throw new IllegalArgumentException(field + " must be a JSON object");
        }
    }

    private boolean isApplicationJdbcUrl(String jdbcUrl) {
        return applicationJdbcUrl != null
            && !applicationJdbcUrl.isBlank()
            && jdbcUrl != null
            && applicationJdbcUrl.trim().equalsIgnoreCase(jdbcUrl.trim());
    }

    private String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }

    private String firstText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
