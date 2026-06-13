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
        toolRegistry.registerTool("crawl_url", crawlUrlMetadata(), new CrawlUrlTool());
        toolRegistry.registerTool("search_and_extract", searchAndExtractMetadata(), new SearchAndExtractTool());
    }

    private ToolMetadata crawlUrlMetadata() {
        return ToolMetadata.builder()
            .id("crawl_url")
            .title("Crawl URL")
            .description("Fetch a web page and return cleaned readable content, chunks, keywords, and cache metadata.")
            .version("1.0.0")
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
                    .description("Whether the caller requests JS rendering. V1 uses JSoup and reports rendered=false.")
                    .required(false)
                    .defaultValue(false)
                    .build(),
                ToolParameter.builder()
                    .name("timeout")
                    .type("integer")
                    .description("Request timeout in milliseconds")
                    .required(false)
                    .defaultValue(crawlerProperties.getTimeoutMs())
                    .minimum(1000)
                    .maximum(60000)
                    .build()
            ))
            .tags(Arrays.asList("mcp", "web", "crawler", "content_processor"))
            .metadata(Map.of(
                "cacheFirst", true,
                "contentProcessor", "jsoup_readability_v1"
            ))
            .build();
    }

    private ToolMetadata searchAndExtractMetadata() {
        return ToolMetadata.builder()
            .id("search_and_extract")
            .title("Internet Evidence Generator")
            .description("Generate ranked internet evidence chunks with citations from web search, crawling, cleaning, scoring, and deduplication.")
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
                "outputContract", "reranked evidence_chunks + citations",
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
                boolean render = input.getParameterAsBoolean("render", false);
                Number timeout = input.getParameterAsNumber("timeout");
                Map<String, Object> result = crawlerService.crawl(
                    url,
                    render,
                    timeout == null ? crawlerProperties.getTimeoutMs() : timeout.intValue()
                );
                return ToolOutput.success(result, "URL crawled and cleaned successfully");
            } catch (Exception ex) {
                return ToolOutput.failure(ex);
            }
        }
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
}
