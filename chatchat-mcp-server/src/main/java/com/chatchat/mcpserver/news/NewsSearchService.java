package com.chatchat.mcpserver.news;

import com.chatchat.common.concurrent.CancellationSupport;
import com.chatchat.common.tool.ToolInput;
import com.chatchat.common.tool.ToolOutput;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/** Isolates the remote news runtime from MCP protocol adaptation and optional enrichers. */
@Service
public class NewsSearchService {
    private final NewsRuntimeClient client;

    public NewsSearchService(NewsRuntimeClient client) {
        this.client = client;
    }

    public SearchResult search(ToolInput input) {
        try {
            ToolOutput output = client.invoke("web_search", input);
            if (output.isSuccess()) return new SearchResult(results(output.getData()), null);
            return new SearchResult(List.of(), "news: " + safe(output.getErrorMessage()));
        } catch (Exception ex) {
            CancellationSupport.rethrowIfCancelled(ex, "news retrieval");
            return new SearchResult(List.of(), "news: " + safe(ex.getMessage()));
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> results(Object data) {
        if (!(data instanceof Map<?, ?> map) || !(map.get("results") instanceof List<?> values)) return List.of();
        return values.stream().filter(Map.class::isInstance).map(value -> (Map<String, Object>) value).toList();
    }

    private String safe(String value) {
        return value == null || value.isBlank() ? "unknown failure" : value;
    }

    public record SearchResult(List<Map<String, Object>> results, String warning) { }
}
