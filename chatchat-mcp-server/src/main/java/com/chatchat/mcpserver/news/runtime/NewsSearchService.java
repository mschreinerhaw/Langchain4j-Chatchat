package com.chatchat.mcpserver.news.runtime;

import com.chatchat.common.concurrent.CancellationSupport;
import com.chatchat.common.tool.ToolInput;
import com.chatchat.common.tool.ToolOutput;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

/** Isolates the remote news runtime from MCP protocol adaptation and optional enrichers. */
@Service
public class NewsSearchService {
    private final NewsRuntimeClient client;

    public NewsSearchService(NewsRuntimeClient client) {
        this.client = client;
    }

    public SearchResult search(ToolInput input) {
        return search(input, 0);
    }

    public SearchResult search(ToolInput input, int upstreamLocalEvidenceCount) {
        try {
            Map<String, Object> context = new LinkedHashMap<>(
                input == null || input.getContext() == null ? Map.of() : input.getContext());
            context.put("upstreamLocalEvidenceCount", Math.max(0, upstreamLocalEvidenceCount));
            ToolInput routedInput = input == null ? ToolInput.builder().context(context).build()
                : ToolInput.builder()
                    .rawInput(input.getRawInput())
                    .parameters(input.getParameters())
                    .requestId(input.getRequestId())
                    .userId(input.getUserId())
                    .conversationId(input.getConversationId())
                    .context(context)
                    .build();
            ToolOutput output = client.invoke("web_search", routedInput);
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
