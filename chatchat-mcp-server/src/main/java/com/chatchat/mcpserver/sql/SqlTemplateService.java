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
            maintenanceTemplate("ORACLE_TABLESPACE_SIZE", "Oracle tablespace size",
                "Summarize Oracle tablespace size in megabytes.",
                "oracle", "storage",
                "SELECT tablespace_name, SUM(bytes)/1024/1024 AS size_mb FROM dba_data_files GROUP BY tablespace_name",
                List.of("tablespace", "storage", "space", "database size", "storage_check")),

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

            maintenanceTemplate("SQLSERVER_SESSIONS", "SQL Server current sessions",
                "Read SQL Server session metadata.",
                "sqlserver", "connection",
                "SELECT * FROM sys.dm_exec_sessions",
                List.of("session", "connection", "dm_exec_sessions", "connection_overflow")),
            maintenanceTemplate("SQLSERVER_REQUESTS", "SQL Server current requests",
                "Read SQL Server active requests for blocking and performance analysis.",
                "sqlserver", "performance",
                "SELECT * FROM sys.dm_exec_requests",
                List.of("request", "blocking", "performance", "dm_exec_requests", "performance_issue")),
            maintenanceTemplate("SQLSERVER_DATABASE_SIZE", "SQL Server database size",
                "Summarize SQL Server database file size in megabytes.",
                "sqlserver", "storage",
                "SELECT DB_NAME(database_id) AS database_name, SUM(size) * 8.0 / 1024 AS size_mb FROM sys.master_files GROUP BY database_id",
                List.of("database size", "storage", "space", "master_files", "storage_check")),
            maintenanceTemplate("SQLSERVER_LOCKS", "SQL Server locks",
                "Read SQL Server lock metadata.",
                "sqlserver", "lock",
                "SELECT * FROM sys.dm_tran_locks",
                List.of("lock", "blocking", "dm_tran_locks", "lock_check")),
            maintenanceTemplate("SQLSERVER_IO_STATS", "SQL Server IO virtual file stats",
                "Read SQL Server virtual file IO statistics.",
                "sqlserver", "performance",
                "SELECT * FROM sys.dm_io_virtual_file_stats(NULL, NULL)",
                List.of("io", "performance", "file stats", "dm_io_virtual_file_stats", "performance_issue")),

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
                List.of("oceanbase", "database size", "storage", "schema size", "space", "storage_check"))
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
