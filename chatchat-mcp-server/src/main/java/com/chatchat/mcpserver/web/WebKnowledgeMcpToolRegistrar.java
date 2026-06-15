package com.chatchat.mcpserver.web;

import com.chatchat.agents.tool.ToolRegistry;
import com.chatchat.common.tool.ToolInput;
import com.chatchat.common.tool.ToolMetadata;
import com.chatchat.common.tool.ToolOutput;
import com.chatchat.common.tool.ToolParameter;
import com.chatchat.mcpserver.tool.McpServerToolRegistrar;
import com.chatchat.tools.builtin.WebSearchToolProperties;
import com.chatchat.tools.web.WebCrawlerProperties;
import com.chatchat.tools.web.WebCrawlerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
public class WebKnowledgeMcpToolRegistrar implements McpServerToolRegistrar {

    private static final Logger log = LoggerFactory.getLogger(WebKnowledgeMcpToolRegistrar.class);

    private final WebCrawlerService crawlerService;
    private final WebSearchExtractService searchExtractService;
    private final WebCrawlerProperties crawlerProperties;
    private final WebSearchToolProperties webSearchProperties;
    private final Environment environment;

    public WebKnowledgeMcpToolRegistrar(WebCrawlerService crawlerService,
                                        WebSearchExtractService searchExtractService,
                                        WebCrawlerProperties crawlerProperties,
                                        WebSearchToolProperties webSearchProperties,
                                        Environment environment) {
        this.crawlerService = crawlerService;
        this.searchExtractService = searchExtractService;
        this.crawlerProperties = crawlerProperties;
        this.webSearchProperties = webSearchProperties;
        this.environment = environment;
    }

    /**
     * Registers tools into the shared tool registry.
     *
     * @param toolRegistry the tool registry value
     */
    @Override
    public void registerTools(ToolRegistry toolRegistry) {
        ToolRegistry.EnhancedTool rawWebSearch = toolRegistry.getEnhancedTool("web_search");
        if (rawWebSearch != null) {
            toolRegistry.registerTool("web_search", webSearchSnippetsMetadata(), new WebSearchSnippetsTool(rawWebSearch));
            toolRegistry.registerTool("web_site_search", webSiteSearchMetadata(), new WebSiteSearchTool(rawWebSearch));
        }
        ToolMetadata crawlUrlMetadata = crawlUrlMetadata();
        ToolMetadata webCrawlerMetadata = webCrawlerMetadata();
        toolRegistry.registerTool("crawl_url", crawlUrlMetadata, new CrawlUrlTool(crawlUrlMetadata));
        toolRegistry.registerTool("web_crawler", webCrawlerMetadata, new CrawlUrlTool(webCrawlerMetadata));
        toolRegistry.registerTool("retrieve_evidence", retrieveEvidenceMetadata(), new RetrieveEvidenceTool());
        toolRegistry.registerTool("search_and_extract", searchAndExtractMetadata(), new SearchAndExtractTool());
    }

    private ToolMetadata webSearchSnippetsMetadata() {
        return ToolMetadata.builder()
            .id("web_search")
            .title("Web Search Snippets")
            .description("Search the public web and return only search result titles, URLs, and short snippets for model-side relevance judgment. This tool does not crawl pages or run secondary site search; call web_site_search or web_crawler after choosing relevant URLs.")
            .version("1.1.0")
            .author("ChatChat MCP Server")
            .categories(Arrays.asList("mcp", "search", "internet"))
            .category("http_web_search")
            .riskLevel(environment.getProperty("chatchat.tools.web-search.risk-level", "low"))
            .operationType(environment.getProperty("chatchat.tools.web-search.operation-type", "read"))
            .runtimeLevel(environment.getProperty("chatchat.tools.web-search.runtime-level", "readonly"))
            .confirmation(Map.of("default", environment.getProperty("chatchat.tools.web-search.confirmation.default", "auto_execute")))
            .inputPolicy(Map.of("must_show_parameters", true))
            .outputType("json")
            .timeoutMillis(0L)
            .agentCompatible(true)
            .parameters(List.of(
                ToolParameter.builder()
                    .name("query")
                    .type("string")
                    .description("Search query. Use site:example.com or include a target website name when a source is required.")
                    .required(true)
                    .minLength(1)
                    .maxLength(500)
                    .build(),
                ToolParameter.builder()
                    .name("num_results")
                    .type("number")
                    .description("Number of short search candidates to return")
                    .required(false)
                    .defaultValue(10)
                    .minimum(1)
                    .maximum(20)
                    .build(),
                ToolParameter.builder()
                    .name("mode")
                    .type("string")
                    .description("Web search fetch mode: browser uses the persistent Playwright Chromium search session when available and falls back to java; java uses browser-like Java HTTP fetching; auto follows web-search configuration.")
                    .required(false)
                    .defaultValue("browser")
                    .enumValues(new String[]{"java", "browser", "auto"})
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
                    .build()
            ))
            .tags(Arrays.asList("mcp", "web", "search", "snippets", "llm_rerank"))
            .metadata(Map.of(
                "workflow", "web_search_then_model_judges_then_optional_web_site_search_or_web_crawler",
                "searchStage", "primary",
                "outputContract", "short search result snippets only",
                "modes", List.of("java", "browser", "auto"),
                "browserFallback", "browser mode falls back to Java HTTP fetching when Playwright fails"
            ))
            .build();
    }

