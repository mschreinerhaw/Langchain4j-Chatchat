package com.chatchat.mcpserver.template;

import com.chatchat.agents.protocol.ModelProtocolJson;
import com.chatchat.mcpserver.database.DatabaseQueryConfig;
import com.chatchat.mcpserver.database.DatabaseQueryConfigService;
import com.chatchat.mcpserver.database.DatabaseQueryMcpToolPublisher;
import com.chatchat.mcpserver.database.DatabaseQuerySqlStep;
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
        if (isBatchDsl(request)) {
            return importBatch(request);
        }
        ValidationResult validation = validateInternal(request, true);
        if (!validation.valid()) {
            throw new IllegalArgumentException(String.join("; ", validation.errors()));
        }
        String registry = validation.targetRegistry();
        AgentRuntimeTemplateDsl.TemplatePlan plan = validation.plan();
        Map<String, Object> root = readRoot(request.dsl());
        String dsl = request.dsl().trim();
        List<DatabaseQuerySqlStep> sqlSteps = databaseQuerySqlSteps(root);
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
            config.setImplementationSteps(text(root.get("implementationSteps")));
            config.setBusinessGroup(firstText(text(root.get("businessGroup")), text(root.get("category")), "default"));
            config.setBusinessGroupName(firstText(text(root.get("businessGroupName")), text(root.get("category")), "default"));
            config.setBusinessGroupDescription(text(root.get("businessGroupDescription")));
            config.setSqlTemplate(dsl);
            config.setSqlStepsJson(sqlSteps.isEmpty() ? null : jsonArrayObjects(sqlSteps));
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
        if (isBatchDsl(request)) {
            return validateBatch(request, requireImportReady);
        }
        AgentRuntimeTemplateDsl.TemplatePlan plan;
        Map<String, Object> root;
        try {
            root = readRoot(request.dsl());
            String requestedType = firstText(request.templateType(), text(root.get("templateType")), "LINUX_CMD");
            String parseDsl = dslForParsing(request.dsl(), root, requestedType);
            plan = AgentRuntimeTemplateDsl.parse(parseDsl, text(root.get("templateCode")), requestedType, defaultStepType(requestedType));
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
        normalized.put("sqlStepCount", databaseQuerySqlSteps(root).size());
        return new ValidationResult(errors.isEmpty(), errors, warnings, registry, plan, normalized);
    }

    private ValidationResult validateBatch(ImportRequest request, boolean requireImportReady) {
        List<ImportRequest> items;
        try {
            items = batchRequests(request);
        } catch (IllegalArgumentException ex) {
            return invalid(List.of(ex.getMessage()));
        }
        if (items.isEmpty()) {
            return invalid(List.of("DSL template batch cannot be empty"));
        }
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<Map<String, Object>> templates = new ArrayList<>();
        for (int index = 0; index < items.size(); index++) {
            ValidationResult item = validateInternal(items.get(index), requireImportReady);
            int position = index + 1;
            item.errors().forEach(error -> errors.add("template[" + position + "]: " + error));
            item.warnings().forEach(warning -> warnings.add("template[" + position + "]: " + warning));
            Map<String, Object> normalized = new LinkedHashMap<>(item.normalized());
            normalized.put("valid", item.valid());
            normalized.put("errors", item.errors());
            normalized.put("warnings", item.warnings());
            normalized.put("targetRegistry", item.targetRegistry());
            templates.add(normalized);
        }
        Map<String, Object> normalized = new LinkedHashMap<>();
        normalized.put("batch", true);
        normalized.put("templateCount", items.size());
        normalized.put("validCount", templates.stream().filter(item -> Boolean.TRUE.equals(item.get("valid"))).count());
        normalized.put("templates", templates);
        return new ValidationResult(errors.isEmpty(), errors, warnings, "batch", null, normalized);
    }

    private ImportResult importBatch(ImportRequest request) {
        ValidationResult validation = validateBatch(request, true);
        if (!validation.valid()) {
            throw new IllegalArgumentException(String.join("; ", validation.errors()));
        }
        List<ImportResult> imported = new ArrayList<>();
        for (ImportRequest item : batchRequests(request)) {
            imported.add(importTemplate(item));
        }
        Map<String, Object> normalized = new LinkedHashMap<>(validation.normalized());
        normalized.put("importedCount", imported.size());
        normalized.put("imports", imported.stream().map(item -> mapOf(
            "targetRegistry", item.targetRegistry(),
            "savedId", item.savedId(),
            "templateCode", item.templateCode(),
            "templateName", item.templateName()
        )).toList());
        return new ImportResult(
            true,
            "batch",
            imported.isEmpty() ? null : imported.get(0).savedId(),
            imported.size() + "_templates",
            "Batch AgentRuntime DSL import",
            normalized,
            imported
        );
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

    private String dslForParsing(String dsl, Map<String, Object> root, String requestedType) {
        if (AgentRuntimeTemplateDsl.looksLikeDsl(dsl)) {
            return dsl;
        }
        List<DatabaseQuerySqlStep> sqlSteps = databaseQuerySqlSteps(root);
        if (sqlSteps.isEmpty()) {
            return dsl;
        }
        Map<String, Object> normalized = new LinkedHashMap<>(root);
        normalized.put("templateType", firstText(text(root.get("templateType")), requestedType, "DATABASE_QUERY"));
        normalized.put("steps", sqlSteps.stream()
            .map(step -> mapOf(
                "stepCode", step.getSqlCode(),
                "stepName", step.getSqlName(),
                "stepType", "SQL",
                "order", step.getExecutionOrder(),
                "command", step.getSqlContent(),
                "required", !"CONTINUE".equalsIgnoreCase(firstText(step.getFailureStrategy(), "STOP")),
                "timeoutSeconds", step.getTimeoutSeconds(),
                "analysisHint", step.getSqlDescription()
            ))
            .toList());
        return ModelProtocolJson.compact(normalized);
    }

    private List<DatabaseQuerySqlStep> databaseQuerySqlSteps(Map<String, Object> root) {
        Object raw = firstPresent(root, "sqlSteps", "sql_steps");
        if (!(raw instanceof Iterable<?> iterable)) {
            return List.of();
        }
        List<DatabaseQuerySqlStep> steps = new ArrayList<>();
        int index = 0;
        for (Object item : iterable) {
            if (!(item instanceof Map<?, ?> rawMap)) {
                index++;
                continue;
            }
            Map<String, Object> map = castMap(rawMap);
            int order = integer(firstPresent(map, "executionOrder", "order"), index + 1);
            String code = firstText(text(firstPresent(map, "sqlCode", "stepCode", "code")), "SQL_" + order);
            String name = firstText(text(firstPresent(map, "sqlName", "stepName", "name")), code);
            String description = firstText(
                text(firstPresent(map, "sqlDescription", "resultSetDescription", "analysisHint", "description")),
                name
            );
            String sql = firstText(text(firstPresent(map, "sqlContent", "sql", "command", "sqlTemplate")));
            if (sql == null) {
                index++;
                continue;
            }
            DatabaseQuerySqlStep step = new DatabaseQuerySqlStep();
            step.setSqlCode(code);
            step.setSqlName(name);
            step.setSqlDescription(description);
            step.setSqlContent(sql);
            step.setExecutionOrder(order);
            step.setEnabled(bool(firstPresent(map, "enabled"), true));
            step.setTimeoutSeconds(integerObject(firstPresent(map, "timeoutSeconds", "timeout_seconds")));
            step.setFailureStrategy(firstText(text(firstPresent(map, "failureStrategy", "onFailure")), "STOP"));
            step.setMaxResultRows(integerObject(firstPresent(map, "maxResultRows", "maxRows", "max_rows")));
            step.setParameters(objectMap(firstPresent(map, "parameters", "params", "inputParams")));
            steps.add(step);
            index++;
        }
        return steps;
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
            Object value = objectMapper.readValue(dsl, Object.class);
            if (value instanceof Map<?, ?> map) {
                return castMap(map);
            }
            throw new IllegalArgumentException("Agent runtime template DSL root must be a JSON object");
        } catch (Exception ex) {
            if (ex instanceof IllegalArgumentException illegalArgumentException) {
                throw illegalArgumentException;
            }
            throw new IllegalArgumentException("Agent runtime template DSL JSON is invalid: " + ex.getMessage());
        }
    }

    private boolean isBatchDsl(ImportRequest request) {
        String text = request == null ? null : request.dsl();
        return text != null && text.trim().startsWith("[");
    }

    private List<ImportRequest> batchRequests(ImportRequest request) {
        try {
            List<Object> values = objectMapper.readValue(request.dsl(), new TypeReference<>() {});
            List<ImportRequest> items = new ArrayList<>();
            for (int index = 0; index < values.size(); index++) {
                Object value = values.get(index);
                if (!(value instanceof Map<?, ?> rawMap)) {
                    throw new IllegalArgumentException("Agent runtime template DSL batch item must be an object at index " + index);
                }
                Map<String, Object> map = castMap(rawMap);
                items.add(new ImportRequest(
                    ModelProtocolJson.compact(value),
                    text(map.get("templateType")) == null ? request.templateType() : null,
                    request.targetRegistry(),
                    request.datasourceId()
                ));
            }
            return items;
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalArgumentException("Agent runtime template DSL batch JSON is invalid: " + ex.getMessage());
        }
    }

    private Map<String, Object> castMap(Map<?, ?> map) {
        Map<String, Object> values = new LinkedHashMap<>();
        map.forEach((key, value) -> values.put(String.valueOf(key), value));
        return values;
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

    private String jsonArrayObjects(List<?> values) {
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

    private Integer integerObject(Object value) {
        if (value == null || String.valueOf(value).isBlank()) {
            return null;
        }
        return integer(value, 0);
    }

    private int integer(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private Map<String, Object> objectMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            return castMap(map);
        }
        return Map.of();
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

    private Map<String, Object> mapOf(Object... values) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int index = 0; index + 1 < values.length; index += 2) {
            map.put(String.valueOf(values[index]), values[index + 1]);
        }
        return map;
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
