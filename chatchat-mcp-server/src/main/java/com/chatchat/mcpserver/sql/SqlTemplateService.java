package com.chatchat.mcpserver.sql;

import com.chatchat.agents.protocol.ModelProtocolJson;
import com.chatchat.mcpserver.template.TemplateParameterValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
@Slf4j
public class SqlTemplateService {

    private static final int LOG_SQL_LIMIT = 4000;
    private static final Pattern TOKEN = Pattern.compile("\\{\\{\\s*([A-Za-z0-9_.-]+)\\s*}}");
    private static final Set<String> RAW_SQL_PARAMETER_KEYS = Set.of(
        "sql",
        "rawsql",
        "raw_sql",
        "statement",
        "query"
    );
    private static final Set<String> SQL_FRAGMENT_PARAMETER_KEYS = Set.of(
        "where",
        "whereclause",
        "where_clause",
        "condition",
        "conditions",
        "filter",
        "filterexpression",
        "filter_expression",
        "orderby",
        "order_by",
        "groupby",
        "group_by",
        "having",
        "join",
        "joinon",
        "join_on",
        "predicate",
        "expression",
        "sqlfragment",
        "sql_fragment",
        "clause"
    );
    private static final Pattern SQL_STATEMENT_TOKEN = Pattern.compile(
        "(?is)(;|--|/\\*|\\*/|\\b(select|show|insert|update|delete|merge|drop|alter|create|truncate|grant|revoke|union|with)\\b)"
    );
    private static final Pattern SQL_FRAGMENT_TOKEN = Pattern.compile(
        "(?is)(;|--|/\\*|\\*/|\\b(select|show|insert|update|delete|merge|drop|alter|create|truncate|grant|revoke|union|with|from|where|join|order\\s+by|group\\s+by|having)\\b)"
    );
    private static final List<String> RETIRED_DEFAULT_CODES = List.of(
        "CHECK_TABLE_COUNT",
        "CHECK_RECENT_DATA",
        "TASK_RESULT",
        "CHECK_TASK_RESULT",
        "TASK_RESULT_QUERY",
        "QUERY_TASK_RESULT",
        "MYSQL_SCHEMA_TABLE_OVERVIEW",
        "MYSQL_TABLE_LOCATION",
        "MYSQL_TABLE_METADATA",
        "ORACLE_TABLE_METADATA",
        "POSTGRES_TABLE_METADATA",
        "SQLSERVER_TABLE_METADATA"
    );