    private ToolMetadata webSiteSearchMetadata() {
        return ToolMetadata.builder()
            .id("web_site_search")
            .title("Exchange Disclosure Site Search")
            .description("Dedicated financial disclosure site search for configured financeSiteSearchTargets only. Returns candidate URLs plus short retrieval snippets for model-side judgment; call web_crawler separately only for URLs the model selects.")
            .version("1.1.0")
            .author("ChatChat MCP Server")
            .categories(Arrays.asList("mcp", "search", "internet", "site-search"))
            .category("http_web_search")
            .riskLevel(environment.getProperty("chatchat.tools.web-search.risk-level", "low"))
            .operationType(environment.getProperty("chatchat.tools.web-search.operation-type", "read"))
            .runtimeLevel(environment.getProperty("chatchat.tools.web-search.runtime-level", "readonly"))
            .confirmation(Map.of("default", environment.getProperty("chatchat.tools.web-search.confirmation.default", "auto_execute")))
            .inputPolicy(Map.of("must_show_parameters", true))
            .outputType("json")
            .timeoutMillis(0L)
            .agentCompatible(true)
            .parameters(List.of(
                ToolParameter.builder()
                    .name("query")
                    .type("string")
                    .description("Optional original user question. The actual exchange keyword should be site_search_query or finance_site_search_query.")
                    .required(false)
                    .minLength(1)
                    .maxLength(500)
                    .build(),
                ToolParameter.builder()
                    .name("seed_urls")
                    .type("array")
                    .description("Advanced override only. Normally leave empty; this tool automatically builds exchange search URLs from configured financeSiteSearchTargets.")
                    .required(false)
                    .metadata(Map.of("items", Map.of("type", "string")))
                    .build(),
                ToolParameter.builder()
                    .name("candidate_results")
                    .type("array")
                    .description("Advanced override only. Normally leave empty; this tool is dedicated to configured exchange search targets, not generic web_search candidates.")
                    .required(false)
                    .metadata(Map.of("items", Map.of("type", "object")))
                    .build(),
                ToolParameter.builder()
                    .name("site_search_query")
                    .type("string")
                    .description("Financial disclosure search keyword. Use a listed company name or stock code, not a full natural-language question. For SSE this maps to webswd; for SZSE it maps to keyword; for CNINFO it maps to keyWord.")
                    .required(false)
                    .maxLength(300)
                    .build(),
                ToolParameter.builder()
                    .name("finance_site_search_query")
                    .type("string")
                    .description("Preferred exchange disclosure search keyword. Fill with listed company name or stock code, for example Dingdian Software/贵州茅台/603383/600519. It maps to site_search_query.")
                    .required(false)
                    .maxLength(300)
                    .build(),
                financeSiteIdParameter(),
                ToolParameter.builder()
                    .name("mode")
                    .type("string")
                    .description("Exchange site-search fetch mode. Leave empty unless debugging; the tool chooses the configured default for the selected exchange targets.")
                    .required(false)
                    .defaultValue("auto")
                    .enumValues(new String[]{"java", "browser", "auto"})
                    .build(),
                ToolParameter.builder()
                    .name("num_results")
                    .type("number")
                    .description("Maximum exchange disclosure results to return")
                    .required(false)
                    .defaultValue(10)
                    .minimum(1)
                    .maximum(20)
                    .build(),
                ToolParameter.builder()
                    .name("max_results")
                    .type("number")
                    .description("Alias of num_results. Use num_results when possible.")
                    .required(false)
                    .defaultValue(10)
                    .minimum(1)
                    .maximum(20)
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
                    .build()
            ))
            .tags(Arrays.asList("mcp", "exchange", "disclosure", "finance", "sse", "szse", "cninfo"))
            .metadata(Map.ofEntries(
                Map.entry("workflow", "configured_finance_disclosure_search"),
                Map.entry("searchStage", "site_search"),
                Map.entry("independentMcpTool", true),
                Map.entry("genericSiteSearch", false),
                Map.entry("inputContract", "finance_site_search_query_or_site_search_query + optional finance_site_id"),
                Map.entry("financeSiteSearchQuery", "Financial disclosure search keyword value: listed company name or stock code, e.g. Dingdian Software/603383"),
                Map.entry("financeSiteSearchTargets", financeSiteSearchTargetsForModel()),
                Map.entry("outputContract", "crawl_candidates + model_selection_text with candidate URLs and short retrieval snippets; no page crawling"),
                Map.entry("crawlDecision", "model decides whether to call web_crawler and which candidate URLs to crawl"),
                Map.entry("modes", List.of("java", "browser", "auto")),
                Map.entry("browserFallback", "browser mode falls back to Java HTTP fetching when Playwright fails")
            ))
            .build();
    }

