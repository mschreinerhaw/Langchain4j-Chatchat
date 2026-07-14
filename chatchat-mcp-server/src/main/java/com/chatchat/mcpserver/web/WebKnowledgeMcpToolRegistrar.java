package com.chatchat.mcpserver.web;

import com.chatchat.mcpserver.mcp.McpToolApplicability;
import com.chatchat.agents.tool.ToolRegistry;
import com.chatchat.common.tool.ToolInput;
import com.chatchat.common.tool.ToolMetadata;
import com.chatchat.common.tool.ToolOutput;
import com.chatchat.common.tool.ToolParameter;
import com.chatchat.tools.mcp.McpServerToolRegistrar;
import com.chatchat.tools.builtin.WebSearchToolProperties;
import com.chatchat.tools.web.SiteIntelligenceResolverService;
import com.chatchat.tools.web.WebCrawlerProperties;
import com.chatchat.tools.web.WebCrawlerService;
import com.chatchat.tools.web.WebPageAnalyzeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Locale;
import java.util.regex.Pattern;

@Component
public class WebKnowledgeMcpToolRegistrar implements McpServerToolRegistrar {

    private static final Logger log = LoggerFactory.getLogger(WebKnowledgeMcpToolRegistrar.class);
    private static final Pattern HTTP_URL_PATTERN = Pattern.compile("https?://[^\\s\"'<>，。；、)）\\]}]+");
    private static final String WEB_CONTRACT_VERSION = "web_evidence_v1";

    private final WebCrawlerService crawlerService;
    private final SiteIntelligenceResolverService siteIntelligenceResolverService;
    private final WebPageAnalyzeService webPageAnalyzeService;
    private final WebSearchExtractService searchExtractService;
    private final WebCrawlerProperties crawlerProperties;
    private final WebSearchToolProperties webSearchProperties;
    private final Environment environment;

