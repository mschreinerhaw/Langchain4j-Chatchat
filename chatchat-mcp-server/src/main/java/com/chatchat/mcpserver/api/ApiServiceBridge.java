package com.chatchat.mcpserver.api;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Intent-level facade for API discovery, disambiguation and governed execution.
 */
@Component
@RequiredArgsConstructor
public class ApiServiceBridge {

    private final ApiTemplateDiscoveryMcpToolPublisher templateDiscovery;
    private final ApiServiceConfigService configService;
    private final ApiInvokeService invokeService;

    public Result run(Map<String, Object> rawArguments) {
        Map<String, Object> arguments = rawArguments == null ? Map.of() : rawArguments;
        String explicitTemplateId = text(arguments.get("templateId"));
        Map<String, Object> discoveryArguments = discoveryArguments(arguments, explicitTemplateId);
        Map<String, Object> discovery = templateDiscovery.query(discoveryArguments);
        List<Map<String, Object>> candidates = maps(discovery.get("templates"));
        if (candidates.isEmpty()) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("message", "No enabled API template matched the request");
            details.put("query", text(arguments.get("query")));
            return new Result(response("NOT_FOUND", false, details), true);
        }

        Map<String, Object> selected = select(candidates, explicitTemplateId);
        if (selected == null) {
            return new Result(response("NEEDS_CLARIFICATION", true, Map.of(
                    "message", "Multiple API templates match the request; select one templateId",
                    "choices", candidates.stream().limit(10).map(this::choice).toList()
            )), false);
        }

        String templateId = text(selected.get("templateId"));
        ApiServiceConfig config = configService.findByToolName(templateId)
                .filter(ApiServiceConfig::isEnabled)
                .orElseThrow(() -> new IllegalArgumentException("API template not found or disabled: " + templateId));
        Map<String, Object> invocation = new LinkedHashMap<>();
        invocation.put("templateId", templateId);
        invocation.put("parameters", map(arguments.get("parameters")));
        putIfPresent(invocation, "purpose", arguments.get("purpose"));
        putIfPresent(invocation, "sourceTaskId", arguments.get("sourceTaskId"));
        ApiInvokeResult executed = invokeService.invoke(config, invocation);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("templateId", templateId);
        details.put("templateTitle", nullable(text(selected.get("title"))));
        details.put("statusCode", executed.statusCode());
        details.put("body", nullable(executed.body()));
        details.put("rawBody", nullable(executed.rawBody()));
        details.put("error", nullable(executed.errorMessage()));
        details.put("cacheHit", executed.cacheHit());
        return new Result(response(executed.success() ? "EXECUTED" : "FAILED", false, details), !executed.success());
    }

    private Map<String, Object> discoveryArguments(Map<String, Object> arguments, String templateId) {
        Map<String, Object> query = new LinkedHashMap<>();
        Map<String, Object> filters = new LinkedHashMap<>(map(arguments.get("filters")));
        putIfPresent(filters, "query", firstText(text(arguments.get("query")), text(arguments.get("intent"))));
        if (!filters.isEmpty()) query.put("filters", filters);
        if (templateId != null) query.put("templateIds", List.of(templateId));
        query.put("limit", 10);
        return query;
    }

    private Map<String, Object> select(List<Map<String, Object>> candidates, String explicitTemplateId) {
        if (explicitTemplateId != null) {
            return candidates.stream().filter(value -> explicitTemplateId.equals(text(value.get("templateId"))))
                    .findFirst().orElse(null);
        }
        if (candidates.size() == 1) return candidates.get(0);
        double first = number(candidates.get(0).get("relevanceScore"));
        double second = number(candidates.get(1).get("relevanceScore"));
        return first > second ? candidates.get(0) : null;
    }

    private Map<String, Object> choice(Map<String, Object> candidate) {
        Map<String, Object> value = new LinkedHashMap<>();
        for (String key : List.of("templateId", "title", "description", "businessGroup", "parameterSchema",
                "requiredParameters", "relevanceScore")) {
            putIfPresent(value, key, candidate.get(key));
        }
        return Map.copyOf(value);
    }

    private Map<String, Object> response(String status, boolean clarification, Map<String, Object> details) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("schemaVersion", "api_service_bridge_result.v1");
        body.put("success", "EXECUTED".equals(status));
        body.put("status", status);
        body.put("requiresClarification", clarification);
        body.put("bridgeManaged", true);
        details.forEach((key, value) -> body.put(key, nullable(value)));
        return body;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> maps(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream().filter(Map.class::isInstance).map(item -> (Map<String, Object>) item).toList();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> map ? new LinkedHashMap<>((Map<String, Object>) map) : new LinkedHashMap<>();
    }

    private double number(Object value) {
        if (value instanceof Number number) return number.doubleValue();
        try {
            return value == null ? 0D : Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return 0D;
        }
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

    private Object nullable(Object value) {
        return value;
    }

    public record Result(Map<String, Object> body, boolean error) {
    }
}