    private ToolParameter financeSiteIdParameter() {
        ToolParameter.ToolParameterBuilder builder = ToolParameter.builder()
            .name("finance_site_id")
            .type("string")
            .description("Optional configured finance disclosure site id. Choose sse for Shanghai Stock Exchange, szse for Shenzhen Stock Exchange, or cninfo for CNINFO/Juchao full-text disclosure search. If omitted or unsupported, the tool searches all configured finance disclosure targets.")
            .required(false)
            .maxLength(80);
        String[] ids = financeSiteSearchTargetIds();
        if (ids.length > 0) {
            builder.enumValues(ids);
        }
        return builder.build();
    }

    private String[] financeSiteSearchTargetIds() {
        return enabledFinanceSiteTargets().stream()
            .map(WebSearchToolProperties.FinanceSiteSearchTarget::getId)
            .filter(this::hasText)
            .distinct()
            .toArray(String[]::new);
    }

    private List<Map<String, Object>> financeSiteSearchTargetsForModel() {
        List<Map<String, Object>> targets = new ArrayList<>();
        for (WebSearchToolProperties.FinanceSiteSearchTarget target : enabledFinanceSiteTargets()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", target.getId());
            item.put("name", target.getName());
            item.put("domain", target.getDomain());
            item.put("homepageUrl", target.getHomepageUrl());
            item.put("searchUrlTemplate", target.getSearchUrlTemplate());
            item.put("queryParam", target.getQueryParam());
            item.put("defaultMode", target.getDefaultMode());
            item.put("informationTypes", target.getInformationTypes());
            item.put("description", target.getDescription());
            targets.add(item);
        }
        return targets;
    }

    private List<WebSearchToolProperties.FinanceSiteSearchTarget> enabledFinanceSiteTargets() {
        if (webSearchProperties == null || webSearchProperties.getFinanceSites() == null) {
            return List.of();
        }
        return webSearchProperties.getFinanceSites().stream()
            .filter(WebSearchToolProperties.FinanceSiteSearchTarget::isEnabled)
            .filter(target -> hasText(target.getId()))
            .toList();
    }

    private ToolMetadata crawlUrlMetadata() {
        return crawlerMetadata(
            "crawl_url",
            "Crawl URL",
            "Fetch a web page and return cleaned readable content, short chunks, cache metadata, and crawlChain. Automatically follows wrapper pages when requestedUrl or the cleaned body is another HTTP/HTTPS URL."
        );
    }

    private ToolMetadata webCrawlerMetadata() {
        return crawlerMetadata(
            "web_crawler",
            "Web Crawler",
            "Independent MCP crawler tool. Fetch one selected HTTP/HTTPS page and return cleaned readable content, short chunks, cache metadata, and crawlChain for model judgment. Automatically follows wrapper pages when requestedUrl or the cleaned body is another HTTP/HTTPS URL."
        );
    }

