package com.chatchat.mcpserver.sql;

import com.chatchat.agents.protocol.ModelProtocolJson;
import com.chatchat.mcpserver.template.TemplateParameterValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class SqlTemplateService {

    private static final Pattern TOKEN = Pattern.compile("\\{\\{\\s*([A-Za-z0-9_.-]+)\\s*}}");

    private final SqlTemplateConfigRepository repository;
    private final ObjectMapper objectMapper;
    private final TemplateParameterValidator parameterValidator;
    private final SqlTemplateSeedProperties seedProperties;

    @Transactional
    public List<SqlTemplateConfig> listAll() {
        ensureDefaults();
        return repository.findAll().stream()
            .sorted(Comparator.comparing(SqlTemplateConfig::getCode))
            .toList();
    }

    @Transactional
    public SqlTemplateConfig save(SqlTemplateConfig config) {
        normalize(config);
        return repository.save(config);
    }

    @Transactional
    public SqlTemplateConfig update(String id, SqlTemplateConfig request) {
        SqlTemplateConfig config = getById(id);
        config.setCode(firstText(request.getCode(), config.getCode()));
        config.setTitle(firstText(request.getTitle(), config.getTitle()));
        config.setDescription(request.getDescription());
        config.setSqlTemplate(firstText(request.getSqlTemplate(), config.getSqlTemplate()));
        config.setParameterSchemaJson(request.getParameterSchemaJson());
        config.setRiskLevel(firstText(request.getRiskLevel(), config.getRiskLevel()));
        config.setCategory(firstText(request.getCategory(), config.getCategory()));
        config.setDatabaseType(firstText(request.getDatabaseType(), config.getDatabaseType()));
        config.setDatasourceId(blankToNull(request.getDatasourceId()));
        config.setRoutingLabelsJson(request.getRoutingLabelsJson());
        config.setIntentSignalsJson(request.getIntentSignalsJson());
        config.setEnabled(request.isEnabled());
        normalize(config);
        return repository.save(config);
    }

    @Transactional
    public void delete(String id) {
        SqlTemplateConfig config = getById(id);
        if (isDefaultCode(config.getCode())) {
            config.setEnabled(false);
            repository.save(config);
            return;
        }
        repository.delete(config);
    }

    @Transactional
    public List<SqlTemplateConfig> listEnabled() {
        ensureDefaults();
        return repository.findByEnabledTrueOrderByCodeAsc();
    }

    public String render(String code, Map<String, Object> parameters) {
        return render(code, parameters, null);
    }

    public String render(String code, Map<String, Object> parameters, SqlDatasourceConfig datasource) {
        return render(code, parameters, datasource, parameters);
    }

    public String render(String code, Map<String, Object> parameters, SqlDatasourceConfig datasource,
                         Map<String, Object> source) {
        ensureDefaults();
        SqlTemplateConfig config = repository.findByCode(requireText(code, "SQL template code is required").toUpperCase(Locale.ROOT))
            .filter(SqlTemplateConfig::isEnabled)
            .orElseThrow(() -> new IllegalArgumentException("SQL template not found or disabled: " + code));
        assertCompatible(config, datasource);
        Map<String, Object> collectedParameters = parameterValidator.collect(
            config.getParameterSchemaJson(),
            parameters,
            source
        );
        Map<String, Object> validatedParameters = parameterValidator.validate(
            config.getCode(),
            config.getParameterSchemaJson(),
            collectedParameters
        );
        Matcher matcher = TOKEN.matcher(config.getSqlTemplate());
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            String name = matcher.group(1);
            Object value = validatedParameters.get(name);
            String replacement = safeSqlLiteral(name, value);
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    public SqlTemplateConfig getById(String id) {
        return repository.findById(requireText(id, "SQL template ID is required"))
            .orElseThrow(() -> new IllegalArgumentException("SQL template not found: " + id));
    }

    public boolean isCompatible(SqlTemplateConfig template, SqlDatasourceConfig datasource) {
        try {
            assertCompatible(template, datasource);
            return true;
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    @Transactional
    public void ensureDefaults() {
        if (seedProperties == null || !seedProperties.isSeedDefaultsEnabled()) {
            return;
        }
        for (DefaultTemplate template : defaults()) {
            if (repository.findByCode(template.code()).isEmpty()) {
                SqlTemplateConfig config = new SqlTemplateConfig();
                config.setCode(template.code());
                config.setTitle(template.title());
                config.setDescription(template.description());
                config.setSqlTemplate(template.sql());
                config.setParameterSchemaJson(writeJson(template.schema()));
                config.setRiskLevel("MEDIUM");
                config.setCategory(categoryFromCode(template.code()));
                config.setDatabaseType("generic");
                config.setIntentSignalsJson(writeJson(List.of(template.code(), template.title(), template.description())));
                config.setEnabled(true);
                repository.save(config);
            }
        }
    }

    private void normalize(SqlTemplateConfig config) {
        config.setCode(requireText(config.getCode(), "SQL template code is required").toUpperCase(Locale.ROOT));
        if (!config.getCode().matches("[A-Z0-9_\\-]{2,128}")) {
            throw new IllegalArgumentException("SQL template code only supports uppercase letters, numbers, underscore and dash");
        }
        config.setTitle(firstText(config.getTitle(), config.getCode()));
        config.setSqlTemplate(requireText(config.getSqlTemplate(), "SQL template is required"));
        config.setRiskLevel(normalizeRisk(config.getRiskLevel()));
        config.setCategory(normalizeCategory(config.getCategory(), config.getCode()));
        config.setDatabaseType(SqlDatasourceConfigService.normalizeDatabaseTypeToken(config.getDatabaseType()));
        config.setDatasourceId(blankToNull(config.getDatasourceId()));
        config.setRoutingLabelsJson(normalizeJsonArray(config.getRoutingLabelsJson()));
        config.setParameterSchemaJson(normalizeJsonObject(config.getParameterSchemaJson()));
        config.setIntentSignalsJson(normalizeJsonArray(config.getIntentSignalsJson()));
    }

    private void assertCompatible(SqlTemplateConfig template, SqlDatasourceConfig datasource) {
        if (template == null || datasource == null) {
            return;
        }
        String templateType = SqlDatasourceConfigService.normalizeDatabaseTypeToken(template.getDatabaseType());
        String datasourceType = SqlDatasourceConfigService.normalizeDatabaseTypeToken(datasource.getDatabaseType());
        if (!"generic".equals(templateType) && !templateType.equals(datasourceType)) {
            throw new IllegalArgumentException("SQL template " + template.getCode()
                + " requires databaseType=" + templateType + ", but datasource "
                + datasource.getName() + " is databaseType=" + datasourceType);
        }
        String boundDatasourceId = blankToNull(template.getDatasourceId());
        if (boundDatasourceId != null && !boundDatasourceId.equals(datasource.getId())) {
            throw new IllegalArgumentException("SQL template " + template.getCode()
                + " is bound to another datasource asset");
        }
        List<String> allowedTemplates = readTemplateAllowlist(datasource.getAllowedTemplatesJson());
        if (!allowedTemplates.isEmpty()
            && !allowedTemplates.contains(template.getCode().trim().toUpperCase(Locale.ROOT))) {
            throw new IllegalArgumentException("SQL template " + template.getCode()
                + " is not allowed by datasource asset " + datasource.getName());
        }
    }

    private List<String> readTemplateAllowlist(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            Object value = objectMapper.readValue(json, Object.class);
            if (value instanceof List<?> list) {
                return list.stream()
                    .map(item -> item == null ? null : String.valueOf(item).trim().toUpperCase(Locale.ROOT))
                    .filter(item -> item != null && !item.isBlank())
                    .distinct()
                    .toList();
            }
        } catch (Exception ignored) {
            // Invalid stale allowlists are treated as legacy unconfigured assets.
        }
        return List.of();
    }

    private String normalizeRisk(String riskLevel) {
        String normalized = firstText(riskLevel, "MEDIUM").trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "LOW", "MEDIUM", "HIGH", "CRITICAL" -> normalized;
            default -> "MEDIUM";
        };
    }

    private String normalizeCategory(String category, String code) {
        String value = firstText(category, categoryFromCode(code));
        return value.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_\\-]", "_");
    }

    private String categoryFromCode(String code) {
        String value = code == null ? "" : code.toLowerCase(Locale.ROOT);
        if (value.contains("count")) {
            return "sql_count";
        }
        if (value.contains("recent") || value.contains("data")) {
            return "sql_sample";
        }
        return "sql_diagnostic";
    }

    private boolean isDefaultCode(String code) {
        String normalized = code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
        return defaults().stream().anyMatch(template -> template.code().equals(normalized));
    }

    private String normalizeJsonObject(String json) {
        if (json == null || json.isBlank()) {
            return writeJson(Map.of("type", "object", "properties", Map.of(), "required", List.of()));
        }
        try {
            Object value = objectMapper.readValue(json, Object.class);
            if (value instanceof Map<?, ?>) {
                return ModelProtocolJson.compact(value);
            }
        } catch (Exception ignored) {
            // Fall through to safe default.
        }
        return writeJson(Map.of("type", "object", "properties", Map.of(), "required", List.of()));
    }

    private String normalizeJsonArray(String json) {
        if (json == null || json.isBlank()) {
            return writeJson(List.of());
        }
        try {
            Object value = objectMapper.readValue(json, Object.class);
            if (value instanceof List<?>) {
                return ModelProtocolJson.compact(value);
            }
        } catch (Exception ignored) {
            // Fall through to safe default.
        }
        return writeJson(List.of());
    }

    private String safeSqlLiteral(String name, Object value) {
        if (value == null) {
            throw new IllegalArgumentException("SQL template parameter is required: " + name);
        }
        if ("table".equals(name) || name.endsWith("Table")) {
            String identifier = String.valueOf(value).trim();
            if (!identifier.matches("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)?")) {
                throw new IllegalArgumentException("SQL table parameter is unsafe: " + name);
            }
            return identifier;
        }
        if (value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        String text = String.valueOf(value).trim();
        if (text.length() > 300) {
            throw new IllegalArgumentException("SQL template parameter is too long: " + name);
        }
        return "'" + text.replace("'", "''") + "'";
    }

    private List<DefaultTemplate> defaults() {
        Map<String, Object> tableSchema = Map.of(
            "type", "object",
            "properties", Map.of("table", Map.of("type", "string")),
            "required", List.of("table")
        );
        return List.of(
            new DefaultTemplate("CHECK_TABLE_COUNT", "Table row count", "Read row count for a selected table.", "SELECT COUNT(*) AS total_count FROM {{table}}", tableSchema),
            new DefaultTemplate("CHECK_RECENT_DATA", "Recent table rows", "Read recent rows from a selected table.", "SELECT * FROM {{table}} LIMIT 100", tableSchema)
        );
    }

    private String writeJson(Object value) {
        try {
            return ModelProtocolJson.compact(value);
        } catch (Exception ex) {
            return "{}";
        }
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

    private record DefaultTemplate(String code, String title, String description, String sql, Map<String, Object> schema) {
    }
}
