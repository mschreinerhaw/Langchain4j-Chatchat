package com.chatchat.mcpserver.web;

import com.chatchat.mcpserver.cache.McpCacheProperties;
import com.chatchat.mcpserver.cache.McpRocksDbStore;
import com.chatchat.tools.web.WebContentProcessor;
import com.chatchat.tools.web.WebCrawlerProperties;
import com.chatchat.tools.web.WebCrawlerService;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Manual runner for web_crawler / crawl_url debugging.
 */
public final class WebCrawlerManualMain {

    private static final String DEFAULT_URL = "http://www.szse.cn/certificate/individual/index.html?code=000802";

    private WebCrawlerManualMain() {
    }

    public static void main(String[] args) throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        Map<String, String> options = parseOptions(args);

        String url = firstNonBlank(options.get("url"), System.getenv("WEB_CRAWLER_URL"), DEFAULT_URL);
        String mode = firstNonBlank(options.get("mode"), System.getenv("WEB_CRAWLER_MODE"), "browser");
        boolean render = parseBoolean(firstNonBlank(options.get("render"), System.getenv("WEB_CRAWLER_RENDER")), false);
        int timeoutMs = parseInt(firstNonBlank(options.get("timeoutMs"), System.getenv("WEB_CRAWLER_TIMEOUT_MS")), 30000);

        WebCrawlerProperties properties = new WebCrawlerProperties();
        properties.setDefaultMode(mode);
        properties.setTimeoutMs(timeoutMs);
        properties.setMaxBodyBytes(parseInt(firstNonBlank(options.get("maxBodyBytes"), System.getenv("WEB_CRAWLER_MAX_BODY_BYTES")), 1048576));
        properties.setMaxHtmlChars(parseInt(firstNonBlank(options.get("maxHtmlChars"), System.getenv("WEB_CRAWLER_MAX_HTML_CHARS")), 80000));
        properties.setMaxTextChars(parseInt(firstNonBlank(options.get("maxTextChars"), System.getenv("WEB_CRAWLER_MAX_TEXT_CHARS")), 60000));
        properties.setChunkChars(parseInt(firstNonBlank(options.get("chunkChars"), System.getenv("WEB_CRAWLER_CHUNK_CHARS")), 1200));
        properties.setChunkOverlapChars(parseInt(firstNonBlank(options.get("chunkOverlapChars"), System.getenv("WEB_CRAWLER_CHUNK_OVERLAP_CHARS")), 120));
        properties.setMaxChunks(parseInt(firstNonBlank(options.get("maxChunks"), System.getenv("WEB_CRAWLER_MAX_CHUNKS")), 20));
        properties.setIncludeHtml(parseBoolean(firstNonBlank(options.get("includeHtml"), System.getenv("WEB_CRAWLER_INCLUDE_HTML")), false));
        properties.setAcceptLanguage("zh-CN,zh;q=0.9,en-US;q=0.8,en;q=0.7");
        properties.getBrowser().setEnabled(true);
        properties.getBrowser().setNavigationTimeoutMs(
            parseInt(firstNonBlank(options.get("browserTimeoutMs"), System.getenv("WEB_CRAWLER_BROWSER_TIMEOUT_MS")), 60000)
        );
        properties.getBrowser().setAcceptLanguage("zh-CN,zh;q=0.9,en-US;q=0.8,en;q=0.7");
        properties.getBrowser().setReferer(firstNonBlank(options.get("referer"), System.getenv("WEB_CRAWLER_REFERER"), ""));

        McpCacheProperties cacheProperties = new McpCacheProperties();
        cacheProperties.setEnabled(false);
        WebPageCacheService cacheService = new WebPageCacheService(new McpRocksDbStore(cacheProperties), objectMapper);
        WebCrawlerService crawlerService = new WebCrawlerService(
            properties,
            new WebContentProcessor(properties),
            cacheService
        );

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("url", url);
        input.put("mode", mode);
        input.put("render", render);
        input.put("timeoutMs", timeoutMs);
        input.put("includeHtml", properties.isIncludeHtml());

        System.out.println("web_crawler manual input:");
        System.out.println(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(input));

        long startedAt = System.currentTimeMillis();
        Map<String, Object> output = crawlerService.crawl(
            url,
            mode,
            render,
            timeoutMs,
            new WebCrawlerService.CrawlRequestContext(
                firstNonBlank(options.get("tenantId"), System.getenv("WEB_CRAWLER_TENANT_ID"), "manual"),
                firstNonBlank(options.get("taskId"), System.getenv("WEB_CRAWLER_TASK_ID"), "manual-web-crawler"),
                firstNonBlank(options.get("agentId"), System.getenv("WEB_CRAWLER_AGENT_ID"), "manual")
            )
        );
        output.put("manualDurationMs", Math.max(0L, System.currentTimeMillis() - startedAt));

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
            } else if (!options.containsKey("url")) {
                options.put("url", arg);
            }
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
