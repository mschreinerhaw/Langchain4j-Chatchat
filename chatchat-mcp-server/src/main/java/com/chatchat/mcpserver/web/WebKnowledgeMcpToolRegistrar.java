package com.chatchat.mcpserver.web;

import com.chatchat.agents.tool.ToolRegistry;
import com.chatchat.common.tool.ToolInput;
import com.chatchat.common.tool.ToolMetadata;
import com.chatchat.common.tool.ToolOutput;
import com.chatchat.common.tool.ToolParameter;
import com.chatchat.mcpserver.tool.McpServerToolRegistrar;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class WebKnowledgeMcpToolRegistrar implements McpServerToolRegistrar {

    private final WebCrawlerService crawlerService;
    private final WebSearchExtractService searchExtractService;
    private final WebCrawlerProperties crawlerProperties;
    private final Environment environment;

    public WebKnowledgeMcpToolRegistrar(WebCrawlerService crawlerService,
                                        WebSearchExtractService searchExtractService,
                                        WebCrawlerProperties crawlerProperties,
                                        Environment environment) {
        this.crawlerService = crawlerService;
        this.searchExtractService = searchExtractService;
        this.crawlerProperties = crawlerProperties;
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
        toolRegistry.registerTool("crawl_url", crawlUrlMetadata(), new CrawlUrlTool());
        toolRegistry.registerTool("retrieve_evidence", retrieveEvidenceMetadata(), new RetrieveEvidenceTool());
        toolRegistry.registerTool("search_and_extract", searchAndExtractMetadata(), new SearchAndExtractTool());
    }

    private ToolMetadata webSearchSnippetsMetadata() {
        return ToolMetadata.builder()
            .id("web_search")
            .title("Web Search Snippets")
            .description("Search the public web and return only search result titles, URLs, and short snippets for model-side relevance judgment. This tool does not crawl pages or run secondary site search; call web_site_search or crawl_url after choosing relevant URLs.")
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
            .timeoutMillis(90000L)
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
                    .description("Web search fetch mode: java uses browser-like Java HTTP fetching; browser tries Playwright Chromium first and falls back to java; auto follows web-search configuration.")
                    .required(false)
                    .defaultValue("java")
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
                "workflow", "web_search_then_model_judges_then_optional_web_site_search_or_crawl_url",
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
            .title("Web Site Search")
            .description("Run secondary site-search against model-selected seed URLs from web_search. Use after the model judges which initial search results are relevant.")
            .version("1.0.0")
            .author("ChatChat MCP Server")
            .categories(Arrays.asList("mcp", "search", "internet", "site-search"))
            .category("http_web_search")
            .riskLevel(environment.getProperty("chatchat.tools.web-search.risk-level", "low"))
            .operationType(environment.getProperty("chatchat.tools.web-search.operation-type", "read"))
            .runtimeLevel(environment.getProperty("chatchat.tools.web-search.runtime-level", "readonly"))
            .confirmation(Map.of("default", environment.getProperty("chatchat.tools.web-search.confirmation.default", "auto_execute")))
            .inputPolicy(Map.of("must_show_parameters", true))
            .outputType("json")
            .timeoutMillis(90000L)
            .agentCompatible(true)
            .parameters(List.of(
                ToolParameter.builder()
                    .name("query")
                    .type("string")
                    .description("Original user query or focused site-search keyword")
                    .required(true)
                    .minLength(1)
                    .maxLength(500)
                    .build(),
                ToolParameter.builder()
                    .name("seed_urls")
                    .type("array")
                    .description("URLs selected from web_search results for secondary site search")
                    .required(false)
                    .metadata(Map.of("items", Map.of("type", "string")))
                    .build(),
                ToolParameter.builder()
                    .name("candidate_results")
                    .type("array")
                    .description("Selected web_search result objects. Each object should include url, title, snippet, and rank when available.")
                    .required(false)
                    .metadata(Map.of("items", Map.of("type", "object")))
                    .build(),
                ToolParameter.builder()
                    .name("site_search_query")
                    .type("string")
                    .description("Optional focused keyword submitted to the target site's own search form/API")
                    .required(false)
                    .maxLength(300)
                    .build(),
                ToolParameter.builder()
                    .name("mode")
                    .type("string")
                    .description("Site-search fetch mode: java uses browser-like Java HTTP fetching; browser tries Playwright Chromium first and falls back to java; auto follows site-search configuration.")
                    .required(false)
                    .defaultValue("java")
                    .enumValues(new String[]{"java", "browser", "auto"})
                    .build(),
                ToolParameter.builder()
                    .name("num_results")
                    .type("number")
                    .description("Maximum secondary result candidates to return")
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
            .tags(Arrays.asList("mcp", "web", "secondary_search", "site_search", "llm_selected"))
            .metadata(Map.of(
                "workflow", "model_selected_secondary_site_search",
                "searchStage", "site_search",
                "outputContract", "secondary site-search result snippets",
                "modes", List.of("java", "browser", "auto"),
                "browserFallback", "browser mode falls back to Java HTTP fetching when Playwright fails"
            ))
            .build();
    }

    private ToolMetadata crawlUrlMetadata() {
        return ToolMetadata.builder()
            .id("crawl_url")
            .title("Crawl URL")
            .description("Fetch a web page and return cleaned readable content, chunks, keywords, and cache metadata. Supports browser rendering mode and Java browser-like HTTP mode.")
            .version("1.1.0")
            .author("ChatChat MCP Server")
            .categories(Arrays.asList("mcp", "crawler", "internet"))
            .category("http_crawler")
            .riskLevel(environment.getProperty("chatchat.mcp.web-crawler.risk-level", "low"))
            .operationType("read")
            .runtimeLevel("readonly")
            .confirmation(Map.of("default", environment.getProperty("chatchat.mcp.web-crawler.confirmation.default", "auto_execute")))
            .inputPolicy(Map.of("must_show_parameters", true))
            .outputType("json")
            .timeoutMillis((long) Math.max(30000, crawlerProperties.getTimeoutMs() + 5000))
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
                    .description("Request timeout in milliseconds")
                    .required(false)
                    .defaultValue(crawlerProperties.getTimeoutMs())
                    .minimum(1000)
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

        /**
         * Returns the metadata.
         *
         * @return the metadata
         */
        @Override
        public ToolMetadata getMetadata() {
            return crawlUrlMetadata();
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
        if ("primary".equals(searchStage)) {
            parameters.put("include_site_search", false);
            parameters.put("fetch_pages", false);
            if (parameters.containsKey("mode")) {
                parameters.put("web_search_mode", parameters.get("mode"));
            }
        } else if ("site_search".equals(searchStage) && parameters.containsKey("mode")) {
            parameters.put("site_search_mode", parameters.get("mode"));
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
