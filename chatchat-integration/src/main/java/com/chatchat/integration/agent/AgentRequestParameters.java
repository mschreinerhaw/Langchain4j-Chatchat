package com.chatchat.integration.agent;

import com.chatchat.common.runtime.agent.AgentDescriptor;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/** Operator-approved, non-secret fixed parameters for A2A transport and message payload. */
final class AgentRequestParameters {
    private static final Pattern NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_.-]{0,63}");
    private static final Pattern SECRET = Pattern.compile("(?i).*(password|secret|token|api[_-]?key|authorization|credential|bearer).*");

    private AgentRequestParameters() { }

    static Map<String, String> query(AgentDescriptor agent) {
        return parameters(agent, "requestQueryParameters");
    }

    static Map<String, String> body(AgentDescriptor agent) {
        return parameters(agent, "requestBodyParameters");
    }

    private static Map<String, String> parameters(AgentDescriptor agent, String key) {
        Object raw = agent.metadata().get(key);
        if (raw == null) return Map.of();
        if (!(raw instanceof Map<?, ?> values) || values.size() > 16)
            throw new IllegalArgumentException(key + " must be an object with at most 16 entries");
        Map<String, String> safe = new LinkedHashMap<>();
        for (var entry : values.entrySet()) {
            if (!(entry.getKey() instanceof String name) || !NAME.matcher(name).matches()
                || SECRET.matcher(name).matches())
                throw new IllegalArgumentException("Unsupported or sensitive request parameter name");
            Object value = entry.getValue();
            if (!(value instanceof String || value instanceof Number || value instanceof Boolean))
                throw new IllegalArgumentException("Request parameter values must be text, number, or boolean");
            String text = String.valueOf(value);
            if (text.isBlank() || text.length() > 256 || text.contains("\r") || text.contains("\n"))
                throw new IllegalArgumentException("Request parameter value must be 1..256 characters");
            safe.put(name, text);
        }
        return Map.copyOf(safe);
    }

    static URI withQuery(URI base, Map<String, String> values) {
        if (values.isEmpty()) return base;
        if (base.getRawFragment() != null)
            throw new IllegalArgumentException("Agent endpoint must not contain a URL fragment");
        StringBuilder url = new StringBuilder(base.toString());
        url.append(base.getRawQuery() == null ? '?' : '&');
        values.forEach((key, value) -> url.append(URLEncoder.encode(key, StandardCharsets.UTF_8))
            .append('=').append(URLEncoder.encode(value, StandardCharsets.UTF_8)).append('&'));
        url.setLength(url.length() - 1);
        return URI.create(url.toString());
    }

}
