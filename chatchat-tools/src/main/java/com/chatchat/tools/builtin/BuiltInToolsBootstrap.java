package com.chatchat.tools.builtin;

import com.chatchat.agents.tool.ToolRegistry;
import com.chatchat.common.tool.ToolInput;
import com.chatchat.common.tool.ToolMetadata;
import com.chatchat.common.tool.ToolOutput;
import com.chatchat.common.tool.ToolParameter;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Date;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDate;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
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
                    .description("Optional tenant identifier used for proxy and cookie isolation")
                    .required(false)
                    .maxLength(128)
                    .build(),
                ToolParameter.builder()
                    .name("sourceTaskId")
                    .type("string")
                    .description("Optional task identifier used for proxy and cookie isolation")
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
                    .name("proxyPool")
                    .type("string")
                    .description("Optional proxy pool name")
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
        long perRequestTimeout = Math.max(1000L, webSearchProperties.getTimeoutMs());
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
     * Web Search Tool implementation
     */
    private static class WebSearchTool implements ToolRegistry.EnhancedTool {

        private final WebSearchToolProperties properties;
        private final ObjectMapper objectMapper;
        private final Semaphore rateSemaphore;
        private final Object rateLock = new Object();
        private final Object cookieLock = new Object();
        private final Map<String, Map<String, String>> cookieJar = new HashMap<>();
        private final Map<String, ProxyRuntimeState> proxyStates = new HashMap<>();
        private final AtomicInteger proxyCursor = new AtomicInteger();
        private final AtomicInteger dailyCalls = new AtomicInteger();
        private volatile LocalDate dailyWindow = LocalDate.now();
        private volatile long lastRequestAtMs;
        private static final Pattern HTTP_URL_PATTERN = Pattern.compile("https?://[^\\s\\)\\]\\}>\"'，。；;,]+", Pattern.CASE_INSENSITIVE);
        private static final Pattern SITE_OPERATOR_PATTERN = Pattern.compile("(?i)(?:^|\\s)site\\s*:\\s*([^\\s]+)");

        /**
         * Creates a new BuiltInToolsBootstrap instance.
         *
         * @param properties the properties value
         * @param objectMapper the object mapper value
         */
        private WebSearchTool(WebSearchToolProperties properties, ObjectMapper objectMapper) {
            this.properties = properties;
            this.objectMapper = objectMapper;
            this.rateSemaphore = new Semaphore(Math.max(1, properties.getRateLimit().getMaxConcurrency()), true);
            loadCookies();
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
                    return ToolOutput.failure("web_search tool is disabled");
                }
                String query = input.getParameterAsString("query", "");
                Number requestedResults = input.getParameterAsNumber("num_results");
                int numResults = requestedResults == null
                    ? properties.getMaxResults()
                    : requestedResults.intValue();
                numResults = Math.max(1, Math.min(properties.getMaxResults(), numResults));

                if (query.isEmpty()) {
                    return ToolOutput.failure("Search query is required");
                }

                Map<String, Object> result = performWebSearch(input, query, numResults);
                return ToolOutput.success(result, buildSearchMessage(result));

            } catch (Exception e) {
                return ToolOutput.failure(e);
            }
        }

        /**
         * Performs the perform web search operation.
         *
         * @param query the query value
         * @param numResults the num results value
         * @return the operation result
         * @throws Exception if the operation fails
         */
        private Map<String, Object> performWebSearch(ToolInput input, String query, int numResults) throws Exception {
            String provider = properties.getProvider() == null
                ? "duckduckgo_html"
                : properties.getProvider().trim().toLowerCase(Locale.ROOT);

            WebSearchQueryIntent queryIntent = analyzeSearchQuery(query);
            WebSearchRequestContext context = requestContext(input, query);
            List<SearchAttempt> attempts = buildSearchAttempts(provider, properties.getEndpoint());
            List<String> errors = new ArrayList<>();
            List<Map<String, Object>> networkAudit = new ArrayList<>();
            for (SearchAttempt attempt : attempts) {
                try {
                    long startedAt = System.currentTimeMillis();
                    log.info("Web search provider attempt started provider={} endpoint={} query={} numResults={} fetchPages={} maxPagesToFetch={}",
                        attempt.provider(),
                        attempt.endpoint(),
                        query,
                        numResults,
                        properties.isFetchPages(),
                        properties.getMaxPagesToFetch());
                    Map<String, Object> result = performWebSearchAttempt(
                        attempt.provider(), attempt.endpoint(), queryIntent, numResults, errors, context, networkAudit);
                    log.info("Web search provider attempt succeeded provider={} durationMs={} resultCount={} pageExcerptCount={}",
                        attempt.provider(),
                        Math.max(0L, System.currentTimeMillis() - startedAt),
                        result.get("count"),
                        result.get("page_excerpt_count"));
                    return result;
                } catch (Exception ex) {
                    errors.add(attempt.provider() + ": " + ex.getMessage());
                    log.warn("Web search provider attempt failed provider={} error={}", attempt.provider(), ex.getMessage());
                }
            }
            throw new IllegalStateException("All web search providers failed: " + String.join("; ", errors));
        }

        /**
         * Performs the perform web search attempt operation.
         *
         * @param provider the provider value
         * @param endpoint the endpoint value
         * @param query the query value
         * @param numResults the num results value
         * @param previousErrors the previous errors value
         * @return the operation result
         * @throws Exception if the operation fails
         */
        private Map<String, Object> performWebSearchAttempt(String provider,
                                                            String endpoint,
                                                            WebSearchQueryIntent queryIntent,
                                                            int numResults,
                                                            List<String> previousErrors,
                                                            WebSearchRequestContext context,
                                                            List<Map<String, Object>> networkAudit) throws Exception {
            String query = queryIntent.originalQuery();
            String searchQuery = queryIntent.searchQuery();
            String siteQuery = queryIntent.siteSearchQuery();
            HtmlResponse searchResponse = sendHtmlRequest(
                endpoint,
                Map.of("q", searchQuery),
                searchQuery,
                "search",
                context,
                networkAudit
            );
            Document document = searchResponse.document();

            int referenceLimit = Math.max(0, Math.min(10, properties.getMaxResults()));
            int fetchLimit = Math.max(numResults, referenceLimit);

            List<Map<String, Object>> fetchedResults = switch (provider) {
                case "duckduckgo_html" -> parseDuckDuckGoResults(document, fetchLimit);
                case "bing_html" -> parseBingResults(document, fetchLimit);
                default -> throw new IllegalArgumentException("Unsupported web search provider: " + properties.getProvider());
            };
            List<Map<String, Object>> primaryResults = targetedPrimaryResults(fetchedResults, queryIntent);
            List<Map<String, Object>> siteSearchResults = discoverSiteSearchResults(
                primaryResults,
                siteQuery,
                fetchLimit,
                context,
                networkAudit
            );
            List<Map<String, Object>> mergedResults = mergeSearchResults(primaryResults, siteSearchResults, fetchLimit);
            List<Map<String, Object>> results = mergedResults.size() <= numResults
                ? mergedResults
                : new ArrayList<>(mergedResults.subList(0, numResults));
            List<String> referenceUrls = mergedResults.stream()
                .map(item -> item.get("url"))
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .filter(url -> !url.isBlank())
                .distinct()
                .limit(referenceLimit)
                .toList();
            List<Map<String, Object>> pageExcerpts = fetchPageExcerpts(results, query, context, networkAudit);

            Map<String, Object> output = new LinkedHashMap<>();
            output.put("query", query);
            output.put("search_query", searchQuery);
            output.put("site_search_query", siteQuery);
            output.put("target_site", queryIntent.targetHost());
            output.put("provider", provider);
            output.put("configuredProvider", properties.getProvider());
            output.put("fallbackUsed", previousErrors != null && !previousErrors.isEmpty());
            output.put("providerErrors", previousErrors == null ? List.of() : previousErrors);
            output.put("count", results.size());
            output.put("reference_url_count", referenceUrls.size());
            output.put("reference_urls", referenceUrls);
            output.put("site_search_enabled", properties.getSiteSearch().isEnabled());
            output.put("site_search_result_count", siteSearchResults.size());
            output.put("page_fetch_enabled", properties.isFetchPages());
            output.put("page_excerpt_count", pageExcerpts.size());
            output.put("contentMode", contentMode(pageExcerpts, siteSearchResults));
            output.put("web_search_audit", properties.getAudit().isIncludeInResult() ? networkAudit : List.of());
            output.put("pageExcerpts", pageExcerpts);
            output.put("evidenceSnippets", pageExcerpts);
            output.put("results", results);
            return output;
        }

        private WebSearchQueryIntent analyzeSearchQuery(String query) {
            String original = query == null ? "" : query.trim();
            String targetUrl = firstUrl(original);
            String targetHost = targetUrl == null ? null : normalizedSearchHost(hostOf(targetUrl));
            Matcher siteMatcher = SITE_OPERATOR_PATTERN.matcher(original);
            if ((targetHost == null || targetHost.isBlank()) && siteMatcher.find()) {
                targetHost = normalizedSearchHost(siteMatcher.group(1));
                targetUrl = "https://" + targetHost + "/";
            }

            String keyword = cleanupSiteKeyword(original);
            String searchQuery = original;
            if (targetHost != null && !targetHost.isBlank() && !containsSiteOperator(original)) {
                searchQuery = keyword.isBlank()
                    ? "site:" + targetHost
                    : "site:" + targetHost + " " + keyword;
            }
            String siteSearchQuery = keyword.isBlank() ? original : keyword;
            return new WebSearchQueryIntent(original, searchQuery, siteSearchQuery, targetUrl, targetHost);
        }

        private List<Map<String, Object>> targetedPrimaryResults(List<Map<String, Object>> fetchedResults,
                                                                 WebSearchQueryIntent queryIntent) {
            if (queryIntent.targetHost() == null || queryIntent.targetHost().isBlank()) {
                return fetchedResults;
            }
            List<Map<String, Object>> targeted = new ArrayList<>();
            Map<String, Object> seed = targetSiteSeed(queryIntent);
            if (seed != null) {
                targeted.add(seed);
            }
            if (fetchedResults != null) {
                for (Map<String, Object> result : fetchedResults) {
                    String url = stringValue(result.get("url"));
                    if (sameSearchDomain(url, queryIntent.targetHost())) {
                        targeted.add(new LinkedHashMap<>(result));
                    }
                }
            }
            return targeted;
        }

        private Map<String, Object> targetSiteSeed(WebSearchQueryIntent queryIntent) {
            String targetUrl = queryIntent.targetUrl();
            if (!isHttpUrl(targetUrl)) {
                return null;
            }
            Map<String, Object> seed = new LinkedHashMap<>();
            seed.put("rank", 1);
            seed.put("title", queryIntent.targetHost());
            seed.put("url", targetUrl);
            seed.put("snippet", "Target site requested by user");
            seed.put("source", "target_site");
            return seed;
        }

        private String cleanupSiteKeyword(String query) {
            String value = query == null ? "" : query.trim();
            value = HTTP_URL_PATTERN.matcher(value).replaceAll(" ");
            value = SITE_OPERATOR_PATTERN.matcher(value).replaceAll(" ");
            value = value.replaceAll("(?i)\\b(from|within|inside|website|site|search|find|lookup|look up|on|in)\\b", " ");
            for (String phrase : List.of(
                "\u4ece\u8fd9\u4e2a\u7f51\u7ad9\u627e",
                "\u4ece\u8fd9\u4e2a\u7f51\u7ad9\u641c\u7d22",
                "\u8fd9\u4e2a\u7f51\u7ad9",
                "\u7f51\u7ad9",
                "\u641c\u7d22",
                "\u67e5\u627e",
                "\u67e5\u8be2",
                "\u4ece"
            )) {
                value = value.replace(phrase, " ");
            }
            return value.replaceAll("\\s+", " ").trim();
        }

        private String firstUrl(String query) {
            if (query == null || query.isBlank()) {
                return null;
            }
            Matcher matcher = HTTP_URL_PATTERN.matcher(query);
            return matcher.find() ? matcher.group() : null;
        }

        private boolean containsSiteOperator(String query) {
            return query != null && SITE_OPERATOR_PATTERN.matcher(query).find();
        }

        private boolean sameSearchDomain(String url, String targetHost) {
            String host = normalizedSearchHost(hostOf(url));
            String target = normalizedSearchHost(targetHost);
            return host != null && target != null && (host.equals(target) || host.endsWith("." + target));
        }

        private String normalizedSearchHost(String host) {
            if (host == null || host.isBlank()) {
                return null;
            }
            String value = host.trim().toLowerCase(Locale.ROOT);
            if (value.startsWith("www.")) {
                value = value.substring(4);
            }
            int slash = value.indexOf('/');
            if (slash >= 0) {
                value = value.substring(0, slash);
            }
            int colon = value.indexOf(':');
            if (colon >= 0) {
                value = value.substring(0, colon);
            }
            return value.isBlank() ? null : value;
        }

        private String contentMode(List<Map<String, Object>> pageExcerpts, List<Map<String, Object>> siteSearchResults) {
            if (pageExcerpts != null && !pageExcerpts.isEmpty()) {
                return siteSearchResults != null && !siteSearchResults.isEmpty()
                    ? "page_and_site_search_enriched"
                    : "page_enriched";
            }
            return siteSearchResults != null && !siteSearchResults.isEmpty()
                ? "site_search_enriched"
                : "search_snippets_only";
        }

        private record WebSearchQueryIntent(
            String originalQuery,
            String searchQuery,
            String siteSearchQuery,
            String targetUrl,
            String targetHost
        ) {
        }

        /**
         * Builds the search attempts.
         *
         * @param provider the provider value
         * @param endpoint the endpoint value
         * @return the built search attempts
         */
        private List<SearchAttempt> buildSearchAttempts(String provider, String endpoint) {
            List<SearchAttempt> attempts = new ArrayList<>();
            attempts.add(new SearchAttempt(provider, endpoint));
            if (!properties.isFallbackEnabled()) {
                return attempts;
            }
            if (!"bing_html".equals(provider)) {
                attempts.add(new SearchAttempt("bing_html", "https://www.bing.com/search"));
            }
            if (!"duckduckgo_html".equals(provider)) {
                attempts.add(new SearchAttempt("duckduckgo_html", "https://duckduckgo.com/html/"));
            }
            return attempts;
        }

        /**
         * Builds the search message.
         *
         * @param result the result value
         * @return the built search message
         */
        private String buildSearchMessage(Map<String, Object> result) {
            Object referenceUrlsValue = result.get("reference_urls");
            if (!(referenceUrlsValue instanceof List<?> referenceUrls) || referenceUrls.isEmpty()) {
                return "Search completed successfully, but no reference URLs were found.";
            }
            StringBuilder message = new StringBuilder("Search completed successfully.\nTop 10 reference URLs:\n");
            int rank = 1;
            for (Object referenceUrl : referenceUrls) {
                if (referenceUrl instanceof String url && !url.isBlank()) {
                    message.append(rank++).append(". ").append(url).append('\n');
                }
            }
            Object pageExcerptCount = result.get("page_excerpt_count");
            if (pageExcerptCount instanceof Number count && count.intValue() > 0) {
                message.append("\nFetched page excerpts: ").append(count.intValue());
            }
            return message.toString().trim();
        }

        /**
         * Performs the fetch page excerpts operation.
         *
         * @param results the results value
         * @param query the query value
         * @return the operation result
         */
        private List<Map<String, Object>> fetchPageExcerpts(List<Map<String, Object>> results,
                                                            String query,
                                                            WebSearchRequestContext context,
                                                            List<Map<String, Object>> networkAudit) {
            if (!properties.isFetchPages() || results == null || results.isEmpty()) {
                return List.of();
            }
            int limit = Math.max(0, Math.min(properties.getMaxPagesToFetch(), results.size()));
            if (limit <= 0) {
                return List.of();
            }

            List<Map<String, Object>> excerpts = new ArrayList<>();
            int attempts = 0;
            for (Map<String, Object> result : results) {
                if (attempts >= limit || excerpts.size() >= limit) {
                    break;
                }
                String url = stringValue(result.get("url"));
                if (!isFetchablePageUrl(url)) {
                    result.put("pageFetched", false);
                    result.put("pageFetchError", "unsupported url");
                    continue;
                }
                if (!isAllowedUrl(url)) {
                    result.put("pageFetched", false);
                    result.put("pageFetchError", "domain not allowed");
                    addAudit(networkAudit, query, url, "page", null, 0, 0, "BLOCKED", "domain not allowed");
                    continue;
                }
                attempts++;
                try {
                    long startedAt = System.currentTimeMillis();
                    log.info("Web search page fetch started attempt={}/{} rank={} url={}",
                        attempts,
                        limit,
                        result.get("rank"),
                        url);
                    HtmlResponse response = sendHtmlRequest(
                        url,
                        Map.of(),
                        query,
                        "page",
                        context,
                        networkAudit
                    );
                    int statusCode = response.statusCode();
                    Document page = response.document();

                    String pageText = extractReadableText(page);
                    if (pageText.isBlank()) {
                        result.put("pageFetched", true);
                        result.put("pageContentAvailable", false);
                        continue;
                    }
                    String excerpt = buildTextExcerpt(pageText, query, properties.getPageExcerptChars());
                    result.put("pageFetched", true);
                    result.put("pageContentAvailable", true);
                    result.put("pageContentLength", pageText.length());
                    result.put("pageContentTruncated", pageText.length() > excerpt.length());
                    result.put("pageExcerpt", excerpt);

                    Map<String, Object> evidence = new LinkedHashMap<>();
                    evidence.put("rank", result.get("rank"));
                    evidence.put("title", firstNonBlank(stringValue(result.get("title")), page.title()));
                    evidence.put("url", url);
                    evidence.put("excerpt", excerpt);
                    excerpts.add(evidence);
                    log.info("Web search page fetch succeeded attempt={}/{} rank={} statusCode={} durationMs={} excerptChars={}",
                        attempts,
                        limit,
                        result.get("rank"),
                        statusCode,
                        Math.max(0L, System.currentTimeMillis() - startedAt),
                        excerpt.length());
                } catch (Exception ex) {
                    result.put("pageFetched", false);
                    result.put("pageFetchError", ex.getMessage());
                    log.warn("Web search page fetch failed attempt={}/{} rank={} url={} error={}",
                        attempts,
                        limit,
                        result.get("rank"),
                        url,
                        ex.getMessage());
                }
            }
            return excerpts;
        }

        private List<Map<String, Object>> discoverSiteSearchResults(List<Map<String, Object>> results,
                                                                    String query,
                                                                    int resultLimit,
                                                                    WebSearchRequestContext context,
                                                                    List<Map<String, Object>> networkAudit) {
            WebSearchToolProperties.SiteSearchProperties siteSearch = properties.getSiteSearch();
            if (siteSearch == null || !siteSearch.isEnabled() || results == null || results.isEmpty()) {
                return List.of();
            }
            int inspectLimit = Math.max(0, siteSearch.getMaxPagesToInspect());
            int secondaryLimit = Math.max(0, siteSearch.getMaxSecondaryPages());
            if (inspectLimit <= 0 || secondaryLimit <= 0) {
                return List.of();
            }

            List<Map<String, Object>> discovered = new ArrayList<>();
            Set<String> seenUrls = new LinkedHashSet<>();
            for (Map<String, Object> result : results) {
                String url = stringValue(result.get("url"));
                if (url != null && !url.isBlank()) {
                    seenUrls.add(normalizeComparableUrl(url));
                }
            }

            int inspected = 0;
            int secondaryRequests = 0;
            log.info("Web search site-search discovery started query={} inspectLimit={} secondaryLimit={} resultLimit={}",
                query,
                inspectLimit,
                secondaryLimit,
                resultLimit);
            for (Map<String, Object> result : results) {
                if (inspected >= inspectLimit || secondaryRequests >= secondaryLimit) {
                    break;
                }
                String pageUrl = stringValue(result.get("url"));
                if (!isFetchablePageUrl(pageUrl) || !isAllowedUrl(pageUrl)) {
                    continue;
                }
                inspected++;
                try {
                    log.info("Web search site-search inspect started inspected={}/{} url={}",
                        inspected,
                        inspectLimit,
                        pageUrl);
                    if (secondaryRequests < secondaryLimit && looksLikeShanghaiStockExchangeUrl(pageUrl)) {
                        secondaryRequests++;
                        List<Map<String, Object>> knownResults = runKnownSiteSearch(
                            null,
                            pageUrl,
                            query,
                            seenUrls,
                            Math.max(1, siteSearch.getMaxLinksPerPage()),
                            context,
                            networkAudit
                        );
                        if (!knownResults.isEmpty()) {
                            result.put("siteSearchAvailable", true);
                            result.put("siteSearchType", "known_jsonp");
                            result.put("siteSearchResultCount", knownResults.size());
                            discovered.addAll(knownResults);
                            log.info("Web search site-search known endpoint succeeded url={} resultCount={} totalDiscovered={}",
                                pageUrl,
                                knownResults.size(),
                                discovered.size());
                            continue;
                        }
                    }
                    HtmlResponse pageResponse = sendHtmlRequest(
                        pageUrl,
                        Map.of(),
                        query,
                        "site_search_page",
                        context,
                        networkAudit
                    );
                    boolean pageHasEvidence = pageContainsQueryEvidence(pageResponse.document(), query);
                    result.put("siteSearchPageContainsQuery", pageHasEvidence);
                    log.info("Web search site-search page inspected url={} queryEvidence={}", pageUrl, pageHasEvidence);
                    if (secondaryRequests < secondaryLimit && knownSearchEndpoint(pageResponse.document(), pageUrl) != null) {
                        secondaryRequests++;
                        List<Map<String, Object>> knownResults = runKnownSiteSearch(
                            pageResponse.document(),
                            pageUrl,
                            query,
                            seenUrls,
                            Math.max(1, siteSearch.getMaxLinksPerPage()),
                            context,
                            networkAudit
                        );
                        if (!knownResults.isEmpty()) {
                            result.put("siteSearchAvailable", true);
                            result.put("siteSearchType", "known_jsonp");
                            result.put("siteSearchResultCount", knownResults.size());
                            discovered.addAll(knownResults);
                            log.info("Web search site-search known endpoint succeeded url={} resultCount={} totalDiscovered={}",
                                pageUrl,
                                knownResults.size(),
                                discovered.size());
                            continue;
                        }
                    }
                    List<SiteSearchForm> forms = findSiteSearchForms(pageResponse.document(), pageUrl, query);
                    if (forms.isEmpty() && !pageHasEvidence) {
                        forms = discoverSearchFormsFromEntrypoints(
                            pageResponse.document(),
                            pageUrl,
                            query,
                            context,
                            networkAudit,
                            Math.max(0, inspectLimit - inspected)
                        );
                        inspected += Math.max(0, Math.min(inspectLimit - inspected, inspectedEntrypointCount(forms)));
                    }
                    if (forms.isEmpty()) {
                        result.put("siteSearchAvailable", false);
                        log.info("Web search site-search no searchable form found url={}", pageUrl);
                        continue;
                    }
                    result.put("siteSearchAvailable", true);
                    result.put("siteSearchFormCount", forms.size());
                    log.info("Web search site-search forms found url={} formCount={}", pageUrl, forms.size());
                    for (SiteSearchForm form : forms) {
                        if (secondaryRequests >= secondaryLimit || discovered.size() >= resultLimit) {
                            break;
                        }
                        secondaryRequests++;
                        log.info("Web search site-search submit started sourceUrl={} method={} actionUrl={} submittedUrl={}",
                            pageUrl,
                            form.method(),
                            form.actionUrl(),
                            form.submittedUrl());
                        HtmlResponse secondaryResponse = sendHtmlRequest(
                            form.actionUrl(),
                            form.parameters(),
                            query,
                            "site_search",
                            context,
                            networkAudit,
                            form.method()
                        );
                        List<Map<String, Object>> secondaryResults = parseSiteSearchLinks(
                            secondaryResponse.document(),
                            pageUrl,
                            form.submittedUrl(),
                            query,
                            seenUrls,
                            Math.max(1, siteSearch.getMaxLinksPerPage())
                        );
                        discovered.addAll(secondaryResults);
                        log.info("Web search site-search submit completed sourceUrl={} resultCount={} totalDiscovered={}",
                            pageUrl,
                            secondaryResults.size(),
                            discovered.size());
                    }
                } catch (Exception ex) {
                    result.put("siteSearchAvailable", false);
                    result.put("siteSearchError", ex.getMessage());
                    log.warn("Web search site-search enrichment failed url={} error={}", pageUrl, ex.getMessage());
                }
            }
            log.info("Web search site-search discovery completed query={} inspected={} secondaryRequests={} discovered={}",
                query,
                inspected,
                secondaryRequests,
                discovered.size());
            return discovered;
        }

        private List<SiteSearchForm> discoverSearchFormsFromEntrypoints(Document document,
                                                                        String pageUrl,
                                                                        String query,
                                                                        WebSearchRequestContext context,
                                                                        List<Map<String, Object>> networkAudit,
                                                                        int maxAdditionalInspections) {
            if (maxAdditionalInspections <= 0) {
                return List.of();
            }
            List<String> candidates = siteSearchEntrypoints(document, pageUrl);
            if (candidates.isEmpty()) {
                return List.of();
            }
            List<SiteSearchForm> forms = new ArrayList<>();
            Set<String> seenForms = new LinkedHashSet<>();
            int inspected = 0;
            for (String candidateUrl : candidates) {
                if (inspected >= maxAdditionalInspections || !isFetchablePageUrl(candidateUrl) || !isAllowedUrl(candidateUrl)) {
                    continue;
                }
                inspected++;
                try {
                    log.info("Web search site-search entrypoint inspect started sourceUrl={} entrypoint={}",
                        pageUrl,
                        candidateUrl);
                    HtmlResponse response = sendHtmlRequest(
                        candidateUrl,
                        Map.of(),
                        query,
                        "site_search_entrypoint",
                        contextWithReferer(context, pageUrl, query),
                        networkAudit
                    );
                    for (SiteSearchForm form : findSiteSearchForms(response.document(), candidateUrl, query)) {
                        String key = normalizeComparableUrl(form.submittedUrl());
                        if (seenForms.add(key)) {
                            forms.add(form);
                        }
                    }
                    log.info("Web search site-search entrypoint inspect completed entrypoint={} formCount={} totalForms={}",
                        candidateUrl,
                        forms.size(),
                        seenForms.size());
                    if (!forms.isEmpty()) {
                        break;
                    }
                } catch (Exception ex) {
                    log.warn("Web search site-search entrypoint inspect failed sourceUrl={} entrypoint={} error={}",
                        pageUrl,
                        candidateUrl,
                        ex.getMessage());
                }
            }
            return forms;
        }

        private int inspectedEntrypointCount(List<SiteSearchForm> forms) {
            if (forms == null || forms.isEmpty()) {
                return 0;
            }
            return (int) forms.stream()
                .map(SiteSearchForm::sourcePageUrl)
                .filter(value -> value != null && !value.isBlank())
                .distinct()
                .count();
        }

        private List<String> siteSearchEntrypoints(Document document, String pageUrl) {
            String origin = originOf(pageUrl);
            if (origin == null || origin.isBlank()) {
                return List.of();
            }
            Set<String> candidates = new LinkedHashSet<>();
            if (document != null) {
                for (Element link : document.select("a[href]")) {
                    String href = normalizeSearchUrl(link.absUrl("href"));
                    if (!isLikelySearchEntrypoint(href, link.text())) {
                        continue;
                    }
                    String host = hostOf(href);
                    String sourceHost = hostOf(pageUrl);
                    if (host != null && sourceHost != null && host.equalsIgnoreCase(sourceHost)) {
                        candidates.add(href);
                    }
                }
            }
            candidates.add(origin + "/");
            candidates.add(origin + "/search");
            candidates.add(origin + "/search/");
            candidates.add(origin + "/home/search");
            candidates.add(origin + "/home/search/");
            candidates.add(origin + "/search.html");
            candidates.add(origin + "/search/index.html");
            candidates.add(origin + "/sousuo");
            candidates.add(origin + "/sousuo/");
            String current = normalizeComparableUrl(pageUrl);
            return candidates.stream()
                .filter(url -> url != null && !url.isBlank())
                .filter(url -> !normalizeComparableUrl(url).equals(current))
                .toList();
        }

        private boolean pageContainsQueryEvidence(Document document, String query) {
            if (document == null || query == null || query.isBlank()) {
                return false;
            }
            String text = cleanHtmlText(document.title() + " " + document.body().text()).toLowerCase(Locale.ROOT);
            if (text.isBlank()) {
                return false;
            }
            List<String> terms = queryTerms(query);
            if (terms.isEmpty()) {
                return false;
            }
            int matched = 0;
            for (String term : terms) {
                if (text.contains(term.toLowerCase(Locale.ROOT))) {
                    matched++;
                }
            }
            return matched > 0 && (terms.size() <= 2 || matched >= Math.min(2, terms.size()));
        }

        private boolean isLikelySearchEntrypoint(String href, String label) {
            if (!isHttpUrl(href)) {
                return false;
            }
            String text = (href + " " + firstNonBlank(label, "")).toLowerCase(Locale.ROOT);
            return containsAny(text, List.of(
                "search",
                "query",
                "find",
                "keyword",
                "sousuo",
                "搜索",
                "检索",
                "查询",
                "站内"
            ));
        }

        private List<Map<String, Object>> runKnownSiteSearch(Document document,
                                                             String sourcePageUrl,
                                                             String query,
                                                             Set<String> seenUrls,
                                                             int maxLinks,
                                                             WebSearchRequestContext context,
                                                             List<Map<String, Object>> networkAudit) throws Exception {
            String endpoint = knownSearchEndpoint(document, sourcePageUrl);
            if (endpoint == null || endpoint.isBlank()) {
                return List.of();
            }
            String callback = "jsonpCallback" + Math.abs((query + ":" + sourcePageUrl).hashCode());
            Map<String, String> parameters = new LinkedHashMap<>();
            parameters.put("jsonCallBack", callback);
            parameters.put("keyword", query);
            parameters.put("page", "0");
            parameters.put("limit", String.valueOf(Math.max(1, maxLinks)));
            parameters.put("spaceId", "3");
            parameters.put("keywordPosition", "content");
            parameters.put("orderByKey", "score");
            parameters.put("orderByDirection", "DESC");
            parameters.put("searchMode", "fuzzy");
            parameters.put("channelId", "10001");

            WebSearchRequestContext siteContext = contextWithReferer(context, sourcePageUrl, query);
            String submittedUrl = urlWithQueryParams(endpoint, parameters);
            log.info("Web search site-search known endpoint request started sourceUrl={} endpoint={} query={}",
                sourcePageUrl,
                endpoint,
                query);
            HtmlResponse response = sendHtmlRequest(
                endpoint,
                parameters,
                query,
                "site_search_known",
                siteContext,
                networkAudit
            );
            String payload = response.document() == null ? "" : response.document().text();
            List<Map<String, Object>> results = parseKnownJsonpSearchResults(
                payload,
                sourcePageUrl,
                submittedUrl,
                query,
                seenUrls,
                maxLinks
            );
            log.info("Web search site-search known endpoint response sourceUrl={} statusCode={} resultCount={}",
                sourcePageUrl,
                response.statusCode(),
                results.size());
            return results;
        }

        private String knownSearchEndpoint(Document document, String sourcePageUrl) {
            if (looksLikeShanghaiStockExchangeUrl(sourcePageUrl)) {
                return "https://query.sse.com.cn/search/getESSearchDoc.do";
            }
            if (document == null) {
                return null;
            }
            String html = document.outerHtml();
            if (!html.contains("sseQueryURL") || !html.contains("getESSearchDoc.do")) {
                return null;
            }
            Matcher matcher = Pattern.compile("sseQueryURL\\s*=\\s*['\"]([^'\"]+)['\"]").matcher(html);
            if (!matcher.find()) {
                return null;
            }
            String base = normalizeProtocolRelativeUrl(matcher.group(1), sourcePageUrl);
            if (base == null || base.isBlank()) {
                return null;
            }
            if (!base.endsWith("/")) {
                base = base + "/";
            }
            return base + "search/getESSearchDoc.do";
        }

        private boolean looksLikeShanghaiStockExchangeUrl(String url) {
            String host = hostOf(url);
            return host != null && (host.equalsIgnoreCase("sse.com.cn") || host.toLowerCase(Locale.ROOT).endsWith(".sse.com.cn"));
        }

        @SuppressWarnings("unchecked")
        private List<Map<String, Object>> parseKnownJsonpSearchResults(String payload,
                                                                       String sourcePageUrl,
                                                                       String searchUrl,
                                                                       String query,
                                                                       Set<String> seenUrls,
                                                                       int maxLinks) throws IOException {
            String json = unwrapJsonp(payload);
            if (json == null || json.isBlank()) {
                return List.of();
            }
            Map<String, Object> root = objectMapper.readValue(json, Map.class);
            Object dataValue = root.get("data");
            if (!(dataValue instanceof Map<?, ?> data)) {
                return List.of();
            }
            Object listValue = data.get("knowledgeList");
            if (!(listValue instanceof List<?> knowledgeList)) {
                return List.of();
            }
            List<Map<String, Object>> results = new ArrayList<>();
            int rank = 1;
            for (Object value : knowledgeList) {
                if (results.size() >= maxLinks || !(value instanceof Map<?, ?> raw)) {
                    continue;
                }
                Map<String, Object> item = (Map<String, Object>) raw;
                String url = absoluteSiteUrl(firstNonBlank(
                    stringValue(item.get("url")),
                    extendValue(item.get("extend"), "CURL")
                ), sourcePageUrl);
                if (!isHttpUrl(url)) {
                    continue;
                }
                String comparable = normalizeComparableUrl(url);
                if (!seenUrls.add(comparable)) {
                    continue;
                }
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("rank", rank++);
                result.put("title", cleanHtmlText(firstNonBlank(stringValue(item.get("title")), url)));
                result.put("url", url);
                result.put("snippet", buildTextExcerpt(cleanHtmlText(stringValue(item.get("rtfContent"))), query,
                    Math.min(500, properties.getPageExcerptChars())));
                result.put("source", "site_search_known");
                result.put("sourcePageUrl", sourcePageUrl);
                result.put("siteSearchUrl", searchUrl);
                result.put("siteSearchEngine", "sse_ess_jsonp");
                result.put("publishedAt", item.get("createTime"));
                result.put("documentId", item.get("documentId"));
                result.put("score", item.get("score"));
                result.put("totalSize", data.get("totalSize"));
                result.put("securityCode", extendValue(item.get("extend"), "ZQDM"));
                result.put("securityName", extendValue(item.get("extend"), "GSJC"));
                results.add(result);
            }
            return results;
        }

        private String unwrapJsonp(String payload) {
            if (payload == null || payload.isBlank()) {
                return null;
            }
            String value = payload.trim();
            int start = value.indexOf('(');
            int end = value.lastIndexOf(')');
            if (start >= 0 && end > start) {
                return value.substring(start + 1, end);
            }
            return value.startsWith("{") ? value : null;
        }

        private String extendValue(Object extendValue, String name) {
            if (!(extendValue instanceof List<?> entries)) {
                return null;
            }
            for (Object entry : entries) {
                if (entry instanceof Map<?, ?> map && name.equals(stringValue(map.get("name")))) {
                    return stringValue(map.get("value"));
                }
            }
            return null;
        }

        private String cleanHtmlText(String value) {
            return value == null ? "" : Jsoup.parse(value).text().replaceAll("\\s+", " ").trim();
        }

        private String absoluteSiteUrl(String url, String sourcePageUrl) {
            if (url == null || url.isBlank()) {
                return null;
            }
            String value = url.trim();
            if (value.startsWith("http://") || value.startsWith("https://")) {
                return value;
            }
            if (value.startsWith("//")) {
                return "https:" + value;
            }
            if (value.startsWith("www.")) {
                return "https://" + value;
            }
            try {
                URI base = URI.create(sourcePageUrl);
                if (looksLikeShanghaiStockExchangeUrl(sourcePageUrl) && value.startsWith("/")) {
                    return "https://www.sse.com.cn" + value;
                }
                return base.resolve(value).toString();
            } catch (Exception ex) {
                return value;
            }
        }

        private String normalizeProtocolRelativeUrl(String value, String sourcePageUrl) {
            if (value == null || value.isBlank()) {
                return null;
            }
            String trimmed = value.trim();
            if (trimmed.startsWith("//")) {
                String scheme = "https";
                try {
                    scheme = firstNonBlank(URI.create(sourcePageUrl).getScheme(), "https");
                } catch (Exception ignored) {
                    // Use https by default for protocol-relative search APIs.
                }
                return scheme + ":" + trimmed;
            }
            if (trimmed.startsWith("/")) {
                try {
                    return URI.create(sourcePageUrl).resolve(trimmed).toString();
                } catch (Exception ignored) {
                    return trimmed;
                }
            }
            return trimmed;
        }

        private WebSearchRequestContext contextWithReferer(WebSearchRequestContext context, String sourcePageUrl, String query) {
            String referer = looksLikeShanghaiStockExchangeUrl(sourcePageUrl)
                ? "https://www.sse.com.cn/home/search/?webswd=" + URLEncoder.encode(query, StandardCharsets.UTF_8)
                : sourcePageUrl;
            return new WebSearchRequestContext(
                context.query(),
                context.tenantId(),
                context.taskId(),
                context.agentId(),
                context.proxyPool(),
                referer
            );
        }

        private List<SiteSearchForm> findSiteSearchForms(Document document, String pageUrl, String query) {
            if (document == null) {
                return List.of();
            }
            List<SiteSearchForm> forms = new ArrayList<>();
            Set<String> seen = new LinkedHashSet<>();
            for (Element form : document.select("form")) {
                Element queryInput = bestSearchInput(form);
                if (queryInput == null) {
                    continue;
                }
                String inputName = firstNonBlank(queryInput.attr("name"), queryInput.attr("id"));
                if (inputName == null || inputName.isBlank()) {
                    continue;
                }
                String actionUrl = form.absUrl("action");
                if (actionUrl == null || actionUrl.isBlank()) {
                    actionUrl = pageUrl;
                }
                if (!looksLikeSiteSearchForm(form, actionUrl, queryInput)) {
                    continue;
                }
                Map<String, String> parameters = formParameters(form, inputName, query);
                if (!parameters.containsKey(inputName)) {
                    parameters.put(inputName, query);
                }
                String method = firstNonBlank(form.attr("method"), "GET").trim().toUpperCase(Locale.ROOT);
                if (!"POST".equals(method)) {
                    method = "GET";
                }
                String submittedUrl = "POST".equals(method)
                    ? actionUrl
                    : urlWithQueryParams(actionUrl, parameters);
                String key = normalizeComparableUrl(urlWithQueryParams(actionUrl, parameters));
                if (seen.add(key)) {
                    forms.add(new SiteSearchForm(actionUrl, parameters, method, submittedUrl, pageUrl));
                }
            }
            return forms;
        }

        private Element bestSearchInput(Element form) {
            Element best = null;
            int bestScore = 0;
            for (Element input : form.select("input")) {
                String type = firstNonBlank(input.attr("type"), "text").toLowerCase(Locale.ROOT);
                if (Set.of("hidden", "submit", "button", "reset", "checkbox", "radio", "file", "image").contains(type)) {
                    continue;
                }
                String name = firstNonBlank(input.attr("name"), input.attr("id"));
                if (name == null || name.isBlank()) {
                    continue;
                }
                int score = searchInputScore(input);
                if (score > bestScore) {
                    best = input;
                    bestScore = score;
                }
            }
            return bestScore > 0 ? best : null;
        }

        private int searchInputScore(Element input) {
            int score = 0;
            String type = firstNonBlank(input.attr("type"), "text").toLowerCase(Locale.ROOT);
            if ("search".equals(type)) {
                score += 6;
            }
            String text = String.join(" ",
                input.attr("name"),
                input.attr("id"),
                input.attr("class"),
                input.attr("placeholder"),
                input.attr("aria-label")
            ).toLowerCase(Locale.ROOT);
            if (containsAny(text, properties.getSiteSearch().getInputNameHints())) {
                score += 5;
            }
            if (text.contains("搜索") || text.contains("检索") || text.contains("查询")
                || text.contains("股票") || text.contains("证券") || text.contains("代码")) {
                score += 5;
            }
            return score;
        }

        private boolean looksLikeSiteSearchForm(Element form, String actionUrl, Element input) {
            String text = String.join(" ",
                form.attr("id"),
                form.attr("class"),
                form.attr("role"),
                form.text(),
                actionUrl,
                input.attr("name"),
                input.attr("id"),
                input.attr("placeholder"),
                input.attr("aria-label")
            ).toLowerCase(Locale.ROOT);
            return containsAny(text, properties.getSiteSearch().getFormActionHints())
                || text.contains("搜索")
                || text.contains("检索")
                || text.contains("查询")
                || text.contains("股票")
                || text.contains("证券")
                || text.contains("代码");
        }

        private Map<String, String> formParameters(Element form, String queryInputName, String query) {
            Map<String, String> parameters = new LinkedHashMap<>();
            for (Element field : form.select("input[name], select[name], textarea[name]")) {
                String name = field.attr("name");
                if (name == null || name.isBlank()) {
                    continue;
                }
                String tag = field.tagName();
                String type = field.attr("type").toLowerCase(Locale.ROOT);
                if ("input".equals(tag) && Set.of("submit", "button", "reset", "file", "image").contains(type)) {
                    continue;
                }
                if ("input".equals(tag) && ("checkbox".equals(type) || "radio".equals(type)) && !field.hasAttr("checked")) {
                    continue;
                }
                String value = name.equals(queryInputName)
                    ? query
                    : firstNonBlank(field.val(), field.attr("value"));
                parameters.put(name, firstNonBlank(value, ""));
            }
            parameters.put(queryInputName, query);
            return parameters;
        }

        private List<Map<String, Object>> parseSiteSearchLinks(Document document,
                                                               String sourcePageUrl,
                                                               String searchUrl,
                                                               String query,
                                                               Set<String> seenUrls,
                                                               int maxLinks) {
            if (document == null || maxLinks <= 0) {
                return List.of();
            }
            List<Map<String, Object>> links = new ArrayList<>();
            Element scope = first(
                document.selectFirst("main"),
                document.selectFirst("[role=main]"),
                document.selectFirst("[class*=search]"),
                document.selectFirst("[id*=search]"),
                document.body()
            );
            if (scope == null) {
                return List.of();
            }
            int rank = 1;
            for (Element link : scope.select("a[href]")) {
                if (links.size() >= maxLinks) {
                    break;
                }
                String href = normalizeSearchUrl(link.absUrl("href"));
                if (!isUsefulSiteSearchLink(href, link.text(), sourcePageUrl, searchUrl, query)) {
                    continue;
                }
                String comparable = normalizeComparableUrl(href);
                if (!seenUrls.add(comparable)) {
                    continue;
                }
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("rank", rank++);
                item.put("title", firstNonBlank(link.text(), href));
                item.put("url", href);
                item.put("snippet", nearbyText(link, query));
                item.put("source", "site_search");
                item.put("sourcePageUrl", sourcePageUrl);
                item.put("siteSearchUrl", searchUrl);
                links.add(item);
            }
            return links;
        }

        private boolean isUsefulSiteSearchLink(String href,
                                               String text,
                                               String sourcePageUrl,
                                               String searchUrl,
                                               String query) {
            if (!isSupportedResultUrl(href)) {
                return false;
            }
            String normalized = normalizeComparableUrl(href);
            if (normalized.equals(normalizeComparableUrl(sourcePageUrl))
                || normalized.equals(normalizeComparableUrl(searchUrl))) {
                return false;
            }
            String host = hostOf(href);
            String sourceHost = hostOf(sourcePageUrl);
            if (host == null || sourceHost == null || !host.equalsIgnoreCase(sourceHost)) {
                return false;
            }
            String label = text == null ? "" : text.replaceAll("\\s+", " ").trim();
            if (label.isBlank() && isDocumentUrl(href)) {
                label = fileNameFromUrl(href);
            }
            if (label.length() < 2) {
                return false;
            }
            String lowerLabel = label.toLowerCase(Locale.ROOT);
            if (Set.of("home", "login", "more", "next", "previous", "首页", "登录", "更多", "下一页", "上一页")
                .contains(lowerLabel)) {
                return false;
            }
            String combined = (href + " " + label).toLowerCase(Locale.ROOT);
            for (String term : queryTerms(query)) {
                if (combined.contains(term.toLowerCase(Locale.ROOT))) {
                    return true;
                }
            }
            return isDocumentUrl(href) || label.length() >= 4;
        }

        private String nearbyText(Element link, String query) {
            Element container = first(
                link.closest("li"),
                link.closest("article"),
                link.closest(".result"),
                link.parent()
            );
            String text = container == null ? link.text() : container.text();
            return buildTextExcerpt(text, query, Math.min(500, properties.getPageExcerptChars()));
        }

        private List<Map<String, Object>> mergeSearchResults(List<Map<String, Object>> primary,
                                                             List<Map<String, Object>> secondary,
                                                             int limit) {
            List<Map<String, Object>> merged = new ArrayList<>();
            Set<String> seen = new LinkedHashSet<>();
            appendSearchResults(merged, seen, primary);
            appendSearchResults(merged, seen, secondary);
            int rank = 1;
            for (Map<String, Object> item : merged) {
                item.put("rank", rank++);
            }
            return merged.size() <= limit ? merged : new ArrayList<>(merged.subList(0, limit));
        }

        private void appendSearchResults(List<Map<String, Object>> merged,
                                         Set<String> seen,
                                         List<Map<String, Object>> source) {
            if (source == null) {
                return;
            }
            for (Map<String, Object> item : source) {
                String url = stringValue(item.get("url"));
                if (url == null || url.isBlank() || !seen.add(normalizeComparableUrl(url))) {
                    continue;
                }
                merged.add(new LinkedHashMap<>(item));
            }
        }

        private HtmlResponse sendHtmlRequest(String url,
                                             Map<String, String> queryParams,
                                             String query,
                                             String phase,
                                             WebSearchRequestContext context,
                                             List<Map<String, Object>> networkAudit) throws Exception {
            return sendHtmlRequest(url, queryParams, query, phase, context, networkAudit, "GET");
        }

        private HtmlResponse sendHtmlRequest(String url,
                                             Map<String, String> queryParams,
                                             String query,
                                             String phase,
                                             WebSearchRequestContext context,
                                             List<Map<String, Object>> networkAudit,
                                             String httpMethod) throws Exception {
            if (!isAllowedUrl(url)) {
                addAudit(networkAudit, query, url, phase, null, 0, 0, "BLOCKED", "domain not allowed");
                throw new IllegalArgumentException("Web search target domain is not allowed: " + hostOf(url));
            }
            String method = firstNonBlank(httpMethod, "GET").toUpperCase(Locale.ROOT);
            int maxAttempts = Math.max(1, properties.getRetry().getMaxAttempts());
            Exception lastException = null;
            for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                WebSearchToolProperties.ProxyConfig proxy = chooseProxy(context, attempt);
                long startedAt = System.currentTimeMillis();
                boolean rateAcquired = false;
                try {
                    rateAcquired = acquireRateSlot();
                    String cookieKey = cookieKey(context, proxy);
                    HtmlResponse browserResponse = tryLocalBrowserRequest(
                        url,
                        queryParams,
                        query,
                        phase,
                        context,
                        proxy,
                        startedAt,
                        networkAudit,
                        method
                    );
                    if (browserResponse != null) {
                        markProxySuccess(proxy);
                        return browserResponse;
                    }

                    org.jsoup.Connection connection = buildJsoupConnection(url, queryParams, phase, context, proxy, method);
                    Map<String, String> cookies = readCookies(cookieKey);
                    if (!cookies.isEmpty()) {
                        connection.cookies(cookies);
                    }
                    org.jsoup.Connection.Response response = connection.execute();
                    updateCookies(cookieKey, response.cookies());
                    int statusCode = response.statusCode();
                    String body = response.body();
                    long durationMs = Math.max(0L, System.currentTimeMillis() - startedAt);
                    addAudit(networkAudit, query, response.url().toString(), phase, proxyId(proxy), statusCode,
                        durationMs, "OK", null);
                    logWebSearchAudit(query, response.url().toString(), phase, proxy, statusCode, durationMs, null);
                    if (shouldRetry(statusCode, body)) {
                        markProxyFailure(proxy);
                        if (attempt < maxAttempts) {
                            sleepBackoff(attempt);
                            continue;
                        }
                        throw new IOException("HTTP " + statusCode + " from " + hostOf(url));
                    }
                    markProxySuccess(proxy);
                    return new HtmlResponse(statusCode, response.parse());
                } catch (Exception ex) {
                    lastException = ex;
                    markProxyFailure(proxy);
                    long durationMs = Math.max(0L, System.currentTimeMillis() - startedAt);
                    addAudit(networkAudit, query, url, phase, proxyId(proxy), 0, durationMs, "FAILED", ex.getMessage());
                    logWebSearchAudit(query, url, phase, proxy, 0, durationMs, ex.getMessage());
                    if (attempt < maxAttempts) {
                        sleepBackoff(attempt);
                    }
                } finally {
                    if (rateAcquired) {
                        rateSemaphore.release();
                    }
                }
            }
            throw lastException == null ? new IOException("web search request failed") : lastException;
        }

        private org.jsoup.Connection buildJsoupConnection(String url,
                                                          Map<String, String> queryParams,
                                                          String phase,
                                                          WebSearchRequestContext context,
                                                          WebSearchToolProperties.ProxyConfig proxy,
                                                          String httpMethod) {
            org.jsoup.Connection connection = Jsoup.connect(url)
                .timeout(Math.max(1000, properties.getTimeoutMs()))
                .maxBodySize(Math.max(1024, properties.getPageMaxBytes()))
                .ignoreHttpErrors(true)
                .ignoreContentType(true)
                .followRedirects(true);
            if ("POST".equalsIgnoreCase(httpMethod)) {
                connection.method(org.jsoup.Connection.Method.POST);
            }
            if (queryParams != null && !queryParams.isEmpty()) {
                connection.data(queryParams);
            }
            applyBrowserHeaders(connection, url, phase, context, proxy);
            applyProxy(connection, proxy);
            return connection;
        }

        private HtmlResponse tryLocalBrowserRequest(String url,
                                                    Map<String, String> queryParams,
                                                    String query,
                                                    String phase,
                                                    WebSearchRequestContext context,
                                                    WebSearchToolProperties.ProxyConfig proxy,
                                                    long startedAt,
                                                    List<Map<String, Object>> networkAudit,
                                                    String httpMethod) {
            if (!"GET".equalsIgnoreCase(firstNonBlank(httpMethod, "GET")) || !localBrowserEnabled()) {
                return null;
            }
            String executable = resolveBrowserExecutable();
            if (executable == null || executable.isBlank()) {
                return null;
            }
            String targetUrl = urlWithQueryParams(url, queryParams);
            try {
                HtmlResponse response = executeLocalBrowser(executable, targetUrl, context, proxy);
                long durationMs = Math.max(0L, System.currentTimeMillis() - startedAt);
                addAudit(networkAudit, query, targetUrl, phase, proxyId(proxy), response.statusCode(), durationMs,
                    "OK", "local browser");
                logWebSearchAudit(query, targetUrl, phase, proxy, response.statusCode(), durationMs, null);
                return response;
            } catch (Exception ex) {
                log.warn("Local browser web_search request failed executable={} url={} error={}",
                    executable, targetUrl, ex.getMessage());
                return null;
            }
        }

        private boolean localBrowserEnabled() {
            WebSearchToolProperties.BrowserProperties browser = properties.getBrowser();
            return browser != null && browser.isEnabled() && browser.isLocalBrowserEnabled();
        }

        private HtmlResponse executeLocalBrowser(String executable,
                                                 String targetUrl,
                                                 WebSearchRequestContext context,
                                                 WebSearchToolProperties.ProxyConfig proxy) throws Exception {
            Path userDataDir = Files.createTempDirectory("chatchat-web-search-browser-");
            try {
                List<String> command = new ArrayList<>();
                WebSearchToolProperties.BrowserProperties browser = properties.getBrowser();
                command.add(executable);
                command.add("--headless=new");
                command.add("--disable-gpu");
                command.add("--disable-extensions");
                command.add("--disable-background-networking");
                command.add("--disable-sync");
                command.add("--disable-features=Translate,OptimizationHints");
                command.add("--no-first-run");
                command.add("--no-default-browser-check");
                command.add("--disable-dev-shm-usage");
                if (browser.isNoSandbox()) {
                    command.add("--no-sandbox");
                }
                command.add("--user-data-dir=" + userDataDir.toAbsolutePath());
                command.add("--window-size=1365,768");
                command.add("--lang=" + browserLanguage(browser.getAcceptLanguage()));
                command.add("--user-agent=" + selectUserAgent(context));
                String proxyArgument = browserProxyArgument(proxy);
                if (proxyArgument != null) {
                    command.add("--proxy-server=" + proxyArgument);
                }
                command.add("--dump-dom");
                command.add(targetUrl);

                ProcessBuilder processBuilder = new ProcessBuilder(command);
                processBuilder.redirectErrorStream(true);
                Process process = processBuilder.start();
                CompletableFuture<String> outputFuture =
                    CompletableFuture.supplyAsync(() -> readProcessOutput(process));
                int timeoutMs = Math.max(1000, browser.getProcessTimeoutMs());
                boolean finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
                if (!finished) {
                    process.destroyForcibly();
                    throw new IOException("local browser timed out after " + timeoutMs + " ms");
                }
                String output = outputFuture.get(1, TimeUnit.SECONDS);
                int exitCode = process.exitValue();
                if (exitCode != 0 && (output == null || output.isBlank())) {
                    throw new IOException("local browser exited with code " + exitCode);
                }
                String html = output == null ? "" : output;
                if (html.isBlank()) {
                    throw new IOException("local browser returned empty DOM");
                }
                return new HtmlResponse(exitCode == 0 ? 200 : 0, Jsoup.parse(html, targetUrl));
            } finally {
                deleteDirectory(userDataDir);
            }
        }

        private String readProcessOutput(Process process) {
            try {
                return new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException ex) {
                return "";
            }
        }

        private String resolveBrowserExecutable() {
            WebSearchToolProperties.BrowserProperties browser = properties.getBrowser();
            String configured = firstNonBlank(browser.getExecutablePath(), osSpecificBrowserPath(browser));
            String resolved = resolveExecutable(configured);
            if (resolved != null) {
                return resolved;
            }
            if (browser.getExecutablePaths() != null) {
                for (String candidate : browser.getExecutablePaths()) {
                    resolved = resolveExecutable(candidate);
                    if (resolved != null) {
                        return resolved;
                    }
                }
            }
            for (String candidate : defaultBrowserCandidates()) {
                resolved = resolveExecutable(candidate);
                if (resolved != null) {
                    return resolved;
                }
            }
            return null;
        }

        private String osSpecificBrowserPath(WebSearchToolProperties.BrowserProperties browser) {
            return isWindows()
                ? browser.getWindowsExecutablePath()
                : browser.getLinuxExecutablePath();
        }

        private List<String> defaultBrowserCandidates() {
            List<String> candidates = new ArrayList<>();
            if (isWindows()) {
                addIfPresent(candidates, System.getenv("PROGRAMFILES"), "Google\\Chrome\\Application\\chrome.exe");
                addIfPresent(candidates, System.getenv("PROGRAMFILES(X86)"), "Google\\Chrome\\Application\\chrome.exe");
                addIfPresent(candidates, System.getenv("LOCALAPPDATA"), "Google\\Chrome\\Application\\chrome.exe");
                addIfPresent(candidates, System.getenv("PROGRAMFILES"), "Microsoft\\Edge\\Application\\msedge.exe");
                addIfPresent(candidates, System.getenv("PROGRAMFILES(X86)"), "Microsoft\\Edge\\Application\\msedge.exe");
                candidates.add("chrome.exe");
                candidates.add("msedge.exe");
            } else {
                candidates.add("/usr/bin/google-chrome");
                candidates.add("/usr/bin/google-chrome-stable");
                candidates.add("/usr/bin/chromium");
                candidates.add("/usr/bin/chromium-browser");
                candidates.add("/snap/bin/chromium");
                candidates.add("google-chrome");
                candidates.add("chromium");
                candidates.add("chromium-browser");
            }
            return candidates;
        }

        private void addIfPresent(List<String> candidates, String basePath, String relativePath) {
            if (basePath != null && !basePath.isBlank()) {
                candidates.add(Path.of(basePath, relativePath).toString());
            }
        }

        private String resolveExecutable(String candidate) {
            if (candidate == null || candidate.isBlank()) {
                return null;
            }
            String normalized = candidate.trim();
            Path directPath = Path.of(normalized);
            if (directPath.isAbsolute() || directPath.getParent() != null) {
                return Files.isRegularFile(directPath) ? directPath.toString() : null;
            }
            String path = System.getenv("PATH");
            if (path == null || path.isBlank()) {
                return normalized;
            }
            List<String> extensions = isWindows()
                ? List.of("", ".exe", ".cmd", ".bat")
                : List.of("");
            for (String directory : path.split(Pattern.quote(System.getProperty("path.separator")))) {
                if (directory == null || directory.isBlank()) {
                    continue;
                }
                for (String extension : extensions) {
                    Path resolved = Path.of(directory, normalized + extension);
                    if (Files.isRegularFile(resolved) && Files.isExecutable(resolved)) {
                        return resolved.toString();
                    }
                }
            }
            return null;
        }

        private boolean isWindows() {
            return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        }

        private String browserLanguage(String acceptLanguage) {
            String value = firstNonBlank(acceptLanguage, "en-US");
            int separator = value.indexOf(',');
            return separator > 0 ? value.substring(0, separator).trim() : value.trim();
        }

        private String browserProxyArgument(WebSearchToolProperties.ProxyConfig proxy) {
            if (!properties.getProxyPool().isEnabled()
                || proxy == null
                || proxy.getHost() == null
                || proxy.getHost().isBlank()
                || proxy.getPort() <= 0) {
                return null;
            }
            String type = firstNonBlank(proxy.getType(), "HTTP").toLowerCase(Locale.ROOT);
            String scheme = type.contains("socks") ? "socks5" : "http";
            return scheme + "://" + proxy.getHost().trim() + ":" + proxy.getPort();
        }

        private String urlWithQueryParams(String url, Map<String, String> queryParams) {
            if (queryParams == null || queryParams.isEmpty()) {
                return url;
            }
            StringBuilder builder = new StringBuilder(url);
            builder.append(url.contains("?") ? "&" : "?");
            boolean first = true;
            for (Map.Entry<String, String> entry : queryParams.entrySet()) {
                if (entry.getKey() == null || entry.getKey().isBlank()) {
                    continue;
                }
                if (!first) {
                    builder.append('&');
                }
                builder.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8));
                builder.append('=');
                builder.append(URLEncoder.encode(firstNonBlank(entry.getValue(), ""), StandardCharsets.UTF_8));
                first = false;
            }
            return builder.toString();
        }

        private void deleteDirectory(Path directory) {
            if (directory == null || !Files.exists(directory)) {
                return;
            }
            try (var stream = Files.walk(directory)) {
                stream.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException ignored) {
                        // Best effort cleanup for transient browser profile files.
                    }
                });
            } catch (IOException ignored) {
                // Best effort cleanup for transient browser profile files.
            }
        }

        private void applyBrowserHeaders(org.jsoup.Connection connection,
                                         String url,
                                         String phase,
                                         WebSearchRequestContext context,
                                         WebSearchToolProperties.ProxyConfig proxy) {
            WebSearchToolProperties.BrowserProperties browser = properties.getBrowser();
            String userAgent = selectUserAgent(context);
            connection.userAgent(userAgent);
            if (!browser.isEnabled()) {
                return;
            }
            connection.header("Accept", browser.getAccept());
            connection.header("Accept-Language", browser.getAcceptLanguage());
            connection.header("Accept-Encoding", "gzip, deflate, br");
            connection.header("Cache-Control", "no-cache");
            connection.header("Pragma", "no-cache");
            connection.header("Upgrade-Insecure-Requests", "1");
            connection.header("DNT", "1");
            connection.header("Sec-Fetch-Dest", "document");
            connection.header("Sec-Fetch-Mode", "navigate");
            connection.header("Sec-Fetch-Site", "search".equals(phase) ? "none" : "cross-site");
            connection.header("Sec-Fetch-User", "?1");
            connection.header("sec-ch-ua", browser.getSecChUa());
            connection.header("sec-ch-ua-mobile", browser.getSecChUaMobile());
            connection.header("sec-ch-ua-platform", browser.getSecChUaPlatform());
            String referer = firstNonBlank(context.referer(), browser.getReferer());
            if (referer != null && !referer.isBlank()) {
                connection.referrer(referer);
            }
            browser.getHeaders().forEach(connection::header);
            if (proxy != null && proxy.getUsername() != null && !proxy.getUsername().isBlank()) {
                String token = Base64.getEncoder().encodeToString(
                    (proxy.getUsername() + ":" + firstNonBlank(proxy.getPassword(), "")).getBytes(StandardCharsets.UTF_8));
                connection.header("Proxy-Authorization", "Basic " + token);
            }
            connection.header("X-ChatChat-Search-Task", context.taskId() == null ? "" : context.taskId());
            connection.header("X-ChatChat-TLS-Profile", firstNonBlank(browser.getTlsFingerprintProfile(), "jdk-default"));
        }

        private void applyProxy(org.jsoup.Connection connection, WebSearchToolProperties.ProxyConfig proxyConfig) {
            Proxy proxy = toProxy(proxyConfig);
            if (proxy != null) {
                connection.proxy(proxy);
            }
        }

        private Proxy toProxy(WebSearchToolProperties.ProxyConfig config) {
            if (!properties.getProxyPool().isEnabled()
                || config == null
                || config.getHost() == null
                || config.getHost().isBlank()
                || config.getPort() <= 0) {
                return null;
            }
            String type = config.getType() == null ? "HTTP" : config.getType().trim().toUpperCase(Locale.ROOT);
            Proxy.Type proxyType = "SOCKS5".equals(type) || "SOCKS".equals(type) ? Proxy.Type.SOCKS : Proxy.Type.HTTP;
            return new Proxy(proxyType, new InetSocketAddress(config.getHost().trim(), config.getPort()));
        }

        private WebSearchToolProperties.ProxyConfig chooseProxy(WebSearchRequestContext context, int attempt) {
            if (!properties.getProxyPool().isEnabled() || properties.getProxyPool().getProxies().isEmpty()) {
                return null;
            }
            List<WebSearchToolProperties.ProxyConfig> candidates = properties.getProxyPool().getProxies().stream()
                .filter(proxy -> matchesProxyScope(proxy, context))
                .toList();
            if (candidates.isEmpty()) {
                candidates = properties.getProxyPool().getProxies();
            }
            long now = System.currentTimeMillis();
            int size = candidates.size();
            int offset = Math.floorMod(proxyCursor.getAndIncrement() + Math.max(0, attempt - 1), size);
            for (int index = 0; index < size; index++) {
                WebSearchToolProperties.ProxyConfig candidate = candidates.get((offset + index) % size);
                ProxyRuntimeState state = proxyState(candidate);
                if (state.openUntilMs <= now) {
                    state.requestCount.incrementAndGet();
                    return candidate;
                }
            }
            WebSearchToolProperties.ProxyConfig fallback = candidates.get(offset % size);
            proxyState(fallback).requestCount.incrementAndGet();
            return fallback;
        }

        private boolean matchesProxyScope(WebSearchToolProperties.ProxyConfig proxy, WebSearchRequestContext context) {
            boolean tenantMatches = proxy.getTenantIds() == null
                || proxy.getTenantIds().isEmpty()
                || (context.tenantId() != null && proxy.getTenantIds().contains(context.tenantId()));
            boolean taskMatches = proxy.getTaskIds() == null
                || proxy.getTaskIds().isEmpty()
                || (context.taskId() != null && proxy.getTaskIds().contains(context.taskId()));
            String pool = firstNonBlank(context.proxyPool(), properties.getProxyPool().getDefaultPool());
            boolean poolMatches = pool == null || pool.isBlank() || pool.equalsIgnoreCase(firstNonBlank(proxy.getPool(), "default"));
            return tenantMatches && taskMatches && poolMatches;
        }

        private ProxyRuntimeState proxyState(WebSearchToolProperties.ProxyConfig proxy) {
            String id = proxyId(proxy);
            synchronized (proxyStates) {
                return proxyStates.computeIfAbsent(id, ignored -> new ProxyRuntimeState());
            }
        }

        private void markProxySuccess(WebSearchToolProperties.ProxyConfig proxy) {
            if (proxy == null) {
                return;
            }
            ProxyRuntimeState state = proxyState(proxy);
            state.failureCount.set(0);
            state.openUntilMs = 0L;
        }

        private void markProxyFailure(WebSearchToolProperties.ProxyConfig proxy) {
            if (proxy == null) {
                return;
            }
            ProxyRuntimeState state = proxyState(proxy);
            int failures = state.failureCount.incrementAndGet();
            if (failures >= 2) {
                state.openUntilMs = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(30);
            }
        }

        private boolean acquireRateSlot() throws InterruptedException {
            if (!properties.getRateLimit().isEnabled()) {
                return false;
            }
            resetDailyWindowIfNeeded();
            int dailyLimit = Math.max(1, properties.getRateLimit().getDailyLimit());
            if (dailyCalls.incrementAndGet() > dailyLimit) {
                throw new IllegalStateException("web_search daily call limit exceeded: " + dailyLimit);
            }
            rateSemaphore.acquire();
            try {
                double qps = Math.max(0.1d, properties.getRateLimit().getQps());
                long minIntervalMs = Math.max(1L, Math.round(1000.0d / qps));
                synchronized (rateLock) {
                    long now = System.currentTimeMillis();
                    long waitMs = lastRequestAtMs + minIntervalMs - now;
                    if (waitMs > 0) {
                        Thread.sleep(waitMs);
                    }
                    lastRequestAtMs = System.currentTimeMillis();
                }
                return true;
            } catch (InterruptedException | RuntimeException ex) {
                rateSemaphore.release();
                throw ex;
            }
        }

        private void resetDailyWindowIfNeeded() {
            LocalDate today = LocalDate.now();
            if (!today.equals(dailyWindow)) {
                synchronized (rateLock) {
                    if (!today.equals(dailyWindow)) {
                        dailyWindow = today;
                        dailyCalls.set(0);
                    }
                }
            }
        }

        private boolean shouldRetry(int statusCode, String body) {
            if (properties.getRetry().getRetryStatusCodes().contains(statusCode)) {
                return true;
            }
            if (body == null || body.isBlank()) {
                return false;
            }
            String lower = body.toLowerCase(Locale.ROOT);
            return properties.getRetry().getRetryBodyKeywords().stream()
                .filter(keyword -> keyword != null && !keyword.isBlank())
                .map(keyword -> keyword.toLowerCase(Locale.ROOT))
                .anyMatch(lower::contains);
        }

        private void sleepBackoff(int attempt) throws InterruptedException {
            long backoffMs = Math.max(0L, properties.getRetry().getBackoffMs());
            if (backoffMs > 0) {
                Thread.sleep(backoffMs * Math.max(1, attempt));
            }
        }

        private WebSearchRequestContext requestContext(ToolInput input, String query) {
            Map<String, Object> parameters = input == null || input.getParameters() == null
                ? Map.of()
                : input.getParameters();
            return new WebSearchRequestContext(
                query,
                firstNonBlank(stringValue(parameters.get("tenantId")), stringValue(parameters.get("tenant_id"))),
                firstNonBlank(stringValue(parameters.get("sourceTaskId")), stringValue(parameters.get("taskId"))),
                stringValue(parameters.get("agentId")),
                stringValue(parameters.get("proxyPool")),
                stringValue(parameters.get("referer"))
            );
        }

        private String selectUserAgent(WebSearchRequestContext context) {
            List<String> userAgents = properties.getBrowser().getUserAgents();
            if (userAgents != null && !userAgents.isEmpty()) {
                int index = Math.abs((context.query() + ":" + stringValue(context.taskId())).hashCode()) % userAgents.size();
                return userAgents.get(index);
            }
            return firstNonBlank(properties.getUserAgent(), "Mozilla/5.0");
        }

        private String cookieKey(WebSearchRequestContext context, WebSearchToolProperties.ProxyConfig proxy) {
            if (!properties.getCookie().isEnabled()) {
                return "disabled";
            }
            String isolation = firstNonBlank(properties.getCookie().getIsolation(), "proxy_task").toLowerCase(Locale.ROOT);
            List<String> parts = new ArrayList<>();
            if (isolation.contains("proxy")) {
                parts.add(proxyId(proxy));
            }
            if (isolation.contains("tenant")) {
                parts.add(firstNonBlank(context.tenantId(), "tenant-default"));
            }
            if (isolation.contains("task")) {
                parts.add(firstNonBlank(context.taskId(), "task-default"));
            }
            if (parts.isEmpty()) {
                parts.add("global");
            }
            return String.join(":", parts);
        }

        private Map<String, String> readCookies(String key) {
            if (!properties.getCookie().isEnabled()) {
                return Map.of();
            }
            synchronized (cookieLock) {
                return new LinkedHashMap<>(cookieJar.getOrDefault(key, Map.of()));
            }
        }

        private void updateCookies(String key, Map<String, String> cookies) {
            if (!properties.getCookie().isEnabled() || cookies == null || cookies.isEmpty()) {
                return;
            }
            synchronized (cookieLock) {
                Map<String, String> values = new LinkedHashMap<>(cookieJar.getOrDefault(key, Map.of()));
                values.putAll(cookies);
                cookieJar.put(key, values);
                saveCookies();
            }
        }

        @SuppressWarnings("unchecked")
        private void loadCookies() {
            if (!properties.getCookie().isEnabled() || !properties.getCookie().isPersist()) {
                return;
            }
            try {
                Path path = Path.of(properties.getCookie().getStorePath());
                if (!Files.exists(path)) {
                    return;
                }
                Map<String, Map<String, String>> loaded = objectMapper.readValue(path.toFile(), Map.class);
                cookieJar.clear();
                loaded.forEach((key, value) -> cookieJar.put(key, new LinkedHashMap<>(value)));
            } catch (Exception ex) {
                log.warn("Failed to load web_search cookies: {}", ex.getMessage());
            }
        }

        private void saveCookies() {
            if (!properties.getCookie().isPersist()) {
                return;
            }
            try {
                Path path = Path.of(properties.getCookie().getStorePath());
                Path parent = path.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                objectMapper.writeValue(path.toFile(), cookieJar);
            } catch (Exception ex) {
                log.warn("Failed to persist web_search cookies: {}", ex.getMessage());
            }
        }

        private boolean isAllowedUrl(String url) {
            if (!properties.getAllowList().isEnabled()) {
                return true;
            }
            String host = hostOf(url);
            if (host == null || host.isBlank()) {
                return false;
            }
            String normalizedHost = host.toLowerCase(Locale.ROOT);
            return properties.getAllowList().getDomains().stream()
                .filter(domain -> domain != null && !domain.isBlank())
                .map(domain -> domain.trim().toLowerCase(Locale.ROOT))
                .anyMatch(domain -> normalizedHost.equals(domain) || normalizedHost.endsWith("." + domain));
        }

        private String hostOf(String url) {
            try {
                return URI.create(url).getHost();
            } catch (Exception ex) {
                return null;
            }
        }

        private String originOf(String url) {
            try {
                URI uri = URI.create(url);
                String scheme = firstNonBlank(uri.getScheme(), "https").toLowerCase(Locale.ROOT);
                String host = uri.getHost();
                if (host == null || host.isBlank()) {
                    return null;
                }
                int port = uri.getPort();
                StringBuilder builder = new StringBuilder(scheme).append("://").append(host);
                if (port > 0 && !(("http".equals(scheme) && port == 80) || ("https".equals(scheme) && port == 443))) {
                    builder.append(':').append(port);
                }
                return builder.toString();
            } catch (Exception ex) {
                return null;
            }
        }

        private String proxyId(WebSearchToolProperties.ProxyConfig proxy) {
            if (proxy == null) {
                return "direct";
            }
            if (proxy.getId() != null && !proxy.getId().isBlank()) {
                return proxy.getId();
            }
            return firstNonBlank(proxy.getType(), "HTTP") + "://" + proxy.getHost() + ":" + proxy.getPort();
        }

        private void addAudit(List<Map<String, Object>> audit,
                              String query,
                              String url,
                              String phase,
                              String proxyId,
                              int statusCode,
                              long durationMs,
                              String outcome,
                              String errorMessage) {
            if (!properties.getAudit().isEnabled() || audit == null) {
                return;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("time", java.time.Instant.now().toString());
            item.put("keyword", query);
            item.put("phase", phase);
            item.put("targetDomain", hostOf(url));
            item.put("proxyId", firstNonBlank(proxyId, "direct"));
            item.put("statusCode", statusCode);
            item.put("durationMs", durationMs);
            item.put("outcome", outcome);
            item.put("errorMessage", errorMessage);
            audit.add(item);
        }

        private void logWebSearchAudit(String query,
                                       String url,
                                       String phase,
                                       WebSearchToolProperties.ProxyConfig proxy,
                                       int statusCode,
                                       long durationMs,
                                       String errorMessage) {
            if (!properties.getAudit().isEnabled()) {
                return;
            }
            log.info("Web search audit keyword={} phase={} domain={} proxyId={} statusCode={} durationMs={} error={}",
                query,
                phase,
                hostOf(url),
                proxyId(proxy),
                statusCode,
                durationMs,
                errorMessage);
        }

        /**
         * Returns whether is fetchable page url.
         *
         * @param url the url value
         * @return whether the condition is satisfied
         */
        private boolean isFetchablePageUrl(String url) {
            if (url == null || url.isBlank()) {
                return false;
            }
            String value = url.toLowerCase(Locale.ROOT);
            if (!isHttpUrl(value)) {
                return false;
            }
            return !(value.endsWith(".pdf")
                || value.endsWith(".doc")
                || value.endsWith(".docx")
                || value.endsWith(".xls")
                || value.endsWith(".xlsx")
                || value.endsWith(".zip")
                || value.endsWith(".rar")
                || value.endsWith(".7z"));
        }

        private boolean isSupportedResultUrl(String url) {
            if (!isHttpUrl(url)) {
                return false;
            }
            String value = stripUrlQueryAndFragment(url).toLowerCase(Locale.ROOT);
            return !(value.endsWith(".zip")
                || value.endsWith(".rar")
                || value.endsWith(".7z")
                || value.endsWith(".tar")
                || value.endsWith(".gz"));
        }

        private boolean isDocumentUrl(String url) {
            if (!isHttpUrl(url)) {
                return false;
            }
            String value = stripUrlQueryAndFragment(url).toLowerCase(Locale.ROOT);
            return value.endsWith(".pdf")
                || value.endsWith(".doc")
                || value.endsWith(".docx")
                || value.endsWith(".xls")
                || value.endsWith(".xlsx")
                || value.endsWith(".csv")
                || value.endsWith(".ppt")
                || value.endsWith(".pptx");
        }

        private String fileNameFromUrl(String url) {
            try {
                String path = URI.create(url).getPath();
                if (path == null || path.isBlank()) {
                    return url;
                }
                int slash = path.lastIndexOf('/');
                return slash >= 0 ? path.substring(slash + 1) : path;
            } catch (Exception ex) {
                return url;
            }
        }

        private String stripUrlQueryAndFragment(String url) {
            if (url == null) {
                return "";
            }
            int query = url.indexOf('?');
            int fragment = url.indexOf('#');
            int cut = -1;
            if (query >= 0 && fragment >= 0) {
                cut = Math.min(query, fragment);
            } else if (query >= 0) {
                cut = query;
            } else if (fragment >= 0) {
                cut = fragment;
            }
            return cut >= 0 ? url.substring(0, cut) : url;
        }

        private boolean isHttpUrl(String url) {
            if (url == null || url.isBlank()) {
                return false;
            }
            String value = url.toLowerCase(Locale.ROOT);
            return value.startsWith("http://") || value.startsWith("https://");
        }

        /**
         * Performs the extract readable text operation.
         *
         * @param page the page value
         * @return the operation result
         */
        private String extractReadableText(Document page) {
            page.select("script,style,noscript,svg,canvas,iframe,header,footer,nav,aside,form").remove();
            Element main = first(
                page.selectFirst("main"),
                page.selectFirst("article"),
                page.selectFirst("[role=main]"),
                page.body()
            );
            return main == null ? "" : main.text().replaceAll("\\s+", " ").trim();
        }

        /**
         * Builds the text excerpt.
         *
         * @param text the text value
         * @param query the query value
         * @param maxChars the max chars value
         * @return the built text excerpt
         */
        private String buildTextExcerpt(String text, String query, int maxChars) {
            String normalized = text == null ? "" : text.replaceAll("\\s+", " ").trim();
            int limit = Math.max(500, Math.min(8000, maxChars));
            if (normalized.length() <= limit) {
                return normalized;
            }
            int start = bestExcerptStart(normalized, query, limit);
            int end = Math.min(normalized.length(), start + limit);
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
         * @param text the text value
         * @param query the query value
         * @param maxChars the max chars value
         * @return the operation result
         */
        private int bestExcerptStart(String text, String query, int maxChars) {
            String lowerText = text.toLowerCase(Locale.ROOT);
            for (String term : queryTerms(query)) {
                int index = lowerText.indexOf(term.toLowerCase(Locale.ROOT));
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
            for (String term : query.trim().split("[\\s,，。；;:：/\\\\]+")) {
                if (term.length() >= 2) {
                    terms.add(term);
                }
            }
            terms.sort((left, right) -> Integer.compare(right.length(), left.length()));
            return terms;
        }

        /**
         * Parses the duck duck go results.
         *
         * @param document the document value
         * @param numResults the num results value
         * @return the parsed duck duck go results
         */
        private List<Map<String, Object>> parseDuckDuckGoResults(Document document, int numResults) {
            List<Map<String, Object>> results = new ArrayList<>();
            for (Element block : document.select(".result, .web-result")) {
                if (results.size() >= numResults) {
                    break;
                }
                Element link = first(block.selectFirst("a.result__a"), block.selectFirst("a[href]"));
                if (link == null) {
                    continue;
                }
                String title = link.text();
                String url = normalizeSearchUrl(link.attr("href"));
                if (title == null || title.isBlank() || url == null || url.isBlank()) {
                    continue;
                }
                Element snippetElement = first(
                    block.selectFirst(".result__snippet"),
                    block.selectFirst(".snippet"),
                    block.selectFirst(".result__body")
                );
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("rank", results.size() + 1);
                item.put("title", title);
                item.put("url", url);
                item.put("snippet", snippetElement == null ? "" : snippetElement.text());
                results.add(item);
            }

            if (results.isEmpty()) {
                for (Element link : document.select("a.result__a")) {
                    if (results.size() >= numResults) {
                        break;
                    }
                    String title = link.text();
                    String url = normalizeSearchUrl(link.attr("href"));
                    if (title == null || title.isBlank() || url == null || url.isBlank()) {
                        continue;
                    }
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("rank", results.size() + 1);
                    item.put("title", title);
                    item.put("url", url);
                    item.put("snippet", "");
                    results.add(item);
                }
            }
            return results;
        }

        /**
         * Parses the bing results.
         *
         * @param document the document value
         * @param numResults the num results value
         * @return the parsed bing results
         */
        private List<Map<String, Object>> parseBingResults(Document document, int numResults) {
            List<Map<String, Object>> results = new ArrayList<>();
            for (Element block : document.select("li.b_algo")) {
                if (results.size() >= numResults) {
                    break;
                }
                Element link = block.selectFirst("h2 a[href]");
                if (link == null) {
                    continue;
                }
                String title = link.text();
                String url = normalizeSearchUrl(link.attr("href"));
                if (title == null || title.isBlank() || url == null || url.isBlank()) {
                    continue;
                }
                Element snippetElement = first(block.selectFirst(".b_caption p"), block.selectFirst("p"));
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("rank", results.size() + 1);
                item.put("title", title);
                item.put("url", url);
                item.put("snippet", snippetElement == null ? "" : snippetElement.text());
                results.add(item);
            }
            return results;
        }

        /**
         * Performs the first operation.
         *
         * @param elements the elements value
         * @return the operation result
         */
        private Element first(Element... elements) {
            for (Element element : elements) {
                if (element != null) {
                    return element;
                }
            }
            return null;
        }

        /**
         * Normalizes the search url.
         *
         * @param href the href value
         * @return the operation result
         */
        private String normalizeSearchUrl(String href) {
            if (href == null || href.isBlank()) {
                return href;
            }
            String value = href.trim();
            try {
                if (value.startsWith("//")) {
                    return "https:" + value;
                }
                if (value.startsWith("/l/?") || value.startsWith("https://duckduckgo.com/l/?")) {
                    URI uri = value.startsWith("http")
                        ? URI.create(value)
                        : URI.create("https://duckduckgo.com" + value);
                    String decoded = queryParam(uri.getRawQuery(), "uddg");
                    if (decoded != null && !decoded.isBlank()) {
                        return decoded;
                    }
                }
                return value;
            } catch (Exception ignored) {
                return value;
            }
        }

        /**
         * Queries the param.
         *
         * @param rawQuery the raw query value
         * @param name the name value
         * @return the operation result
         */
        private String queryParam(String rawQuery, String name) {
            if (rawQuery == null || rawQuery.isBlank()) {
                return null;
            }
            for (String part : rawQuery.split("&")) {
                int equals = part.indexOf('=');
                String key = equals < 0 ? part : part.substring(0, equals);
                if (!name.equals(key)) {
                    continue;
                }
                String value = equals < 0 ? "" : part.substring(equals + 1);
                return URLDecoder.decode(value, StandardCharsets.UTF_8);
            }
            return null;
        }

        private boolean containsAny(String value, List<String> candidates) {
            if (value == null || value.isBlank() || candidates == null || candidates.isEmpty()) {
                return false;
            }
            String lower = value.toLowerCase(Locale.ROOT);
            return candidates.stream()
                .filter(candidate -> candidate != null && !candidate.isBlank())
                .map(candidate -> candidate.trim().toLowerCase(Locale.ROOT))
                .anyMatch(lower::contains);
        }

        private String normalizeComparableUrl(String url) {
            if (url == null || url.isBlank()) {
                return "";
            }
            try {
                URI uri = URI.create(url.trim()).normalize();
                String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
                String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
                int port = uri.getPort();
                String authority = host;
                if (port > 0 && !(("http".equals(scheme) && port == 80) || ("https".equals(scheme) && port == 443))) {
                    authority = authority + ":" + port;
                }
                String path = uri.getRawPath() == null || uri.getRawPath().isBlank() ? "/" : uri.getRawPath();
                String query = uri.getRawQuery();
                StringBuilder builder = new StringBuilder();
                if (!scheme.isBlank()) {
                    builder.append(scheme).append("://");
                }
                builder.append(authority).append(path);
                if (query != null && !query.isBlank()) {
                    builder.append('?').append(query);
                }
                return builder.toString();
            } catch (Exception ex) {
                int fragment = url.indexOf('#');
                return (fragment >= 0 ? url.substring(0, fragment) : url).trim();
            }
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

        private record SearchAttempt(String provider, String endpoint) {
        }

        private record HtmlResponse(int statusCode, Document document) {
        }

        private record SiteSearchForm(String actionUrl,
                                      Map<String, String> parameters,
                                      String method,
                                      String submittedUrl,
                                      String sourcePageUrl) {
        }

        private record WebSearchRequestContext(String query,
                                               String tenantId,
                                               String taskId,
                                               String agentId,
                                               String proxyPool,
                                               String referer) {
        }

        private static class ProxyRuntimeState {

            private final AtomicInteger requestCount = new AtomicInteger();
            private final AtomicInteger failureCount = new AtomicInteger();
            private volatile long openUntilMs;
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
                throw new IllegalStateException("document search auth username/password must be configured");
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
            for (String term : query.trim().split("[\\s,，。；;:：/\\\\]+")) {
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
