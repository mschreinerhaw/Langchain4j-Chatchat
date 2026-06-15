package com.chatchat.tools.builtin;

import com.chatchat.common.tool.ToolInput;
import com.chatchat.common.tool.ToolOutput;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Manual runner for web_site_search / site_search debugging.
 */
public final class WebSiteSearchManualMain {

    private static final String DEFAULT_SEED_URL = "https://www.sse.com.cn/";
    private static final String DEFAULT_SITE_QUERY = "\u9876\u70b9\u8f6f\u4ef6";

    private WebSiteSearchManualMain() {
    }

    public static void main(String[] args) throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        Map<String, String> options = parseOptions(args);

        String siteQuery = firstNonBlank(
            options.get("siteQuery"),
            options.get("site_search_query"),
            System.getenv("WEB_SITE_SEARCH_QUERY"),
            DEFAULT_SITE_QUERY
        );
        String query = firstNonBlank(
            options.get("query"),
            System.getenv("WEB_SITE_SEARCH_ORIGINAL_QUERY"),
            siteQuery
        );
        List<String> seedUrls = splitCsv(firstNonBlank(
            options.get("seedUrls"),
            options.get("seed_urls"),
            System.getenv("WEB_SITE_SEARCH_SEED_URLS"),
            DEFAULT_SEED_URL
        ));
        int numResults = parseInt(firstNonBlank(options.get("num"), System.getenv("WEB_SITE_SEARCH_NUM_RESULTS")), 5);
        String mode = firstNonBlank(options.get("mode"), System.getenv("WEB_SITE_SEARCH_MODE"), "java");

        WebSearchToolProperties properties = new WebSearchToolProperties();
        properties.setProvider("bing_html");
        properties.setEndpoint(firstNonBlank(
            options.get("endpoint"),
            System.getenv("WEB_SEARCH_ENDPOINT"),
            "https://cn.bing.com/search"
        ));
        properties.setTimeoutMs(parseInt(firstNonBlank(options.get("timeoutMs"), System.getenv("WEB_SITE_SEARCH_TIMEOUT_MS")), 30000));
        properties.setMaxResults(Math.max(numResults, 10));
        properties.setFetchPages(false);
        properties.setBrowserFallbackToJava(true);
        properties.setDefaultMode("java");
        properties.getSiteSearch().setEnabled(true);
        properties.getSiteSearch().setDefaultMode(mode);
        properties.getSiteSearch().setBrowserFallbackToJava(true);
        properties.getSiteSearch().setMaxPagesToInspect(parseInt(firstNonBlank(options.get("inspect"), System.getenv("WEB_SITE_SEARCH_INSPECT")), 3));
        properties.getSiteSearch().setMaxSecondaryPages(parseInt(firstNonBlank(options.get("secondary"), System.getenv("WEB_SITE_SEARCH_SECONDARY")), 3));
        properties.getSiteSearch().setMaxLinksPerPage(parseInt(firstNonBlank(options.get("links"), System.getenv("WEB_SITE_SEARCH_LINKS")), 5));
        properties.getBrowser().setEnabled(true);
        properties.getBrowser().setNavigationTimeoutMs(
            parseInt(firstNonBlank(options.get("browserTimeoutMs"), System.getenv("WEB_SITE_SEARCH_BROWSER_TIMEOUT_MS")), 60000)
        );
        properties.getBrowser().setAcceptLanguage("zh-CN,zh;q=0.9,en-US;q=0.8,en;q=0.7");

        WebSearchTool tool = new WebSearchTool(properties, objectMapper);
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("query", query);
        parameters.put("site_search_query", siteQuery);
        parameters.put("seed_urls", seedUrls);
        parameters.put("num_results", numResults);
        parameters.put("search_stage", "site_search");
        parameters.put("site_search_mode", mode);

        System.out.println("web_site_search manual input:");
        System.out.println(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(parameters));

        long startedAt = System.currentTimeMillis();
        ToolOutput output = tool.execute(ToolInput.builder()
            .parameters(parameters)
            .requestId("manual-web-site-search")
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
            }
        }
        return options;
    }

    private static List<String> splitCsv(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return Arrays.stream(value.split("[,\\n]"))
            .map(String::trim)
            .filter(item -> !item.isBlank())
            .toList();
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
}