    private ToolMetadata crawlerMetadata(String id, String title, String description) {
        return ToolMetadata.builder()
            .id(id)
            .title(title)
            .description(description)
            .version("1.1.0")
            .author("ChatChat MCP Server")
            .categories(Arrays.asList("mcp", "crawler", "internet"))
            .category("http_crawler")
            .riskLevel(environment.getProperty("chatchat.tools.web-crawler.risk-level", "low"))
            .operationType("read")
            .runtimeLevel("readonly")
            .confirmation(Map.of("default", environment.getProperty("chatchat.tools.web-crawler.confirmation.default", "auto_execute")))
            .inputPolicy(Map.of("must_show_parameters", true))
            .outputType("json")
            .timeoutMillis(0L)
            .agentCompatible(true)
            .parameters(List.of(
                ToolParameter.builder()
                    .name("url")
                    .type("string")
                    .description("HTTP or HTTPS URL to crawl")
                    .required(true)
                    .minLength(8)
                    .maxLength(2000)
                    .build(),
                ToolParameter.builder()
                    .name("render")
                    .type("boolean")
                    .description("Compatibility flag. When true, crawl_url uses browser mode.")
                    .required(false)
                    .defaultValue(false)
                    .build(),
                ToolParameter.builder()
                    .name("mode")
                    .type("string")
                    .description("Crawl mode: java uses browser-like JSoup HTTP fetching; browser uses Playwright Chromium rendering; auto follows crawler configuration.")
                    .required(false)
                    .defaultValue(crawlerProperties.getDefaultMode())
                    .enumValues(new String[]{"java", "browser", "auto"})
                    .build(),
                ToolParameter.builder()
                    .name("timeout")
                    .type("integer")
                    .description("Request timeout in milliseconds. 0 means no request timeout.")
                    .required(false)
                    .defaultValue(crawlerProperties.getTimeoutMs())
                    .minimum(0)
                    .maximum(60000)
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
                    .description("Optional agent identifier used for audit context")
                    .required(false)
                    .maxLength(128)
                    .build()
            ))
            .tags(Arrays.asList("mcp", "web", "crawler", "content_processor"))
            .metadata(Map.of(
                "independentMcpTool", true,
                "cacheFirst", true,
                "contentProcessor", "jsoup_readability_v1",
                "modes", List.of("java", "browser", "auto"),
                "javaMode", "browser-like HTTP fetch using JSoup",
                "browserMode", "Playwright Chromium rendering"
            ))
            .build();
    }

    private ToolMetadata searchAndExtractMetadata() {
        return ToolMetadata.builder()
            .id("search_and_extract")
            .title("Internet Evidence Generator Debug")
            .description("Debug and compatibility tool that returns full web search, crawl, raw evidence, rerank, and observability data.")
            .version("1.0.0")
            .author("ChatChat MCP Server")
            .categories(Arrays.asList("mcp", "search", "crawler", "internet"))
            .category("http_web_search")
            .riskLevel(environment.getProperty("chatchat.mcp.search-and-extract.risk-level", "low"))
            .operationType("read")
            .runtimeLevel("readonly")
            .confirmation(Map.of("default", environment.getProperty("chatchat.mcp.search-and-extract.confirmation.default", "auto_execute")))
            .inputPolicy(Map.of("must_show_parameters", true))
            .outputType("json")
            .timeoutMillis(90000L)
            .agentCompatible(false)
            .parameters(List.of(
                ToolParameter.builder()
                    .name("query")
                    .type("string")
                    .description("User question or search query")
                    .required(true)
                    .minLength(1)
                    .maxLength(500)
                    .build(),
                ToolParameter.builder()
                    .name("mode")
                    .type("string")
                    .description("Search mode")
                    .required(false)
                    .defaultValue("fast")
                    .enumValues(new String[]{"fast", "deep", "cached"})
                    .build(),
                ToolParameter.builder()
                    .name("topK")
                    .type("integer")
                    .description("Maximum ranked URLs to return")
                    .required(false)
                    .defaultValue(5)
                    .minimum(1)
                    .maximum(20)
                    .build()
            ))
            .tags(Arrays.asList("mcp", "web", "search", "extract", "cache_first"))
            .metadata(Map.of(
                "cacheFirst", true,
                "workflow", "web-search-crawl-content-process-evidence",
                "outputContract", "debug output including raw search/pages/evidence",
                "schemaVersion", EvidenceContractService.SCHEMA_VERSION,
                "evidenceLayer", "InternetEvidenceService",
                "rankerVersion", InternetEvidenceService.VERSION,
                "rerankerVersion", EvidenceReranker.VERSION,
                "observabilityVersion", "evidence_observability_v1"
            ))
            .build();
    }

