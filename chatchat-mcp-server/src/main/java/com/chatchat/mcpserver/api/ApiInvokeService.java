package com.chatchat.mcpserver.api;

import com.chatchat.mcpserver.audit.InvocationAuditService;
import com.chatchat.mcpserver.cache.ApiResponseCacheService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class ApiInvokeService {

    private static final Pattern DOUBLE_BRACE_TOKEN = Pattern.compile("\\{\\{\\s*([A-Za-z0-9_.-]+)\\s*}}");
    private static final Pattern SINGLE_BRACE_TOKEN = Pattern.compile("\\{\\s*([A-Za-z0-9_.-]+)\\s*}");

    private final ObjectMapper objectMapper;
    private final InvocationAuditService auditService;
    private final ApiResponseCacheService cacheService;

    public ApiInvokeResult invoke(ApiServiceConfig config, Map<String, Object> arguments) {
        long startedAt = System.currentTimeMillis();
        Map<String, Object> args = arguments == null ? Map.of() : arguments;
        Set<String> consumed = new LinkedHashSet<>();
        ApiInvokeResult result;

        var cached = cacheService.get(config, args);
        if (cached.isPresent()) {
            result = cached.get();
            auditService.recordApiCall(config, args, result, System.currentTimeMillis() - startedAt);
            return result;
        }

        try {
            String method = config.getMethod().toUpperCase(Locale.ROOT);
            String url = renderUrl(config.getUrlTemplate(), args, consumed);
            Map<String, String> headers = renderHeaders(config.getHeadersJson(), args, consumed);
            String body = renderBody(config, args, consumed);

            if (method.equals("GET") || method.equals("DELETE")) {
                url = appendQueryParams(url, args, consumed);
            }

            HttpRequest.BodyPublisher publisher = hasRequestBody(method, body)
                ? HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)
                : HttpRequest.BodyPublishers.noBody();

            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofMillis(Math.max(1000, config.getTimeoutMs())))
                .method(method, publisher);

            headers.forEach(requestBuilder::header);
            if (hasRequestBody(method, body) && !containsHeader(headers, "content-type")) {
                requestBuilder.header("Content-Type", "application/json");
            }

            HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(Math.max(1000, config.getTimeoutMs())))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

            HttpResponse<String> response = client.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());
            Object parsedBody = parseBody(response.body());
            boolean success = response.statusCode() >= 200 && response.statusCode() < 300;
            result = new ApiInvokeResult(success, response.statusCode(), response.headers().map(), parsedBody,
                response.body(), success ? null : "API returned HTTP " + response.statusCode());
        } catch (Exception ex) {
            result = new ApiInvokeResult(false, 0, Map.of(), null, null, ex.getMessage());
        }
        cacheService.put(config, args, result);
        auditService.recordApiCall(config, args, result, System.currentTimeMillis() - startedAt);
        return result;
    }

    private String renderUrl(String template, Map<String, Object> args, Set<String> consumed) {
        String rendered = replaceTokens(template, args, consumed, true);
        rendered = replaceSingleBraceTokens(rendered, args, consumed);
        return rendered;
    }

    private Map<String, String> renderHeaders(String headersJson, Map<String, Object> args, Set<String> consumed)
        throws IOException {
        if (headersJson == null || headersJson.isBlank()) {
            return Map.of();
        }
        Map<String, Object> raw = objectMapper.readValue(headersJson, new TypeReference<>() {});
        Map<String, String> headers = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : raw.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank() || entry.getValue() == null) {
                continue;
            }
            headers.put(entry.getKey(), replaceTokens(String.valueOf(entry.getValue()), args, consumed, false));
        }
        return headers;
    }

    private String renderBody(ApiServiceConfig config, Map<String, Object> args, Set<String> consumed)
        throws IOException {
        String method = config.getMethod().toUpperCase(Locale.ROOT);
        if (method.equals("GET") || method.equals("DELETE")) {
            return null;
        }
        if (config.getBodyTemplate() != null && !config.getBodyTemplate().isBlank()) {
            return replaceTokens(config.getBodyTemplate(), args, consumed, false);
        }
        if (args.isEmpty()) {
            return null;
        }
        return objectMapper.writeValueAsString(args);
    }

    private String appendQueryParams(String url, Map<String, Object> args, Set<String> consumed) {
        if (args.isEmpty()) {
            return url;
        }
        StringBuilder builder = new StringBuilder(url);
        boolean hasQuery = url.contains("?");
        for (Map.Entry<String, Object> entry : args.entrySet()) {
            if (entry.getValue() == null || consumed.contains(entry.getKey())) {
                continue;
            }
            if (entry.getValue() instanceof Iterable<?> iterable) {
                for (Object item : iterable) {
                    appendQueryParam(builder, hasQuery, entry.getKey(), item);
                    hasQuery = true;
                }
            } else {
                appendQueryParam(builder, hasQuery, entry.getKey(), entry.getValue());
                hasQuery = true;
            }
        }
        return builder.toString();
    }

    private void appendQueryParam(StringBuilder builder, boolean hasQuery, String key, Object value) {
        if (value == null) {
            return;
        }
        builder.append(hasQuery ? '&' : '?')
            .append(urlEncode(key))
            .append('=')
            .append(urlEncode(String.valueOf(value)));
    }

    private String replaceSingleBraceTokens(String template, Map<String, Object> args, Set<String> consumed) {
        Matcher matcher = SINGLE_BRACE_TOKEN.matcher(template);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            String key = matcher.group(1);
            Object value = args.get(key);
            if (value == null) {
                continue;
            }
            consumed.add(key);
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(urlEncode(String.valueOf(value))));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    private String replaceTokens(String template, Map<String, Object> args, Set<String> consumed, boolean urlEncode) {
        Matcher matcher = DOUBLE_BRACE_TOKEN.matcher(template);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            String key = matcher.group(1);
            Object value = args.get(key);
            if (value == null) {
                continue;
            }
            consumed.add(key);
            String replacement = String.valueOf(value);
            if (urlEncode) {
                replacement = urlEncode(replacement);
            } else {
                replacement = escapeJsonString(replacement);
            }
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    private boolean hasRequestBody(String method, String body) {
        return body != null && !body.isBlank() && !method.equals("GET") && !method.equals("DELETE");
    }

    private boolean containsHeader(Map<String, String> headers, String name) {
        return headers.keySet().stream().anyMatch(key -> key.equalsIgnoreCase(name));
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
            return objectMapper.readValue(trimmed, Object.class);
        } catch (Exception ignored) {
            return body;
        }
    }

    private String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private String escapeJsonString(String value) {
        try {
            String json = objectMapper.writeValueAsString(value);
            return json.substring(1, json.length() - 1);
        } catch (Exception ignored) {
            return value;
        }
    }
}