    private final SqlTemplateConfigRepository repository;
    private final ObjectMapper objectMapper;
    private final TemplateParameterValidator parameterValidator;
    private final SqlTemplateSeedProperties seedProperties;
    private final DynamicDateParamService dynamicDateParamService;

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
        parameters = dynamicDateParamService.enrichParameters(parameters, datasource, config.getSqlTemplate());
        rejectUndeclaredRawSqlParameters(config, parameters);
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
        log.info("MCP SQL template render requested: templateId={}, title={}, category={}, riskLevel={}, databaseType={}, datasourceId={}, datasourceName={}, env={}, parameters={}, collectedParameters={}, sqlTemplate={}",
            config.getCode(), config.getTitle(), config.getCategory(), config.getRiskLevel(), config.getDatabaseType(),
            datasource == null ? null : datasource.getId(), datasource == null ? null : datasource.getName(),
            datasource == null ? null : datasource.getEnvironment(), parameters, validatedParameters,
            truncateSql(config.getSqlTemplate()));
        String dynamicSqlTemplate = dynamicDateParamService.resolveSqlPlaceholders(config.getSqlTemplate(), datasource);
        Matcher matcher = TOKEN.matcher(dynamicSqlTemplate);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            String name = matcher.group(1);
            Object value = validatedParameters.get(name);
            String replacement = safeSqlLiteral(name, value);
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(buffer);
        String renderedSql = buffer.toString();
        log.info("MCP SQL template rendered: templateId={}, datasourceId={}, datasourceName={}, env={}, renderedSql={}",
            config.getCode(), datasource == null ? null : datasource.getId(), datasource == null ? null : datasource.getName(),
            datasource == null ? null : datasource.getEnvironment(), truncateSql(renderedSql));
        return renderedSql;
    }

    public Map<String, Object> executionMetadata(String code) {
        if (code == null || code.isBlank()) {
            return Map.of();
        }
        ensureDefaults();
        SqlTemplateConfig config = repository.findByCode(code.trim().toUpperCase(Locale.ROOT))
            .filter(SqlTemplateConfig::isEnabled)
            .orElse(null);
        if (config == null) {
            return Map.of();
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("templateId", config.getCode());
        metadata.put("businessName", config.getTitle());
        metadata.put("businessDescription", config.getDescription());
        metadata.put("category", config.getCategory());
        metadata.put("riskLevel", config.getRiskLevel());
        return metadata;
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
        removeRetiredDefaults();
        boolean createMissingDefaults = seedProperties != null && seedProperties.isSeedDefaultsEnabled();
        for (DefaultTemplate template : defaults()) {
            var existing = repository.findByCode(template.code());
            if (existing.isEmpty()) {
                if (!createMissingDefaults) {
                    continue;
                }
                SqlTemplateConfig config = new SqlTemplateConfig();
                config.setCode(template.code());
                config.setTitle(template.title());
                config.setDescription(template.description());
                config.setSqlTemplate(template.sql());
                config.setParameterSchemaJson(writeJson(template.schema()));
                config.setRiskLevel(template.riskLevel());
                config.setCategory(template.category());
                config.setDatabaseType(template.databaseType());
                config.setRoutingLabelsJson(writeJson(template.routingLabels()));
                config.setIntentSignalsJson(writeJson(template.intentSignals()));
                config.setEnabled(true);
                repository.save(config);
            } else {
                refreshDefaultTemplate(existing.get(), template);
            }
        }
    }

    private void refreshDefaultTemplate(SqlTemplateConfig existing, DefaultTemplate template) {
        String parameterSchema = writeJson(template.schema());
        String routingLabels = writeJson(template.routingLabels());
        String intentSignals = writeJson(template.intentSignals());
        if (Objects.equals(existing.getTitle(), template.title())
            && Objects.equals(existing.getDescription(), template.description())
            && Objects.equals(existing.getSqlTemplate(), template.sql())
            && Objects.equals(existing.getParameterSchemaJson(), parameterSchema)
            && Objects.equals(existing.getRiskLevel(), template.riskLevel())
            && Objects.equals(existing.getCategory(), template.category())
            && Objects.equals(existing.getDatabaseType(), template.databaseType())
            && Objects.equals(existing.getRoutingLabelsJson(), routingLabels)
            && Objects.equals(existing.getIntentSignalsJson(), intentSignals)
            && existing.getDatasourceId() == null) {
            return;
        }
        existing.setTitle(template.title());
        existing.setDescription(template.description());
        existing.setSqlTemplate(template.sql());
        existing.setParameterSchemaJson(parameterSchema);
        existing.setRiskLevel(template.riskLevel());
        existing.setCategory(template.category());
        existing.setDatabaseType(template.databaseType());
        existing.setDatasourceId(null);
        existing.setRoutingLabelsJson(routingLabels);
        existing.setIntentSignalsJson(intentSignals);
        repository.save(existing);
        log.info("Refreshed managed SQL default template: {}", template.code());
    }

    private void removeRetiredDefaults() {
        for (String code : RETIRED_DEFAULT_CODES) {
            repository.findByCode(code).ifPresent(repository::delete);
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
                List<String> allowed = list.stream()
                    .map(item -> item == null ? null : String.valueOf(item).trim().toUpperCase(Locale.ROOT))
                    .filter(item -> item != null && !item.isBlank())
                    .distinct()
                    .toList();
                return allowed;
            }
        } catch (Exception ignored) {
            // Invalid stale allowlists are treated as legacy unconfigured assets.
        }
        return List.of();
    }

    private void rejectUndeclaredRawSqlParameters(SqlTemplateConfig config, Map<String, Object> parameters) {
        if (parameters == null || parameters.isEmpty()) {
            return;
        }
        Set<String> declared = declaredParameterNames(config.getParameterSchemaJson());
        for (String name : parameters.keySet()) {
            String normalized = normalizeParameterName(name);
            if (RAW_SQL_PARAMETER_KEYS.contains(normalized) && !declared.contains(normalized)) {
                throw new IllegalArgumentException("SQL template " + config.getCode()
                    + " does not accept parameter " + name
                    + ". Pass raw SQL as top-level sql without templateId, or use a template that declares this parameter.");
            }
            if (RAW_SQL_PARAMETER_KEYS.contains(normalized)) {
                throw new IllegalArgumentException("SQL template " + config.getCode()
                    + " does not allow raw SQL parameter " + name
                    + ". SQL templates are strict and must use typed parameters only.");
            }
            if (SQL_FRAGMENT_PARAMETER_KEYS.contains(normalized)) {
                throw new IllegalArgumentException("SQL template " + config.getCode()
                    + " does not allow SQL fragment parameter " + name
                    + ". Use a static template with typed parameters, or add an AST-validated flex template implementation.");
            }
            rejectSqlStructureInValue(config, name, parameters.get(name), SQL_STATEMENT_TOKEN);
        }
    }

    private void rejectSqlStructureInValue(SqlTemplateConfig config, String name, Object value, Pattern pattern) {
        if (value == null) {
            return;
        }
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                rejectSqlStructureInValue(config, name + "." + entry.getKey(), entry.getValue(), SQL_FRAGMENT_TOKEN);
            }
            return;
        }
        if (value instanceof Iterable<?> iterable) {
            int index = 0;
            for (Object item : iterable) {
                rejectSqlStructureInValue(config, name + "[" + index++ + "]", item, SQL_FRAGMENT_TOKEN);
            }
            return;
        }
        if (!(value instanceof CharSequence)) {
            return;
        }
        String text = String.valueOf(value);
        if (text.isBlank() || !pattern.matcher(text).find()) {
            return;
        }
        throw new IllegalArgumentException("SQL template " + config.getCode()
            + " parameter " + name
            + " contains SQL syntax. SQL templates are strict; select a registered template and pass typed values only.");
    }

    private Set<String> declaredParameterNames(String schemaJson) {
        if (schemaJson == null || schemaJson.isBlank()) {
            return Set.of();
        }
        try {
            Object value = objectMapper.readValue(schemaJson, Object.class);
            Map<String, Object> schema = objectMap(value);
            Map<String, Object> properties = objectMap(schema.get("properties"));
            if (properties.isEmpty()) {
                return Set.of();
            }
            return properties.keySet().stream()
                .map(this::normalizeParameterName)
                .collect(java.util.stream.Collectors.toSet());
        } catch (Exception ex) {
            throw new IllegalArgumentException("Template parameterSchema is invalid");
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> objectMap(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private String normalizeParameterName(String name) {
        return name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
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
        return List.of(
            maintenanceTemplate("MYSQL_SHOW_PROCESSLIST", "MySQL current connections",
                "Show current MySQL sessions and running statements.",
                "mysql", "connection",
                "SHOW PROCESSLIST",
                List.of("connection", "session", "processlist", "connection_overflow", "performance_issue")),
            maintenanceTemplate("MYSQL_SHOW_STATUS", "MySQL status variables",
                "Show MySQL server status counters for health and performance inspection.",
                "mysql", "instance",
                "SHOW STATUS",
                List.of("status", "instance", "health", "performance", "performance_issue")),
            maintenanceTemplate("MYSQL_INNODB_STATUS", "MySQL InnoDB engine status",
                "Show InnoDB engine status for lock and deadlock troubleshooting.",
                "mysql", "lock",
                "SHOW ENGINE INNODB STATUS",
                List.of("innodb", "lock", "deadlock", "transaction", "lock_check")),
            maintenanceTemplate("MYSQL_INNODB_TRX", "MySQL InnoDB transactions",
                "Read active InnoDB transaction metadata.",
                "mysql", "lock",
                "SELECT * FROM information_schema.INNODB_TRX",
                List.of("transaction", "lock", "innodb trx", "long transaction", "lock_check")),
            maintenanceTemplate("MYSQL_DATABASE_SIZE", "MySQL database size",
                "Summarize MySQL database size by schema in megabytes.",
                "mysql", "storage",
                "SELECT table_schema AS db, SUM(data_length + index_length)/1024/1024 AS size_mb FROM information_schema.tables GROUP BY table_schema",
                List.of("database size", "storage", "schema size", "space", "storage_check")),
            maintenanceTemplate("MYSQL_INSTANCE_VARIABLES", "MySQL instance variables",
                "Read key MySQL instance variables for runtime configuration inspection.",
                "mysql", "instance",
                """
                    SELECT variable_name, variable_value
                    FROM performance_schema.global_variables
                    WHERE variable_name IN (
                        'version',
                        'version_comment',
                        'max_connections',
                        'innodb_buffer_pool_size',
                        'innodb_flush_log_at_trx_commit',
                        'sync_binlog',
                        'slow_query_log',
                        'long_query_time',
                        'read_only',
                        'super_read_only'
                    )
                    ORDER BY variable_name
                    """,
                List.of("variables", "configuration", "version", "max connections", "buffer pool", "database status")),
            maintenanceTemplate("MYSQL_CONNECTION_STATUS", "MySQL connection status counters",
                "Read MySQL connection related status counters for capacity and saturation analysis.",
                "mysql", "connection",
                """
                    SELECT variable_name, variable_value
                    FROM performance_schema.global_status
                    WHERE variable_name IN (
                        'Threads_connected',
                        'Threads_running',
                        'Max_used_connections',
                        'Connections',
                        'Aborted_connects',
                        'Connection_errors_max_connections'
                    )
                    ORDER BY variable_name
                    """,
                List.of("connection status", "threads connected", "threads running", "max used connections", "connection_overflow")),
            maintenanceTemplate("MYSQL_TOP_TABLES_SIZE", "MySQL largest tables",
                "Rank MySQL tables by total data and index size.",
                "mysql", "storage",
                """
                    SELECT table_schema,
                           table_name,
                           ROUND((data_length + index_length) / 1024 / 1024, 2) AS total_mb,
                           ROUND(data_length / 1024 / 1024, 2) AS data_mb,
                           ROUND(index_length / 1024 / 1024, 2) AS index_mb,
                           table_rows
                    FROM information_schema.tables
                    WHERE table_schema NOT IN ('information_schema', 'mysql', 'performance_schema', 'sys')
                    ORDER BY total_mb DESC
                    LIMIT 50
                    """,
                List.of("largest table", "table size", "storage", "space", "capacity", "storage_check")),
            maintenanceTemplate("MYSQL_STATEMENT_DIGEST_TOP", "MySQL top statement digests",
                "Read top MySQL statement digests by total latency from performance_schema.",
                "mysql", "performance",
                """
                    SELECT digest_text,
                           count_star,
                           ROUND(sum_timer_wait / 1000000000000, 3) AS total_seconds,
                           ROUND(avg_timer_wait / 1000000000000, 6) AS avg_seconds,
                           sum_rows_examined,
                           sum_rows_sent
                    FROM performance_schema.events_statements_summary_by_digest
                    WHERE digest_text IS NOT NULL
                    ORDER BY sum_timer_wait DESC
                    LIMIT 20
                    """,
                List.of("statement digest", "top sql", "slow query", "latency", "rows examined", "performance_issue")),

            maintenanceTemplate("ORACLE_SESSION_OVERVIEW", "Oracle current sessions",
                "Read Oracle session metadata from v$session.",
                "oracle", "connection",
                "SELECT * FROM v$session",
                List.of("session", "connection", "active session", "connection_overflow")),
            maintenanceTemplate("ORACLE_INSTANCE_STATUS", "Oracle instance status",
                "Read Oracle instance name and status.",
                "oracle", "instance",
                "SELECT instance_name, status FROM v$instance",
                List.of("instance", "status", "health", "database status")),
            maintenanceTemplate("ORACLE_LOCKS", "Oracle lock view",
                "Read Oracle lock metadata from v$lock.",
                "oracle", "lock",
                "SELECT * FROM v$lock",
                List.of("lock", "blocking", "wait", "lock_check")),
            maintenanceTemplate("ORACLE_SYSTEM_EVENTS", "Oracle system wait events",
                "Read Oracle system wait event counters for performance analysis.",
                "oracle", "performance",
                "SELECT * FROM v$system_event",
                List.of("wait event", "performance", "cpu", "system event", "performance_issue")),
            maintenanceTemplate("ORACLE_TABLESPACE_USAGE", "Oracle tablespace usage",
                "Summarize Oracle tablespace capacity, used space, free space, and utilization percentage.",
                "oracle", "storage",
                "SELECT df.tablespace_name, "
                    + "ROUND(df.total_bytes / 1024 / 1024, 2) AS total_mb, "
                    + "ROUND((df.total_bytes - NVL(fs.free_bytes, 0)) / 1024 / 1024, 2) AS used_mb, "
                    + "ROUND(NVL(fs.free_bytes, 0) / 1024 / 1024, 2) AS free_mb, "
                    + "ROUND((df.total_bytes - NVL(fs.free_bytes, 0)) * 100 / NULLIF(df.total_bytes, 0), 2) AS used_pct "
                    + "FROM (SELECT tablespace_name, SUM(bytes) AS total_bytes FROM dba_data_files GROUP BY tablespace_name) df "
                    + "LEFT JOIN (SELECT tablespace_name, SUM(bytes) AS free_bytes FROM dba_free_space GROUP BY tablespace_name) fs "
                    + "ON fs.tablespace_name = df.tablespace_name ORDER BY used_pct DESC",
                List.of("tablespace usage", "tablespace utilization", "used space", "free space", "usage rate",
                    "utilization", "storage usage", "storage_check")),
            maintenanceTemplate("ORACLE_TABLESPACE_SIZE", "Oracle tablespace size",
                "Summarize Oracle tablespace size in megabytes.",
                "oracle", "storage",
                "SELECT tablespace_name, SUM(bytes)/1024/1024 AS size_mb FROM dba_data_files GROUP BY tablespace_name",
                List.of("tablespace", "storage", "space", "database size", "storage_check")),
            maintenanceTemplate("ORACLE_DATABASE_OVERVIEW", "Oracle database overview",
                "Read Oracle database and instance overview for health inspection.",
                "oracle", "instance",
                """
                    SELECT d.name AS database_name,
                           d.open_mode,
                           d.database_role,
                           i.instance_name,
                           i.host_name,
                           i.version,
                           i.status,
                           i.startup_time
                    FROM v$database d
                    CROSS JOIN v$instance i
                    """,
                List.of("database overview", "instance status", "open mode", "database role", "startup time", "database status")),
            maintenanceTemplate("ORACLE_ACTIVE_SESSIONS", "Oracle active sessions",
                "Read Oracle active user sessions with wait and blocking context.",
                "oracle", "connection",
                """
                    SELECT sid,
                           serial#,
                           username,
                           status,
                           machine,
                           program,
                           event,
                           wait_class,
                           blocking_session,
                           seconds_in_wait,
                           sql_id
                    FROM v$session
                    WHERE type = 'USER'
                      AND status = 'ACTIVE'
                    ORDER BY seconds_in_wait DESC
                    """,
                List.of("active session", "session wait", "blocking session", "connection", "wait event", "connection_overflow")),
            maintenanceTemplate("ORACLE_TOP_SQL_ELAPSED", "Oracle top SQL by elapsed time",
                "Read Oracle SQL area statements ordered by elapsed time for performance analysis.",
                "oracle", "performance",
                """
                    SELECT sql_id,
                           executions,
                           elapsed_seconds,
                           cpu_seconds,
                           buffer_gets,
                           disk_reads,
                           rows_processed,
                           sql_text
                    FROM (
                        SELECT sql_id,
                               executions,
                               ROUND(elapsed_time / 1000000, 2) AS elapsed_seconds,
                               ROUND(cpu_time / 1000000, 2) AS cpu_seconds,
                               buffer_gets,
                               disk_reads,
                               rows_processed,
                               SUBSTR(sql_text, 1, 1000) AS sql_text
                        FROM v$sqlarea
                        ORDER BY elapsed_time DESC
                    )
                    WHERE ROWNUM <= 20
                    """,
                List.of("top sql", "elapsed time", "cpu time", "buffer gets", "disk reads", "slow query", "performance_issue")),
            maintenanceTemplate("ORACLE_WAIT_CLASS_SUMMARY", "Oracle wait class summary",
                "Summarize Oracle system wait counters by wait class.",
                "oracle", "performance",
                """
                    SELECT wait_class,
                           SUM(total_waits) AS total_waits,
                           ROUND(SUM(time_waited_micro) / 1000000, 2) AS waited_seconds
                    FROM v$system_event
                    WHERE wait_class <> 'Idle'
                    GROUP BY wait_class
                    ORDER BY waited_seconds DESC
                    """,
                List.of("wait class", "system wait", "performance", "latency", "wait event", "performance_issue")),

            maintenanceTemplate("POSTGRES_ACTIVITY", "PostgreSQL current activity",
                "Read PostgreSQL sessions and active queries from pg_stat_activity.",
                "postgresql", "connection",
                "SELECT * FROM pg_stat_activity",
                List.of("activity", "connection", "session", "active query", "connection_overflow")),
            maintenanceTemplate("POSTGRES_DATABASE_SIZE", "PostgreSQL database size",
                "Read PostgreSQL database sizes.",
                "postgresql", "storage",
                "SELECT pg_database.datname, pg_size_pretty(pg_database_size(pg_database.datname)) AS size FROM pg_database",
                List.of("database size", "storage", "space", "storage_check")),
            maintenanceTemplate("POSTGRES_TABLE_SIZE_RANKING", "PostgreSQL table size ranking",
                "Rank user tables by total relation size.",
                "postgresql", "storage",
                "SELECT relname AS table_name, pg_size_pretty(pg_total_relation_size(relid)) AS size FROM pg_catalog.pg_statio_user_tables ORDER BY pg_total_relation_size(relid) DESC",
                List.of("table size", "storage", "ranking", "largest table", "storage_check")),
            maintenanceTemplate("POSTGRES_LOCKS", "PostgreSQL locks",
                "Read PostgreSQL lock metadata from pg_locks.",
                "postgresql", "lock",
                "SELECT * FROM pg_locks",
                List.of("lock", "blocking", "pg_locks", "lock_check")),
            maintenanceTemplate("POSTGRES_LONG_TRANSACTIONS", "PostgreSQL long running transactions",
                "Read non-idle PostgreSQL activity ordered by query start time.",
                "postgresql", "performance",
                "SELECT * FROM pg_stat_activity WHERE state != 'idle' ORDER BY query_start",
                List.of("long transaction", "slow query", "active query", "performance_issue", "transaction")),
            maintenanceTemplate("POSTGRES_DATABASE_STATS", "PostgreSQL database statistics",
                "Read PostgreSQL database statistics counters for health and workload analysis.",
                "postgresql", "instance",
                """
                    SELECT datname,
                           numbackends,
                           xact_commit,
                           xact_rollback,
                           blks_read,
                           blks_hit,
                           tup_returned,
                           tup_fetched,
                           tup_inserted,
                           tup_updated,
                           tup_deleted,
                           deadlocks,
                           temp_files,
                           temp_bytes
                    FROM pg_stat_database
                    WHERE datname IS NOT NULL
                    ORDER BY numbackends DESC, datname
                    """,
                List.of("database stats", "transaction", "cache hit", "deadlock", "temp files", "database status")),
            maintenanceTemplate("POSTGRES_WAIT_ACTIVITY", "PostgreSQL wait activity",
                "Read PostgreSQL sessions currently waiting on locks, IO, or other wait events.",
                "postgresql", "performance",
                """
                    SELECT pid,
                           usename,
                           datname,
                           state,
                           wait_event_type,
                           wait_event,
                           now() - state_change AS state_age,
                           now() - query_start AS query_age,
                           LEFT(query, 1000) AS query
                    FROM pg_stat_activity
                    WHERE wait_event IS NOT NULL
                    ORDER BY query_age DESC NULLS LAST
                    """,
                List.of("wait event", "wait activity", "lock wait", "io wait", "active query", "performance_issue")),
            maintenanceTemplate("POSTGRES_BLOCKING_CHAINS", "PostgreSQL blocking chains",
                "Read PostgreSQL blocked and blocking session pairs for lock troubleshooting.",
                "postgresql", "lock",
                """
                    SELECT blocked.pid AS blocked_pid,
                           blocked.usename AS blocked_user,
                           blocked.datname AS blocked_database,
                           blocking.pid AS blocking_pid,
                           blocking.usename AS blocking_user,
                           now() - blocked.query_start AS blocked_duration,
                           LEFT(blocked.query, 1000) AS blocked_query,
                           LEFT(blocking.query, 1000) AS blocking_query
                    FROM pg_stat_activity blocked
                    JOIN pg_stat_activity blocking
                      ON blocking.pid = ANY(pg_blocking_pids(blocked.pid))
                    ORDER BY blocked_duration DESC
                    """,
                List.of("blocking chain", "blocked session", "blocking session", "lock wait", "lock_check")),
            maintenanceTemplate("POSTGRES_BGWRITER_STATS", "PostgreSQL bgwriter statistics",
                "Read PostgreSQL background writer and checkpoint counters.",
                "postgresql", "performance",
                """
                    SELECT checkpoints_timed,
                           checkpoints_req,
                           checkpoint_write_time,
                           checkpoint_sync_time,
                           buffers_checkpoint,
                           buffers_clean,
                           maxwritten_clean,
                           buffers_backend,
                           buffers_backend_fsync,
                           buffers_alloc
                    FROM pg_stat_bgwriter
                    """,
                List.of("bgwriter", "checkpoint", "buffers", "write time", "performance", "database status")),

            maintenanceTemplate("SQLSERVER_SESSIONS", "SQL Server current sessions",
                "Read SQL Server session metadata.",
                "sqlserver", "connection",
                """
                    SELECT TOP (200)
                        session_id,
                        login_time,
                        host_name,
                        program_name,
                        login_name,
                        status,
                        cpu_time,
                        memory_usage,
                        total_scheduled_time,
                        total_elapsed_time,
                        reads,
                        writes,
                        logical_reads,
                        open_transaction_count,
                        last_request_start_time,
                        last_request_end_time
                    FROM sys.dm_exec_sessions
                    WHERE is_user_process = 1
                    ORDER BY session_id
                    """,
                List.of("session", "connection", "dm_exec_sessions", "connection_overflow")),
            maintenanceTemplate("SQLSERVER_REQUESTS", "SQL Server current requests",
                "Read SQL Server active requests for blocking and performance analysis.",
                "sqlserver", "performance",
                """
                    SELECT TOP (200)
                        session_id,
                        request_id,
                        start_time,
                        status,
                        command,
                        blocking_session_id,
                        wait_type,
                        wait_time,
                        wait_resource,
                        cpu_time,
                        total_elapsed_time,
                        reads,
                        writes,
                        logical_reads,
                        database_id
                    FROM sys.dm_exec_requests
                    ORDER BY total_elapsed_time DESC
                    """,
                List.of("request", "blocking", "performance", "dm_exec_requests", "performance_issue")),
            maintenanceTemplate("SQLSERVER_DATABASE_SIZE", "SQL Server database size",
                "Summarize SQL Server database file size in megabytes.",
                "sqlserver", "storage",
                "SELECT DB_NAME(database_id) AS database_name, SUM(size) * 8.0 / 1024 AS size_mb FROM sys.master_files GROUP BY database_id",
                List.of("database size", "storage", "space", "master_files", "storage_check")),
            maintenanceTemplate("SQLSERVER_LOCKS", "SQL Server locks",
                "Read SQL Server lock metadata.",
                "sqlserver", "lock",
                """
                    SELECT TOP (500)
                        request_session_id,
                        resource_type,
                        resource_database_id,
                        resource_associated_entity_id,
                        request_mode,
                        request_type,
                        request_status,
                        request_owner_type
                    FROM sys.dm_tran_locks
                    ORDER BY request_session_id, resource_type
                    """,
                List.of("lock", "blocking", "dm_tran_locks", "lock_check")),
            maintenanceTemplate("SQLSERVER_IO_STATS", "SQL Server IO virtual file stats",
                "Read SQL Server virtual file IO statistics.",
                "sqlserver", "performance",
                """
                    SELECT TOP (500)
                        database_id,
                        file_id,
                        num_of_reads,
                        num_of_bytes_read,
                        io_stall_read_ms,
                        num_of_writes,
                        num_of_bytes_written,
                        io_stall_write_ms,
                        io_stall,
                        size_on_disk_bytes
                    FROM sys.dm_io_virtual_file_stats(NULL, NULL)
                    ORDER BY io_stall DESC
                    """,
                List.of("io", "performance", "file stats", "dm_io_virtual_file_stats", "performance_issue")),
            maintenanceTemplate("SQLSERVER_INSTANCE_OVERVIEW", "SQL Server instance overview",
                "Read SQL Server instance version and host level properties.",
                "sqlserver", "instance",
                """
                    SELECT
                        CAST(SERVERPROPERTY('MachineName') AS nvarchar(256)) AS machine_name,
                        CAST(SERVERPROPERTY('ServerName') AS nvarchar(256)) AS server_name,
                        CAST(SERVERPROPERTY('Edition') AS nvarchar(256)) AS edition,
                        CAST(SERVERPROPERTY('ProductVersion') AS nvarchar(128)) AS product_version,
                        CAST(SERVERPROPERTY('ProductLevel') AS nvarchar(128)) AS product_level,
                        CAST(SERVERPROPERTY('EngineEdition') AS int) AS engine_edition,
                        CAST(SERVERPROPERTY('IsClustered') AS int) AS is_clustered,
                        CAST(SERVERPROPERTY('IsHadrEnabled') AS int) AS is_hadr_enabled
                    """,
                List.of("instance overview", "version", "edition", "hadr", "cluster", "database status")),
            maintenanceTemplate("SQLSERVER_WAIT_STATS", "SQL Server wait statistics",
                "Read SQL Server wait statistics excluding common idle waits.",
                "sqlserver", "performance",
                """
                    SELECT TOP (50)
                        wait_type,
                        waiting_tasks_count,
                        wait_time_ms,
                        signal_wait_time_ms,
                        wait_time_ms - signal_wait_time_ms AS resource_wait_time_ms
                    FROM sys.dm_os_wait_stats
                    WHERE wait_type NOT LIKE 'SLEEP%'
                      AND wait_type NOT IN (
                          'BROKER_TASK_STOP',
                          'BROKER_TO_FLUSH',
                          'CLR_AUTO_EVENT',
                          'CLR_MANUAL_EVENT',
                          'LAZYWRITER_SLEEP',
                          'LOGMGR_QUEUE',
                          'REQUEST_FOR_DEADLOCK_SEARCH',
                          'SQLTRACE_BUFFER_FLUSH',
                          'XE_TIMER_EVENT',
                          'XE_DISPATCHER_WAIT',
                          'WAITFOR'
                      )
                    ORDER BY wait_time_ms DESC
                    """,
                List.of("wait stats", "wait type", "resource wait", "signal wait", "latency", "performance_issue")),
            maintenanceTemplate("SQLSERVER_DATABASE_STATS", "SQL Server database runtime statistics",
                "Read SQL Server database state and recovery metadata.",
                "sqlserver", "instance",
                """
                    SELECT
                        name AS database_name,
                        state_desc,
                        recovery_model_desc,
                        compatibility_level,
                        user_access_desc,
                        is_read_only,
                        is_auto_close_on,
                        is_auto_shrink_on,
                        page_verify_option_desc
                    FROM sys.databases
                    ORDER BY name
                    """,
                List.of("database status", "recovery model", "compatibility", "read only", "database overview")),
            maintenanceTemplate("SQLSERVER_MEMORY_OVERVIEW", "SQL Server memory overview",
                "Read SQL Server host memory counters.",
                "sqlserver", "performance",
                """
                    SELECT
                        total_physical_memory_kb,
                        available_physical_memory_kb,
                        total_page_file_kb,
                        available_page_file_kb,
                        system_memory_state_desc
                    FROM sys.dm_os_sys_memory
                    """,
                List.of("memory", "physical memory", "available memory", "resource", "performance_issue")),

            maintenanceTemplate("DM_SESSIONS", "Dameng current sessions",
                "Read Dameng active session metadata.",
                "dm", "connection",
                "SELECT * FROM V$SESSIONS",
                List.of("dameng", "dm", "session", "connection", "active session", "connection_overflow")),
            maintenanceTemplate("DM_INSTANCE_STATUS", "Dameng instance status",
                "Read Dameng instance runtime status.",
                "dm", "instance",
                "SELECT * FROM V$INSTANCE",
                List.of("dameng", "dm", "instance", "status", "health", "database status")),
            maintenanceTemplate("DM_LOCKS", "Dameng lock view",
                "Read Dameng lock metadata for blocking troubleshooting.",
                "dm", "lock",
                "SELECT * FROM V$LOCKS",
                List.of("dameng", "dm", "lock", "blocking", "wait", "lock_check")),
            maintenanceTemplate("DM_SQL_HISTORY", "Dameng SQL history",
                "Read Dameng SQL execution history for performance inspection.",
                "dm", "performance",
                "SELECT * FROM V$SQL_HISTORY",
                List.of("dameng", "dm", "sql history", "slow query", "performance", "performance_issue")),
            maintenanceTemplate("DM_TABLESPACE_SIZE", "Dameng tablespace size",
                "Summarize Dameng tablespace data file size in megabytes.",
                "dm", "storage",
                "SELECT TABLESPACE_NAME, SUM(BYTES)/1024/1024 AS SIZE_MB FROM DBA_DATA_FILES GROUP BY TABLESPACE_NAME",
                List.of("dameng", "dm", "tablespace", "storage", "space", "database size", "storage_check")),
            maintenanceTemplate("DM_ACTIVE_SESSIONS", "Dameng active sessions",
                "Read Dameng active sessions for runtime connection and wait inspection.",
                "dm", "connection",
                """
                    SELECT *
                    FROM V$SESSIONS
                    WHERE STATE IS NOT NULL
                    ORDER BY SESS_ID
                    """,
                List.of("dameng", "dm", "active session", "session", "connection", "wait", "connection_overflow")),
            maintenanceTemplate("DM_TOP_SQL_HISTORY", "Dameng top SQL history",
                "Read Dameng SQL history ordered by elapsed time for performance troubleshooting.",
                "dm", "performance",
                """
                    SELECT *
                    FROM V$SQL_HISTORY
                    ORDER BY ELAPSED_TIME DESC
                    """,
                List.of("dameng", "dm", "top sql", "sql history", "elapsed time", "slow query", "performance_issue")),
            maintenanceTemplate("DM_LOCK_WAIT_OVERVIEW", "Dameng lock wait overview",
                "Read Dameng lock view ordered by session for blocking and lock wait analysis.",
                "dm", "lock",
                """
                    SELECT *
                    FROM V$LOCKS
                    ORDER BY SESS_ID
                    """,
                List.of("dameng", "dm", "lock wait", "blocking", "lock", "lock_check")),
            maintenanceTemplate("DM_TABLESPACE_USAGE", "Dameng tablespace usage",
                "Summarize Dameng tablespace data file capacity for storage inspection.",
                "dm", "storage",
                """
                    SELECT TABLESPACE_NAME,
                           ROUND(SUM(BYTES) / 1024 / 1024, 2) AS TOTAL_MB
                    FROM DBA_DATA_FILES
                    GROUP BY TABLESPACE_NAME
                    ORDER BY TOTAL_MB DESC
                    """,
                List.of("dameng", "dm", "tablespace usage", "storage", "capacity", "space", "storage_check")),

            maintenanceTemplate("TDSQL_SHOW_PROCESSLIST", "TDSQL current connections",
                "Show current TDSQL sessions and running statements.",
                "tdsql", "connection",
                "SHOW PROCESSLIST",
                List.of("tdsql", "connection", "session", "processlist", "connection_overflow")),
            maintenanceTemplate("TDSQL_SHOW_STATUS", "TDSQL status variables",
                "Show TDSQL server status counters for health and performance inspection.",
                "tdsql", "instance",
                "SHOW STATUS",
                List.of("tdsql", "status", "instance", "health", "performance", "performance_issue")),
            maintenanceTemplate("TDSQL_INNODB_STATUS", "TDSQL InnoDB engine status",
                "Show TDSQL InnoDB engine status for lock and deadlock troubleshooting.",
                "tdsql", "lock",
                "SHOW ENGINE INNODB STATUS",
                List.of("tdsql", "innodb", "lock", "deadlock", "transaction", "lock_check")),
            maintenanceTemplate("TDSQL_INNODB_TRX", "TDSQL InnoDB transactions",
                "Read active TDSQL InnoDB transaction metadata.",
                "tdsql", "lock",
                "SELECT * FROM information_schema.INNODB_TRX",
                List.of("tdsql", "transaction", "lock", "innodb trx", "long transaction", "lock_check")),
            maintenanceTemplate("TDSQL_DATABASE_SIZE", "TDSQL database size",
                "Summarize TDSQL database size by schema in megabytes.",
                "tdsql", "storage",
                "SELECT table_schema AS db, SUM(data_length + index_length)/1024/1024 AS size_mb FROM information_schema.tables GROUP BY table_schema",
                List.of("tdsql", "database size", "storage", "schema size", "space", "storage_check")),
            maintenanceTemplate("TDSQL_INSTANCE_VARIABLES", "TDSQL instance variables",
                "Read key TDSQL instance variables for runtime configuration inspection.",
                "tdsql", "instance",
                """
                    SELECT variable_name, variable_value
                    FROM performance_schema.global_variables
                    WHERE variable_name IN (
                        'version',
                        'version_comment',
                        'max_connections',
                        'innodb_buffer_pool_size',
                        'innodb_flush_log_at_trx_commit',
                        'sync_binlog',
                        'slow_query_log',
                        'long_query_time',
                        'read_only',
                        'super_read_only'
                    )
                    ORDER BY variable_name
                    """,
                List.of("tdsql", "variables", "configuration", "version", "max connections", "buffer pool", "database status")),
            maintenanceTemplate("TDSQL_CONNECTION_STATUS", "TDSQL connection status counters",
                "Read TDSQL connection related status counters for capacity and saturation analysis.",
                "tdsql", "connection",
                """
                    SELECT variable_name, variable_value
                    FROM performance_schema.global_status
                    WHERE variable_name IN (
                        'Threads_connected',
                        'Threads_running',
                        'Max_used_connections',
                        'Connections',
                        'Aborted_connects',
                        'Connection_errors_max_connections'
                    )
                    ORDER BY variable_name
                    """,
                List.of("tdsql", "connection status", "threads connected", "threads running", "connection_overflow")),
            maintenanceTemplate("TDSQL_TOP_TABLES_SIZE", "TDSQL largest tables",
                "Rank TDSQL tables by total data and index size.",
                "tdsql", "storage",
                """
                    SELECT table_schema,
                           table_name,
                           ROUND((data_length + index_length) / 1024 / 1024, 2) AS total_mb,
                           ROUND(data_length / 1024 / 1024, 2) AS data_mb,
                           ROUND(index_length / 1024 / 1024, 2) AS index_mb,
                           table_rows
                    FROM information_schema.tables
                    WHERE table_schema NOT IN ('information_schema', 'mysql', 'performance_schema', 'sys')
                    ORDER BY total_mb DESC
                    LIMIT 50
                    """,
                List.of("tdsql", "largest table", "table size", "storage", "capacity", "storage_check")),
            maintenanceTemplate("TDSQL_STATEMENT_DIGEST_TOP", "TDSQL top statement digests",
                "Read top TDSQL statement digests by total latency from performance_schema.",
                "tdsql", "performance",
                """
                    SELECT digest_text,
                           count_star,
                           ROUND(sum_timer_wait / 1000000000000, 3) AS total_seconds,
                           ROUND(avg_timer_wait / 1000000000000, 6) AS avg_seconds,
                           sum_rows_examined,
                           sum_rows_sent
                    FROM performance_schema.events_statements_summary_by_digest
                    WHERE digest_text IS NOT NULL
                    ORDER BY sum_timer_wait DESC
                    LIMIT 20
                    """,
                List.of("tdsql", "statement digest", "top sql", "slow query", "latency", "performance_issue")),

            maintenanceTemplate("TIDB_PROCESSLIST", "TiDB current connections",
                "Show current TiDB sessions and running statements.",
                "tidb", "connection",
                "SHOW PROCESSLIST",
                List.of("tidb", "connection", "session", "processlist", "connection_overflow")),
            maintenanceTemplate("TIDB_CLUSTER_INFO", "TiDB cluster information",
                "Read TiDB cluster node metadata for daily health inspection.",
                "tidb", "instance",
                "SELECT * FROM information_schema.CLUSTER_INFO",
                List.of("tidb", "cluster", "instance", "node", "status", "health", "database status")),
            maintenanceTemplate("TIDB_TRANSACTIONS", "TiDB running transactions",
                "Read TiDB running transaction metadata for lock and long transaction troubleshooting.",
                "tidb", "lock",
                "SELECT * FROM information_schema.CLUSTER_TIDB_TRX",
                List.of("tidb", "transaction", "lock", "long transaction", "blocking", "lock_check")),
            maintenanceTemplate("TIDB_STATEMENTS_SUMMARY", "TiDB statement summary",
                "Read TiDB statement summary ordered by average latency for performance inspection.",
                "tidb", "performance",
                "SELECT * FROM information_schema.STATEMENTS_SUMMARY ORDER BY AVG_LATENCY DESC",
                List.of("tidb", "statement summary", "slow query", "latency", "performance", "performance_issue")),
            maintenanceTemplate("TIDB_DATABASE_SIZE", "TiDB database size",
                "Summarize TiDB database size by schema in megabytes.",
                "tidb", "storage",
                "SELECT table_schema AS db, SUM(data_length + index_length)/1024/1024 AS size_mb FROM information_schema.tables GROUP BY table_schema",
                List.of("tidb", "database size", "storage", "schema size", "space", "storage_check")),
            maintenanceTemplate("TIDB_NODE_LOAD", "TiDB node load",
                "Read TiDB cluster node load metrics for CPU, memory and IO pressure inspection.",
                "tidb", "performance",
                """
                    SELECT *
                    FROM information_schema.CLUSTER_LOAD
                    ORDER BY INSTANCE, DEVICE_TYPE, DEVICE_NAME
                    """,
                List.of("tidb", "cluster load", "cpu", "memory", "io", "node load", "performance_issue")),
            maintenanceTemplate("TIDB_CLUSTER_HARDWARE", "TiDB cluster hardware",
                "Read TiDB cluster hardware metadata for capacity and resource inspection.",
                "tidb", "instance",
                """
                    SELECT *
                    FROM information_schema.CLUSTER_HARDWARE
                    ORDER BY INSTANCE, DEVICE_TYPE, DEVICE_NAME
                    """,
                List.of("tidb", "cluster hardware", "cpu", "memory", "disk", "capacity", "database status")),
            maintenanceTemplate("TIDB_RECENT_SLOW_QUERIES", "TiDB recent slow queries",
                "Read recent TiDB slow query records from cluster slow query view.",
                "tidb", "performance",
                """
                    SELECT time,
                           query_time,
                           process_time,
                           wait_time,
                           backoff_time,
                           request_count,
                           total_keys,
                           process_keys,
                           db,
                           digest,
                           left(query, 500) AS query_text
                    FROM information_schema.CLUSTER_SLOW_QUERY
                    ORDER BY time DESC
                    LIMIT 20
                    """,
                List.of("tidb", "slow query", "cluster slow query", "query_time", "latency", "performance_issue")),
            maintenanceTemplate("TIDB_DATA_LOCK_WAITS", "TiDB data lock waits",
                "Read TiDB data lock wait metadata for blocking and transaction troubleshooting.",
                "tidb", "lock",
                """
                    SELECT *
                    FROM information_schema.DATA_LOCK_WAITS
                    """,
                List.of("tidb", "data lock waits", "lock wait", "blocking", "transaction", "lock_check")),
            maintenanceTemplate("TIDB_TABLE_STORAGE_STATS", "TiDB table storage stats",
                "Read TiDB table storage statistics for large table and region capacity inspection.",
                "tidb", "storage",
                """
                    SELECT table_schema,
                           table_name,
                           table_id,
                           peer_count,
                           region_count,
                           empty_region_count,
                           table_size,
                           table_keys
                    FROM information_schema.TABLE_STORAGE_STATS
                    ORDER BY table_size DESC
                    """,
                List.of("tidb", "table storage", "table size", "region count", "storage", "storage_check")),
            maintenanceTemplate("TIDB_REGION_STATUS", "TiDB region status",
                "Read TiKV region status for hotspot and region distribution inspection.",
                "tidb", "storage",
                """
                    SELECT *
                    FROM information_schema.TIKV_REGION_STATUS
                    ORDER BY WRITTEN_BYTES DESC
                    LIMIT 50
                    """,
                List.of("tidb", "tikv region", "hot region", "written bytes", "read bytes", "storage_check")),
            maintenanceTemplate("TIDB_ANALYZE_STATUS", "TiDB analyze status",
                "Read TiDB analyze job status for statistics freshness inspection.",
                "tidb", "instance",
                """
                    SELECT *
                    FROM information_schema.ANALYZE_STATUS
                    ORDER BY START_TIME DESC
                    LIMIT 50
                    """,
                List.of("tidb", "analyze status", "statistics", "stats freshness", "health", "database status")),
            maintenanceTemplate("TIDB_STATEMENTS_ERRORS", "TiDB statement error summary",
                "Read TiDB statement summary ordered by error count and latency.",
                "tidb", "performance",
                """
                    SELECT digest,
                           digest_text,
                           schema_name,
                           exec_count,
                           sum_errors,
                           avg_latency,
                           max_latency,
                           avg_processed_keys,
                           avg_total_keys
                    FROM information_schema.STATEMENTS_SUMMARY
                    WHERE sum_errors > 0
                    ORDER BY sum_errors DESC, avg_latency DESC
                    """,
                List.of("tidb", "statement errors", "sql error", "statement summary", "performance_issue")),

            maintenanceTemplate("KINGBASE_ACTIVITY", "Kingbase current activity",
                "Read KingbaseES sessions and active queries from sys_stat_activity.",
                "kingbase", "connection",
                "SELECT * FROM sys_stat_activity",
                List.of("kingbase", "kingbasees", "session", "connection", "active query", "connection_overflow")),
            maintenanceTemplate("KINGBASE_DATABASE_STATS", "Kingbase database statistics",
                "Read KingbaseES database statistics counters for health and performance inspection.",
                "kingbase", "instance",
                "SELECT * FROM sys_stat_database",
                List.of("kingbase", "kingbasees", "database stats", "status", "health", "performance", "performance_issue")),
            maintenanceTemplate("KINGBASE_LOCKS", "Kingbase locks",
                "Read KingbaseES lock metadata for blocking troubleshooting.",
                "kingbase", "lock",
                "SELECT * FROM sys_locks",
                List.of("kingbase", "kingbasees", "lock", "blocking", "sys_locks", "lock_check")),
            maintenanceTemplate("KINGBASE_LONG_QUERIES", "Kingbase long running queries",
                "Read non-idle KingbaseES activity ordered by query start time.",
                "kingbase", "performance",
                "SELECT * FROM sys_stat_activity WHERE state != 'idle' ORDER BY query_start",
                List.of("kingbase", "kingbasees", "long query", "slow query", "active query", "performance_issue")),
            maintenanceTemplate("KINGBASE_DATABASE_SIZE", "Kingbase database size",
                "Read KingbaseES database sizes.",
                "kingbase", "storage",
                "SELECT datname, sys_size_pretty(sys_database_size(oid)) AS size FROM sys_database",
                List.of("kingbase", "kingbasees", "database size", "storage", "space", "storage_check")),
            maintenanceTemplate("KINGBASE_WAIT_ACTIVITY", "Kingbase wait activity",
                "Read active KingbaseES sessions with wait event information for runtime diagnosis.",
                "kingbase", "performance",
                """
                    SELECT pid, usename, datname, application_name, client_addr, state,
                           wait_event_type, wait_event,
                           now() - query_start AS query_age,
                           left(query, 500) AS query_text
                    FROM sys_stat_activity
                    WHERE state <> 'idle' OR wait_event IS NOT NULL
                    ORDER BY query_start NULLS LAST
                    """,
                List.of("kingbase", "kingbasees", "wait event", "active session", "latency", "performance_issue")),
            maintenanceTemplate("KINGBASE_BLOCKING_OVERVIEW", "Kingbase blocking overview",
                "Read KingbaseES blocking and waiting lock relationships without recursive traversal.",
                "kingbase", "lock",
                """
                    SELECT blocked_locks.pid AS blocked_pid,
                           blocked_activity.usename AS blocked_user,
                           blocking_locks.pid AS blocking_pid,
                           blocking_activity.usename AS blocking_user,
                           blocked_activity.wait_event_type,
                           blocked_activity.wait_event,
                           now() - blocked_activity.query_start AS blocked_query_age,
                           left(blocked_activity.query, 500) AS blocked_query,
                           left(blocking_activity.query, 500) AS blocking_query
                    FROM sys_locks blocked_locks
                    JOIN sys_stat_activity blocked_activity
                      ON blocked_activity.pid = blocked_locks.pid
                    JOIN sys_locks blocking_locks
                      ON blocking_locks.locktype = blocked_locks.locktype
                     AND blocking_locks.database IS NOT DISTINCT FROM blocked_locks.database
                     AND blocking_locks.relation IS NOT DISTINCT FROM blocked_locks.relation
                     AND blocking_locks.page IS NOT DISTINCT FROM blocked_locks.page
                     AND blocking_locks.tuple IS NOT DISTINCT FROM blocked_locks.tuple
                     AND blocking_locks.virtualxid IS NOT DISTINCT FROM blocked_locks.virtualxid
                     AND blocking_locks.transactionid IS NOT DISTINCT FROM blocked_locks.transactionid
                     AND blocking_locks.classid IS NOT DISTINCT FROM blocked_locks.classid
                     AND blocking_locks.objid IS NOT DISTINCT FROM blocked_locks.objid
                     AND blocking_locks.objsubid IS NOT DISTINCT FROM blocked_locks.objsubid
                     AND blocking_locks.pid <> blocked_locks.pid
                    JOIN sys_stat_activity blocking_activity
                      ON blocking_activity.pid = blocking_locks.pid
                    WHERE NOT blocked_locks.granted
                      AND blocking_locks.granted
                    ORDER BY blocked_activity.query_start NULLS LAST
                    """,
                List.of("kingbase", "kingbasees", "blocking", "blocked session", "lock wait", "lock_check")),
            maintenanceTemplate("KINGBASE_LONG_TRANSACTIONS", "Kingbase long transactions",
                "Read long running KingbaseES transactions and active queries.",
                "kingbase", "performance",
                """
                    SELECT pid, usename, datname, application_name, client_addr, state,
                           now() - xact_start AS transaction_age,
                           now() - query_start AS query_age,
                           left(query, 500) AS query_text
                    FROM sys_stat_activity
                    WHERE xact_start IS NOT NULL
                      AND state <> 'idle'
                    ORDER BY xact_start
                    """,
                List.of("kingbase", "kingbasees", "long transaction", "long query", "active query", "performance_issue")),
            maintenanceTemplate("KINGBASE_CACHE_HIT_RATIO", "Kingbase cache hit ratio",
                "Summarize KingbaseES database cache hit ratio and transaction counters.",
                "kingbase", "performance",
                """
                    SELECT datname,
                           numbackends,
                           xact_commit,
                           xact_rollback,
                           blks_read,
                           blks_hit,
                           CASE WHEN blks_hit + blks_read = 0 THEN NULL
                                ELSE round(blks_hit * 100.0 / (blks_hit + blks_read), 2)
                           END AS cache_hit_percent,
                           tup_returned,
                           tup_fetched,
                           tup_inserted,
                           tup_updated,
                           tup_deleted
                    FROM sys_stat_database
                    WHERE datname IS NOT NULL
                    ORDER BY cache_hit_percent NULLS FIRST, blks_read DESC
                    """,
                List.of("kingbase", "kingbasees", "cache hit", "buffer hit", "database stats", "performance_issue")),
            maintenanceTemplate("KINGBASE_TOP_TABLES_SIZE", "Kingbase largest tables",
                "Read largest KingbaseES tables with total, table and index size.",
                "kingbase", "storage",
                """
                    SELECT n.nspname AS schema_name,
                           c.relname AS table_name,
                           sys_size_pretty(sys_total_relation_size(c.oid)) AS total_size,
                           sys_size_pretty(sys_relation_size(c.oid)) AS table_size,
                           sys_size_pretty(sys_total_relation_size(c.oid) - sys_relation_size(c.oid)) AS index_size,
                           sys_total_relation_size(c.oid) AS total_bytes
                    FROM sys_class c
                    JOIN sys_namespace n ON n.oid = c.relnamespace
                    WHERE c.relkind IN ('r', 'p')
                      AND n.nspname NOT IN ('sys_catalog', 'information_schema')
                    ORDER BY sys_total_relation_size(c.oid) DESC
                    """,
                List.of("kingbase", "kingbasees", "largest table", "table size", "storage", "capacity", "storage_check")),
            maintenanceTemplate("KINGBASE_TABLE_ACTIVITY", "Kingbase table activity",
                "Read KingbaseES table scan, DML and vacuum/analyze activity counters.",
                "kingbase", "performance",
                """
                    SELECT schemaname,
                           relname,
                           seq_scan,
                           seq_tup_read,
                           idx_scan,
                           idx_tup_fetch,
                           n_tup_ins,
                           n_tup_upd,
                           n_tup_del,
                           n_live_tup,
                           n_dead_tup,
                           last_vacuum,
                           last_autovacuum,
                           last_analyze,
                           last_autoanalyze
                    FROM sys_stat_user_tables
                    ORDER BY n_dead_tup DESC, seq_tup_read DESC
                    """,
                List.of("kingbase", "kingbasees", "table activity", "dead tuple", "vacuum", "analyze", "performance_issue")),
            maintenanceTemplate("KINGBASE_INDEX_USAGE", "Kingbase index usage",
                "Read KingbaseES index usage counters to identify unused or low-hit indexes.",
                "kingbase", "performance",
                """
                    SELECT schemaname,
                           relname AS table_name,
                           indexrelname AS index_name,
                           idx_scan,
                           idx_tup_read,
                           idx_tup_fetch
                    FROM sys_stat_user_indexes
                    ORDER BY idx_scan ASC, idx_tup_read DESC
                    """,
                List.of("kingbase", "kingbasees", "index usage", "unused index", "idx_scan", "performance_issue")),
            maintenanceTemplate("KINGBASE_BGWRITER_STATS", "Kingbase bgwriter statistics",
                "Read KingbaseES checkpoint and background writer counters.",
                "kingbase", "performance",
                """
                    SELECT checkpoints_timed,
                           checkpoints_req,
                           checkpoint_write_time,
                           checkpoint_sync_time,
                           buffers_checkpoint,
                           buffers_clean,
                           maxwritten_clean,
                           buffers_backend,
                           buffers_backend_fsync,
                           buffers_alloc,
                           stats_reset
                    FROM sys_stat_bgwriter
                    """,
                List.of("kingbase", "kingbasees", "bgwriter", "checkpoint", "buffers", "write time", "performance_issue")),

            maintenanceTemplate("OCEANBASE_PROCESSLIST", "OceanBase current connections",
                "Show current OceanBase sessions and running statements.",
                "oceanbase", "connection",
                "SHOW PROCESSLIST",
                List.of("oceanbase", "connection", "session", "processlist", "connection_overflow")),
            maintenanceTemplate("OCEANBASE_SERVERS", "OceanBase server status",
                "Read OceanBase server status metadata.",
                "oceanbase", "instance",
                "SELECT * FROM oceanbase.DBA_OB_SERVERS",
                List.of("oceanbase", "server", "instance", "status", "health", "database status")),
            maintenanceTemplate("OCEANBASE_TENANTS", "OceanBase tenant status",
                "Read OceanBase tenant metadata for daily health inspection.",
                "oceanbase", "instance",
                "SELECT * FROM oceanbase.DBA_OB_TENANTS",
                List.of("oceanbase", "tenant", "status", "health", "resource", "database status")),
            maintenanceTemplate("OCEANBASE_SQL_AUDIT", "OceanBase SQL audit",
                "Read OceanBase SQL audit records for slow SQL and performance inspection.",
                "oceanbase", "performance",
                "SELECT * FROM oceanbase.GV$OB_SQL_AUDIT ORDER BY ELAPSED_TIME DESC",
                List.of("oceanbase", "sql audit", "slow query", "elapsed time", "performance", "performance_issue")),
            maintenanceTemplate("OCEANBASE_DATABASE_SIZE", "OceanBase database size",
                "Summarize OceanBase database size by schema in megabytes.",
                "oceanbase", "storage",
                "SELECT table_schema AS db, SUM(data_length + index_length)/1024/1024 AS size_mb FROM information_schema.tables GROUP BY table_schema",
                List.of("oceanbase", "database size", "storage", "schema size", "space", "storage_check")),
            maintenanceTemplate("OCEANBASE_SERVER_OVERVIEW", "OceanBase server overview",
                "Read OceanBase server metadata for cluster health inspection.",
                "oceanbase", "instance",
                """
                    SELECT SVR_IP,
                           SQL_PORT,
                           ZONE,
                           STATUS,
                           START_SERVICE_TIME,
                           BUILD_VERSION
                    FROM oceanbase.DBA_OB_SERVERS
                    ORDER BY ZONE, SVR_IP
                    """,
                List.of("oceanbase", "server overview", "zone", "server status", "cluster", "database status")),
            maintenanceTemplate("OCEANBASE_TENANT_OVERVIEW", "OceanBase tenant overview",
                "Read OceanBase tenant metadata for resource and status inspection.",
                "oceanbase", "instance",
                """
                    SELECT TENANT_ID,
                           TENANT_NAME,
                           TENANT_TYPE,
                           STATUS,
                           PRIMARY_ZONE,
                           LOCALITY,
                           CREATE_TIME
                    FROM oceanbase.DBA_OB_TENANTS
                    ORDER BY TENANT_ID
                    """,
                List.of("oceanbase", "tenant overview", "tenant status", "resource", "locality", "database status")),
            maintenanceTemplate("OCEANBASE_TOP_SQL_AUDIT", "OceanBase top SQL audit",
                "Read OceanBase SQL audit records ordered by elapsed time.",
                "oceanbase", "performance",
                """
                    SELECT SVR_IP,
                           TENANT_ID,
                           USER_NAME,
                           DB_NAME,
                           QUERY_SQL,
                           ELAPSED_TIME,
                           EXECUTE_TIME,
                           QUEUE_TIME,
                           RETURN_ROWS,
                           AFFECTED_ROWS
                    FROM oceanbase.GV$OB_SQL_AUDIT
                    ORDER BY ELAPSED_TIME DESC
                    LIMIT 20
                    """,
                List.of("oceanbase", "top sql", "sql audit", "elapsed time", "slow query", "performance_issue")),
            maintenanceTemplate("OCEANBASE_TOP_TABLES_SIZE", "OceanBase largest tables",
                "Rank OceanBase tables by total data and index size.",
                "oceanbase", "storage",
                """
                    SELECT table_schema,
                           table_name,
                           ROUND((data_length + index_length) / 1024 / 1024, 2) AS total_mb,
                           ROUND(data_length / 1024 / 1024, 2) AS data_mb,
                           ROUND(index_length / 1024 / 1024, 2) AS index_mb,
                           table_rows
                    FROM information_schema.tables
                    WHERE table_schema NOT IN ('information_schema', 'mysql', 'performance_schema', 'sys', 'oceanbase')
                    ORDER BY total_mb DESC
                    LIMIT 50
                    """,
                List.of("oceanbase", "largest table", "table size", "storage", "capacity", "storage_check"))
        );
    }

    private DefaultTemplate maintenanceTemplate(String code,
                                                String title,
                                                String description,
                                                String databaseType,
                                                String maintenanceCategory,
                                                String sql,
                                                List<String> intentSignals) {
        return new DefaultTemplate(
            code,
            title,
            description,
            sql,
            emptySchema(),
            "LOW",
            "maintenance_" + maintenanceCategory,
            databaseType,
            List.of(databaseType, "maintenance", maintenanceCategory),
            templateSignals(code, title, description, intentSignals)
        );
    }

    private Map<String, Object> emptySchema() {
        return Map.of("type", "object", "properties", Map.of(), "required", List.of());
    }

    private List<String> templateSignals(String code, String title, String description, List<String> extraSignals) {
        return java.util.stream.Stream.concat(
                java.util.stream.Stream.of(code, title, description),
                extraSignals == null ? java.util.stream.Stream.empty() : extraSignals.stream()
            )
            .filter(value -> value != null && !value.isBlank())
            .map(String::trim)
            .distinct()
            .toList();
    }

    private String writeJson(Object value) {
        try {
            return ModelProtocolJson.compact(value);
        } catch (Exception ex) {
            return "{}";
        }
    }

    private String truncateSql(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.replace("\r", "\\r").replace("\n", "\\n");
        if (normalized.length() <= LOG_SQL_LIMIT) {
            return normalized;
        }
        return normalized.substring(0, LOG_SQL_LIMIT) + "...<truncated>";
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

    private record DefaultTemplate(String code,
                                   String title,
                                   String description,
                                   String sql,
                                   Map<String, Object> schema,
                                   String riskLevel,
                                   String category,
                                   String databaseType,
                                   List<String> routingLabels,
                                   List<String> intentSignals) {
    }
}
