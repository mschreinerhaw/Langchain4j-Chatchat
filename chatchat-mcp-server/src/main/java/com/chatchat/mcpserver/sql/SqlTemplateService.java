package com.chatchat.mcpserver.sql;

import com.chatchat.agents.protocol.ModelProtocolJson;

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
        config.setEnabled(request.isEnabled());
        normalize(config);
        return repository.save(config);
    }

    public String render(String code, Map<String, Object> parameters) {
        ensureDefaults();
        SqlTemplateConfig config = repository.findByCode(requireText(code, "SQL template code is required").toUpperCase(Locale.ROOT))
            .filter(SqlTemplateConfig::isEnabled)
            .orElseThrow(() -> new IllegalArgumentException("SQL template not found or disabled: " + code));
        Matcher matcher = TOKEN.matcher(config.getSqlTemplate());
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            String name = matcher.group(1);
            Object value = parameters == null ? null : parameters.get(name);
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

    @Transactional
    public void ensureDefaults() {
        for (DefaultTemplate template : defaults()) {
            if (repository.findByCode(template.code()).isEmpty()) {
                SqlTemplateConfig config = new SqlTemplateConfig();
                config.setCode(template.code());
                config.setTitle(template.title());
                config.setDescription(template.description());
                config.setSqlTemplate(template.sql());
                config.setParameterSchemaJson(writeJson(template.schema()));
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
            new DefaultTemplate("CHECK_TABLE_COUNT", "表行数", "查询指定表行数。", "SELECT COUNT(*) AS total_count FROM {{table}}", tableSchema),
            new DefaultTemplate("CHECK_RECENT_DATA", "最近数据", "查询指定表最近数据。", "SELECT * FROM {{table}} LIMIT 100", tableSchema),
            new DefaultTemplate("CHECK_TASK_RESULT", "任务结果", "按任务 ID 查询结果。", "SELECT * FROM task_result WHERE task_id = {{taskId}} LIMIT 100",
                Map.of("type", "object", "properties", Map.of("taskId", Map.of("type", "string")), "required", List.of("taskId"))),
            new DefaultTemplate("CHECK_CUSTOMER_ASSET", "客户资产", "按客户 ID 查询资产。", "SELECT * FROM customer_asset WHERE customer_id = {{customerId}} LIMIT 100",
                Map.of("type", "object", "properties", Map.of("customerId", Map.of("type", "string")), "required", List.of("customerId")))
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

    private record DefaultTemplate(String code, String title, String description, String sql, Map<String, Object> schema) {
    }
}
