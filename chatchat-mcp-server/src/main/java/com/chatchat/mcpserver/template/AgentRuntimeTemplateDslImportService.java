package com.chatchat.mcpserver.template;

import com.chatchat.agents.protocol.ModelProtocolJson;
import com.chatchat.mcpserver.database.DatabaseQueryConfig;
import com.chatchat.mcpserver.database.DatabaseQueryConfigService;
import com.chatchat.mcpserver.database.DatabaseQueryMcpToolPublisher;
import com.chatchat.mcpserver.ops.CommandTemplateConfig;
import com.chatchat.mcpserver.ops.CommandTemplateService;
import com.chatchat.mcpserver.ops.OpsMcpToolPublisher;
import com.chatchat.mcpserver.search.McpTemplateLuceneIndexService;
import com.chatchat.mcpserver.sql.SqlMcpToolPublisher;
import com.chatchat.mcpserver.sql.SqlTemplateConfig;
import com.chatchat.mcpserver.sql.SqlTemplateService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class AgentRuntimeTemplateDslImportService {

    private static final Set<String> LINUX_TYPES = Set.of("LINUX_CMD", "SSH_CMD", "SHELL", "OPS_CHECK");
    private static final Set<String> SQL_TEMPLATE_TYPES = Set.of("DB_SQL", "SQL_OPS", "SQL_MAINTENANCE", "DATABASE_SQL");
    private static final Set<String> DATABASE_QUERY_TYPES = Set.of("DATABASE_QUERY", "BUSINESS_QUERY", "BUSINESS_DB_SQL");
    private static final Set<String> SHELL_STEP_TYPES = Set.of("SHELL", "COMMAND", "LINUX_CMD", "SSH_CMD");
    private static final Set<String> SQL_STEP_TYPES = Set.of("SQL", "DB_SQL", "DATABASE_SQL");

    private final ObjectMapper objectMapper;
    private final CommandTemplateService commandTemplateService;
    private final SqlTemplateService sqlTemplateService;
    private final DatabaseQueryConfigService databaseQueryConfigService;
    private final OpsMcpToolPublisher opsPublisher;
    private final SqlMcpToolPublisher sqlPublisher;
    private final DatabaseQueryMcpToolPublisher databaseQueryPublisher;
    private final McpTemplateLuceneIndexService templateIndexService;

    public ValidationResult validate(ImportRequest request) {
        return validateInternal(request, false);
    }

    @Transactional
    public ImportResult importTemplate(ImportRequest request) {
        ValidationResult validation = validateInternal(request, true);
        if (!validation.valid()) {
            throw new IllegalArgumentException(String.join("; ", validation.errors()));
        }
        String registry = validation.targetRegistry();
        AgentRuntimeTemplateDsl.TemplatePlan plan = validation.plan();
        Map<String, Object> root = readRoot(request.dsl());
        String dsl = request.dsl().trim();
        Object saved;
        String savedId;
        if ("linux_command_template".equals(registry)) {
            CommandTemplateConfig config = existingCommandTemplate(plan.templateCode());
            config.setCode(plan.templateCode());
            config.setTitle(plan.templateName());
            config.setDescription(firstText(text(root.get("description")), plan.templateName()));
            config.setCommandTemplate(dsl);
            config.setParameterSchemaJson(jsonObject(root.get("parameterSchema"), emptyParameterSchema()));
            config.setRiskLevel(firstText(plan.riskLevel(), "LOW"));
            config.setCategory(firstText(text(root.get("category")), "agent_runtime_dsl"));
            config.setIntentSignalsJson(jsonArray(intentSignals(root, plan)));
            config.setEnabled(bool(root.get("enabled"), true));
            saved = config.getId() == null
                ? commandTemplateService.save(config)
                : commandTemplateService.update(config.getId(), config);
            savedId = ((CommandTemplateConfig) saved).getId();
            opsPublisher.refresh();
            templateIndexService.upsertCommandTemplates(List.of((CommandTemplateConfig) saved));
        } else if ("sql_ops_template".equals(registry)) {
            SqlTemplateConfig config = existingSqlTemplate(plan.templateCode());
            config.setCode(plan.templateCode());
            config.setTitle(plan.templateName());
            config.setDescription(firstText(text(root.get("description")), plan.templateName()));
            config.setSqlTemplate(dsl);
            config.setParameterSchemaJson(jsonObject(root.get("parameterSchema"), emptyParameterSchema()));
            config.setRiskLevel(firstText(plan.riskLevel(), "LOW"));
            config.setCategory(firstText(text(root.get("category")), "agent_runtime_dsl"));
            config.setDatabaseType(firstText(text(root.get("databaseType")), plan.targetType(), "generic"));
            config.setDatasourceId(firstText(text(root.get("datasourceId")), text(root.get("datasource_id"))));
            config.setRoutingLabelsJson(jsonArray(stringList(firstPresent(root, "routingLabels", "labels"))));
            config.setIntentSignalsJson(jsonArray(intentSignals(root, plan)));
            config.setEnabled(bool(root.get("enabled"), true));
            saved = config.getId() == null
                ? sqlTemplateService.save(config)
                : sqlTemplateService.update(config.getId(), config);
            savedId = ((SqlTemplateConfig) saved).getId();
            sqlPublisher.refresh();
            templateIndexService.upsertSqlTemplates(List.of((SqlTemplateConfig) saved));
        } else {
            DatabaseQueryConfig config = existingDatabaseQuery(plan.templateCode());
            config.setToolName(plan.templateCode());
            config.setTitle(plan.templateName());
            config.setDatasourceId(firstText(text(root.get("datasourceId")), text(root.get("datasource_id")), request.datasourceId()));
            config.setDescription(firstText(text(root.get("description")), plan.templateName()));
            config.setBusinessGroup(firstText(text(root.get("businessGroup")), text(root.get("category")), "default"));
            config.setBusinessGroupName(firstText(text(root.get("businessGroupName")), text(root.get("category")), "default"));
            config.setBusinessGroupDescription(text(root.get("businessGroupDescription")));
            config.setSqlTemplate(dsl);
            config.setInputSchemaJson(jsonObject(root.get("parameterSchema"), emptyParameterSchema()));
            config.setGovernanceJson(jsonObject(firstPresent(root, "governance", "analysisPolicy"), Map.of()));
            config.setRoutingLabelsJson(jsonArray(stringList(firstPresent(root, "routingLabels", "labels"))));
            config.setCapabilitiesJson(jsonArray(List.of("database_query", "sql_query_execute", "agent_runtime_dsl")));
            config.setTemplateIntent(firstText(text(root.get("intent")), text(root.get("templateIntent")), "general_query"));
            config.setDatabaseType(firstText(text(root.get("databaseType")), plan.targetType(), "generic"));
            config.setTagsJson(jsonArray(intentSignals(root, plan)));
            config.setRiskLevel(firstText(plan.riskLevel(), "read_only"));
            config.setOwner(firstText(text(root.get("owner")), "admin"));
            config.setRating(number(root.get("rating"), 0.0));
            config.setUsageCount(longNumber(root.get("usageCount"), 0L));
            config.setMaxRows(integer(root.get("maxRows"), 50));
            config.setTimeoutSeconds(firstInt(plan.timeoutSeconds(), integer(root.get("timeoutSeconds"), 30)));
            config.setEnabled(bool(root.get("enabled"), true));
            saved = config.getId() == null
                ? databaseQueryConfigService.create(config)
                : databaseQueryConfigService.update(config.getId(), config);
            savedId = ((DatabaseQueryConfig) saved).getId();
            databaseQueryPublisher.refresh();
            templateIndexService.upsertDatabaseQueryTemplates(List.of((DatabaseQueryConfig) saved));
        }
        return new ImportResult(
            true,
            registry,
            savedId,
            plan.templateCode(),
            plan.templateName(),
            validation.normalized(),
            saved
        );
    }

    private ValidationResult validateInternal(ImportRequest request, boolean requireImportReady) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        if (request == null || request.dsl() == null || request.dsl().isBlank()) {
            return invalid(List.of("DSL template content is required"));
        }
        AgentRuntimeTemplateDsl.TemplatePlan plan;
        Map<String, Object> root;
        try {
            root = readRoot(request.dsl());
            String requestedType = firstText(request.templateType(), text(root.get("templateType")), "LINUX_CMD");
            plan = AgentRuntimeTemplateDsl.parse(request.dsl(), text(root.get("templateCode")), requestedType, defaultStepType(requestedType));
        } catch (IllegalArgumentException ex) {
            return invalid(List.of(ex.getMessage()));
        }
        String templateCode = plan.templateCode();
        if (templateCode == null || templateCode.isBlank()) {
            errors.add("templateCode is required");
        } else if (!templateCode.matches("[A-Za-z0-9_\\-]{2,128}")) {
            errors.add("templateCode only supports letters, numbers, underscore and dash");
        }
        String registry = targetRegistry(request, plan);
        if (registry == null) {
            errors.add("templateType is unsupported: " + plan.templateType());
        }
        validateSteps(plan, registry, errors);
        if ("database_query_template".equals(registry)
            && firstText(text(root.get("datasourceId")), text(root.get("datasource_id")), request.datasourceId()) == null) {
            String message = "database query DSL import requires datasourceId";
            if (requireImportReady) {
                errors.add(message);
            } else {
                warnings.add(message);
            }
        }
        Map<String, Object> normalized = new LinkedHashMap<>(AgentRuntimeTemplateDsl.metadata(plan));
        normalized.put("targetRegistry", registry);
        normalized.put("description", text(root.get("description")));
        normalized.put("category", text(root.get("category")));
        normalized.put("datasourceId", firstText(text(root.get("datasourceId")), text(root.get("datasource_id")), request.datasourceId()));
        normalized.put("intentSignals", intentSignals(root, plan));
        return new ValidationResult(errors.isEmpty(), errors, warnings, registry, plan, normalized);
    }

    private void validateSteps(AgentRuntimeTemplateDsl.TemplatePlan plan, String registry, List<String> errors) {
        if (plan.steps().isEmpty()) {
            errors.add("steps cannot be empty");
            return;
        }
        Set<String> allowed = "linux_command_template".equals(registry) ? SHELL_STEP_TYPES : SQL_STEP_TYPES;
        for (AgentRuntimeTemplateDsl.TemplateStep step : plan.steps()) {
            if (!allowed.contains(step.stepType())) {
                errors.add("step " + step.stepCode() + " stepType " + step.stepType()
                    + " is not allowed for " + registry);
            }
            if (step.command() == null || step.command().isBlank()) {
                errors.add("step " + step.stepCode() + " command/sql is required");
            }
        }
    }

    private String targetRegistry(ImportRequest request, AgentRuntimeTemplateDsl.TemplatePlan plan) {
        String requested = normalizeType(firstText(request.targetRegistry(), request.templateType(), plan.templateType()));
        if ("LINUX_COMMAND_TEMPLATE".equals(requested) || LINUX_TYPES.contains(requested)) {
            return "linux_command_template";
        }
        if ("SQL_OPS_TEMPLATE".equals(requested) || SQL_TEMPLATE_TYPES.contains(requested)) {
            return "sql_ops_template";
        }
        if ("DATABASE_QUERY_TEMPLATE".equals(requested) || DATABASE_QUERY_TYPES.contains(requested)) {
            return "database_query_template";
        }
        return null;
    }

    private CommandTemplateConfig existingCommandTemplate(String code) {
        return commandTemplateService.listAll().stream()
            .filter(item -> equalsIgnoreCase(item.getCode(), code))
            .findFirst()
            .orElseGet(CommandTemplateConfig::new);
    }

    private SqlTemplateConfig existingSqlTemplate(String code) {
        return sqlTemplateService.listAll().stream()
            .filter(item -> equalsIgnoreCase(item.getCode(), code))
            .findFirst()
            .orElseGet(SqlTemplateConfig::new);
    }

    private DatabaseQueryConfig existingDatabaseQuery(String code) {
        return databaseQueryConfigService.listAll().stream()
            .filter(item -> equalsIgnoreCase(item.getToolName(), code))
            .findFirst()
            .orElseGet(DatabaseQueryConfig::new);
    }

    private List<String> intentSignals(Map<String, Object> root, AgentRuntimeTemplateDsl.TemplatePlan plan) {
        LinkedHashSet<String> signals = new LinkedHashSet<>();
        signals.addAll(stringList(firstPresent(root, "intentSignals", "keywords", "tags")));
        add(signals, plan.templateCode(), plan.templateName(), plan.templateType(), plan.targetType(), text(root.get("description")));
        for (AgentRuntimeTemplateDsl.TemplateStep step : plan.steps()) {
            add(signals, step.stepCode(), step.stepName(), step.stepType(), step.analysisHint());
        }
        flattenText(signals, plan.analysisPolicy());
        return signals.stream().filter(item -> item != null && !item.isBlank()).toList();
    }

    private void flattenText(Set<String> values, Object value) {
        if (value instanceof Map<?, ?> map) {
            map.forEach((key, item) -> {
                if (key != null) {
                    values.add(String.valueOf(key));
                }
                flattenText(values, item);
            });
        } else if (value instanceof Iterable<?> iterable) {
            iterable.forEach(item -> flattenText(values, item));
        } else if (value != null && !String.valueOf(value).isBlank()) {
            values.add(String.valueOf(value).trim());
        }
    }

    private void add(Set<String> target, String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                target.add(value.trim());
            }
        }
    }

    private String defaultStepType(String templateType) {
        String normalized = normalizeType(templateType);
        return SQL_TEMPLATE_TYPES.contains(normalized) || DATABASE_QUERY_TYPES.contains(normalized) ? "SQL" : "SHELL";
    }

    private Map<String, Object> readRoot(String dsl) {
        try {
            return objectMapper.readValue(dsl, new TypeReference<>() {});
        } catch (Exception ex) {
            throw new IllegalArgumentException("Agent runtime template DSL JSON is invalid: " + ex.getMessage());
        }
    }

    private Map<String, Object> emptyParameterSchema() {
        return Map.of("type", "object", "properties", Map.of(), "required", List.of());
    }

    private Object firstPresent(Map<String, Object> values, String... keys) {
        for (String key : keys) {
            Object value = values.get(key);
            if (value != null && !String.valueOf(value).isBlank()) {
                return value;
            }
        }
        return null;
    }

    private String jsonObject(Object value, Map<String, Object> fallback) {
        try {
            Object actual = value == null ? fallback : value;
            if (!(actual instanceof Map<?, ?>)) {
                actual = fallback;
            }
            return ModelProtocolJson.compact(actual);
        } catch (Exception ex) {
            return "{}";
        }
    }

    private String jsonArray(List<String> values) {
        try {
            return ModelProtocolJson.compact(values == null ? List.of() : values);
        } catch (Exception ex) {
            return "[]";
        }
    }

    private List<String> stringList(Object value) {
        if (value instanceof Iterable<?> iterable) {
            List<String> values = new ArrayList<>();
            for (Object item : iterable) {
                if (item != null && !String.valueOf(item).isBlank()) {
                    values.add(String.valueOf(item).trim());
                }
            }
            return values;
        }
        if (value == null || String.valueOf(value).isBlank()) {
            return List.of();
        }
        return List.of(String.valueOf(value).trim());
    }

    private int integer(Object value, int fallback) {
        try {
            return value == null ? fallback : Integer.parseInt(String.valueOf(value));
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private int firstInt(Integer value, int fallback) {
        return value == null || value <= 0 ? fallback : value;
    }

    private double number(Object value, double fallback) {
        try {
            return value == null ? fallback : Double.parseDouble(String.valueOf(value));
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private long longNumber(Object value, long fallback) {
        try {
            return value == null ? fallback : Long.parseLong(String.valueOf(value));
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private boolean bool(Object value, boolean fallback) {
        if (value == null) {
            return fallback;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }

    private String normalizeType(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT).replace('-', '_');
    }

    private boolean equalsIgnoreCase(String left, String right) {
        return left != null && right != null && left.equalsIgnoreCase(right);
    }

    private String text(Object value) {
        return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value).trim();
    }

    private String firstText(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private ValidationResult invalid(List<String> errors) {
        return new ValidationResult(false, errors, List.of(), null, null, Map.of());
    }

    public record ImportRequest(
        String dsl,
        String templateType,
        String targetRegistry,
        String datasourceId
    ) {
    }

    public record ValidationResult(
        boolean valid,
        List<String> errors,
        List<String> warnings,
        String targetRegistry,
        AgentRuntimeTemplateDsl.TemplatePlan plan,
        Map<String, Object> normalized
    ) {
    }

    public record ImportResult(
        boolean imported,
        String targetRegistry,
        String savedId,
        String templateCode,
        String templateName,
        Map<String, Object> normalized,
        Object saved
    ) {
    }
}
