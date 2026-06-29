package com.chatchat.mcpserver.sql;

import com.chatchat.agents.protocol.ModelProtocolJson;
import com.chatchat.mcpserver.template.TemplateParameterValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
@Slf4j
public class SqlTemplateService {

    private static final int LOG_SQL_LIMIT = 4000;
    private static final Pattern TOKEN = Pattern.compile("\\{\\{\\s*([A-Za-z0-9_.-]+)\\s*}}");
    private static final List<String> RETIRED_DEFAULT_CODES = List.of(
        "CHECK_TABLE_COUNT",
        "CHECK_RECENT_DATA",
        "TASK_RESULT",
        "CHECK_TASK_RESULT",
        "TASK_RESULT_QUERY",
        "QUERY_TASK_RESULT"
    );

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
        log.info("MCP SQL template render requested: templateId={}, title={}, category={}, riskLevel={}, databaseType={}, datasourceId={}, datasourceName={}, env={}, parameters={}, collectedParameters={}, sqlTemplate={}",
            config.getCode(), config.getTitle(), config.getCategory(), config.getRiskLevel(), config.getDatabaseType(),
            datasource == null ? null : datasource.getId(), datasource == null ? null : datasource.getName(),
            datasource == null ? null : datasource.getEnvironment(), parameters, validatedParameters,
            truncateSql(config.getSqlTemplate()));
        Matcher matcher = TOKEN.matcher(config.getSqlTemplate());
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
        removeRetiredDefaults();
        for (DefaultTemplate template : defaults()) {
            var existing = repository.findByCode(template.code());
            if (existing.isEmpty()) {
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
            } else if (shouldRefreshDefault(existing.get(), template)) {
                SqlTemplateConfig config = existing.get();
                config.setSqlTemplate(template.sql());
                config.setParameterSchemaJson(writeJson(template.schema()));
                config.setIntentSignalsJson(writeJson(template.intentSignals()));
                repository.save(config);
                log.info("Refreshed managed SQL default template: {}", template.code());
            }
        }
    }

    private boolean shouldRefreshDefault(SqlTemplateConfig existing, DefaultTemplate template) {
        if (existing == null || template == null || existing.getCode() == null) {
            return false;
        }
        String code = existing.getCode().trim().toUpperCase(Locale.ROOT);
        String sql = existing.getSqlTemplate() == null ? "" : existing.getSqlTemplate().toLowerCase(Locale.ROOT);
        return switch (code) {
            case "MYSQL_TABLE_METADATA" -> sql.contains("table_schema = database()");
            case "POSTGRES_TABLE_METADATA" -> sql.contains("table_schema = current_schema()");
            case "SQLSERVER_TABLE_METADATA" -> !sql.contains("table_schema");
            default -> false;
        };
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
                if (allowed.contains("MYSQL_TABLE_METADATA")) {
                    LinkedHashSet<String> expanded = new LinkedHashSet<>(allowed);
                    expanded.add("MYSQL_SCHEMA_TABLE_OVERVIEW");
                    expanded.add("MYSQL_TABLE_LOCATION");
                    return expanded.stream().toList();
                }
                return allowed;
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
            maintenanceTemplate("MYSQL_SCHEMA_TABLE_OVERVIEW", "MySQL schema table overview",
                "List user schemas/databases and table counts to understand where tables may live.",
                "mysql", "metadata",
                "SELECT table_schema AS schema_name, COUNT(*) AS table_count FROM information_schema.tables WHERE table_schema NOT IN ('information_schema', 'mysql', 'performance_schema', 'sys') GROUP BY table_schema ORDER BY table_schema",
                List.of("schema overview", "database list", "table count", "tables by database", "metadata discovery")),
            metadataTemplate("MYSQL_TABLE_LOCATION", "MySQL table location",
                "Locate which schema/database contains a table before reading column metadata.",
                "mysql",
                "SELECT table_schema AS schema_name, table_name, table_type, table_rows FROM information_schema.tables WHERE table_schema NOT IN ('information_schema', 'mysql', 'performance_schema', 'sys') AND LOWER(table_name) = LOWER({{tableName}}) ORDER BY table_schema, table_name",
                List.of("table location", "find table schema", "which database has table", "metadata discovery", "table resolve")),
            metadataTemplate("MYSQL_TABLE_METADATA", "MySQL table metadata",
                "Read MySQL column metadata for a table in an explicit schema/database.",
                "mysql",
                "SELECT column_name, column_type, is_nullable, column_default, column_key, extra, column_comment FROM information_schema.columns WHERE table_schema = COALESCE(NULLIF({{schemaName}}, ''), DATABASE()) AND table_name = {{tableName}} ORDER BY ordinal_position",
                List.of("table metadata", "column metadata", "table schema", "describe table", "metadata_query")),

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
            metadataTemplate("ORACLE_TABLE_METADATA", "Oracle table metadata",
                "Read Oracle column metadata for a table accessible to the current user.",
                "oracle",
                "SELECT column_name, data_type, data_length, data_precision, data_scale, nullable, data_default FROM user_tab_columns WHERE table_name = UPPER({{tableName}}) ORDER BY column_id",
                List.of("table metadata", "column metadata", "table schema", "describe table", "metadata_query")),

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
            metadataTemplate("POSTGRES_TABLE_METADATA", "PostgreSQL table metadata",
                "Read PostgreSQL column metadata for a table in an explicit schema.",
                "postgresql",
                "SELECT column_name, data_type, is_nullable, column_default, character_maximum_length, numeric_precision, numeric_scale FROM information_schema.columns WHERE table_schema = COALESCE(NULLIF({{schemaName}}, ''), current_schema()) AND table_name = {{tableName}} ORDER BY ordinal_position",
                List.of("table metadata", "column metadata", "table schema", "describe table", "metadata_query")),

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
            metadataTemplate("SQLSERVER_TABLE_METADATA", "SQL Server table metadata",
                "Read SQL Server column metadata for a table in an explicit schema.",
                "sqlserver",
                "SELECT column_name, data_type, is_nullable, column_default, character_maximum_length, numeric_precision, numeric_scale FROM information_schema.columns WHERE table_schema = COALESCE(NULLIF({{schemaName}}, ''), SCHEMA_NAME()) AND table_name = {{tableName}} ORDER BY ordinal_position",
                List.of("table metadata", "column metadata", "table schema", "describe table", "metadata_query"))
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

    private DefaultTemplate metadataTemplate(String code,
                                             String title,
                                             String description,
                                             String databaseType,
                                             String sql,
                                             List<String> intentSignals) {
        return new DefaultTemplate(
            code,
            title,
            description,
            sql,
            tableNameSchema(),
            "LOW",
            "maintenance_metadata",
            databaseType,
            List.of(databaseType, "maintenance", "metadata"),
            templateSignals(code, title, description, intentSignals)
        );
    }

    private Map<String, Object> tableNameSchema() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "tableName", Map.of(
                    "type", "string",
                    "description", "Table name to inspect in the current schema/database",
                    "minLength", 1,
                    "maxLength", 128,
                    "pattern", "[A-Za-z_][A-Za-z0-9_]*"
                ),
                "schemaName", Map.of(
                    "type", "string",
                    "description", "Schema/database name to inspect. The SQL executor fills this from execution context or JDBC URL when omitted.",
                    "maxLength", 128,
                    "pattern", "[A-Za-z_][A-Za-z0-9_]*"
                )
            ),
            "required", List.of("tableName")
        );
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
