package com.chatchat.mcpserver.api;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Read-only facade over the existing API template discovery protocol. */
@Component
@RequiredArgsConstructor
public class ApiServiceBridge {
    private final ApiTemplateDiscoveryMcpToolPublisher templateDiscovery;

    public Result query(Map<String, Object> rawArguments) {
        Map<String, Object> arguments = rawArguments == null ? Map.of() : rawArguments;
        Map<String, Object> discovery = templateDiscovery.query(discoveryArguments(arguments));
        Map<String, Object> body = new LinkedHashMap<>(discovery);
        List<Map<String, Object>> candidates = maps(discovery.get("templates"));
        body.put("schemaVersion", "api_service_query_result.v1");
        body.put("status", candidates.isEmpty() ? "NO_CANDIDATE" : "CANDIDATES_FOUND");
        body.put("requiresModelReview", !candidates.isEmpty());
        body.put("bridgeManaged", true);
        body.put("bridgeTool", ApiMcpToolPublisher.BRIDGE_TOOL_NAME);
        body.put("executionTool", ApiMcpToolPublisher.EXECUTE_TOOL_NAME);
        return new Result(Map.copyOf(body), false);
    }

    private Map<String, Object> discoveryArguments(Map<String, Object> arguments) {
        Map<String, Object> query = new LinkedHashMap<>();
        Map<String, Object> filters = map(arguments.get("filters"));
        putIfPresent(filters, "query", firstText(text(arguments.get("query")), text(arguments.get("intent"))));
        if (!filters.isEmpty()) query.put("filters", filters);
        putIfPresent(query, "templateIds", arguments.get("templateIds"));
        putIfPresent(query, "excludeTemplateIds", arguments.get("excludeTemplateIds"));
        putIfPresent(query, "bilingualIntent", arguments.get("bilingualIntent"));
        putIfPresent(query, "bilingualQuery", arguments.get("bilingualQuery"));
        putIfPresent(query, "intentZh", arguments.get("intentZh"));
        putIfPresent(query, "intentEn", arguments.get("intentEn"));
        putIfPresent(query, "limit", arguments.get("limit"));
        return query;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> maps(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream().filter(Map.class::isInstance).map(item -> (Map<String, Object>) item).toList();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> map
            ? new LinkedHashMap<>((Map<String, Object>) map) : new LinkedHashMap<>();
    }

    private void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (value != null && (!(value instanceof String text) || !text.isBlank())) target.put(key, value);
    }

    private String firstText(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return value;
        return null;
    }

    private String text(Object value) {
        if (value == null) return null;
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    public record Result(Map<String, Object> body, boolean error) { }
}
