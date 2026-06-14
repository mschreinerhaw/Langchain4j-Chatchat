package com.chatchat.tools.builtin;

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
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Date;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.Duration;
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
 * - Web Search: Internet search capability
 * - File System: Secure file operations
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BuiltInToolsBootstrap {

    private final ToolRegistry toolRegistry;
    private final WebSearchToolProperties webSearchProperties;
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
        registerWebSearchTool();
        registerDocumentSearchTool();
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
     * Register web search tool with metadata
     */
    private void registerWebSearchTool() {
        String confirmationAction = environment.getProperty(
            "chatchat.tools.web-search.confirmation.default",
            "auto_execute"
        );
        ToolMetadata metadata = ToolMetadata.builder()
            .id("web_search")
            .title("Web Search")
            .description("Search the internet for current information, news, and web resources. " +
                "Use this when you need up-to-date information not in your knowledge base.")
            .version("1.0.0")
            .author("ChatChat System")
            .categories(Arrays.asList("search", "internet"))
            .category("public_web_search")
            .riskLevel(environment.getProperty("chatchat.tools.web-search.risk-level", "low"))
            .operationType(environment.getProperty("chatchat.tools.web-search.operation-type", "read"))
            .runtimeLevel(environment.getProperty("chatchat.tools.web-search.runtime-level", "readonly"))
            .userVisible(true)
            .confirmation(Map.of(
                "default", confirmationAction,
                "allow_user_override", true
            ))
            .permissions(Map.of("roles", List.of()))
            .inputPolicy(Map.of(
                "must_show_parameters", true,
                "allow_auto_fill", true,
                "sensitive_params", List.of("query"),
                "parameter_rules", Map.of(
                    "query", Map.of("action", environment.getProperty(
                        "chatchat.tools.web-search.parameter-policy.query",
                        confirmationAction
                    )),
                    "num_results", Map.of("action", "auto_execute")
                )
            ))
            .outputPolicy(Map.of(
                "mask_fields", List.of(),
                "max_rows_without_confirm", webSearchProperties.getMaxResults()
            ))
            .outputType("json")
            .returnDirect(false)
            .timeoutMillis(webSearchTimeoutMillis())
            .isRateLimited(true)
            .maxCallsPerMinute(10)
            .agentCompatible(true)
            .parameters(Arrays.asList(
                ToolParameter.builder()
                    .name("query")
                    .type("string")
                    .description("Search query (e.g., 'latest AI news', 'weather in New York')")
                    .required(true)
                    .minLength(1)
                    .maxLength(500)
                    .build(),
                ToolParameter.builder()
                    .name("num_results")
                    .type("number")
                    .description("Number of search results to return (default: 10)")
                    .required(false)
                    .defaultValue(10)
                    .minimum(1)
                    .maximum(100)
                    .build(),
                ToolParameter.builder()
                    .name("tenantId")
                    .type("string")
                    .description("Optional tenant identifier used for request isolation")
                    .required(false)
                    .maxLength(128)
                    .build(),
                ToolParameter.builder()
                    .name("sourceTaskId")
                    .type("string")
                    .description("Optional task identifier used for request isolation")
                    .required(false)
                    .maxLength(128)
                    .build(),
                ToolParameter.builder()
                    .name("agentId")
                    .type("string")
                    .description("Optional agent identifier used for audit and rate control")
                    .required(false)
                    .maxLength(128)
                    .build(),
                ToolParameter.builder()
                    .name("referer")
                    .type("string")
                    .description("Optional browser Referer header override")
                    .required(false)
                    .maxLength(500)
                    .build()
            ))
            .tags(Arrays.asList("search", "internet", "external"))
            .build();

        WebSearchTool webSearchTool = new WebSearchTool(webSearchProperties, objectMapper);
        toolRegistry.registerTool("web_search", metadata, webSearchTool);
        log.info("Web Search tool registered");
    }

    /**
     * Performs the web search timeout millis operation.
     *
     * @return the operation result
     */
    private long webSearchTimeoutMillis() {
        long configuredTimeoutMs = webSearchProperties.getTimeoutMs();
        if (configuredTimeoutMs <= 0) {
            return 0L;
        }
        long perRequestTimeout = Math.max(1000L, configuredTimeoutMs);
        int searchAttempts = webSearchProperties.isFallbackEnabled() ? 3 : 1;
        int pageFetches = webSearchProperties.isFetchPages()
            ? Math.max(0, webSearchProperties.getMaxPagesToFetch())
            : 0;
        int siteSearchFetches = webSearchProperties.getSiteSearch().isEnabled()
            ? Math.max(0, webSearchProperties.getSiteSearch().getMaxPagesToInspect())
                + Math.max(0, webSearchProperties.getSiteSearch().getMaxSecondaryPages())
            : 0;
        return Math.max(30000L, perRequestTimeout * (searchAttempts + pageFetches + siteSearchFetches + 1));
    }

    /**
     * Register document search tool backed by the ChatChat knowledge library API.
     */
    private void registerDocumentSearchTool() {
        String apiBaseUrl = environment.getProperty("chatchat.tools.document-search.api-base-url", "http://localhost:8080");
        String searchPath = environment.getProperty("chatchat.tools.document-search.search-path", "/api/v1/search");
        String confirmationAction = environment.getProperty(
            "chatchat.tools.document-search.confirmation.default",
            "ask_before_execute"
        );
        ToolMetadata metadata = ToolMetadata.builder()
            .id("document_search")
            .title("Knowledge Document Search")
            .description("Search indexed knowledge-base documents with the platform retrieval API. " +
                "Use this for internal research documents and pass document_ids or tags when the agent has a limited document scope.")
            .version("1.0.0")
            .author("ChatChat System")
            .categories(Arrays.asList("search", "knowledge-base", "document"))
            .category("knowledge_document_search")
            .riskLevel(environment.getProperty("chatchat.tools.document-search.risk-level", "medium"))
            .operationType("read")
            .runtimeLevel(environment.getProperty("chatchat.tools.document-search.runtime-level", "readonly"))
            .userVisible(true)
            .confirmation(Map.of(
                "default", confirmationAction,
                "allow_user_override", true
            ))
            .inputPolicy(Map.of(
                "must_show_parameters", true,
                "allow_auto_fill", true,
                "sensitive_params", List.of("document_ids"),
                "parameter_rules", Map.of(
                    "document_ids", Map.of("action", "ask_before_execute"),
                    "tags", Map.of("action", "auto_execute")
                )
            ))
            .outputPolicy(Map.of(
                "mask_fields", List.of(),
                "max_rows_without_confirm", environment.getProperty("chatchat.tools.document-search.max-limit", Integer.class, 20)
            ))
            .outputType("json")
            .returnDirect(false)
            .timeoutMillis(environment.getProperty("chatchat.tools.document-search.timeout-ms", Long.class, 20000L))
            .agentCompatible(true)
            .parameters(Arrays.asList(
                ToolParameter.builder()
                    .name("query")
                    .type("string")
                    .description("Document search query.")
                    .required(true)
                    .minLength(1)
                    .maxLength(1000)
                    .build(),
                ToolParameter.builder()
                    .name("document_ids")
                    .type("array")
                    .description("Optional document IDs that the search is allowed to retrieve from.")
                    .required(false)
                    .metadata(Map.of("items", Map.of("type", "string")))
                    .build(),
                ToolParameter.builder()
                    .name("tags")
                    .type("array")
                    .description("Optional document tags to filter by.")
                    .required(false)
                    .metadata(Map.of("items", Map.of("type", "string")))
                    .build(),
                ToolParameter.builder()
                    .name("company")
                    .type("string")
                    .description("Optional company filter.")
                    .required(false)
                    .maxLength(200)
                    .build(),
                ToolParameter.builder()
                    .name("industry")
                    .type("string")
                    .description("Optional industry filter.")
                    .required(false)
                    .maxLength(200)
                    .build(),
                ToolParameter.builder()
                    .name("limit")
                    .type("integer")
                    .description("Maximum number of documents to return.")
                    .required(false)
                    .defaultValue(environment.getProperty("chatchat.tools.document-search.default-limit", Integer.class, 5))
                    .minimum(1)
                    .maximum(environment.getProperty("chatchat.tools.document-search.max-limit", Integer.class, 20))
                    .build()
            ))
            .tags(Arrays.asList("search", "document", "knowledge-base", "agent"))
            .metadata(Map.of("readOnly", true))
            .build();

        DocumentSearchTool documentSearchTool = new DocumentSearchTool(environment, objectMapper);
        toolRegistry.registerTool("document_search", metadata, documentSearchTool);
        log.info("Knowledge Document Search tool registered, target={}{}", apiBaseUrl, searchPath);
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
                    .description("Maximum number of rows to return.")
                    .required(false)
                    .defaultValue(databaseToolProperties.getDefaultMaxRows())
                    .minimum(1)
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
     * Knowledge document search tool implementation.
     */
    private static class DocumentSearchTool implements ToolRegistry.EnhancedTool {

        private final Environment environment;
        private final ObjectMapper objectMapper;
        private final HttpClient httpClient;
        private volatile String documentSearchToken;

        /**
         * Creates a new BuiltInToolsBootstrap instance.
         *
         * @param environment the environment value
         * @param objectMapper the object mapper value
         */
        private DocumentSearchTool(Environment environment, ObjectMapper objectMapper) {
            this.environment = environment;
            this.objectMapper = objectMapper;
            int timeoutMs = environment.getProperty("chatchat.tools.document-search.timeout-ms", Integer.class, 20000);
            this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(Math.max(1000, timeoutMs)))
                .build();
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
        @SuppressWarnings("unchecked")
        public ToolOutput execute(ToolInput input) {
            URI uri = null;
            try {
                if (!environment.getProperty("chatchat.tools.document-search.enabled", Boolean.class, true)) {
                    return ToolOutput.failure("document_search tool is disabled");
                }
                String query = input.getParameterAsString("query", "");
                if (query == null || query.isBlank()) {
                    return ToolOutput.failure("query parameter is required");
                }
                int limit = resolveLimit(input);
                uri = buildSearchUri(input, query.trim(), limit);
                int timeoutMs = environment.getProperty("chatchat.tools.document-search.timeout-ms", Integer.class, 20000);
                HttpResponse<String> response = sendDocumentApiGet(uri, timeoutMs);
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    return ToolOutput.failure("document search API returned HTTP " + response.statusCode() + " from " + uri);
                }
                Map<String, Object> payload = objectMapper.readValue(response.body(), Map.class);
                Object data = payload.containsKey("data") ? payload.get("data") : payload;
                enrichWithDocumentContent(data, query.trim());
                return ToolOutput.success(data, "Document search completed successfully");
            } catch (Exception e) {
                String target = uri == null ? "" : " for " + uri;
                return ToolOutput.failure("document search API call failed" + target + ": " + e.getMessage());
            }
        }

        /**
         * Performs the enrich with document content operation.
         *
         * @param data the data value
         * @param query the query value
         */
        @SuppressWarnings("unchecked")
        private void enrichWithDocumentContent(Object data, String query) {
            if (!(data instanceof Map<?, ?> rawData)) {
                return;
            }
            Map<String, Object> page = (Map<String, Object>) rawData;
            Object resultsValue = page.get("results");
            if (!(resultsValue instanceof List<?> results) || results.isEmpty()) {
                page.put("contentMode", "no_results");
                return;
            }

            int excerptChars = resolveExcerptChars();
            List<Map<String, Object>> evidenceSnippets = new ArrayList<>();
            for (Object item : results) {
                if (!(item instanceof Map<?, ?> rawResult)) {
                    continue;
                }
                Map<String, Object> result = (Map<String, Object>) rawResult;
                enrichOneResult(result, query, excerptChars, evidenceSnippets);
            }
            page.put("contentMode", evidenceSnippets.isEmpty() ? "search_summary_only" : "detail_enriched");
            page.put("evidenceSnippets", evidenceSnippets);
        }

        /**
         * Performs the enrich one result operation.
         *
         * @param result the result value
         * @param query the query value
         * @param excerptChars the excerpt chars value
         * @param evidenceSnippets the evidence snippets value
         */
        @SuppressWarnings("unchecked")
        private void enrichOneResult(Map<String, Object> result,
                                     String query,
                                     int excerptChars,
                                     List<Map<String, Object>> evidenceSnippets) {
            URI detailUri = buildDetailUri(result);
            if (detailUri == null) {
                result.put("detailFetched", false);
                result.put("detailFetchError", "missing docId/detailPath");
                return;
            }
            try {
                int timeoutMs = environment.getProperty("chatchat.tools.document-search.timeout-ms", Integer.class, 20000);
                HttpResponse<String> detailResponse = sendDocumentApiGet(detailUri, timeoutMs);
                if (detailResponse.statusCode() < 200 || detailResponse.statusCode() >= 300) {
                    result.put("detailFetched", false);
                    result.put("detailFetchError", "document detail API returned HTTP " + detailResponse.statusCode() + " from " + detailUri);
                    return;
                }
                Map<String, Object> payload = objectMapper.readValue(detailResponse.body(), Map.class);
                Object detailValue = payload.containsKey("data") ? payload.get("data") : payload;
                if (!(detailValue instanceof Map<?, ?> detail)) {
                    result.put("detailFetched", false);
                    result.put("detailFetchError", "document detail API returned unexpected payload");
                    return;
                }

                String content = stringValue(detail.get("content"));
                if (content == null || content.isBlank()) {
                    result.put("detailFetched", true);
                    result.put("contentAvailable", false);
                    return;
                }
                String excerpt = buildContentExcerpt(content, query, excerptChars);
                List<Map<String, Object>> chunkEvidence = matchedChunkEvidence(result);
                result.put("detailFetched", true);
                result.put("contentAvailable", true);
                result.put("contentLength", content.length());
                if (!chunkEvidence.isEmpty()) {
                    String combined = chunkEvidence.stream()
                        .map(item -> stringValue(item.get("excerpt")))
                        .filter(text -> text != null && !text.isBlank())
                        .reduce((left, right) -> left + "\n\n" + right)
                        .orElse(excerpt);
                    result.put("contentTruncated", true);
                    result.put("contentExcerpt", combined);
                    evidenceSnippets.addAll(chunkEvidence);
                } else {
                    result.put("contentTruncated", content.replaceAll("\\s+", " ").trim().length() > excerptChars);
                    result.put("contentExcerpt", excerpt);
                    Map<String, Object> evidence = new LinkedHashMap<>();
                    evidence.put("docId", firstNonBlank(stringValue(result.get("docId")), stringValue(detail.get("docId"))));
                    evidence.put("title", firstNonBlank(stringValue(result.get("title")), stringValue(detail.get("title"))));
                    evidence.put("excerpt", excerpt);
                    evidenceSnippets.add(evidence);
                }
            } catch (Exception ex) {
                result.put("detailFetched", false);
                result.put("detailFetchError", "document detail API call failed for " + detailUri + ": " + ex.getMessage());
            }
        }

        /**
         * Sends the document api get.
         *
         * @param uri the uri value
         * @param timeoutMs the timeout ms value
         * @return the operation result
         * @throws IOException if the operation fails
         * @throws InterruptedException if the operation fails
         */
        private HttpResponse<String> sendDocumentApiGet(URI uri, int timeoutMs) throws IOException, InterruptedException {
            HttpResponse<String> response = httpClient.send(buildDocumentApiGet(uri, timeoutMs), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() == 401 && isDocumentSearchAuthEnabled() && configuredDocumentSearchToken().isBlank()) {
                documentSearchToken = null;
                response = httpClient.send(buildDocumentApiGet(uri, timeoutMs), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            }
            return response;
        }

        /**
         * Builds the document api get.
         *
         * @param uri the uri value
         * @param timeoutMs the timeout ms value
         * @return the built document api get
         * @throws IOException if the operation fails
         * @throws InterruptedException if the operation fails
         */
        private HttpRequest buildDocumentApiGet(URI uri, int timeoutMs) throws IOException, InterruptedException {
            HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofMillis(Math.max(1000, timeoutMs)))
                .GET();
            String token = resolveDocumentSearchToken();
            if (token != null && !token.isBlank()) {
                builder.header("Authorization", "Bearer " + token);
            }
            return builder.build();
        }

        /**
         * Resolves the document search token.
         *
         * @return the resolved document search token
         * @throws IOException if the operation fails
         * @throws InterruptedException if the operation fails
         */
        private String resolveDocumentSearchToken() throws IOException, InterruptedException {
            if (!isDocumentSearchAuthEnabled()) {
                return "";
            }
            String configuredToken = configuredDocumentSearchToken();
            if (!configuredToken.isBlank()) {
                return configuredToken;
            }
            if (!hasDocumentSearchLoginCredentials()) {
                return "";
            }
            String cachedToken = documentSearchToken;
            if (cachedToken != null && !cachedToken.isBlank()) {
                return cachedToken;
            }
            synchronized (this) {
                cachedToken = documentSearchToken;
                if (cachedToken != null && !cachedToken.isBlank()) {
                    return cachedToken;
                }
                documentSearchToken = loginForDocumentSearchToken();
                return documentSearchToken;
            }
        }

        /**
         * Performs the login for document search token operation.
         *
         * @return the operation result
         * @throws IOException if the operation fails
         * @throws InterruptedException if the operation fails
         */
        private String loginForDocumentSearchToken() throws IOException, InterruptedException {
            String username = environment.getProperty("chatchat.tools.document-search.auth.username", "admin");
            String password = environment.getProperty("chatchat.tools.document-search.auth.password", "123456");
            if (username == null || username.isBlank() || password == null || password.isBlank()) {
                return "";
            }
            int timeoutMs = environment.getProperty("chatchat.tools.document-search.timeout-ms", Integer.class, 20000);
            URI loginUri = buildDocumentSearchLoginUri();
            String body = objectMapper.writeValueAsString(Map.of(
                "username", username,
                "password", password
            ));
            HttpRequest loginRequest = HttpRequest.newBuilder(loginUri)
                .timeout(Duration.ofMillis(Math.max(1000, timeoutMs)))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
            HttpResponse<String> loginResponse = httpClient.send(loginRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (loginResponse.statusCode() < 200 || loginResponse.statusCode() >= 300) {
                throw new IllegalStateException("document search login API returned HTTP " + loginResponse.statusCode() + " from " + loginUri);
            }
            return extractLoginToken(loginResponse.body(), loginUri);
        }

        /**
         * Performs the extract login token operation.
         *
         * @param body the body value
         * @param loginUri the login uri value
         * @return the operation result
         * @throws IOException if the operation fails
         */
        @SuppressWarnings("unchecked")
        private String extractLoginToken(String body, URI loginUri) throws IOException {
            Map<String, Object> payload = objectMapper.readValue(body, Map.class);
            Object code = payload.get("code");
            if (code instanceof Number number && number.intValue() >= 400) {
                throw new IllegalStateException("document search login API returned code " + number.intValue() + " from " + loginUri);
            }
            Object data = payload.containsKey("data") ? payload.get("data") : payload;
            if (!(data instanceof Map<?, ?> loginData)) {
                throw new IllegalStateException("document search login API returned unexpected payload from " + loginUri);
            }
            String token = stringValue(loginData.get("token"));
            if (token == null || token.isBlank()) {
                throw new IllegalStateException("document search login API did not return token from " + loginUri);
            }
            return token.trim();
        }

        /**
         * Builds the document search login uri.
         *
         * @return the built document search login uri
         */
        private URI buildDocumentSearchLoginUri() {
            String loginPath = environment.getProperty(
                "chatchat.tools.document-search.auth.login-path",
                "/api/v1/enterprise/auth/login"
            );
            if (loginPath.startsWith("http://") || loginPath.startsWith("https://")) {
                return URI.create(loginPath);
            }
            StringBuilder url = new StringBuilder(trimTrailingSlash(documentSearchApiBaseUrl()));
            if (!loginPath.startsWith("/")) {
                url.append('/');
            }
            url.append(loginPath);
            return URI.create(url.toString());
        }

        /**
         * Returns whether is document search auth enabled.
         *
         * @return whether the condition is satisfied
         */
        private boolean isDocumentSearchAuthEnabled() {
            return environment.getProperty("chatchat.tools.document-search.auth.enabled", Boolean.class, true);
        }

        /**
         * Performs the configured document search token operation.
         *
         * @return the operation result
         */
        private String configuredDocumentSearchToken() {
            return environment.getProperty("chatchat.tools.document-search.auth.bearer-token", "").trim();
        }

        private boolean hasDocumentSearchLoginCredentials() {
            String username = environment.getProperty("chatchat.tools.document-search.auth.username", "admin");
            String password = environment.getProperty("chatchat.tools.document-search.auth.password", "123456");
            return username != null && !username.isBlank() && password != null && !password.isBlank();
        }

        /**
         * Performs the matched chunk evidence operation.
         *
         * @param result the result value
         * @return the operation result
         */
        @SuppressWarnings("unchecked")
        private List<Map<String, Object>> matchedChunkEvidence(Map<String, Object> result) {
            Object chunksValue = result.get("matchedChunks");
            if (!(chunksValue instanceof List<?> chunks) || chunks.isEmpty()) {
                return List.of();
            }
            List<Map<String, Object>> evidence = new ArrayList<>();
            for (Object chunkValue : chunks) {
                if (!(chunkValue instanceof Map<?, ?> rawChunk)) {
                    continue;
                }
                Map<String, Object> chunk = (Map<String, Object>) rawChunk;
                String text = stringValue(chunk.get("text"));
                if (text == null || text.isBlank()) {
                    continue;
                }
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("docId", stringValue(result.get("docId")));
                item.put("title", stringValue(result.get("title")));
                item.put("chunkId", stringValue(chunk.get("chunkId")));
                item.put("chunkIndex", chunk.get("chunkIndex"));
                item.put("score", chunk.get("score"));
                item.put("excerpt", text);
                evidence.add(item);
            }
            return evidence;
        }

        /**
         * Builds the detail uri.
         *
         * @param result the result value
         * @return the built detail uri
         */
        private URI buildDetailUri(Map<String, Object> result) {
            String apiBaseUrl = documentSearchApiBaseUrl();
            String detailPath = stringValue(result.get("detailPath"));
            if (detailPath == null || detailPath.isBlank()) {
                String docId = stringValue(result.get("docId"));
                if (docId == null || docId.isBlank()) {
                    return null;
                }
                String template = environment.getProperty(
                    "chatchat.tools.document-search.detail-path-template",
                    "/api/v1/search/documents/{docId}"
                );
                String encodedDocId = encodePathSegment(docId.trim());
                detailPath = template.contains("{docId}")
                    ? template.replace("{docId}", encodedDocId)
                    : trimTrailingSlash(template) + "/" + encodedDocId;
            }
            if (detailPath.startsWith("http://") || detailPath.startsWith("https://")) {
                return URI.create(detailPath);
            }
            StringBuilder url = new StringBuilder(trimTrailingSlash(apiBaseUrl));
            if (!detailPath.startsWith("/")) {
                url.append('/');
            }
            url.append(detailPath);
            return URI.create(url.toString());
        }

        /**
         * Resolves the excerpt chars.
         *
         * @return the resolved excerpt chars
         */
        private int resolveExcerptChars() {
            int defaultChars = environment.getProperty("chatchat.tools.document-search.default-excerpt-chars", Integer.class, 3000);
            int maxChars = environment.getProperty("chatchat.tools.document-search.max-excerpt-chars", Integer.class, 8000);
            return Math.max(500, Math.min(Math.max(500, maxChars), defaultChars));
        }

        /**
         * Builds the content excerpt.
         *
         * @param content the content value
         * @param query the query value
         * @param maxChars the max chars value
         * @return the built content excerpt
         */
        private String buildContentExcerpt(String content, String query, int maxChars) {
            String normalized = content == null ? "" : content.replaceAll("\\s+", " ").trim();
            if (normalized.length() <= maxChars) {
                return normalized;
            }

            int start = bestExcerptStart(normalized, query, maxChars);
            int end = Math.min(normalized.length(), start + maxChars);
            String excerpt = normalized.substring(start, end).trim();
            if (start > 0) {
                excerpt = "..." + excerpt;
            }
            if (end < normalized.length()) {
                excerpt = excerpt + "...";
            }
            return excerpt;
        }

        /**
         * Performs the best excerpt start operation.
         *
         * @param content the content value
         * @param query the query value
         * @param maxChars the max chars value
         * @return the operation result
         */
        private int bestExcerptStart(String content, String query, int maxChars) {
            String lowerContent = content.toLowerCase(Locale.ROOT);
            for (String term : queryTerms(query)) {
                int index = lowerContent.indexOf(term.toLowerCase(Locale.ROOT));
                if (index >= 0) {
                    return Math.max(0, index - maxChars / 4);
                }
            }
            return 0;
        }

        /**
         * Queries the terms.
         *
         * @param query the query value
         * @return the operation result
         */
        private List<String> queryTerms(String query) {
            if (query == null || query.isBlank()) {
                return List.of();
            }
            List<String> terms = new ArrayList<>();
            for (String term : query.trim().split("[\\s,\\uFF0C\\u3002\\uFF1B;:\\uFF1A\\u3001\\\\]+")) {
                if (term.length() >= 2) {
                    terms.add(term);
                }
            }
            terms.sort((left, right) -> Integer.compare(right.length(), left.length()));
            return terms;
        }

        /**
         * Builds the search uri.
         *
         * @param input the input value
         * @param query the query value
         * @param limit the limit value
         * @return the built search uri
         */
        private URI buildSearchUri(ToolInput input, String query, int limit) {
            String apiBaseUrl = documentSearchApiBaseUrl();
            String searchPath = environment.getProperty("chatchat.tools.document-search.search-path", "/api/v1/search");
            StringBuilder url = new StringBuilder(trimTrailingSlash(apiBaseUrl));
            if (!searchPath.startsWith("/")) {
                url.append('/');
            }
            url.append(searchPath);
            List<String> params = new ArrayList<>();
            addParam(params, "keyword", query);
            addParam(params, "tag", joinValues(input.getParameter("tags")));
            addParam(params, "docIds", joinValues(input.getParameter("document_ids")));
            addParam(params, "company", input.getParameterAsString("company", ""));
            addParam(params, "industry", input.getParameterAsString("industry", ""));
            addParam(params, "limit", String.valueOf(limit));
            if (!params.isEmpty()) {
                url.append('?').append(String.join("&", params));
            }
            return URI.create(url.toString());
        }

        /**
         * Resolves the limit.
         *
         * @param input the input value
         * @return the resolved limit
         */
        private int resolveLimit(ToolInput input) {
            Number requested = input.getParameterAsNumber("limit");
            int defaultLimit = environment.getProperty("chatchat.tools.document-search.default-limit", Integer.class, 5);
            int maxLimit = environment.getProperty("chatchat.tools.document-search.max-limit", Integer.class, 20);
            int value = requested == null ? defaultLimit : requested.intValue();
            return Math.max(1, Math.min(Math.max(1, maxLimit), value));
        }

        /**
         * Performs the document search api base url operation.
         *
         * @return the operation result
         */
        private String documentSearchApiBaseUrl() {
            return environment.getProperty("chatchat.tools.document-search.api-base-url", "http://localhost:8080");
        }

        /**
         * Performs the join values operation.
         *
         * @param value the value value
         * @return the operation result
         */
        private String joinValues(Object value) {
            if (value == null) {
                return "";
            }
            if (value instanceof Iterable<?> iterable) {
                List<String> values = new ArrayList<>();
                for (Object item : iterable) {
                    if (item != null && !String.valueOf(item).isBlank()) {
                        values.add(String.valueOf(item).trim());
                    }
                }
                return String.join(",", values);
            }
            if (value.getClass().isArray()) {
                Object[] values = (Object[]) value;
                return Arrays.stream(values)
                    .filter(item -> item != null && !String.valueOf(item).isBlank())
                    .map(item -> String.valueOf(item).trim())
                    .reduce((left, right) -> left + "," + right)
                    .orElse("");
            }
            return String.valueOf(value).trim();
        }

        /**
         * Adds the param.
         *
         * @param params the params value
         * @param name the name value
         * @param value the value value
         */
        private void addParam(List<String> params, String name, String value) {
            if (value == null || value.isBlank()) {
                return;
            }
            params.add(encode(name) + "=" + encode(value.trim()));
        }

        /**
         * Performs the encode operation.
         *
         * @param value the value value
         * @return the operation result
         */
        private String encode(String value) {
            return URLEncoder.encode(value, StandardCharsets.UTF_8);
        }

        /**
         * Performs the encode path segment operation.
         *
         * @param value the value value
         * @return the operation result
         */
        private String encodePathSegment(String value) {
            return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
        }

        /**
         * Performs the trim trailing slash operation.
         *
         * @param value the value value
         * @return the operation result
         */
        private String trimTrailingSlash(String value) {
            if (value == null || value.isBlank()) {
                return "http://localhost:8080";
            }
            String text = value.trim();
            while (text.endsWith("/")) {
                text = text.substring(0, text.length() - 1);
            }
            return text;
        }

        /**
         * Performs the string value operation.
         *
         * @param value the value value
         * @return the operation result
         */
        private String stringValue(Object value) {
            return value == null ? null : String.valueOf(value);
        }

        /**
         * Performs the first non blank operation.
         *
         * @param first the first value
         * @param second the second value
         * @return the operation result
         */
        private String firstNonBlank(String first, String second) {
            return first == null || first.isBlank() ? second : first;
        }
    }

    /**
     * Read-only database query tool implementation
     */
    private static class DatabaseQueryTool implements ToolRegistry.EnhancedTool {

        private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);
        private static final Pattern LINE_COMMENT = Pattern.compile("(?m)--.*$");

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
                Map<String, Object> params = resolveParams(input);

                JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
                jdbcTemplate.setMaxRows(maxRows);
                jdbcTemplate.setQueryTimeout(Math.max(1, properties.getQueryTimeoutSeconds()));
                NamedParameterJdbcTemplate namedTemplate = new NamedParameterJdbcTemplate(jdbcTemplate);

                List<Map<String, Object>> rows = namedTemplate.queryForList(safeSql, params)
                    .stream()
                    .map(this::normalizeRow)
                    .toList();

                Map<String, Object> result = new LinkedHashMap<>();
                result.put("sql", safeSql);
                result.put("dataSource", "external");
                result.put("rowCount", rows.size());
                result.put("maxRows", maxRows);
                result.put("columns", rows.isEmpty() ? List.of() : new ArrayList<>(rows.get(0).keySet()));
                result.put("rows", rows);
                result.put("readOnly", true);
                result.put("possiblyTruncated", rows.size() >= maxRows);

                return ToolOutput.success(result, "Database query completed successfully");
            } catch (Exception e) {
                return ToolOutput.failure(e);
            }
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
                input.getParameterAsString("driver_class", "")
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
            String lower = normalized.toLowerCase(Locale.ROOT);
            boolean allowed = properties.getAllowedPrefixes().stream()
                .map(prefix -> prefix.toLowerCase(Locale.ROOT))
                .anyMatch(prefix -> lower.equals(prefix) || lower.startsWith(prefix + " "));
            if (!allowed) {
                throw new IllegalArgumentException("Only read-only SQL statements are allowed");
            }
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
            return Math.max(1, Math.min(properties.getMaxRows(), value));
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

