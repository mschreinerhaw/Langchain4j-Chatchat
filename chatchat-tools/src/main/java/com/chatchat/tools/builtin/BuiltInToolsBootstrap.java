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
import java.net.URI;
import java.net.URLDecoder;
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
                    .build()
            ))
            .tags(Arrays.asList("search", "internet", "external"))
            .build();

        WebSearchTool webSearchTool = new WebSearchTool(webSearchProperties);
        toolRegistry.registerTool("web_search", metadata, webSearchTool);
        log.info("Web Search tool registered");
    }

    private long webSearchTimeoutMillis() {
        long perRequestTimeout = Math.max(1000L, webSearchProperties.getTimeoutMs());
        int searchAttempts = webSearchProperties.isFallbackEnabled() ? 3 : 1;
        int pageFetches = webSearchProperties.isFetchPages()
            ? Math.max(0, webSearchProperties.getMaxPagesToFetch())
            : 0;
        return Math.max(30000L, perRequestTimeout * (searchAttempts + pageFetches + 1));
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

        @Override
        public ToolMetadata getMetadata() {
            // Metadata is provided at registration time
            return null;
        }

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

        private WebSearchTool(WebSearchToolProperties properties) {
            this.properties = properties;
        }

        @Override
        public ToolMetadata getMetadata() {
            return null;
        }

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

                Map<String, Object> result = performWebSearch(query, numResults);
                return ToolOutput.success(result, buildSearchMessage(result));

            } catch (Exception e) {
                return ToolOutput.failure(e);
            }
        }

        private Map<String, Object> performWebSearch(String query, int numResults) throws Exception {
            String provider = properties.getProvider() == null
                ? "duckduckgo_html"
                : properties.getProvider().trim().toLowerCase(Locale.ROOT);

            List<SearchAttempt> attempts = buildSearchAttempts(provider, properties.getEndpoint());
            List<String> errors = new ArrayList<>();
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
                    Map<String, Object> result = performWebSearchAttempt(attempt.provider(), attempt.endpoint(), query, numResults, errors);
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

        private Map<String, Object> performWebSearchAttempt(String provider,
                                                            String endpoint,
                                                            String query,
                                                            int numResults,
                                                            List<String> previousErrors) throws Exception {
            Document document = Jsoup.connect(endpoint)
                .userAgent(properties.getUserAgent())
                .timeout(Math.max(1000, properties.getTimeoutMs()))
                .data("q", query)
                .get();

            int referenceLimit = Math.max(0, Math.min(10, properties.getMaxResults()));
            int fetchLimit = Math.max(numResults, referenceLimit);

            List<Map<String, Object>> fetchedResults = switch (provider) {
                case "duckduckgo_html" -> parseDuckDuckGoResults(document, fetchLimit);
                case "bing_html" -> parseBingResults(document, fetchLimit);
                default -> throw new IllegalArgumentException("Unsupported web search provider: " + properties.getProvider());
            };
            List<Map<String, Object>> results = fetchedResults.size() <= numResults
                ? fetchedResults
                : new ArrayList<>(fetchedResults.subList(0, numResults));
            List<String> referenceUrls = fetchedResults.stream()
                .map(item -> item.get("url"))
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .filter(url -> !url.isBlank())
                .distinct()
                .limit(referenceLimit)
                .toList();
            List<Map<String, Object>> pageExcerpts = fetchPageExcerpts(results, query);

            Map<String, Object> output = new LinkedHashMap<>();
            output.put("query", query);
            output.put("provider", provider);
            output.put("configuredProvider", properties.getProvider());
            output.put("fallbackUsed", previousErrors != null && !previousErrors.isEmpty());
            output.put("providerErrors", previousErrors == null ? List.of() : previousErrors);
            output.put("count", results.size());
            output.put("reference_url_count", referenceUrls.size());
            output.put("reference_urls", referenceUrls);
            output.put("page_fetch_enabled", properties.isFetchPages());
            output.put("page_excerpt_count", pageExcerpts.size());
            output.put("contentMode", pageExcerpts.isEmpty() ? "search_snippets_only" : "page_enriched");
            output.put("pageExcerpts", pageExcerpts);
            output.put("evidenceSnippets", pageExcerpts);
            output.put("results", results);
            return output;
        }

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

        private List<Map<String, Object>> fetchPageExcerpts(List<Map<String, Object>> results, String query) {
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
                attempts++;
                try {
                    long startedAt = System.currentTimeMillis();
                    log.info("Web search page fetch started attempt={}/{} rank={} url={}",
                        attempts,
                        limit,
                        result.get("rank"),
                        url);
                    org.jsoup.Connection.Response response = Jsoup.connect(url)
                        .userAgent(properties.getUserAgent())
                        .timeout(Math.max(1000, properties.getTimeoutMs()))
                        .maxBodySize(Math.max(1024, properties.getPageMaxBytes()))
                        .ignoreHttpErrors(true)
                        .execute();
                    int statusCode = response.statusCode();
                    if (statusCode < 200 || statusCode >= 300) {
                        result.put("pageFetched", false);
                        result.put("pageFetchError", "HTTP " + statusCode);
                        continue;
                    }
                    Document page = response.parse();

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

        private boolean isFetchablePageUrl(String url) {
            if (url == null || url.isBlank()) {
                return false;
            }
            String value = url.toLowerCase(Locale.ROOT);
            if (!value.startsWith("http://") && !value.startsWith("https://")) {
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

        private Element first(Element... elements) {
            for (Element element : elements) {
                if (element != null) {
                    return element;
                }
            }
            return null;
        }

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

        private String stringValue(Object value) {
            return value == null ? null : String.valueOf(value);
        }

        private String firstNonBlank(String first, String second) {
            return first == null || first.isBlank() ? second : first;
        }

        private record SearchAttempt(String provider, String endpoint) {
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

        private DocumentSearchTool(Environment environment, ObjectMapper objectMapper) {
            this.environment = environment;
            this.objectMapper = objectMapper;
            int timeoutMs = environment.getProperty("chatchat.tools.document-search.timeout-ms", Integer.class, 20000);
            this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(Math.max(1000, timeoutMs)))
                .build();
        }

        @Override
        public ToolMetadata getMetadata() {
            return null;
        }

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

        private HttpResponse<String> sendDocumentApiGet(URI uri, int timeoutMs) throws IOException, InterruptedException {
            HttpResponse<String> response = httpClient.send(buildDocumentApiGet(uri, timeoutMs), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() == 401 && isDocumentSearchAuthEnabled() && configuredDocumentSearchToken().isBlank()) {
                documentSearchToken = null;
                response = httpClient.send(buildDocumentApiGet(uri, timeoutMs), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            }
            return response;
        }

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

        private boolean isDocumentSearchAuthEnabled() {
            return environment.getProperty("chatchat.tools.document-search.auth.enabled", Boolean.class, true);
        }

        private String configuredDocumentSearchToken() {
            return environment.getProperty("chatchat.tools.document-search.auth.bearer-token", "").trim();
        }

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

        private int resolveExcerptChars() {
            int defaultChars = environment.getProperty("chatchat.tools.document-search.default-excerpt-chars", Integer.class, 3000);
            int maxChars = environment.getProperty("chatchat.tools.document-search.max-excerpt-chars", Integer.class, 8000);
            return Math.max(500, Math.min(Math.max(500, maxChars), defaultChars));
        }

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

        private int resolveLimit(ToolInput input) {
            Number requested = input.getParameterAsNumber("limit");
            int defaultLimit = environment.getProperty("chatchat.tools.document-search.default-limit", Integer.class, 5);
            int maxLimit = environment.getProperty("chatchat.tools.document-search.max-limit", Integer.class, 20);
            int value = requested == null ? defaultLimit : requested.intValue();
            return Math.max(1, Math.min(Math.max(1, maxLimit), value));
        }

        private String documentSearchApiBaseUrl() {
            return environment.getProperty("chatchat.tools.document-search.api-base-url", "http://localhost:8080");
        }

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

        private void addParam(List<String> params, String name, String value) {
            if (value == null || value.isBlank()) {
                return;
            }
            params.add(encode(name) + "=" + encode(value.trim()));
        }

        private String encode(String value) {
            return URLEncoder.encode(value, StandardCharsets.UTF_8);
        }

        private String encodePathSegment(String value) {
            return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
        }

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

        private String stringValue(Object value) {
            return value == null ? null : String.valueOf(value);
        }

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

        private DatabaseQueryTool(DynamicJdbcDriverLoader dynamicJdbcDriverLoader,
                                  DatabaseToolProperties properties,
                                  String applicationJdbcUrl,
                                  ObjectMapper objectMapper) {
            this.dynamicJdbcDriverLoader = dynamicJdbcDriverLoader;
            this.properties = properties;
            this.applicationJdbcUrl = applicationJdbcUrl;
            this.objectMapper = objectMapper;
        }

        @Override
        public ToolMetadata getMetadata() {
            return null;
        }

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

        private boolean isApplicationJdbcUrl(String jdbcUrl) {
            return applicationJdbcUrl != null
                && !applicationJdbcUrl.isBlank()
                && applicationJdbcUrl.trim().equalsIgnoreCase(jdbcUrl.trim());
        }

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

        private int resolveMaxRows(ToolInput input) {
            Number requested = input.getParameterAsNumber("max_rows");
            int value = requested == null ? properties.getDefaultMaxRows() : requested.intValue();
            return Math.max(1, Math.min(properties.getMaxRows(), value));
        }

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

        private Map<String, Object> normalizeRow(Map<String, Object> row) {
            Map<String, Object> normalized = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : row.entrySet()) {
                normalized.put(entry.getKey(), normalizeValue(entry.getValue()));
            }
            return normalized;
        }

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

        @Override
        public ToolMetadata getMetadata() {
            return null;
        }

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

        MathExpressionEvaluator(String expression) {
            this.expression = expression;
        }

        double evaluate() throws Exception {
            double result = parseExpression();
            if (pos != expression.length()) {
                throw new IllegalArgumentException("Unexpected characters at position " + pos);
            }
            return result;
        }

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

        private double parseUnary() throws Exception {
            if (pos < expression.length() &&
                (expression.charAt(pos) == '-' || expression.charAt(pos) == '+')) {
                char op = expression.charAt(pos++);
                double value = parseUnary();
                return op == '-' ? -value : value;
            }
            return parsePrimary();
        }

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