    private ToolMetadata retrieveEvidenceMetadata() {
        return ToolMetadata.builder()
            .id("retrieve_evidence")
            .title("Retrieve Internet Evidence")
            .description("Return a compact evidence package for LLM reasoning: ranked evidence_chunks, citations, source URLs, and retrieval metadata.")
            .version("1.0.0")
            .author("ChatChat MCP Server")
            .categories(Arrays.asList("mcp", "search", "crawler", "internet", "rag"))
            .category("http_web_search")
            .riskLevel(environment.getProperty("chatchat.mcp.retrieve-evidence.risk-level", "low"))
            .operationType("read")
            .runtimeLevel("readonly")
            .confirmation(Map.of("default", environment.getProperty("chatchat.mcp.retrieve-evidence.confirmation.default", "auto_execute")))
            .inputPolicy(Map.of("must_show_parameters", true))
            .outputType("json")
            .timeoutMillis(90000L)
            .agentCompatible(true)
            .parameters(List.of(
                ToolParameter.builder()
                    .name("query")
                    .type("string")
                    .description("User question or search query")
                    .required(true)
                    .minLength(1)
                    .maxLength(500)
                    .build(),
                ToolParameter.builder()
                    .name("mode")
                    .type("string")
                    .description("Retrieval mode")
                    .required(false)
                    .defaultValue("fast")
                    .enumValues(new String[]{"fast", "deep", "cached"})
                    .build(),
                ToolParameter.builder()
                    .name("topK")
                    .type("integer")
                    .description("Maximum ranked URLs to search before evidence compression")
                    .required(false)
                    .defaultValue(5)
                    .minimum(1)
                    .maximum(20)
                    .build()
            ))
            .tags(Arrays.asList("mcp", "web", "search", "evidence", "rag", "cache_first"))
            .metadata(Map.of(
                "cacheFirst", true,
                "workflow", "web-search-crawl-content-process-evidence",
                "outputContract", "compact evidence_chunks + citations only",
                "schemaVersion", EvidenceContractService.SCHEMA_VERSION,
                "evidenceLayer", "InternetEvidenceService",
                "rankerVersion", InternetEvidenceService.VERSION,
                "rerankerVersion", EvidenceReranker.VERSION,
                "observabilityVersion", "evidence_observability_v1"
            ))
            .build();
    }

    private final class CrawlUrlTool implements ToolRegistry.EnhancedTool {

        private final ToolMetadata metadata;

        private CrawlUrlTool(ToolMetadata metadata) {
            this.metadata = metadata;
        }

