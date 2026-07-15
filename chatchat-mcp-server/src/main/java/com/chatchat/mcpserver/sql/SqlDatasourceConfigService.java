package com.chatchat.mcpserver.sql;

import com.chatchat.agents.protocol.ModelProtocolJson;

import com.chatchat.mcpserver.routing.AssetExecutionTargetBinding;
import com.chatchat.mcpserver.routing.ExecutionTargetService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
    private final ExecutionTargetService executionTargetService;
    private final SqlMetadataAssetRegistryService metadataAssetRegistryService;

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
        SqlDatasourceConfig saved = repository.save(config);
        metadataAssetRegistryService.syncDefaultForDatasource(saved);
        syncExecutionTargets(saved, config.getExecutionTargets());
        return saved;
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
        config.setDatabaseType(firstText(request.getDatabaseType(), config.getDatabaseType()));
        config.setUsername(blankToNull(request.getUsername()));
        config.setPassword(blankToNull(request.getPassword()));
        config.setEnabled(request.isEnabled());
        config.setEnvironment(firstText(request.getEnvironment(), config.getEnvironment()));
        config.setRuntimeAction("confirm_required");
        config.setMetadataScopeType(request.getMetadataScopeType());
        config.setMetadataScopeValue(blankToNull(request.getMetadataScopeValue()));
        config.setMetadataAutoRefreshEnabled(request.isMetadataAutoRefreshEnabled());
        config.setMetadataRefreshIntervalMinutes(request.getMetadataRefreshIntervalMinutes());
        config.setRoutingLabelsJson(request.getRoutingLabelsJson());
        config.setRoutingLabels(request.getRoutingLabels());
        config.setCapabilitiesJson(request.getCapabilitiesJson());
        config.setCapabilities(request.getCapabilities());
        config.setDefaultTimeoutSeconds(request.getDefaultTimeoutSeconds());
        config.setDefaultMaxRows(request.getDefaultMaxRows());
        config.setSensitiveTablesJson(normalizeJsonArray(request.getSensitiveTablesJson(), "sensitiveTables"));
        config.setSensitiveFieldsJson(normalizeJsonArray(request.getSensitiveFieldsJson(), "sensitiveFields"));
        config.setAllowedTablesJson(normalizeJsonArray(request.getAllowedTablesJson(), "allowedTables"));
        config.setAllowedTemplatesJson(normalizeJsonArray(request.getAllowedTemplatesJson(), "allowedTemplates"));
        config.setGovernanceJson(normalizeJsonObject(request.getGovernanceJson(), "governance"));
        normalize(config, id);
        SqlDatasourceConfig saved = repository.save(config);
        metadataAssetRegistryService.syncDefaultForDatasource(saved);
        syncExecutionTargets(saved, request.getExecutionTargets());
        return saved;
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

    @Transactional
    public void delete(String id) {
        SqlDatasourceConfig config = getById(id);
        repository.delete(config);
        metadataAssetRegistryService.deleteByDatasource(id);
    }

    private void normalize(SqlDatasourceConfig config, String currentId) {
        config.setName(firstText(config.getName(), config.getJdbcUrl()));
        assertUniqueName(config.getName(), currentId);
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
        config.setDatabaseType(normalizeDatabaseType(config.getDatabaseType(), config.getJdbcUrl(), config.getDriverClass()));
        config.setEnvironment(normalizeEnvironment(config.getEnvironment()));
        config.setRuntimeAction("confirm_required");
        config.setMetadataScopeType(normalizeMetadataScopeType(config.getMetadataScopeType()));
        config.setMetadataScopeValue(blankToNull(config.getMetadataScopeValue()));
        config.setMetadataRefreshIntervalMinutes(normalizeMetadataRefreshIntervalMinutes(config.getMetadataRefreshIntervalMinutes()));
        config.setRoutingLabelsJson(normalizeJsonArray(mergedProtocolValues(config.getRoutingLabelsJson(), config.getRoutingLabels()), "routingLabels"));
        String capabilitiesJson = normalizeJsonArray(
            mergedProtocolValues(config.getCapabilitiesJson(), config.getCapabilities()), "capabilities");
        config.setCapabilitiesJson("[]".equals(capabilitiesJson)
            ? ModelProtocolJson.compact(List.of("jdbc", "sql_query_execute"))
            : capabilitiesJson);
        config.setDefaultTimeoutSeconds(Math.max(1, Math.min(config.getDefaultTimeoutSeconds(), 60)));
        config.setDefaultMaxRows(Math.max(1, Math.min(config.getDefaultMaxRows(), 5000)));
        config.setSensitiveTablesJson(normalizeJsonArray(config.getSensitiveTablesJson(), "sensitiveTables"));
        config.setSensitiveFieldsJson(normalizeJsonArray(config.getSensitiveFieldsJson(), "sensitiveFields"));
        config.setAllowedTablesJson(normalizeJsonArray(config.getAllowedTablesJson(), "allowedTables"));
        config.setAllowedTemplatesJson(normalizeJsonArray(config.getAllowedTemplatesJson(), "allowedTemplates"));
        config.setGovernanceJson(normalizeJsonObject(
            mergeGovernanceLabels(config.getGovernanceJson(), config.getRoutingLabelsJson(), config.getCapabilitiesJson()),
            "governance"
        ));
    }

    private void assertUniqueName(String name, String currentId) {
        String normalized = requireText(name, "SQL datasource name cannot be empty");
        repository.findByNameIgnoreCase(normalized)
            .filter(existing -> currentId == null || !existing.getId().equals(currentId))
            .ifPresent(existing -> {
                throw new IllegalArgumentException("SQL datasource name already exists: " + normalized);
            });
    }

    private void syncExecutionTargets(SqlDatasourceConfig asset, List<AssetExecutionTargetBinding> bindings) {
        if (asset == null || bindings == null || bindings.isEmpty()) {
            return;
        }
        for (AssetExecutionTargetBinding binding : bindings) {
            if (binding == null || binding.targetKey() == null || binding.targetKey().isBlank()) {
                continue;
            }
            executionTargetService.upsertAssetBinding(
                binding,
                ExecutionTargetService.ASSET_TYPE_SQL_DATASOURCE,
                asset.getEnvironment(),
                "TOOL_NAME",
                asset.getToolName()
            );
        }
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

    private String normalizeMetadataScopeType(String value) {
        String normalized = firstText(value, "JDBC_DATABASE").trim().toUpperCase(Locale.ROOT)
            .replace('-', '_')
            .replace(' ', '_');
        return switch (normalized) {
            case "JDBC_DATABASE", "LOGIN_USER_SCHEMA", "EXPLICIT_SCHEMA" -> normalized;
            default -> "JDBC_DATABASE";
        };
    }

    private int normalizeMetadataRefreshIntervalMinutes(int value) {
        int minutes = value <= 0 ? 60 : value;
        return Math.max(5, Math.min(minutes, 7 * 24 * 60));
    }

    public static String normalizeDatabaseType(String value, String jdbcUrl, String driverClass) {
        String explicit = blankToNullStatic(value);
        if (explicit != null && !"generic".equalsIgnoreCase(explicit)) {
            return normalizeDatabaseTypeToken(explicit);
        }
        String inferred = inferDatabaseType(jdbcUrl, driverClass);
        return inferred == null ? "generic" : inferred;
    }

    public static String normalizeDatabaseTypeToken(String value) {
        String normalized = blankToNullStatic(value);
        if (normalized == null) {
            return "generic";
        }
        normalized = normalized.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_\\-]", "_");
        return normalized.isBlank() ? "generic" : normalized;
    }

    private static String inferDatabaseType(String jdbcUrl, String driverClass) {
        String probe = ((jdbcUrl == null ? "" : jdbcUrl) + " " + (driverClass == null ? "" : driverClass))
            .toLowerCase(Locale.ROOT);
        if (probe.contains("tdsql")) {
            return "tdsql";
        }
        if (probe.contains("oceanbase")) {
            return "oceanbase";
        }
        if (probe.contains("tidb")) {
            return "tidb";
        }
        if (probe.contains("jdbc:kingbase") || probe.contains("kingbase")) {
            return "kingbase";
        }
        if (probe.contains("jdbc:mysql") || probe.contains("mysql")) {
            return "mysql";
        }
        if (probe.contains("jdbc:postgresql") || probe.contains("postgresql") || probe.contains("postgres")) {
            return "postgresql";
        }
        if (probe.contains("jdbc:oracle") || probe.contains("oracle")) {
            return "oracle";
        }
        if (probe.contains("jdbc:hive2") || probe.contains("hive")) {
            return "hive";
        }
        if (probe.contains("inceptor")) {
            return "inceptor";
        }
        if (probe.contains("goldendb")) {
            return "goldendb";
        }
        if (probe.contains("jdbc:dm") || probe.contains("dm.jdbc") || probe.contains("dameng")) {
            return "dm";
        }
        if (probe.contains("jdbc:sqlserver") || probe.contains("sqlserver")) {
            return "sqlserver";
        }
        if (probe.contains("jdbc:mariadb") || probe.contains("mariadb")) {
            return "mariadb";
        }
        if (probe.contains("jdbc:clickhouse") || probe.contains("clickhouse")) {
            return "clickhouse";
        }
        return null;
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

    private String mergedProtocolValues(String json, List<String> values) {
        LinkedHashSet<String> merged = new LinkedHashSet<>();
        readJsonArray(json).forEach(merged::add);
        if (values != null) {
            values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .forEach(merged::add);
        }
        return merged.isEmpty() ? null : ModelProtocolJson.compact(new ArrayList<>(merged));
    }

    private String mergeGovernanceLabels(String governanceJson, String routingLabelsJson, String capabilitiesJson) {
        Map<String, Object> governance = new LinkedHashMap<>();
        if (governanceJson != null && !governanceJson.isBlank()) {
            try {
                governance.putAll(objectMapper.readValue(governanceJson, new TypeReference<Map<String, Object>>() {}));
            } catch (Exception ex) {
                throw new IllegalArgumentException("governance must be a JSON object");
            }
        }
        LinkedHashSet<String> labels = new LinkedHashSet<>();
        labels.addAll(stringValues(governance.get("labels")));
        readJsonArray(routingLabelsJson).forEach(labels::add);
        if (!labels.isEmpty()) {
            governance.put("labels", new ArrayList<>(labels));
        }
        LinkedHashSet<String> capabilities = new LinkedHashSet<>();
        capabilities.addAll(stringValues(governance.get("capabilities")));
        readJsonArray(capabilitiesJson).forEach(capabilities::add);
        if (!capabilities.isEmpty()) {
            governance.put("capabilities", new ArrayList<>(capabilities));
        }
        return governance.isEmpty() ? null : ModelProtocolJson.compact(governance);
    }

    private List<String> stringValues(Object value) {
        if (value instanceof List<?> list) {
            return list.stream()
                .filter(item -> item != null && !String.valueOf(item).isBlank())
                .map(item -> String.valueOf(item).trim())
                .toList();
        }
        if (value == null || String.valueOf(value).isBlank()) {
            return List.of();
        }
        return List.of(String.valueOf(value).trim());
    }

    private List<String> readJsonArray(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {}).stream()
                .filter(item -> item != null && !item.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
        } catch (Exception ignored) {
            return List.of();
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

    private static String blankToNullStatic(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
