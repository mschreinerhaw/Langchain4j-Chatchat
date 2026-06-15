package com.chatchat.tools.builtin;

import com.chatchat.common.tool.ToolInput;
import com.chatchat.common.tool.ToolOutput;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Manual runner for local web_search debugging.
 */
public final class WebSearchToolManualMain {

    private static final String DEFAULT_QUERY =
        "https://cn.bing.com/search query=2026 最新AI资讯信息";

    private WebSearchToolManualMain() {
    }

    public static void main(String[] args) throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        Map<String, String> options = parseOptions(args);

        String query = firstNonBlank(
            options.get("query"),
            System.getenv("WEB_SEARCH_QUERY"),
            DEFAULT_QUERY
        );
        int numResults = parseInt(firstNonBlank(options.get("num"), System.getenv("WEB_SEARCH_NUM_RESULTS")), 5);
        boolean fetchPages = parseBoolean(firstNonBlank(options.get("fetchPages"), System.getenv("WEB_SEARCH_FETCH_PAGES")), false);
        boolean includeSiteSearch = parseBoolean(firstNonBlank(options.get("siteSearch"), System.getenv("WEB_SEARCH_SITE_SEARCH")), false);
        String searchStage = firstNonBlank(options.get("stage"), System.getenv("WEB_SEARCH_STAGE"), "primary");
        String webSearchMode = firstNonBlank(options.get("mode"), System.getenv("WEB_SEARCH_MODE"), "java");
        String endpoint = firstNonBlank(options.get("endpoint"), System.getenv("WEB_SEARCH_ENDPOINT"), "https://cn.bing.com/search");

        WebSearchToolProperties properties = new WebSearchToolProperties();
        properties.setProvider("bing_html");
        properties.setEndpoint(endpoint);
        properties.setTimeoutMs(parseInt(firstNonBlank(options.get("timeoutMs"), System.getenv("WEB_SEARCH_TIMEOUT_MS")), 30000));
        properties.setMaxResults(Math.max(numResults, 10));
        properties.setFetchPages(fetchPages);
        properties.setMaxPagesToFetch(parseInt(firstNonBlank(options.get("maxPages"), System.getenv("WEB_SEARCH_MAX_PAGES")), 2));
        properties.setBrowserFallbackToJava(true);
        properties.setDefaultMode(webSearchMode);
        properties.getSiteSearch().setEnabled(true);
        properties.getSiteSearch().setDefaultMode(webSearchMode);
        properties.getSiteSearch().setBrowserFallbackToJava(true);
        properties.getBrowser().setEnabled(true);
        properties.getBrowser().setNavigationTimeoutMs(
            parseInt(firstNonBlank(options.get("browserTimeoutMs"), System.getenv("WEB_SEARCH_BROWSER_TIMEOUT_MS")), 60000)
        );
        properties.getBrowser().setAcceptLanguage("zh-CN,zh;q=0.9,en-US;q=0.8,en;q=0.7");
        properties.getBrowser().setReferer("https://cn.bing.com/");

        WebSearchTool tool = new WebSearchTool(properties, objectMapper);
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("query", query);
        parameters.put("num_results", numResults);
        parameters.put("search_stage", searchStage);
        parameters.put("fetch_pages", fetchPages);
        parameters.put("include_site_search", includeSiteSearch);
        parameters.put("web_search_mode", webSearchMode);
        parameters.put("site_search_mode", webSearchMode);

        System.out.println("web_search manual input:");
        System.out.println(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(parameters));

        long startedAt = System.currentTimeMillis();
        ToolOutput output = tool.execute(ToolInput.builder()
            .parameters(parameters)
            .requestId("manual-web-search")
            .userId("manual")
            .build());
        long durationMs = Math.max(0L, System.currentTimeMillis() - startedAt);

        System.out.println("durationMs=" + durationMs);
        System.out.println("success=" + output.isSuccess());
        if (output.getMessage() != null && !output.getMessage().isBlank()) {
            System.out.println(output.getMessage());
        }
        if (!output.isSuccess()) {
            System.out.println("error=" + output.getErrorMessage());
            if (output.getErrorDetails() != null) {
                System.out.println(output.getErrorDetails());
            }
        }
        System.out.println(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(output));
    }

    private static Map<String, String> parseOptions(String[] args) {
        Map<String, String> options = new LinkedHashMap<>();
        if (args == null || args.length == 0) {
            return options;
        }
        StringBuilder positionalQuery = new StringBuilder();
        for (String arg : args) {
            if (arg == null || arg.isBlank()) {
                continue;
            }
            if (arg.startsWith("--")) {
                int equals = arg.indexOf('=');
                if (equals > 2) {
                    options.put(arg.substring(2, equals), arg.substring(equals + 1));
                } else {
                    options.put(arg.substring(2), "true");
                }
            } else {
                if (!positionalQuery.isEmpty()) {
                    positionalQuery.append(' ');
                }
                positionalQuery.append(arg);
            }
        }
        if (!positionalQuery.isEmpty() && !options.containsKey("query")) {
            options.put("query", positionalQuery.toString());
        }
        return options;
    }

    private static String firstNonBlank(String... values) {
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

    private static int parseInt(String value, int defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ex) {
            return defaultValue;
        }
    }

    private static boolean parseBoolean(String value, boolean defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return Boolean.parseBoolean(value.trim());
    }
}
