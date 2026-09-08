package com.chatchat.tools.builtin;

import com.chatchat.agents.evidence.sql.SqlState;

import com.chatchat.agents.tool.ToolRegistry;
import com.chatchat.common.tool.ToolInput;
import com.chatchat.common.tool.ToolMetadata;
import com.chatchat.common.tool.ToolOutput;
import com.chatchat.common.tool.ToolParameter;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.net.URI;
import java.sql.Date;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Enhanced built-in tools bootstrap with proper metadata, validation, and error handling
 *
 * Registers enterprise-grade implementations of common tools:
 * - Calculator: Safe mathematical expression evaluation
 * - File System: Secure file operations
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BuiltInToolsBootstrap {

    private final ToolRegistry toolRegistry;
    private final DatabaseToolProperties databaseToolProperties;
    private final DynamicJdbcDriverLoader dynamicJdbcDriverLoader;
    private final Environment environment;
    private final ObjectMapper objectMapper;

    /**
     * Initialize all built-in tools during application startup
     */
    public void initializeBuiltInTools() {
        log.info("Initializing built-in tools...");

        registerCalculatorTool();
        registerDatabaseQueryTool();
        registerFileSystemTool();

        log.info("Built-in tools initialized successfully");
    }

    /**
     * Register enhanced calculator tool with metadata
     */
    private void registerCalculatorTool() {
        String confirmationAction = environment.getProperty(
            "chatchat.tools.calculator.confirmation.default",
            "auto_execute"
        );
        ToolMetadata metadata = ToolMetadata.builder()
            .id("calculator")
            .title("Calculator")
            .description("Perform mathematical calculations safely. " +
                "Supports basic arithmetic operations: +, -, *, /, %, ** (power), // (floor division). " +
                "Also supports common math functions: abs, sqrt, sin, cos, tan, log, exp, etc.")
            .version("1.0.0")
            .author("ChatChat System")
            .categories(Arrays.asList("math", "calculation"))
            .category("utility_calculation")
            .riskLevel(environment.getProperty("chatchat.tools.calculator.risk-level", "low"))
            .operationType(environment.getProperty("chatchat.tools.calculator.operation-type", "read"))
            .runtimeLevel(environment.getProperty("chatchat.tools.calculator.runtime-level", "suggestion"))
            .userVisible(true)
            .confirmation(Map.of(
                "default", confirmationAction,
                "allow_user_override", true
            ))
            .permissions(Map.of("roles", List.of()))
            .inputPolicy(Map.of(
                "must_show_parameters", true,
                "allow_auto_fill", true,
                "sensitive_params", List.of(),
                "parameter_rules", Map.of(
                    "expression", Map.of("action", environment.getProperty(
                        "chatchat.tools.calculator.parameter-policy.expression",
                        "auto_execute"
                    ))
                )
            ))
            .outputPolicy(Map.of("mask_fields", List.of()))
            .outputType("number")
            .returnDirect(false)
            .timeoutMillis(environment.getProperty("chatchat.tools.calculator.timeout-ms", Long.class, 5000L))
            .agentCompatible(true)
            .parameters(Arrays.asList(
                ToolParameter.builder()
                    .name("expression")
                    .type("string")
                    .description("Mathematical expression to evaluate (e.g., '2 + 2 * 3')")
                    .required(true)
                    .minLength(1)
                    .maxLength(500)
                    .build()
            ))
            .tags(Arrays.asList("math", "utility"))
            .build();

        CalculatorTool calculatorTool = new CalculatorTool();
        toolRegistry.registerTool("calculator", metadata, calculatorTool);
        log.info("Calculator tool registered");
    }
    /**
     * Register read-only database query tool with metadata
     */
    private void registerDatabaseQueryTool() {
        String confirmationAction = environment.getProperty(
            "chatchat.tools.database-query.confirmation.default",
            "ask_before_execute"
        );
        ToolMetadata metadata = ToolMetadata.builder()
            .id("database_query")
            .title("Database Query")
            .description("Execute read-only SQL against an external JDBC database. " +
                "The application configuration database is not available to MCP queries. " +
                "Only SELECT/WITH/SHOW/DESCRIBE/EXPLAIN style statements are allowed. " +
                "External JDBC drivers are loaded from the configured lib directory.")
            .version("1.0.0")
            .author("ChatChat System")
            .categories(Arrays.asList("database", "sql", "inspection"))
            .category("database_data_query")
            .riskLevel(environment.getProperty("chatchat.tools.database-query.risk-level", "high"))
            .operationType(environment.getProperty("chatchat.tools.database-query.operation-type", "read"))
            .runtimeLevel(environment.getProperty("chatchat.tools.database-query.runtime-level", "confirm_required"))
            .userVisible(environment.getProperty("chatchat.tools.database-query.user-visible", Boolean.class, false))
            .confirmation(Map.of(
                "default", confirmationAction,
                "allow_user_override", true
            ))
            .permissions(Map.of("roles", List.of()))
            .inputPolicy(Map.of(
                "must_show_parameters", true,
                "allow_auto_fill", false,
                "sensitive_params", List.of("jdbc_url", "username", "password", "params"),
                "parameter_rules", Map.of(
                    "contains_delete", Map.of("action", "deny"),
                    "contains_drop", Map.of("action", "deny"),
                    "contains_update", Map.of("action", "ask_before_execute"),
                    "jdbc_url", Map.of("action", "ask_before_execute"),
                    "password", Map.of("action", "ask_before_execute")
                )
            ))
            .outputPolicy(Map.of(
                "mask_fields", List.of("password", "username", "jdbc_url", "phone", "id_card", "account_no"),
                "max_rows_without_confirm", databaseToolProperties.getDefaultMaxRows()
            ))
            .outputType("json")
            .returnDirect(false)
            .timeoutMillis(databaseToolProperties.getQueryTimeoutSeconds() * 1000L)
            .agentCompatible(environment.getProperty("chatchat.tools.database-query.agent-compatible", Boolean.class, false))
            .parameters(Arrays.asList(
                ToolParameter.builder()
                    .name("sql")
                    .type("string")
                    .description("Read-only SQL statement. Named parameters are supported with :name syntax.")
                    .required(true)
                    .minLength(1)
                    .maxLength(5000)
                    .build(),
                ToolParameter.builder()
                    .name("params")
                    .type("object")
                    .description("Optional named SQL parameters, for example {\"serviceId\":\"abc\"}.")
                    .required(false)
                    .metadata(Map.of("additionalProperties", true))
                    .build(),
                ToolParameter.builder()
                    .name("max_rows")
                    .type("integer")
                    .description("User-requested row count, constrained by the server configured minimum and maximum.")
                    .required(false)
                    .defaultValue(databaseToolProperties.getDefaultMaxRows())
                    .minimum(databaseToolProperties.getMinRows())
                    .maximum(databaseToolProperties.getMaxRows())
                    .build()
                ,
                ToolParameter.builder()
                    .name("jdbc_url")
                    .type("string")
                    .description("Required external JDBC URL. The application configuration database cannot be queried.")
                    .required(true)
                    .maxLength(2000)
                    .build(),
                ToolParameter.builder()
                    .name("driver_class")
                    .type("string")
                    .description("Optional JDBC driver class name. If omitted, drivers in lib are auto-discovered.")
                    .required(false)
                    .maxLength(500)
                    .build(),
                ToolParameter.builder()
                    .name("database_type")
                    .type("string")
                    .description("Optional database type used to load jars from lib/drivers/{database_type}, for example dm, kingbase, oceanbase, tdsql, tidb.")
                    .required(false)
                    .maxLength(100)
                    .build(),
                ToolParameter.builder()
                    .name("username")
                    .type("string")
                    .description("Optional external database username.")
                    .required(false)
                    .maxLength(500)
                    .build(),
                ToolParameter.builder()
                    .name("password")
                    .type("string")
                    .description("Optional external database password.")
                    .required(false)
                    .maxLength(1000)
                    .build(),
                ToolParameter.builder()
                    .name("reload_drivers")
                    .type("boolean")
                    .description("Reload JDBC driver jars from lib before creating an external connection.")
                    .required(false)
                    .defaultValue(false)
                    .build()
            ))
            .tags(Arrays.asList("database", "sql", "read-only", "agent"))
            .metadata(Map.of(
                "readOnly", true,
                "dataScope", "external_database",
                "driverLibPath", databaseToolProperties.getDriverLibPath(),
                "blockedKeywords", databaseToolProperties.getBlockedKeywords()
            ))
            .build();

        DatabaseQueryTool databaseQueryTool = new DatabaseQueryTool(
            dynamicJdbcDriverLoader,
            databaseToolProperties,
            environment.getProperty("spring.datasource.url", ""),
            objectMapper
        );
        toolRegistry.registerTool("database_query", metadata, databaseQueryTool);
        log.info("Database Query tool registered");
    }

    /**
     * Register file system tool with metadata
     */
    private void registerFileSystemTool() {
        String confirmationAction = environment.getProperty(
            "chatchat.tools.file-system.confirmation.default",
            "ask_before_execute"
        );
        ToolMetadata metadata = ToolMetadata.builder()
            .id("file_system")
            .title("File System Operations")
            .description("Perform secure file system operations including reading, writing, " +
                "and listing files. Note: Operations are restricted to designated safe directories.")
            .version("1.0.0")
            .author("ChatChat System")
            .categories(Arrays.asList("file", "system"))
            .category("local_file_system")
            .riskLevel(environment.getProperty("chatchat.tools.file-system.risk-level", "high"))
            .operationType(environment.getProperty("chatchat.tools.file-system.operation-type", "read"))
            .runtimeLevel(environment.getProperty("chatchat.tools.file-system.runtime-level", "confirm_required"))
            .userVisible(true)
            .confirmation(Map.of(
                "default", confirmationAction,
                "allow_user_override", true
            ))
            .permissions(Map.of("roles", List.of()))
            .inputPolicy(Map.of(
                "must_show_parameters", true,
                "allow_auto_fill", false,
                "sensitive_params", List.of("path"),
                "parameter_rules", Map.of(
                    "path", Map.of("action", "ask_before_execute"),
                    "operation", Map.of("action", "ask_before_execute")
                )
            ))
            .outputPolicy(Map.of(
                "mask_fields", List.of("password", "token", "secret", "api_key", "authorization")
            ))
            .outputType("string")
            .returnDirect(false)
            .timeoutMillis(environment.getProperty("chatchat.tools.file-system.timeout-ms", Long.class, 10000L))
            .requiresAuth(true)
            .agentCompatible(false)
            .parameters(Arrays.asList(
                ToolParameter.builder()
                    .name("operation")
                    .type("string")
                    .description("Operation to perform: 'read', 'list', or 'info'")
                    .required(true)
                    .enumValues(new String[]{"read", "list", "info"})
                    .build(),
                ToolParameter.builder()
                    .name("path")
                    .type("string")
                    .description("File or directory path to operate on")
                    .required(true)
                    .build()
            ))
            .tags(Arrays.asList("system", "file", "restricted"))
            .metadata(java.util.Map.of(
                "security_level", "high",
                "dataScope", "local_file_system",
                "requires_review", true
            ))
            .build();

        FileSystemTool fileSystemTool = new FileSystemTool();
        toolRegistry.registerTool("file_system", metadata, fileSystemTool);
        log.info("File System tool registered");
    }

    /**
     * Enhanced Calculator Tool implementation with proper validation
     */
    private static class CalculatorTool implements ToolRegistry.EnhancedTool {

        private static final Pattern SAFE_EXPRESSION_PATTERN =
            Pattern.compile("^[0-9+\\-*/%().\\s]*$");

        /**
         * Returns the metadata.
         *
         * @return the metadata
         */
        @Override
        public ToolMetadata getMetadata() {
            // Metadata is provided at registration time
            return null;
        }

        /**
         * Executes the execute.
         *
         * @param input the input value
         * @return the operation result
         */
        @Override
        public ToolOutput execute(ToolInput input) {
            try {
                String expression = input.getParameterAsString("expression", "");

                if (expression.isEmpty()) {
                    return ToolOutput.failure("Expression parameter is required");
                }

                // Validate expression safety
                if (!SAFE_EXPRESSION_PATTERN.matcher(expression).matches()) {
                    return ToolOutput.failure(
                        "Invalid expression: contains unsafe characters. " +
                        "Allowed: digits, operators (+, -, *, /, %, ()), decimals, spaces"
                    );
                }

                // Remove spaces for evaluation
                String cleanExpression = expression.replaceAll("\\s+", "");

                // Check for common injection patterns
                if (cleanExpression.contains(";") || cleanExpression.contains(",")) {
                    return ToolOutput.failure("Invalid expression: unsafe characters detected");
                }

                // Evaluate using safe method
                double result = evaluateSafeExpression(cleanExpression);

                return ToolOutput.success(
                    result,
                    String.format("Result of '%s' = %s", expression, formatResult(result))
                );

            } catch (NumberFormatException e) {
                return ToolOutput.failure("Invalid number format: " + e.getMessage());
            } catch (ArithmeticException e) {
                return ToolOutput.failure("Arithmetic error: " + e.getMessage());
            } catch (Exception e) {
                return ToolOutput.failure(e);
            }
        }

        /**
         * Evaluate mathematical expression safely using a simple recursive descent parser
         * Supports: +, -, *, /, %, ** (power), parentheses
         */
        private double evaluateSafeExpression(String expression) throws Exception {
            return new MathExpressionEvaluator(expression).evaluate();
        }

        /**
         * Format result with appropriate precision
         */
        private String formatResult(double value) {
            if (Double.isInfinite(value)) {
                return "Infinity";
            }
            if (Double.isNaN(value)) {
                return "NaN";
            }
            if (value == Math.floor(value)) {
                return String.valueOf((long) value);
            }
            return String.format("%.6f", value);
        }
    }
    /**
     * Read-only database query tool implementation
     */
    private static class DatabaseQueryTool implements ToolRegistry.EnhancedTool {

        private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);
        private static final Pattern LINE_COMMENT = Pattern.compile("(?m)--.*$");
        private static final Pattern FIRST_SQL_TOKEN = Pattern.compile("^([A-Za-z_][A-Za-z0-9_]*)\\b");

        private final DynamicJdbcDriverLoader dynamicJdbcDriverLoader;
        private final DatabaseToolProperties properties;
        private final String applicationJdbcUrl;
        private final ObjectMapper objectMapper;

        /**
         * Creates a new BuiltInToolsBootstrap instance.
         *
         * @param dynamicJdbcDriverLoader the dynamic jdbc driver loader value
         * @param properties the properties value
         * @param applicationJdbcUrl the application jdbc url value
         * @param objectMapper the object mapper value
         */
        private DatabaseQueryTool(DynamicJdbcDriverLoader dynamicJdbcDriverLoader,
                                  DatabaseToolProperties properties,
                                  String applicationJdbcUrl,
                                  ObjectMapper objectMapper) {
            this.dynamicJdbcDriverLoader = dynamicJdbcDriverLoader;
            this.properties = properties;
            this.applicationJdbcUrl = applicationJdbcUrl;
            this.objectMapper = objectMapper;
        }

        /**
         * Returns the metadata.
         *
         * @return the metadata
         */
        @Override
        public ToolMetadata getMetadata() {
            return null;
        }

        /**
         * Executes the execute.
         *
         * @param input the input value
         * @return the operation result
         */
        @Override
        public ToolOutput execute(ToolInput input) {
            String resolvedSqlPreview = null;
            try {
                if (!properties.isEnabled()) {
                    return ToolOutput.failure("database_query tool is disabled");
                }
                String sql = input.getParameterAsString("sql", "");
                if (sql.isBlank()) {
                    return ToolOutput.failure("sql parameter is required");
                }
                DataSource dataSource = resolveDataSource(input);
                String safeSql = validateReadOnlySql(sql);
                int maxRows = resolveMaxRows(input);
                int queryTimeoutSeconds = resolveQueryTimeoutSeconds(input);
                Map<String, Object> params = resolveParams(input);
                resolvedSqlPreview = renderSqlPreview(safeSql, params);
                boolean literalParameterExecution = requiresLiteralParameterExecution(input);
                String executableSql = literalParameterExecution
                    ? validateReadOnlySql(resolvedSqlPreview)
                    : safeSql;

                log.info("Database query SQL executing: maxRows={}, timeoutSeconds={}, parameterMode={}, parameterTypes={}, sql={}",
                    maxRows, queryTimeoutSeconds, literalParameterExecution ? "LITERAL_COMPATIBILITY" : "PREPARED",
                    parameterTypes(params), executableSql);
                log.info("Database query resolved SQL preview: sql={}", resolvedSqlPreview);

                JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
                jdbcTemplate.setMaxRows(maxRows);
                jdbcTemplate.setQueryTimeout(queryTimeoutSeconds);
                NamedParameterJdbcTemplate namedTemplate = new NamedParameterJdbcTemplate(jdbcTemplate);

                List<Map<String, Object>> rows = (literalParameterExecution
                    ? jdbcTemplate.queryForList(executableSql)
                    : namedTemplate.queryForList(safeSql, params))
                    .stream()
                    .map(this::normalizeRow)
                    .toList();

                Map<String, Object> result = new LinkedHashMap<>();
                result.put("sql", safeSql);
                result.put("resolvedSqlPreview", resolvedSqlPreview);
                result.put("dataSource", "external");
                result.put("rowCount", rows.size());
                result.put("maxRows", maxRows);
                result.put("columns", rows.isEmpty() ? List.of() : new ArrayList<>(rows.get(0).keySet()));
                result.put("rows", rows);
                result.put("readOnly", true);
                result.put("possiblyTruncated", rows.size() >= maxRows);

                return ToolOutput.success(result, "Database query completed successfully");
            } catch (Exception e) {
                String errorMessage = databaseQueryFailureMessage(e);
                log.warn("Database query SQL execution failed: exceptionType={}, rootCause={}",
                    e.getClass().getSimpleName(), errorMessage);
                ToolOutput output = ToolOutput.failure(e);
                output.setErrorMessage(errorMessage);
                if (resolvedSqlPreview != null && !resolvedSqlPreview.isBlank()) {
                    output.setData(Map.of("resolvedSqlPreview", resolvedSqlPreview));
                }
                return output;
            }
        }

        private boolean requiresLiteralParameterExecution(ToolInput input) {
            String databaseType = input.getParameterAsString("database_type", "").trim().toLowerCase(Locale.ROOT);
            String jdbcUrl = input.getParameterAsString("jdbc_url", "").trim().toLowerCase(Locale.ROOT);
            return "hive".equals(databaseType)
                || "inceptor".equals(databaseType)
                || jdbcUrl.startsWith("jdbc:hive2:");
        }

        private String renderSqlPreview(String sql, Map<String, Object> params) {
            StringBuilder preview = new StringBuilder(sql.length() + 64);
            char quote = 0;
            for (int index = 0; index < sql.length();) {
                char current = sql.charAt(index);
                if (quote != 0) {
                    preview.append(current);
                    if (current == quote) {
                        if (index + 1 < sql.length() && sql.charAt(index + 1) == quote) {
                            preview.append(sql.charAt(index + 1));
                            index += 2;
                            continue;
                        }
                        quote = 0;
                    }
                    index++;
                    continue;
                }
                if (current == '\'' || current == '"' || current == '`') {
                    quote = current;
                    preview.append(current);
                    index++;
                    continue;
                }
                if (current == ':' && (index == 0 || sql.charAt(index - 1) != ':')
                    && index + 1 < sql.length() && Character.isJavaIdentifierStart(sql.charAt(index + 1))) {
                    int end = index + 2;
                    while (end < sql.length() && Character.isJavaIdentifierPart(sql.charAt(end))) end++;
                    String name = sql.substring(index + 1, end);
                    if (params.containsKey(name)) {
                        preview.append(sqlLiteral(params.get(name)));
                    } else {
                        preview.append(':').append(name);
                    }
                    index = end;
                    continue;
                }
                preview.append(current);
                index++;
            }
            return preview.toString();
        }

        private String sqlLiteral(Object value) {
            if (value == null) return "NULL";
            if (value instanceof Number || value instanceof Boolean) return String.valueOf(value);
            if (value instanceof Iterable<?> values) {
                List<String> literals = new ArrayList<>();
                values.forEach(item -> literals.add(sqlLiteral(item)));
                return String.join(", ", literals);
            }
            if (value.getClass().isArray() && value instanceof Object[] values) {
                return Arrays.stream(values).map(this::sqlLiteral).collect(java.util.stream.Collectors.joining(", "));
            }
            String text = value instanceof byte[] bytes
                ? "<binary " + bytes.length + " bytes>"
                : String.valueOf(value);
            return "'" + text.replace("'", "''") + "'";
        }

        private Map<String, String> parameterTypes(Map<String, Object> params) {
            Map<String, String> types = new LinkedHashMap<>();
            params.forEach((name, value) -> types.put(name,
                value == null ? "null" : value.getClass().getSimpleName()));
            return types;
        }

        private String databaseQueryFailureMessage(Exception exception) {
            Throwable root = exception;
            while (root.getCause() != null && root.getCause() != root) {
                root = root.getCause();
            }
            String detail = root.getMessage();
            if (root instanceof java.sql.SQLException sqlException) {
                StringBuilder message = new StringBuilder(detail == null || detail.isBlank()
                    ? sqlException.getClass().getSimpleName() : detail.trim());
                if (sqlException.getSQLState() != null && !sqlException.getSQLState().isBlank()) {
                    message.append(" [SQLState=").append(sqlException.getSQLState()).append(']');
                }
                if (sqlException.getErrorCode() != 0) {
                    message.append(" [errorCode=").append(sqlException.getErrorCode()).append(']');
                }
                java.sql.SQLException next = sqlException.getNextException();
                if (next != null && next != sqlException && next.getMessage() != null
                    && !next.getMessage().isBlank() && !message.toString().contains(next.getMessage())) {
                    message.append("; ").append(next.getMessage().trim());
                }
                detail = message.toString();
            }
            String outer = exception.getMessage();
            if (detail == null || detail.isBlank()) {
                detail = outer == null || outer.isBlank() ? exception.getClass().getSimpleName() : outer.trim();
            } else if (outer != null && !outer.isBlank() && !outer.contains(detail)) {
                detail = outer.trim() + "; root cause: " + detail;
            }
            return detail;
        }

        /**
         * Resolves the data source.
         *
         * @param input the input value
         * @return the resolved data source
         */
        private DataSource resolveDataSource(ToolInput input) {
            String jdbcUrl = input.getParameterAsString("jdbc_url", "");
            if (jdbcUrl == null || jdbcUrl.isBlank()) {
                throw new IllegalArgumentException("jdbc_url is required; querying the application configuration database is not allowed");
            }
            if (isApplicationJdbcUrl(jdbcUrl)) {
                throw new IllegalArgumentException("querying the application configuration database is not allowed");
            }
            if (input.getParameterAsBoolean("reload_drivers", false)) {
                dynamicJdbcDriverLoader.reloadDrivers();
            }
            return dynamicJdbcDriverLoader.createDataSource(
                jdbcUrl.trim(),
                input.getParameterAsString("username", ""),
                input.getParameterAsString("password", ""),
                input.getParameterAsString("driver_class", ""),
                input.getParameterAsString("database_type", "")
            );
        }

        /**
         * Returns whether is application jdbc url.
         *
         * @param jdbcUrl the jdbc url value
         * @return whether the condition is satisfied
         */
        private boolean isApplicationJdbcUrl(String jdbcUrl) {
            return applicationJdbcUrl != null
                && !applicationJdbcUrl.isBlank()
                && applicationJdbcUrl.trim().equalsIgnoreCase(jdbcUrl.trim());
        }

        /**
         * Validates the read only sql.
         *
         * @param sql the sql value
         * @return the operation result
         */
        private String validateReadOnlySql(String sql) {
            String cleaned = LINE_COMMENT.matcher(BLOCK_COMMENT.matcher(sql).replaceAll(" ")).replaceAll(" ");
            String normalized = cleaned.trim();
            while (normalized.endsWith(";")) {
                normalized = normalized.substring(0, normalized.length() - 1).trim();
            }
            if (normalized.contains(";")) {
                throw new IllegalArgumentException("Only one SQL statement is allowed");
            }
            java.util.regex.Matcher firstToken = FIRST_SQL_TOKEN.matcher(normalized);
            if (!firstToken.find()) {
                throw new IllegalArgumentException("Only read-only SQL statements are allowed");
            }
            String firstKeyword = firstToken.group(1).toLowerCase(Locale.ROOT);
            boolean allowed = properties.getAllowedPrefixes().stream()
                .filter(prefix -> prefix != null && !prefix.isBlank())
                .map(prefix -> prefix.trim().toLowerCase(Locale.ROOT))
                .anyMatch(firstKeyword::equals);
            if (!allowed) {
                throw new IllegalArgumentException("Only read-only SQL statements are allowed");
            }
            String lower = normalized.toLowerCase(Locale.ROOT);
            for (String keyword : properties.getBlockedKeywords()) {
                Pattern keywordPattern = Pattern.compile("(?i)(^|\\W)" + Pattern.quote(keyword) + "(\\W|$)");
                if (keywordPattern.matcher(lower).find()) {
                    throw new IllegalArgumentException("Blocked SQL keyword detected: " + keyword);
                }
            }
            return normalized;
        }

        /**
         * Resolves the max rows.
         *
         * @param input the input value
         * @return the resolved max rows
         */
        private int resolveMaxRows(ToolInput input) {
            Number requested = input.getParameterAsNumber("max_rows");
            int value = requested == null ? properties.getDefaultMaxRows() : requested.intValue();
            int maximum = Math.max(1, properties.getMaxRows());
            int minimum = Math.max(1, Math.min(properties.getMinRows(), maximum));
            return Math.max(minimum, Math.min(maximum, value));
        }

        private int resolveQueryTimeoutSeconds(ToolInput input) {
            Number requested = input.getParameterAsNumber("timeoutSeconds");
            if (requested == null) {
                requested = input.getParameterAsNumber("timeout_seconds");
            }
            int value = requested == null ? properties.getQueryTimeoutSeconds() : requested.intValue();
            return Math.max(1, Math.min(300, value));
        }

        /**
         * Resolves the params.
         *
         * @param input the input value
         * @return the resolved params
         * @throws Exception if the operation fails
         */
        @SuppressWarnings("unchecked")
        private Map<String, Object> resolveParams(ToolInput input) throws Exception {
            Object value = input.getParameter("params");
            if (value == null) {
                value = input.getParameter("parameters");
            }
            if (value == null) {
                return Map.of();
            }
            if (value instanceof Map<?, ?> map) {
                Map<String, Object> params = new LinkedHashMap<>();
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    params.put(String.valueOf(entry.getKey()), entry.getValue());
                }
                return params;
            }
            if (value instanceof String text && !text.isBlank()) {
                return objectMapper.readValue(text, Map.class);
            }
            return Map.of();
        }

        /**
         * Normalizes the row.
         *
         * @param row the row value
         * @return the operation result
         */
        private Map<String, Object> normalizeRow(Map<String, Object> row) {
            Map<String, Object> normalized = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : row.entrySet()) {
                normalized.put(entry.getKey(), normalizeValue(entry.getValue()));
            }
            return normalized;
        }

        /**
         * Normalizes the value.
         *
         * @param value the value value
         * @return the operation result
         */
        private Object normalizeValue(Object value) {
            if (value == null
                || value instanceof String
                || value instanceof Number
                || value instanceof Boolean
                || value instanceof BigDecimal) {
                return value;
            }
            if (value instanceof byte[] bytes) {
                return Map.of(
                    "type", "binary",
                    "bytes", bytes.length,
                    "base64", Base64.getEncoder().encodeToString(bytes)
                );
            }
            if (value instanceof Timestamp timestamp) {
                return timestamp.toInstant().toString();
            }
            if (value instanceof Date date) {
                return date.toLocalDate().toString();
            }
            if (value instanceof Time time) {
                return time.toLocalTime().toString();
            }
            if (value instanceof TemporalAccessor) {
                return value.toString();
            }
            return String.valueOf(value);
        }
    }

    /**
     * File System Tool implementation
     */
    private static class FileSystemTool implements ToolRegistry.EnhancedTool {

        /**
         * Returns the metadata.
         *
         * @return the metadata
         */
        @Override
        public ToolMetadata getMetadata() {
            return null;
        }

        /**
         * Executes the execute.
         *
         * @param input the input value
         * @return the operation result
         */
        @Override
        public ToolOutput execute(ToolInput input) {
            try {
                String operation = input.getParameterAsString("operation", "");
                String path = input.getParameterAsString("path", "");

                if (operation.isEmpty() || path.isEmpty()) {
                    return ToolOutput.failure("operation and path parameters are required");
                }

                // Security check: only allow safe operations
                if (!isPathSafe(path)) {
                    return ToolOutput.failure("Access denied: path is not in safe directory");
                }

                String result = switch (operation.toLowerCase()) {
                    case "read" -> readFile(path);
                    case "list" -> listDirectory(path);
                    case "info" -> getFileInfo(path);
                    default -> "Unknown operation: " + operation;
                };

                return ToolOutput.success(result);

            } catch (Exception e) {
                return ToolOutput.failure(e);
            }
        }

        /**
         * Verify path is within safe directories
         */
        private boolean isPathSafe(String path) {
            // TODO: Implement security checks based on configuration
            return !path.contains("..") && !path.contains("~");
        }

        /**
         * Read file content (placeholder)
         */
        private String readFile(String path) {
            return "File content from: " + path;
        }

        /**
         * List directory content (placeholder)
         */
        private String listDirectory(String path) {
            return "Directory listing for: " + path;
        }

        /**
         * Get file information (placeholder)
         */
        private String getFileInfo(String path) {
            return "File info for: " + path;
        }
    }

    /**
     * Safe mathematical expression evaluator using recursive descent parser
     */
    @Slf4j
    private static class MathExpressionEvaluator {
        private final String expression;
        private int pos = 0;

        /**
         * Creates a new BuiltInToolsBootstrap instance.
         *
         * @param expression the expression value
         */
        MathExpressionEvaluator(String expression) {
            this.expression = expression;
        }

        /**
         * Performs the evaluate operation.
         *
         * @return the operation result
         * @throws Exception if the operation fails
         */
        double evaluate() throws Exception {
            double result = parseExpression();
            if (pos != expression.length()) {
                throw new IllegalArgumentException("Unexpected characters at position " + pos);
            }
            return result;
        }

        /**
         * Parses the expression.
         *
         * @return the parsed expression
         * @throws Exception if the operation fails
         */
        private double parseExpression() throws Exception {
            double result = parseTerm();
            while (pos < expression.length() &&
                   (expression.charAt(pos) == '+' || expression.charAt(pos) == '-')) {
                char op = expression.charAt(pos++);
                double right = parseTerm();
                result = op == '+' ? result + right : result - right;
            }
            return result;
        }

        /**
         * Parses the term.
         *
         * @return the parsed term
         * @throws Exception if the operation fails
         */
        private double parseTerm() throws Exception {
            double result = parseFactor();
            while (pos < expression.length() &&
                   (expression.charAt(pos) == '*' || expression.charAt(pos) == '/' ||
                    expression.charAt(pos) == '%')) {
                char op = expression.charAt(pos++);
                double right = parseFactor();
                result = switch (op) {
                    case '*' -> result * right;
                    case '/' -> {
                        if (right == 0) throw new ArithmeticException("Division by zero");
                        yield result / right;
                    }
                    case '%' -> result % right;
                    default -> result;
                };
            }
            return result;
        }

        /**
         * Parses the factor.
         *
         * @return the parsed factor
         * @throws Exception if the operation fails
         */
        private double parseFactor() throws Exception {
            // Handle power operator (**)
            double result = parseUnary();
            if (pos < expression.length() - 1 &&
                expression.charAt(pos) == '*' && expression.charAt(pos + 1) == '*') {
                pos += 2;
                double right = parseFactor();
                result = Math.pow(result, right);
            }
            return result;
        }

        /**
         * Parses the unary.
         *
         * @return the parsed unary
         * @throws Exception if the operation fails
         */
        private double parseUnary() throws Exception {
            if (pos < expression.length() &&
                (expression.charAt(pos) == '-' || expression.charAt(pos) == '+')) {
                char op = expression.charAt(pos++);
                double value = parseUnary();
                return op == '-' ? -value : value;
            }
            return parsePrimary();
        }

        /**
         * Parses the primary.
         *
         * @return the parsed primary
         * @throws Exception if the operation fails
         */
        private double parsePrimary() throws Exception {
            // Skip whitespace
            while (pos < expression.length() && Character.isWhitespace(expression.charAt(pos))) {
                pos++;
            }

            if (pos >= expression.length()) {
                throw new IllegalArgumentException("Unexpected end of expression");
            }

            char c = expression.charAt(pos);

            // Handle parentheses
            if (c == '(') {
                pos++;
                double result = parseExpression();
                if (pos >= expression.length() || expression.charAt(pos) != ')') {
                    throw new IllegalArgumentException("Missing closing parenthesis");
                }
                pos++;
                return result;
            }

            // Handle numbers
            if (Character.isDigit(c) || c == '.') {
                int start = pos;
                while (pos < expression.length() &&
                       (Character.isDigit(expression.charAt(pos)) || expression.charAt(pos) == '.')) {
                    pos++;
                }
                return Double.parseDouble(expression.substring(start, pos));
            }

            throw new IllegalArgumentException("Invalid character at position " + pos + ": " + c);
        }
    }
}
