package com.chatchat.mcpserver.news.tool;

import com.chatchat.common.tool.ToolParameter;
import com.chatchat.mcpserver.news.financial.FinancialEnrichmentService;
import com.chatchat.mcpserver.news.runtime.NewsSearchService;
import com.chatchat.runtime.mcp.registry.McpCapabilityCodes;
import com.chatchat.runtime.mcp.registry.McpToolDefinition;
import com.chatchat.runtime.mcp.registry.McpToolExecutor;
import com.chatchat.runtime.mcp.registry.McpToolProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Publishes the web_search capability and delegates execution to its staged workflow. */
@Component
public class RemoteNewsMcpToolProvider implements McpToolProvider {
    private final WebSearchExecutionWorkflow workflow;
    private final Map<String, McpToolDefinition> definitions;

    @Autowired
    public RemoteNewsMcpToolProvider(WebSearchExecutionWorkflow workflow) {
        this.workflow = workflow;
        McpToolDefinition webSearch = definition("web_search", "Unified Web Search",
            "Unified one-call retrieval for current hotspots, place names, knowledge beyond the local corpus, "
                + "news, and governed financial data. Governed financial data and local news are searched first; "
                + "external search is a supplemental fallback and is not "
                + "a separate user-facing tool. The tool dynamically matches the governed financial-data-asset "
                + "index and reads bounded observations from relevant collected datasets before considering the "
                + "external API. Financial asset mapping is an internal stage of this tool and always receives the "
                + "same query. For an exact dataset follow-up, call web_search again with dataset.",
            List.of(text("query", "Original user question retained for local routing and audit; it is never sent directly to the external search provider", false),
                stringArray("queryTerms", "Analyzed, independent search keywords or short phrases. Local retrieval searches each item; external retrieval combines them and excludes the original question.", 8),
                stringArray("keywords", "Alias of queryTerms for analyzed search keywords", 8),
                text("intent", "Analyzed search intent used by external retrieval when analyzed keywords are unavailable", false),
                number("num_results", "Maximum number of unified search results to return", 10, 1, 50),
                bool("financial_data_required", "Compatibility marker for callers that explicitly require financial "
                    + "observations. Normal web_search calls already retrieve dynamically matched local financial data; "
                    + "callers never need to guess a dataset code.", false),
                number("financial_dataset_limit", "Maximum dynamically matched datasets when financial_data_required is true", 2, 1, 3),
                number("financial_row_limit", "Maximum rows per dynamically matched financial dataset", 20, 1, 50),
                text("dataset", "Optional dataset code returned by a financial_data_asset result", false),
                object("filters", "Exact-match filters on registered fields, such as securityCode or quoteCode"),
                text("startDate", "Optional observation start date in YYYY-MM-DD", false),
                text("endDate", "Optional observation end date in YYYY-MM-DD", false),
                text("historyMode", "Storage tier: auto (default), daily (7-day hot data), or weekly (snapshots)", false),
                text("discovery_id", "Discovery identifier returned by the first call; pass it back for retrieval-chain auditing", false),
                number("limit", "Maximum observation rows to return when dataset is provided", 50, 1, 200)), true, 30);
        this.definitions = Map.of(webSearch.name(), webSearch);
    }

    /** Compatibility constructor used by isolated tests and non-Spring embeddings. */
    public RemoteNewsMcpToolProvider(NewsSearchService newsSearch,
                                     Optional<FinancialEnrichmentService> financialEnrichment) {
        this(new WebSearchExecutionWorkflow(newsSearch, financialEnrichment));
    }

    @Override public String capabilityCode() { return McpCapabilityCodes.NEWS; }
    @Override public Collection<McpToolDefinition> definitions() { return definitions.values(); }
    @Override public Optional<McpToolExecutor> findExecutor(String toolName) {
        return definitions.containsKey(toolName) ? Optional.of(workflow::execute) : Optional.empty();
    }

    private McpToolDefinition definition(String name, String title, String description,
                                         List<ToolParameter> parameters, boolean callable, int seconds) {
        return new McpToolDefinition(name, title, description, McpCapabilityCodes.NEWS, "chatchat-mcp-server",
            parameters, true, callable, Duration.ofSeconds(seconds));
    }
    private ToolParameter text(String name, String description, boolean required) {
        return ToolParameter.builder().name(name).type("string").description(description).required(required).build();
    }
    private ToolParameter object(String name, String description) {
        return ToolParameter.builder().name(name).type("object").description(description).required(false).build();
    }
    private ToolParameter stringArray(String name, String description, int maxItems) {
        return ToolParameter.builder().name(name).type("array").description(description).required(false)
            .metadata(Map.of("items", Map.of("type", "string"), "maxItems", maxItems)).build();
    }
    private ToolParameter bool(String name, String description, boolean value) {
        return ToolParameter.builder().name(name).type("boolean").description(description).required(false)
            .defaultValue(value).build();
    }
    private ToolParameter number(String name, String description, int value, int min, int max) {
        return ToolParameter.builder().name(name).type("number").description(description).required(false)
            .defaultValue(value).minimum(min).maximum(max).build();
    }
}
