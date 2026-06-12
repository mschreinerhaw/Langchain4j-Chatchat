package com.chatchat.mcpserver.ops;

import com.chatchat.mcpserver.audit.InvocationAuditService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class HttpRequestToolService {

    private static final List<String> ALLOWED_METHODS = List.of("GET", "POST", "PUT", "DELETE");
    private static final Pattern DOUBLE_BRACE_TOKEN = Pattern.compile("\\{\\{\\s*([A-Za-z0-9_.-]+)\\s*}}");
    private static final Pattern SINGLE_BRACE_TOKEN = Pattern.compile("\\{([A-Za-z0-9_.-]+)}");

    private final ObjectMapper objectMapper;
    private final InvocationAuditService auditService;

    public HttpRequestToolResult execute(Map<String, Object> arguments) {
        long startedAt = System.currentTimeMillis();
        Map<String, Object> request = new LinkedHashMap<>(arguments == null ? Map.of() : arguments);
        HttpRequestToolResult result;
        try {
            String method = normalizeMethod(text(request, "method"));
            String url = requireText(text(request, "url"), "url is required");
            int timeoutMs = normalizeTimeout(request.get("timeoutMs"));
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofMillis(timeoutMs));
            readHeaders(request.get("headers")).forEach(builder::header);
            if ("GET".equals(method)) {
                builder.GET();
            } else {
                String body = bodyText(request.get("body"));
                builder.method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
                if (readHeaders(request.get("headers")).keySet().stream().noneMatch("content-type"::equalsIgnoreCase)) {
                    builder.header("Content-Type", "application/json");
                }
            }
            HttpResponse<String> response = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(timeoutMs))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build()
                .send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            long durationMs = Math.max(0, System.currentTimeMillis() - startedAt);
            result = new HttpRequestToolResult(
                response.statusCode() >= 200 && response.statusCode() < 300,
                method,
                url,
                response.statusCode(),
                parseBody(response.body()),
                response.body(),
                Map.copyOf(response.headers().map()),
                durationMs,
                response.statusCode() >= 200 && response.statusCode() < 300 ? null : "HTTP " + response.statusCode()
            );
        } catch (Exception ex) {
            long durationMs = Math.max(0, System.currentTimeMillis() - startedAt);
            result = new HttpRequestToolResult(
                false,
                text(request, "method"),
                text(request, "url"),
                0,
                null,
                null,
                Map.of(),
                durationMs,
                ex.getMessage()
            );
        }
        auditService.recordOpsHttpCall(request, result);
        return result;
    }

    public HttpRequestToolResult execute(HttpEndpointConfig config, Map<String, Object> arguments) {
        Map<String, Object> request = toRequest(config, arguments == null ? Map.of() : arguments);
        request.put("toolName", config.getToolName());
        request.put("endpointId", config.getId());
        return execute(request);
    }

    private String normalizeMethod(String value) {
        String method = value == null || value.isBlank() ? "GET" : value.trim().toUpperCase(Locale.ROOT);
        if (!ALLOWED_METHODS.contains(method)) {
            throw new IllegalArgumentException("HTTP method must be GET, POST, PUT or DELETE");
        }
        return method;
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> readHeaders(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, String> headers = new LinkedHashMap<>();
        map.forEach((key, headerValue) -> {
            if (key != null && headerValue != null) {
                headers.put(String.valueOf(key), String.valueOf(headerValue));
            }
        });
        return headers;
    }

    private String bodyText(Object body) throws Exception {
        if (body == null) {
            return "";
        }
        if (body instanceof String text) {
            return text;
        }
        return objectMapper.writeValueAsString(body);
    }

    private Map<String, Object> toRequest(HttpEndpointConfig config, Map<String, Object> arguments) {
        Set<String> consumed = new LinkedHashSet<>();
        Map<String, Object> request = new LinkedHashMap<>();
        String method = normalizeMethod(config.getMethod());
        String url = replaceTokens(config.getUrlTemplate(), arguments, consumed, true);
        url = replaceSingleBraceTokens(url, arguments, consumed);
        Map<String, String> headers = renderHeaders(config.getHeadersJson(), arguments, consumed);
        Object body = renderBody(config, arguments, consumed);
        if ("GET".equals(method) || "DELETE".equals(method)) {
            url = appendQueryParams(url, arguments, consumed);
        }
        request.put("method", method);
        request.put("url", url);
        request.put("headers", headers);
        request.put("body", body);
        request.put("timeoutMs", config.getTimeoutMs());
        if (arguments.containsKey("sourceTaskId")) {
            request.put("sourceTaskId", arguments.get("sourceTaskId"));
        }
        return request;
    }

    private Map<String, String> renderHeaders(String headersJson, Map<String, Object> arguments, Set<String> consumed) {
        if (headersJson == null || headersJson.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> raw = objectMapper.readValue(headersJson, new TypeReference<>() {});
            Map<String, String> headers = new LinkedHashMap<>();
            raw.forEach((key, value) -> {
                if (key != null && value != null) {
                    headers.put(key, replaceTokens(String.valueOf(value), arguments, consumed, false));
                }
            });
            return headers;
        } catch (Exception ex) {
            throw new IllegalArgumentException("headers must be a JSON object");
        }
    }

    private Object renderBody(HttpEndpointConfig config, Map<String, Object> arguments, Set<String> consumed) {
        String method = normalizeMethod(config.getMethod());
        if ("GET".equals(method) || "DELETE".equals(method)) {
            return null;
        }
        if (config.getBodyTemplate() != null && !config.getBodyTemplate().isBlank()) {
            return replaceTokens(config.getBodyTemplate(), arguments, consumed, false);
        }
        Map<String, Object> body = new LinkedHashMap<>(arguments);
        body.remove("sourceTaskId");
        return body;
    }

    private String appendQueryParams(String url, Map<String, Object> arguments, Set<String> consumed) {
        StringBuilder builder = new StringBuilder(url);
        boolean hasQuery = url.contains("?");
        for (Map.Entry<String, Object> entry : arguments.entrySet()) {
            if (entry.getValue() == null || consumed.contains(entry.getKey()) || "sourceTaskId".equals(entry.getKey())) {
                continue;
            }
            if (entry.getValue() instanceof Iterable<?> iterable) {
                for (Object item : iterable) {
                    hasQuery = appendQueryParam(builder, hasQuery, entry.getKey(), item);
                }
            } else {
                hasQuery = appendQueryParam(builder, hasQuery, entry.getKey(), entry.getValue());
            }
        }
        return builder.toString();
    }

    private boolean appendQueryParam(StringBuilder builder, boolean hasQuery, String key, Object value) {
        if (value == null) {
            return hasQuery;
        }
        builder.append(hasQuery ? '&' : '?')
            .append(urlEncode(key))
            .append('=')
            .append(urlEncode(String.valueOf(value)));
        return true;
    }

    private String replaceSingleBraceTokens(String template, Map<String, Object> arguments, Set<String> consumed) {
        Matcher matcher = SINGLE_BRACE_TOKEN.matcher(template);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            String key = matcher.group(1);
            Object value = arguments.get(key);
            if (value == null) {
                continue;
            }
            consumed.add(key);
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(urlEncode(String.valueOf(value))));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    private String replaceTokens(String template, Map<String, Object> arguments, Set<String> consumed, boolean urlEncode) {
        if (template == null || template.isBlank()) {
            return template;
        }
        Matcher matcher = DOUBLE_BRACE_TOKEN.matcher(template);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            String key = matcher.group(1);
            Object value = arguments.get(key);
            if (value == null) {
                continue;
            }
            consumed.add(key);
            String replacement = String.valueOf(value);
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(urlEncode ? urlEncode(replacement) : replacement));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    private String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private Object parseBody(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        String trimmed = body.trim();
        if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) {
            return body;
        }
        try {
            return objectMapper.readValue(trimmed, new TypeReference<>() {});
        } catch (Exception ignored) {
            return body;
        }
    }

    private int normalizeTimeout(Object value) {
        if (value instanceof Number number) {
            return Math.max(1000, Math.min(number.intValue(), 60000));
        }
        return 10000;
    }

    private String text(Map<String, Object> request, String key) {
        Object value = request == null ? null : request.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }
}