        /**
         * Returns the metadata.
         *
         * @return the metadata
         */
        @Override
        public ToolMetadata getMetadata() {
            return metadata;
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
                String url = input.getParameterAsString("url", "");
                String mode = input.getParameterAsString("mode", crawlerProperties.getDefaultMode());
                boolean render = input.getParameterAsBoolean("render", false);
                Number timeout = input.getParameterAsNumber("timeout");
                Map<String, Object> result = crawlerService.crawl(
                    url,
                    mode,
                    render,
                    timeout == null ? crawlerProperties.getTimeoutMs() : timeout.intValue(),
                    new WebCrawlerService.CrawlRequestContext(
                        input.getParameterAsString("tenantId", ""),
                        input.getParameterAsString("sourceTaskId", ""),
                        input.getParameterAsString("agentId", "")
                    )
                );
                return ToolOutput.success(result, "URL crawled and cleaned successfully");
            } catch (Exception ex) {
                return ToolOutput.failure(ex);
            }
        }
    }

    private final class WebSearchSnippetsTool implements ToolRegistry.EnhancedTool {

        private final ToolRegistry.EnhancedTool delegate;

        private WebSearchSnippetsTool(ToolRegistry.EnhancedTool delegate) {
            this.delegate = delegate;
        }

        @Override
        public ToolMetadata getMetadata() {
            return webSearchSnippetsMetadata();
        }

        @Override
        public ToolOutput execute(ToolInput input) {
            return delegate.execute(webSearchInput(input, "primary"));
        }
    }

    private final class WebSiteSearchTool implements ToolRegistry.EnhancedTool {

        private final ToolRegistry.EnhancedTool delegate;

        private WebSiteSearchTool(ToolRegistry.EnhancedTool delegate) {
            this.delegate = delegate;
        }

        @Override
        public ToolMetadata getMetadata() {
            return webSiteSearchMetadata();
        }

        @Override
        public ToolOutput execute(ToolInput input) {
            return delegate.execute(webSearchInput(input, "site_search"));
        }
    }

    private ToolInput webSearchInput(ToolInput input, String searchStage) {
        Map<String, Object> parameters = new LinkedHashMap<>(input == null || input.getParameters() == null
            ? Map.of()
            : input.getParameters());
        parameters.put("search_stage", searchStage);
        if ("site_search".equals(searchStage)) {
            applyFinanceSiteSearchDefaults(parameters);
        }
        if ("primary".equals(searchStage)) {
            parameters.put("include_site_search", false);
            parameters.put("fetch_pages", false);
            if (parameters.containsKey("mode")) {
                parameters.put("web_search_mode", parameters.get("mode"));
            }
        } else if ("site_search".equals(searchStage) && parameters.containsKey("mode")) {
            parameters.put("site_search_mode", parameters.get("mode"));
        }
        if ("site_search".equals(searchStage)
            && !hasText(parameters.get("query"))
            && hasText(parameters.get("site_search_query"))) {
            parameters.put("query", parameters.get("site_search_query"));
        }
        return ToolInput.builder()
            .rawInput(input == null ? null : input.getRawInput())
            .parameters(parameters)
            .requestId(input == null ? null : input.getRequestId())
            .userId(input == null ? null : input.getUserId())
            .conversationId(input == null ? null : input.getConversationId())
            .context(input == null || input.getContext() == null ? Map.of() : input.getContext())
            .build();
    }

    private void applyFinanceSiteSearchDefaults(Map<String, Object> parameters) {
        Object requestedFinanceSiteId = parameters.get("finance_site_id");
        if (!hasText(parameters.get("num_results")) && hasText(parameters.get("max_results"))) {
            parameters.put("num_results", parameters.get("max_results"));
        }
        if (!hasText(parameters.get("site_search_query")) && hasText(parameters.get("finance_site_search_query"))) {
            parameters.put("site_search_query", parameters.get("finance_site_search_query"));
        }
        List<WebSearchToolProperties.FinanceSiteSearchTarget> targets = financeSiteSearchTargets(parameters.get("finance_site_id"));
        if (targets.isEmpty()) {
            log.warn("MCP exchange site-search has no configured targets requestedFinanceSiteId={} keyword={} rawArgs={}",
                requestedFinanceSiteId,
                parameters.get("site_search_query"),
                parameters);
            return;
        }
        if (!hasAny(parameters, "seed_urls", "selected_urls", "urls", "candidate_results", "target_url")) {
            String keyword = String.valueOf(parameters.getOrDefault("site_search_query", "")).trim();
            List<String> seedUrls = targets.stream()
                .map(target -> financeSiteSeedUrl(target, keyword))
                .filter(this::hasText)
                .distinct()
                .toList();
            if (!seedUrls.isEmpty()) {
                parameters.put("seed_urls", seedUrls);
            }
        }
        if (!hasText(parameters.get("mode"))) {
            String mode = financeSiteSearchMode(targets);
            if (hasText(mode)) {
                parameters.put("mode", mode);
                parameters.put("site_search_mode", mode);
            }
        }
        parameters.put("finance_site_targets", targets.stream()
            .map(WebSearchToolProperties.FinanceSiteSearchTarget::getId)
            .filter(this::hasText)
            .toList());
        log.info("MCP exchange site-search prepared requestedFinanceSiteId={} resolvedTargets={} keyword={} seedUrls={} mode={} numResults={} rawArgs={}",
            requestedFinanceSiteId,
            parameters.get("finance_site_targets"),
            parameters.get("site_search_query"),
            parameters.get("seed_urls"),
            firstNonBlank(String.valueOf(parameters.getOrDefault("site_search_mode", "")), String.valueOf(parameters.getOrDefault("mode", ""))),
            parameters.get("num_results"),
            parameters);
    }

    private List<WebSearchToolProperties.FinanceSiteSearchTarget> financeSiteSearchTargets(Object rawId) {
        if (!hasText(rawId)) {
            return enabledFinanceSiteTargets();
        }
        String id = String.valueOf(rawId).trim();
        Optional<WebSearchToolProperties.FinanceSiteSearchTarget> selected = enabledFinanceSiteTargets().stream()
            .filter(target -> id.equalsIgnoreCase(target.getId()))
            .findFirst();
        return selected.map(List::of).orElseGet(this::enabledFinanceSiteTargets);
    }

    private String financeSiteSearchMode(List<WebSearchToolProperties.FinanceSiteSearchTarget> targets) {
        if (targets == null || targets.isEmpty()) {
            return "auto";
        }
        if (targets.stream().anyMatch(target -> "browser".equalsIgnoreCase(target.getDefaultMode()))) {
            return "browser";
        }
        return targets.stream()
            .map(WebSearchToolProperties.FinanceSiteSearchTarget::getDefaultMode)
            .filter(this::hasText)
            .findFirst()
            .orElse("auto");
    }

    private String financeSiteSeedUrl(WebSearchToolProperties.FinanceSiteSearchTarget target, String keyword) {
        String template = target.getSearchUrlTemplate();
        if (!hasText(template)) {
            return target.getHomepageUrl();
        }
        String encodedKeyword = URLEncoder.encode(firstNonBlank(keyword, ""), StandardCharsets.UTF_8);
        if (template.contains("{keyword}")) {
            return template.replace("{keyword}", encodedKeyword);
        }
        if (template.contains("{}")) {
            return template.replace("{}", encodedKeyword);
        }
        if (template.endsWith("=") || template.endsWith("/") || template.endsWith("?")) {
            return template + encodedKeyword;
        }
        return template;
    }

    private boolean hasAny(Map<String, Object> parameters, String... names) {
        if (parameters == null || names == null) {
            return false;
        }
        for (String name : names) {
            if (hasText(parameters.get(name))) {
                return true;
            }
        }
        return false;
    }

    private boolean hasText(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof CharSequence text) {
            return !text.toString().isBlank();
        }
        if (value instanceof Collection<?> collection) {
            return !collection.isEmpty();
        }
        if (value instanceof Map<?, ?> map) {
            return !map.isEmpty();
        }
        return !String.valueOf(value).isBlank();
    }

    private String firstNonBlank(String... values) {
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

    private final class SearchAndExtractTool implements ToolRegistry.EnhancedTool {

        /**
         * Returns the metadata.
         *
         * @return the metadata
         */
        @Override
        public ToolMetadata getMetadata() {
            return searchAndExtractMetadata();
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
                String query = input.getParameterAsString("query", "");
                String mode = input.getParameterAsString("mode", "fast");
                Number topK = input.getParameterAsNumber("topK");
                Map<String, Object> result = searchExtractService.searchAndExtract(
                    query,
                    mode,
                    topK == null ? 5 : topK.intValue()
                );
                return ToolOutput.success(result, "Search and extraction completed successfully");
            } catch (Exception ex) {
                return ToolOutput.failure(ex);
            }
        }
    }

    private final class RetrieveEvidenceTool implements ToolRegistry.EnhancedTool {

        /**
         * Returns the metadata.
         *
         * @return the metadata
         */
        @Override
        public ToolMetadata getMetadata() {
            return retrieveEvidenceMetadata();
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
                String query = input.getParameterAsString("query", "");
                String mode = input.getParameterAsString("mode", "fast");
                Number topK = input.getParameterAsNumber("topK");
                Map<String, Object> result = searchExtractService.retrieveEvidence(
                    query,
                    mode,
                    topK == null ? 5 : topK.intValue()
                );
                return ToolOutput.success(result, "Evidence retrieved successfully");
            } catch (Exception ex) {
                return ToolOutput.failure(ex);
            }
        }
    }
}
