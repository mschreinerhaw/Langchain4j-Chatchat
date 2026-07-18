package com.chatchat.mcpserver.news;

import com.chatchat.common.tool.ToolInput;
import com.chatchat.common.tool.ToolOutput;
import com.chatchat.common.tool.ToolParameter;
import com.chatchat.runtime.mcp.registry.McpCapabilityCodes;
import com.chatchat.runtime.mcp.registry.McpToolDefinition;
import com.chatchat.runtime.mcp.registry.McpToolExecutor;
import com.chatchat.runtime.mcp.registry.McpToolProvider;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
public class RemoteNewsMcpToolProvider implements McpToolProvider {
    private final NewsRuntimeClient client;
    private final Map<String, McpToolDefinition> definitions;

    public RemoteNewsMcpToolProvider(NewsRuntimeClient client) {
        this.client = client;
        this.definitions = List.of(
            definition("web_search", "News Search", "Performs hybrid keyword and semantic search across news article content and PDF/Excel attachment chunks.",
                List.of(text("query", "News topic or keywords to search for", true), number("num_results", "Maximum number of results to return", 10, 1, 50)), true, 30),
            definition("news_search", "Structured News Search", "Searches news article content and attachment evidence chunks by keyword, source, publication time, and category.",
                List.of(text("query", "Search keywords or topic", true), array("sourceIds", "Optional news source IDs"),
                    text("startTime", "Start time in ISO-8601 format", false), text("endTime", "End time in ISO-8601 format", false),
                    array("categories", "Optional categories"), number("size", "Maximum number of results to return", 10, 1, 50)), true, 30),
            definition("news_latest", "Latest News", "Retrieves the latest news from the news index.",
                List.of(array("sourceIds", "Optional news source IDs"), number("hours", "Number of hours to look back", 24, 1, 720),
                    number("size", "Maximum number of results to return", 10, 1, 50)), true, 20),
            definition("news_source_status", "News Source Status", "Retrieves news source configuration status and collection record counts in read-only mode.",
                List.of(text("sourceId", "Optional news source ID", false)), false, 10)
        ).stream().collect(java.util.stream.Collectors.toMap(McpToolDefinition::name, item -> item));
    }

    @Override public String capabilityCode() { return McpCapabilityCodes.NEWS; }
    @Override public Collection<McpToolDefinition> definitions() { return definitions.values(); }
    @Override public Optional<McpToolExecutor> findExecutor(String toolName) {
        return definitions.containsKey(toolName) ? Optional.of(new RemoteExecutor(toolName)) : Optional.empty();
    }

    private class RemoteExecutor implements McpToolExecutor {
        private final String toolName;
        private RemoteExecutor(String toolName) { this.toolName = toolName; }
        @Override public ToolOutput execute(ToolInput input) {
            try { return client.invoke(toolName, input); }
            catch (Exception ex) { return ToolOutput.failure(ex); }
        }
    }

    private McpToolDefinition definition(String name, String title, String description,
                                         List<ToolParameter> parameters, boolean callable, int seconds) {
        return new McpToolDefinition(name, title, description, McpCapabilityCodes.NEWS, "chatchat-runtime-news",
            parameters, true, callable, Duration.ofSeconds(seconds));
    }
    private ToolParameter text(String name, String description, boolean required) {
        return ToolParameter.builder().name(name).type("string").description(description).required(required).build();
    }
    private ToolParameter array(String name, String description) {
        return ToolParameter.builder().name(name).type("array").description(description).required(false).build();
    }
    private ToolParameter number(String name, String description, int value, int min, int max) {
        return ToolParameter.builder().name(name).type("number").description(description).required(false)
            .defaultValue(value).minimum(min).maximum(max).build();
    }
}