    public WebKnowledgeMcpToolRegistrar(WebCrawlerService crawlerService,
                                        SiteIntelligenceResolverService siteIntelligenceResolverService,
                                        WebPageAnalyzeService webPageAnalyzeService,
                                        WebSearchExtractService searchExtractService,
                                        WebCrawlerProperties crawlerProperties,
                                        WebSearchToolProperties webSearchProperties,
                                        Environment environment) {
        this.crawlerService = crawlerService;
        this.siteIntelligenceResolverService = siteIntelligenceResolverService;
        this.webPageAnalyzeService = webPageAnalyzeService;
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
            toolRegistry.registerTool("finance_site_search", financeSiteSearchMetadata(), new FinanceSiteSearchTool(rawWebSearch));
            toolRegistry.registerTool("generic_web_site_search", genericWebSiteSearchMetadata(), new GenericWebSiteSearchTool(rawWebSearch));
        }
        ToolMetadata crawlUrlMetadata = crawlUrlMetadata();
        ToolMetadata webCrawlerMetadata = webCrawlerMetadata();
        toolRegistry.registerTool("crawl_url", crawlUrlMetadata, new CrawlUrlTool(crawlUrlMetadata));
        toolRegistry.registerTool("web_crawler", webCrawlerMetadata, new CrawlUrlTool(webCrawlerMetadata));
        toolRegistry.registerTool("web_page_analyze", webPageAnalyzeMetadata(), new WebPageAnalyzeTool());
        toolRegistry.registerTool("site_intelligence_resolver", siteIntelligenceResolverMetadata(), new SiteIntelligenceResolverTool());
        toolRegistry.registerTool("retrieve_financial_evidence", retrieveFinancialEvidenceMetadata(), new RetrieveEvidenceTool());
        toolRegistry.registerTool("search_and_extract", searchAndExtractMetadata(), new SearchAndExtractTool());
    }

    private ToolMetadata webSearchSnippetsMetadata() {
        return ToolMetadata.builder()
            .id("web_search")
            .title("Web Search Snippets")
            .description("Search the public web and return only search result titles, URLs, and short snippets for model-side relevance judgment. This tool does not crawl pages or run secondary site search; call web_page_analyze to inspect candidate pages before crawling, finance_site_search for financial disclosure sites, generic_web_site_search for non-financial sites, or web_crawler after choosing relevant URLs.")
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
            .parameters(withWebGovernanceParameters(
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
                "workflow", "web_search_then_web_page_analyze_then_model_selects_urls_then_optional_finance_site_search_or_generic_web_site_search_or_web_crawler",
                "searchStage", "primary",
                "outputContract", "short search result snippets only",
                "modes", List.of("java", "browser", "auto"),
                "browserFallback", "browser mode falls back to Java HTTP fetching when Playwright fails",
                McpToolApplicability.META_KEY, McpToolApplicability.of(
                    "web:public_search_discovery",
                    "Public web search candidate discovery",
                    List.of("web"),
                    "Search the public web and return titles, URLs and short snippets for model-side relevance judgment.",
                    List.of("Find candidate public pages before a bound page-analysis or crawler tool is invoked."),
                    List.of("Crawling full pages", "Private or authenticated data", "Selecting or replacing Agent-bound tools")
                )
            ))
            .build();
    }

    private ToolMetadata financeSiteSearchMetadata() {
        return ToolMetadata.builder()
            .id("finance_site_search")
            .title("Finance Site Search")
            .description("Finance-only site search for configured exchange/disclosure targets such as SSE, SZSE, and CNINFO. Use this for listed-company announcements, exchange disclosures, finance filings, and securities-market source discovery. Do not use this tool for generic websites, media sites, company sites, blogs, or non-financial information retrieval; use generic_web_site_search and web_crawler for those.")
            .version("1.2.0")
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
            .parameters(withWebGovernanceParameters(
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
                Map.entry("financeOnly", true),
                Map.entry("doNotUseFor", List.of("generic websites", "media sites", "company sites", "blogs", "non-financial site search")),
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

    private ToolMetadata genericWebSiteSearchMetadata() {
        return ToolMetadata.builder()
            .id("generic_web_site_search")
            .title("Generic Website Site Search")
            .description("Generic non-financial site search. Use this when the model has a website entrance URL, media site, company site, blog, documentation site, or portal and needs to discover/search within that site. For financial disclosure exchanges use finance_site_search instead.")
            .version("1.0.0")
            .author("ChatChat MCP Server")
            .categories(Arrays.asList("mcp", "search", "internet", "site-search", "generic"))
            .category("http_web_search")
            .riskLevel(environment.getProperty("chatchat.tools.web-search.risk-level", "low"))
            .operationType(environment.getProperty("chatchat.tools.web-search.operation-type", "read"))
            .runtimeLevel(environment.getProperty("chatchat.tools.web-search.runtime-level", "readonly"))
            .confirmation(Map.of("default", environment.getProperty("chatchat.tools.web-search.confirmation.default", "auto_execute")))
            .inputPolicy(Map.of("must_show_parameters", true))
            .outputType("json")
            .timeoutMillis(0L)
            .agentCompatible(true)
            .parameters(withWebGovernanceParameters(
                ToolParameter.builder()
                    .name("site_url")
                    .type("string")
                    .description("Website entrance URL to search within, for example https://www.jiqizhixin.com/")
                    .required(true)
                    .minLength(8)
                    .maxLength(2000)
                    .build(),
                ToolParameter.builder()
                    .name("site_search_query")
                    .type("string")
                    .description("Keyword or phrase to search within the target website.")
                    .required(true)
                    .minLength(1)
                    .maxLength(300)
                    .build(),
                ToolParameter.builder()
                    .name("query")
                    .type("string")
                    .description("Optional original user question. If omitted, site_search_query is used.")
                    .required(false)
                    .maxLength(500)
                    .build(),
                ToolParameter.builder()
                    .name("mode")
                    .type("string")
                    .description("Discovery and site-search mode. auto uses browser when enabled; java uses static HTTP parsing; browser uses Playwright.")
                    .required(false)
                    .defaultValue("auto")
                    .enumValues(new String[]{"java", "browser", "auto"})
                    .build(),
                ToolParameter.builder()
                    .name("num_results")
                    .type("number")
                    .description("Maximum generic site-search candidates to return")
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
            .tags(Arrays.asList("mcp", "web", "generic_site_search", "site_intelligence", "llm_rerank"))
            .metadata(Map.of(
                "workflow", "site_intelligence_resolver_then_generic_site_search",
                "financeOnly", false,
                "useFor", List.of("media sites", "company sites", "blogs", "documentation sites", "portal homepages"),
                "useFinanceSiteSearchFor", "financial disclosure exchange targets only",
                "outputContract", "site_intelligence + crawl_candidates + model_selection_text with candidate URLs and evidence cards",
                "searchStage", "site_search"
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

    private ToolMetadata webPageAnalyzeMetadata() {
        return ToolMetadata.builder()
            .id("web_page_analyze")
            .title("Web Page Analyze")
            .description("Analyze one current web page before crawling. It fetches only the given URL and returns page metadata, search forms, ranked links with short snippets, pagination candidates, and recommended next actions so the model can choose which URL to crawl. This tool does not crawl the whole site and does not extract full page content.")
            .version("1.0.0")
            .author("ChatChat MCP Server")
            .categories(Arrays.asList("mcp", "crawler", "internet", "page-analysis"))
            .category("http_page_analysis")
            .riskLevel(environment.getProperty("chatchat.tools.web-page-analyze.risk-level", "low"))
            .operationType("read")
            .runtimeLevel("readonly")
            .confirmation(Map.of("default", environment.getProperty("chatchat.tools.web-page-analyze.confirmation.default", "auto_execute")))
            .inputPolicy(Map.of("must_show_parameters", true))
            .outputType("json")
            .timeoutMillis(0L)
            .agentCompatible(true)
            .parameters(withWebGovernanceParameters(
                ToolParameter.builder()
                    .name("url")
                    .type("string")
                    .description("HTTP or HTTPS page URL to analyze. The tool only inspects this page and does not recursively crawl the site. Preferred parameter name is url; site_url/pageUrl/target_url/base_url are also accepted for compatibility.")
                    .required(true)
                    .minLength(8)
                    .maxLength(2000)
                    .build(),
                ToolParameter.builder()
                    .name("query")
                    .type("string")
                    .description("Optional user question or retrieval goal used to score links and recommended actions.")
                    .required(false)
                    .maxLength(1000)
                    .build(),
                ToolParameter.builder()
                    .name("maxLinks")
                    .type("integer")
                    .description("Maximum ranked links to return from the current page")
                    .required(false)
                    .defaultValue(50)
                    .minimum(1)
                    .maximum(200)
                    .build()
            ))
            .tags(Arrays.asList("mcp", "web", "page_analyze", "url_map", "crawl_planning"))
            .metadata(Map.of(
                "workflow", "web_search_then_web_page_analyze_then_model_selects_urls_then_crawl_url",
                "outputContract", "pageUrl + title + description + canonicalUrl + pageType + searchForms + ranked links(url,text,title,snippet,type,score) + pagination + recommendedActions",
                "crawlPolicy", "analyze first, then crawl only model-selected URLs",
                "doesNotDo", List.of("full site crawling", "recursive crawling", "full content extraction")
            ))
            .build();
    }

    private ToolMetadata siteIntelligenceResolverMetadata() {
        return ToolMetadata.builder()
            .id("site_intelligence_resolver")
            .title("Site Intelligence Resolver")
            .description("Discover how a website can be searched: search forms, URL templates, sitemap hints, route candidates, and recommended retrieval strategy. Use this before generic_web_site_search when the model only has a non-financial entrance URL such as a portal homepage.")
            .version("1.0.0")
            .author("ChatChat MCP Server")
            .categories(Arrays.asList("mcp", "crawler", "internet", "site-intelligence"))
            .category("http_site_intelligence")
            .riskLevel(environment.getProperty("chatchat.tools.site-intelligence.risk-level", "low"))
            .operationType("read")
            .runtimeLevel("readonly")
            .confirmation(Map.of("default", environment.getProperty("chatchat.tools.site-intelligence.confirmation.default", "auto_execute")))
            .inputPolicy(Map.of("must_show_parameters", true))
            .outputType("json")
            .timeoutMillis(0L)
            .agentCompatible(true)
            .parameters(withWebGovernanceParameters(
                ToolParameter.builder()
                    .name("url")
                    .type("string")
                    .description("HTTP or HTTPS site entrance URL, for example https://finance.sina.com.cn/. Preferred parameter name is url; site_url/pageUrl/target_url/base_url/homepageUrl are also accepted for compatibility.")
                    .required(true)
                    .minLength(8)
                    .maxLength(2000)
                    .build(),
                ToolParameter.builder()
                    .name("probe_query")
                    .type("string")
                    .description("Optional sample keyword used only to produce sample search URLs from discovered templates.")
                    .required(false)
                    .defaultValue("test")
                    .maxLength(200)
                    .build(),
                ToolParameter.builder()
                    .name("mode")
                    .type("string")
                    .description("Discovery mode: java/static uses HTML parsing; browser uses Playwright DOM and network sniffing; auto uses browser when enabled.")
                    .required(false)
                    .defaultValue("auto")
                    .enumValues(new String[]{"java", "browser", "auto"})
                    .build()
            ))
            .tags(Arrays.asList("mcp", "site", "intelligence", "search_discovery", "routing"))
            .metadata(Map.of(
                "workflow", "site_entry_url_to_search_capabilities",
                "outputContract", "capabilities + search_endpoint_candidates + discovered_routes + recommended_strategy",
                "layers", List.of("static_html_analysis", "browser_dom_analysis", "network_search_request_sniffing", "heuristic_known_site_rules"),
                "useBefore", "generic_web_site_search or web_crawler when only a non-financial site entrance URL is available"
            ))
            .build();
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
            .parameters(withWebGovernanceParameters(
                ToolParameter.builder()
                    .name("url")
                    .type("string")
                    .description("HTTP or HTTPS URL to crawl. Preferred parameter name is url; pageUrl/site_url/target_url are also accepted for compatibility.")
                    .required(true)
                    .minLength(8)
                    .maxLength(2000)
                    .build(),
                ToolParameter.builder()
                    .name("query")
                    .type("string")
                    .description("Optional user query used to rank returned evidence blocks for model judgment.")
                    .required(false)
                    .maxLength(1000)
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
            .timeoutMillis(0L)
            .agentCompatible(false)
            .parameters(withWebGovernanceParameters(
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

    private ToolMetadata retrieveFinancialEvidenceMetadata() {
        return ToolMetadata.builder()
            .id("retrieve_financial_evidence")
            .title("Retrieve Financial Evidence")
            .description("Finance-oriented evidence-chain retrieval. Use this for financial information, listed-company disclosures, securities-market questions, filings, announcements, and finance-source evidence packages. It returns compact ranked evidence_chunks, citations, source URLs, and retrieval metadata for LLM reasoning. For non-financial information, compose web_search, generic_web_site_search, site_intelligence_resolver, and web_crawler directly.")
            .version("1.0.0")
            .author("ChatChat MCP Server")
            .categories(Arrays.asList("mcp", "search", "crawler", "internet", "rag", "finance"))
            .category("http_web_search")
            .riskLevel(environment.getProperty("chatchat.mcp.retrieve-evidence.risk-level", "low"))
            .operationType("read")
            .runtimeLevel("readonly")
            .confirmation(Map.of("default", environment.getProperty("chatchat.mcp.retrieve-evidence.confirmation.default", "auto_execute")))
            .inputPolicy(Map.of("must_show_parameters", true))
            .outputType("json")
            .timeoutMillis(0L)
            .agentCompatible(true)
            .parameters(withWebGovernanceParameters(
                ToolParameter.builder()
                    .name("query")
                    .type("string")
                    .description("Financial user question or search query")
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
            .tags(Arrays.asList("mcp", "web", "search", "evidence", "rag", "finance", "financial_evidence", "cache_first"))
            .metadata(Map.of(
                "cacheFirst", true,
                "workflow", "financial-web-search-crawl-content-process-evidence",
                "financeOnly", true,
                "doNotUseFor", List.of("generic non-financial website search", "general news unrelated to finance", "documentation lookup", "blog search"),
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
                String url = urlParameter(input, "url", "pageUrl", "page_url", "site_url", "target_url", "base_url", "homepageUrl", "homepage_url");
                if (!hasText(url)) {
                    return missingUrlFailure("crawl_url/web_crawler", input);
                }
                WebToolRequestContext context = webToolRequestContext(input);
                if (!isAllowedUrl(url, context)) {
                    return domainPolicyFailure(metadata.getId(), url, context);
                }
                String mode = input.getParameterAsString("mode", crawlerProperties.getDefaultMode());
                boolean render = input.getParameterAsBoolean("render", false);
                Map<String, Object> result = crawlerService.crawl(
                    url,
                    mode,
                    render,
                    0,
                    new WebCrawlerService.CrawlRequestContext(
                        context.tenantId(),
                        context.taskId(),
                        context.agentId(),
                        input.getParameterAsString("query", "")
                    )
                );
                return ToolOutput.success(enrichWebToolResult(result, metadata.getId(), context), "URL crawled and cleaned successfully");
            } catch (Exception ex) {
                return ToolOutput.failure(ex);
            }
        }
    }

    private final class WebPageAnalyzeTool implements ToolRegistry.EnhancedTool {

        @Override
        public ToolMetadata getMetadata() {
            return webPageAnalyzeMetadata();
        }

        @Override
        public ToolOutput execute(ToolInput input) {
            try {
                String url = urlParameter(input, "url", "pageUrl", "page_url", "site_url", "target_url", "base_url", "homepageUrl", "homepage_url");
                if (!hasText(url)) {
                    return missingUrlFailure("web_page_analyze", input);
                }
                WebToolRequestContext context = webToolRequestContext(input);
                if (!isAllowedUrl(url, context)) {
                    return domainPolicyFailure("web_page_analyze", url, context);
                }
                String query = input.getParameterAsString("query", "");
                Number maxLinks = input.getParameterAsNumber("maxLinks");
                Map<String, Object> result = webPageAnalyzeService.analyze(
                    url,
                    query,
                    maxLinks == null ? 50 : maxLinks.intValue(),
                    0
                );
                return ToolOutput.success(enrichWebToolResult(result, "web_page_analyze", context), "Web page analyzed successfully");
            } catch (Exception ex) {
                return ToolOutput.failure(ex);
            }
        }
    }

    private final class SiteIntelligenceResolverTool implements ToolRegistry.EnhancedTool {

        @Override
        public ToolMetadata getMetadata() {
            return siteIntelligenceResolverMetadata();
        }

        @Override
        public ToolOutput execute(ToolInput input) {
            try {
                String url = urlParameter(input, "url", "site_url", "pageUrl", "page_url", "target_url", "base_url", "homepageUrl", "homepage_url", "entrance_url");
                if (!hasText(url)) {
                    return missingUrlFailure("site_intelligence_resolver", input);
                }
                WebToolRequestContext context = webToolRequestContext(input);
                if (!isAllowedUrl(url, context)) {
                    return domainPolicyFailure("site_intelligence_resolver", url, context);
                }
                String mode = input.getParameterAsString("mode", "auto");
                String probeQuery = input.getParameterAsString("probe_query", "test");
                Map<String, Object> result = siteIntelligenceResolverService.resolve(
                    url,
                    mode,
                    probeQuery,
                    0
                );
                return ToolOutput.success(enrichWebToolResult(result, "site_intelligence_resolver", context), "Site intelligence resolved successfully");
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

    private final class FinanceSiteSearchTool implements ToolRegistry.EnhancedTool {

        private final ToolRegistry.EnhancedTool delegate;

        private FinanceSiteSearchTool(ToolRegistry.EnhancedTool delegate) {
            this.delegate = delegate;
        }

        @Override
        public ToolMetadata getMetadata() {
            return financeSiteSearchMetadata();
        }

        @Override
        public ToolOutput execute(ToolInput input) {
            return delegate.execute(webSearchInput(input, "site_search"));
        }
    }

    private final class GenericWebSiteSearchTool implements ToolRegistry.EnhancedTool {

        private final ToolRegistry.EnhancedTool delegate;

        private GenericWebSiteSearchTool(ToolRegistry.EnhancedTool delegate) {
            this.delegate = delegate;
        }

        @Override
        public ToolMetadata getMetadata() {
            return genericWebSiteSearchMetadata();
        }

        @Override
        public ToolOutput execute(ToolInput input) {
            try {
                String siteUrl = input.getParameterAsString("site_url", "");
                String keyword = input.getParameterAsString("site_search_query", "");
                WebToolRequestContext context = webToolRequestContext(input);
                if (hasText(siteUrl) && !isAllowedUrl(siteUrl, context)) {
                    return domainPolicyFailure("generic_web_site_search", siteUrl, context);
                }
                String mode = input.getParameterAsString("mode", "auto");
                Map<String, Object> siteIntelligence = siteIntelligenceResolverService.resolve(
                    siteUrl,
                    mode,
                    keyword,
                    0
                );
                ToolInput searchInput = genericSiteSearchInput(input, siteUrl, keyword, mode, siteIntelligence);
                ToolOutput output = delegate.execute(searchInput);
                if (output.isSuccess() && output.getData() instanceof Map<?, ?> data) {
                    Map<String, Object> merged = new LinkedHashMap<>();
                    data.forEach((key, value) -> {
                        if (key != null) {
                            merged.put(String.valueOf(key), value);
                        }
                    });
                    merged.put("site_intelligence", enrichWebToolResult(siteIntelligence, "site_intelligence_resolver", context));
                    merged.put("tool_scope", "generic_non_financial_site_search");
                    enrichWebToolResult(merged, "generic_web_site_search", context);
                    return ToolOutput.success(merged, "Generic site search completed successfully");
                }
                return output;
            } catch (Exception ex) {
                return ToolOutput.failure(ex);
            }
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

    private ToolInput genericSiteSearchInput(ToolInput input,
                                             String siteUrl,
                                             String keyword,
                                             String mode,
                                             Map<String, Object> siteIntelligence) {
        Map<String, Object> parameters = new LinkedHashMap<>(input == null || input.getParameters() == null
            ? Map.of()
            : input.getParameters());
        parameters.remove("site_url");
        parameters.put("search_stage", "site_search");
        parameters.put("site_search_query", keyword);
        parameters.put("query", firstNonBlank(input == null ? null : input.getParameterAsString("query", ""), keyword));
        parameters.put("site_search_mode", mode);
        if (!hasText(parameters.get("num_results")) && hasText(parameters.get("max_results"))) {
            parameters.put("num_results", parameters.get("max_results"));
        }
        List<String> seedUrls = genericSiteSearchSeedUrls(siteUrl, keyword, siteIntelligence);
        parameters.put("seed_urls", seedUrls);
        parameters.put("target_url", siteUrl);
        parameters.put("generic_site_search", true);
        return ToolInput.builder()
            .rawInput(input == null ? null : input.getRawInput())
            .parameters(parameters)
            .requestId(input == null ? null : input.getRequestId())
            .userId(input == null ? null : input.getUserId())
            .conversationId(input == null ? null : input.getConversationId())
            .context(input == null || input.getContext() == null ? Map.of() : input.getContext())
            .build();
    }

    @SuppressWarnings("unchecked")
    private List<String> genericSiteSearchSeedUrls(String siteUrl,
                                                   String keyword,
                                                   Map<String, Object> siteIntelligence) {
        List<String> seeds = new ArrayList<>();
        if (hasText(siteUrl)) {
            seeds.add(siteUrl);
        }
        Object capabilitiesValue = siteIntelligence == null ? null : siteIntelligence.get("capabilities");
        if (capabilitiesValue instanceof Map<?, ?> capabilities) {
            Object endpointsValue = capabilities.get("search_endpoint_candidates");
            if (endpointsValue instanceof List<?> endpoints) {
                for (Object endpointValue : endpoints) {
                    if (!(endpointValue instanceof Map<?, ?> endpoint)) {
                        continue;
                    }
                    double confidence = numberValue(endpoint.get("confidence"), 0);
                    if (confidence < 0.6) {
                        continue;
                    }
                    String sampleUrl = firstNonBlank(
                        stringValue(endpoint.get("sample_url")),
                        searchTemplateToUrl(stringValue(endpoint.get("url")), keyword)
                    );
                    if (hasText(sampleUrl)) {
                        seeds.add(sampleUrl);
                    }
                }
            }
        }
        Object routesValue = siteIntelligence == null ? null : siteIntelligence.get("discovered_routes");
        if (routesValue instanceof List<?> routes) {
            for (Object routeValue : routes) {
                if (!(routeValue instanceof Map<?, ?> route)) {
                    continue;
                }
                double confidence = numberValue(route.get("confidence"), 0);
                if (confidence >= 0.65 && hasText(route.get("url"))) {
                    seeds.add(stringValue(route.get("url")));
                }
            }
        }
        return seeds.stream()
            .filter(this::hasText)
            .distinct()
            .limit(12)
            .toList();
    }

    private String searchTemplateToUrl(String template, String keyword) {
        if (!hasText(template)) {
            return null;
        }
        String encoded = encodeQueryValue(keyword);
        return template.contains("{q}") ? template.replace("{q}", encoded) : template;
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
            String keyword = blankToEmpty(stringValue(parameters.get("site_search_query")));
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
        String encodedKeyword = encodeQueryValue(keyword);
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

    private List<ToolParameter> withWebGovernanceParameters(ToolParameter... parameters) {
        List<ToolParameter> values = new ArrayList<>();
        if (parameters != null) {
            values.addAll(Arrays.asList(parameters));
        }
        List<String> names = values.stream().map(ToolParameter::getName).toList();
        if (!names.contains("tenantId")) {
            values.add(ToolParameter.builder()
                .name("tenantId")
                .type("string")
                .description("Optional tenant identifier used for request isolation")
                .required(false)
                .maxLength(128)
                .build());
        }
        if (!names.contains("userId")) {
            values.add(ToolParameter.builder()
                .name("userId")
                .type("string")
                .description("Optional user identifier used for audit and request isolation")
                .required(false)
                .maxLength(128)
                .build());
        }
        if (!names.contains("roles")) {
            values.add(ToolParameter.builder()
                .name("roles")
                .type("array")
                .description("Optional caller roles used for policy-aware web retrieval")
                .required(false)
                .metadata(Map.of("items", Map.of("type", "string")))
                .build());
        }
        if (!names.contains("sourceTaskId")) {
            values.add(ToolParameter.builder()
                .name("sourceTaskId")
                .type("string")
                .description("Optional task identifier used for request isolation")
                .required(false)
                .maxLength(128)
                .build());
        }
        if (!names.contains("agentId")) {
            values.add(ToolParameter.builder()
                .name("agentId")
                .type("string")
                .description("Optional agent identifier used for audit context")
                .required(false)
                .maxLength(128)
                .build());
        }
        if (!names.contains("allowedDomains")) {
            values.add(ToolParameter.builder()
                .name("allowedDomains")
                .type("array")
                .description("Optional request-scoped allow-list. When set, the web tool only returns or fetches URLs under these domains.")
                .required(false)
                .metadata(Map.of("items", Map.of("type", "string")))
                .build());
        }
        if (!names.contains("blockedDomains")) {
            values.add(ToolParameter.builder()
                .name("blockedDomains")
                .type("array")
                .description("Optional request-scoped deny-list. Matching URLs are blocked even when other allow rules match.")
                .required(false)
                .metadata(Map.of("items", Map.of("type", "string")))
                .build());
        }
        return values;
    }

    private WebToolRequestContext webToolRequestContext(ToolInput input) {
        Map<String, Object> parameters = input == null || input.getParameters() == null ? Map.of() : input.getParameters();
        return new WebToolRequestContext(
            firstNonBlank(stringValue(parameters.get("tenantId")), stringValue(parameters.get("tenant_id")), "default"),
            firstNonBlank(stringValue(parameters.get("userId")), stringValue(parameters.get("user_id")), input == null ? null : input.getUserId(), "anonymous"),
            stringList(firstObject(parameters, "roles", "role")),
            firstNonBlank(stringValue(parameters.get("sourceTaskId")), stringValue(parameters.get("taskId")), stringValue(parameters.get("task_id"))),
            firstNonBlank(stringValue(parameters.get("agentId")), stringValue(parameters.get("agent_id"))),
            stringList(firstObject(parameters, "allowedDomains", "allowed_domains", "allowDomains")),
            stringList(firstObject(parameters, "blockedDomains", "blocked_domains", "denyDomains"))
        );
    }

    private Object firstObject(Map<String, Object> parameters, String... names) {
        if (parameters == null || names == null) {
            return null;
        }
        for (String name : names) {
            Object value = parameters.get(name);
            if (hasText(value)) {
                return value;
            }
        }
        return null;
    }

    private List<String> stringList(Object value) {
        List<String> values = new ArrayList<>();
        if (value instanceof Collection<?> collection) {
            for (Object item : collection) {
                addTextValue(values, item);
            }
        } else if (value instanceof String text) {
            for (String part : text.split("[,;\\s]+")) {
                addTextValue(values, part);
            }
        } else {
            addTextValue(values, value);
        }
        return values;
    }

    private void addTextValue(List<String> values, Object value) {
        if (value == null) {
            return;
        }
        String text = String.valueOf(value).trim();
        if (!text.isBlank() && !values.contains(text)) {
            values.add(text);
        }
    }

    private boolean isAllowedUrl(String url, WebToolRequestContext context) {
        String host = normalizedHost(url);
        if (!hasText(host)) {
            return false;
        }
        if (matchesDomain(host, context == null ? List.of() : context.blockedDomains())) {
            return false;
        }
        List<String> allowedDomains = context == null ? List.of() : context.allowedDomains();
        return allowedDomains.isEmpty() || matchesDomain(host, allowedDomains);
    }

    private String normalizedHost(String url) {
        try {
            URI uri = URI.create(url);
            String host = uri.getHost();
            if (!hasText(host)) {
                return "";
            }
            return host.toLowerCase(Locale.ROOT);
        } catch (Exception ignored) {
            return "";
        }
    }

    private boolean matchesDomain(String normalizedHost, List<String> domains) {
        if (!hasText(normalizedHost) || domains == null || domains.isEmpty()) {
            return false;
        }
        for (String domain : domains) {
            String candidate = normalizedHost(domain);
            if (!hasText(candidate)) {
                candidate = domain == null ? "" : domain.trim().toLowerCase(Locale.ROOT);
            }
            if (candidate.startsWith(".")) {
                candidate = candidate.substring(1);
            }
            if (hasText(candidate) && (normalizedHost.equals(candidate) || normalizedHost.endsWith("." + candidate))) {
                return true;
            }
        }
        return false;
    }

    private Map<String, Object> enrichWebToolResult(Map<String, Object> result,
                                                    String toolName,
                                                    WebToolRequestContext context) {
        Map<String, Object> values = result == null ? new LinkedHashMap<>() : result;
        values.putIfAbsent("contractVersion", WEB_CONTRACT_VERSION);
        values.put("requestContext", webRequestContextMap(context));
        values.put("governance", webGovernanceMap(toolName, context));
        return values;
    }

    private Map<String, Object> webRequestContextMap(WebToolRequestContext context) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("tenantId", context == null ? "default" : firstNonBlank(context.tenantId(), "default"));
        values.put("userId", context == null ? "anonymous" : firstNonBlank(context.userId(), "anonymous"));
        values.put("roles", context == null ? List.of() : context.roles());
        values.put("taskId", context == null ? null : context.taskId());
        values.put("agentId", context == null ? null : context.agentId());
        values.put("allowedDomains", context == null ? List.of() : context.allowedDomains());
        values.put("blockedDomains", context == null ? List.of() : context.blockedDomains());
        return values;
    }

    private Map<String, Object> webGovernanceMap(String toolName, WebToolRequestContext context) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("tool", toolName);
        values.put("contractVersion", WEB_CONTRACT_VERSION);
        values.put("domainPolicy", Map.of(
            "requestAllowListEnabled", context != null && !context.allowedDomains().isEmpty(),
            "allowedDomains", context == null ? List.of() : context.allowedDomains(),
            "blockedDomains", context == null ? List.of() : context.blockedDomains()
        ));
        return values;
    }

    private ToolOutput domainPolicyFailure(String toolName, String url, WebToolRequestContext context) {
        Map<String, Object> result = enrichWebToolResult(new LinkedHashMap<>(), toolName, context);
        result.put("url", url);
        result.put("blocked", true);
        result.put("blockReason", "domain_policy_denied");
        return ToolOutput.builder()
            .success(false)
            .data(result)
            .errorMessage("URL is blocked by request domain policy: " + url)
            .build();
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

    private String urlParameter(ToolInput input, String... names) {
        if (input == null) {
            return "";
        }
        Map<String, Object> parameters = input.getParameters() == null ? Map.of() : input.getParameters();
        for (String name : names) {
            Object value = parameters.get(name);
            if (hasText(value)) {
                String extracted = extractHttpUrl(String.valueOf(value));
                return hasText(extracted) ? extracted : String.valueOf(value).trim();
            }
        }
        for (Object value : parameters.values()) {
            if (value instanceof String text) {
                String extracted = extractHttpUrl(text);
                if (hasText(extracted)) {
                    return extracted;
                }
            }
        }
        return extractHttpUrl(input.getRawInput());
    }

    private String extractHttpUrl(String value) {
        if (!hasText(value)) {
            return "";
        }
        var matcher = HTTP_URL_PATTERN.matcher(value);
        return matcher.find() ? matcher.group() : "";
    }

    private ToolOutput missingUrlFailure(String toolName, ToolInput input) {
        Map<String, Object> parameters = input == null || input.getParameters() == null ? Map.of() : input.getParameters();
        String message = "url is required; accepted url parameter aliases: url, site_url, pageUrl, page_url, target_url, base_url, homepageUrl";
        log.warn("Web tool missing URL tool={} requestId={} args={}", toolName, input == null ? null : input.getRequestId(), parameters);
        return ToolOutput.failure(message);
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String blankToEmpty(String value) {
        return value == null || value.isBlank() ? "" : value.trim();
    }

    private String encodeQueryValue(String value) {
        return URLEncoder.encode(blankToEmpty(value), StandardCharsets.UTF_8);
    }

    private double numberValue(Object value, double fallback) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Double.parseDouble(text.trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
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

    private record WebToolRequestContext(String tenantId,
                                         String userId,
                                         List<String> roles,
                                         String taskId,
                                         String agentId,
                                         List<String> allowedDomains,
                                         List<String> blockedDomains) {
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
                return ToolOutput.success(enrichWebToolResult(result, "search_and_extract", webToolRequestContext(input)),
                    "Search and extraction completed successfully");
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
            return retrieveFinancialEvidenceMetadata();
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
                return ToolOutput.success(enrichWebToolResult(result, "retrieve_financial_evidence", webToolRequestContext(input)),
                    "Evidence retrieved successfully");
            } catch (Exception ex) {
                return ToolOutput.failure(ex);
            }
        }
    }
}
