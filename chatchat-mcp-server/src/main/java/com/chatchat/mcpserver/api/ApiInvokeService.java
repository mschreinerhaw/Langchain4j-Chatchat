package com.chatchat.mcpserver.api;

import com.chatchat.agents.protocol.ModelProtocolJson;

import com.chatchat.mcpserver.audit.InvocationAuditService;
import com.chatchat.mcpserver.cache.ApiResponseCacheService;
import com.chatchat.mcpserver.ops.HttpEndpointConfig;
import com.chatchat.mcpserver.ops.HttpEndpointConfigService;
import com.chatchat.mcpserver.template.TemplateParameterValidator;
import com.chatchat.tools.livedata.LivedataSessionService;
import com.chatchat.common.tool.ToolLogSummarizer;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
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
@Slf4j
public class ApiInvokeService {

    private static final Pattern DOUBLE_BRACE_TOKEN = Pattern.compile("\\{\\{\\s*([A-Za-z0-9_.-]+)\\s*}}");
    private static final Pattern SINGLE_BRACE_TOKEN = Pattern.compile("\\{\\s*([A-Za-z0-9_.-]+)\\s*}");

    private final ObjectMapper objectMapper;
    private final InvocationAuditService auditService;
    private final ApiResponseCacheService cacheService;
    private final ObjectProvider<LivedataSessionService> livedataSessionServiceProvider;
    private final TemplateParameterValidator parameterValidator;
    private final HttpEndpointConfigService gatewayConfigService;

    /**
     * Performs the invoke operation.
     *
     * @param config the config value
     * @param arguments the arguments value
     * @return the operation result
     */
    public ApiInvokeResult invoke(ApiServiceConfig config, Map<String, Object> arguments) {
        long startedAt = System.currentTimeMillis();
        Map<String, Object> auditArgs = validatedTemplateArguments(config, arguments == null ? Map.of() : arguments);
        ApiInvokeResult result;
        ApiHttpTransport transport;
        try {
            transport = httpTransport(config);
        } catch (Exception ex) {
            result = new ApiInvokeResult(false, 0, Map.of(), null, null, ex.getMessage());
            auditService.recordApiCall(config, auditArgs, result, Math.max(0L, System.currentTimeMillis() - startedAt));
            log.warn("External API transport resolution failed apiServiceId={} tool={} gatewayId={} error={}",
                config.getId(),
                config.getToolName(),
                config.getGatewayId(),
                ex.getMessage(),
                ex);
            return result;
        }
        log.info("External API invoke started apiServiceId={} tool={} gatewayId={} method={} urlTemplate={} args={}",
            config.getId(),
            config.getToolName(),
            transport.gatewayId(),
            transport.method(),
            transport.urlTemplate(),
            ToolLogSummarizer.summarize(auditArgs));

        var cached = cacheService.get(config, auditArgs);
        if (cached.isPresent()) {
            result = cached.get();
            auditService.recordApiCall(config, auditArgs, result, System.currentTimeMillis() - startedAt);
            log.info("External API invoke cache hit apiServiceId={} tool={} success={} statusCode={} durationMs={}",
                config.getId(),
                config.getToolName(),
                result.success(),
                result.statusCode(),
                Math.max(0L, System.currentTimeMillis() - startedAt));
            return result;
        }

        try {
            HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(Math.max(1000, transport.timeoutMs())))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
            Map<String, Object> renderArgs = enrichArguments(transport, auditArgs, false);
            HttpRequest request = buildRequest(transport, renderArgs);
            log.info("External API HTTP request prepared apiServiceId={} tool={} gatewayId={} method={} uri={} timeoutMs={} args={}",
                config.getId(),
                config.getToolName(),
                transport.gatewayId(),
                request.method(),
                safeUri(request.uri()),
                transport.timeoutMs(),
                ToolLogSummarizer.summarize(renderArgs));
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (isAuthFailure(response) && usesLivedataSession(transport)) {
                renderArgs = enrichArguments(transport, auditArgs, true);
                request = buildRequest(transport, renderArgs);
                log.info("External API HTTP request retrying with refreshed session apiServiceId={} tool={} gatewayId={} method={} uri={} timeoutMs={}",
                    config.getId(),
                    config.getToolName(),
                    transport.gatewayId(),
                    request.method(),
                    safeUri(request.uri()),
                    transport.timeoutMs());
                response = client.send(request, HttpResponse.BodyHandlers.ofString());
            }
            result = toResult(response);
            log.info("External API HTTP response received apiServiceId={} tool={} statusCode={} bodyChars={} bodySummary={}",
                config.getId(),
                config.getToolName(),
                response.statusCode(),
                response.body() == null ? 0 : response.body().length(),
                ToolLogSummarizer.summarize(result.body()));
        } catch (Exception ex) {
            result = new ApiInvokeResult(false, 0, Map.of(), null, null, ex.getMessage());
            log.warn("External API invoke threw apiServiceId={} tool={} durationMs={} error={}",
                config.getId(),
                config.getToolName(),
                Math.max(0L, System.currentTimeMillis() - startedAt),
                ex.getMessage(),
                ex);
        }
        cacheService.put(config, auditArgs, result);
        long durationMs = Math.max(0L, System.currentTimeMillis() - startedAt);
        auditService.recordApiCall(config, auditArgs, result, durationMs);
        if (result.success()) {
            log.info("External API invoke succeeded apiServiceId={} tool={} statusCode={} durationMs={} result={}",
                config.getId(),
                config.getToolName(),
                result.statusCode(),
                durationMs,
                ToolLogSummarizer.summarize(result.body()));
        } else {
            log.warn("External API invoke failed apiServiceId={} tool={} statusCode={} durationMs={} error={} result={}",
                config.getId(),
                config.getToolName(),
                result.statusCode(),
                durationMs,
                result.errorMessage(),
                ToolLogSummarizer.summarize(result.body()));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> validatedTemplateArguments(ApiServiceConfig config, Map<String, Object> arguments) {
        Object explicit = arguments.get("parameters");
        Map<String, Object> explicitParameters = explicit instanceof Map<?, ?> map
            ? new LinkedHashMap<>((Map<String, Object>) map)
            : Map.of();
        Map<String, Object> parameters = parameterValidator.validateDeclaredOnly(
            config.getToolName(),
            config.getInputSchemaJson(),
            explicitParameters,
            arguments
        );
        copyIfPresent(arguments, parameters, "sourceTaskId");
        return parameters;
    }

    private void copyIfPresent(Map<String, Object> source, Map<String, Object> target, String key) {
        if (source == null || target == null || key == null || !source.containsKey(key)) {
            return;
        }
        Object value = source.get(key);
        if (value != null && !String.valueOf(value).isBlank()) {
            target.put(key, value);
        }
    }

    /**
     * Builds the request.
     *
     * @param config the config value
     * @param args the args value
     * @return the built request
     * @throws IOException if the operation fails
     */
    private HttpRequest buildRequest(ApiHttpTransport transport, Map<String, Object> args) throws IOException {
        Set<String> consumed = new LinkedHashSet<>();
        String method = transport.method().toUpperCase(Locale.ROOT);
        String url = renderUrl(transport.urlTemplate(), args, consumed);
        Map<String, String> headers = renderHeaders(transport.headersJson(), args, consumed);
        String body = renderBody(transport, args, consumed);

        if (method.equals("GET") || method.equals("DELETE")) {
            url = appendQueryParams(url, args, consumed);
        }

        HttpRequest.BodyPublisher publisher = hasRequestBody(method, body)
            ? HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)
            : HttpRequest.BodyPublishers.noBody();

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofMillis(Math.max(1000, transport.timeoutMs())))
            .method(method, publisher);

        headers.forEach(requestBuilder::header);
        if (hasRequestBody(method, body) && !containsHeader(headers, "content-type")) {
            requestBuilder.header("Content-Type", "application/json");
        }
        return requestBuilder.build();
    }

    /**
     * Performs the enrich arguments operation.
     *
     * @param transport the transport value
     * @param args the args value
     * @param forceRefreshSession the force refresh session value
     * @return the operation result
     */
    private Map<String, Object> enrichArguments(ApiHttpTransport transport, Map<String, Object> args, boolean forceRefreshSession) {
        if (!usesLivedataSession(transport)) {
            return args;
        }
        LivedataSessionService sessionService = livedataSessionServiceProvider.getIfAvailable();
        if (sessionService == null) {
            return args;
        }
        Map<String, Object> enriched = new LinkedHashMap<>(args);
        String sessionId = forceRefreshSession ? sessionService.refreshSessionId() : sessionService.currentSessionId();
        enriched.put(LivedataSessionService.SESSION_ARGUMENT, sessionId);
        return enriched;
    }

    /**
     * Returns whether uses livedata session.
     *
     * @param transport the transport value
     * @return whether the condition is satisfied
     */
    private boolean usesLivedataSession(ApiHttpTransport transport) {
        return transport.bodyTemplate() != null
            && transport.bodyTemplate().contains("{{" + LivedataSessionService.SESSION_ARGUMENT + "}}");
    }

    /**
     * Returns whether is auth failure.
     *
     * @param response the response value
     * @return whether the condition is satisfied
     */
    private boolean isAuthFailure(HttpResponse<String> response) {
        if (response.statusCode() == 401 || response.statusCode() == 403) {
            return true;
        }
        String body = response.body();
        if (body == null || body.isBlank()) {
            return false;
        }
        try {
            Object parsed = objectMapper.readValue(body, Object.class);
            if (parsed instanceof Map<?, ?> map) {
                Object code = map.get("code");
                String codeText = code == null ? "" : String.valueOf(code);
                return "401".equals(codeText) || "403".equals(codeText);
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    /**
     * Converts the value to result.
     *
     * @param response the response value
     * @return the converted result
     */
    private ApiInvokeResult toResult(HttpResponse<String> response) {
        Object parsedBody = parseBody(response.body());
        boolean success = response.statusCode() >= 200 && response.statusCode() < 300;
        return new ApiInvokeResult(success, response.statusCode(), response.headers().map(), parsedBody,
            response.body(), success ? null : "API returned HTTP " + response.statusCode());
    }

    /**
     * Performs the render url operation.
     *
     * @param template the template value
     * @param args the args value
     * @param consumed the consumed value
     * @return the operation result
     */
    private String renderUrl(String template, Map<String, Object> args, Set<String> consumed) {
        String rendered = replaceTokens(template, args, consumed, true);
        rendered = replaceSingleBraceTokens(rendered, args, consumed);
        return rendered;
    }

    /**
     * Performs the render headers operation.
     *
     * @param headersJson the headers json value
     * @param args the args value
     * @param consumed the consumed value
     * @return the operation result
     * @throws IOException if the operation fails
     */
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

    /**
     * Performs the render body operation.
     *
     * @param transport the transport value
     * @param args the args value
     * @param consumed the consumed value
     * @return the operation result
     * @throws IOException if the operation fails
     */
    private String renderBody(ApiHttpTransport transport, Map<String, Object> args, Set<String> consumed)
        throws IOException {
        String method = transport.method().toUpperCase(Locale.ROOT);
        if (method.equals("GET") || method.equals("DELETE")) {
            return null;
        }
        if (transport.bodyTemplate() != null && !transport.bodyTemplate().isBlank()) {
            return replaceTokens(transport.bodyTemplate(), args, consumed, false);
        }
        if (args.isEmpty()) {
            return null;
        }
        return ModelProtocolJson.compact(args);
    }

    private ApiHttpTransport httpTransport(ApiServiceConfig config) {
        if (config.getGatewayId() != null && !config.getGatewayId().isBlank()) {
            HttpEndpointConfig gateway = gatewayConfigService.getById(config.getGatewayId());
            if (!gateway.isEnabled()) {
                throw new IllegalStateException("API gateway asset is disabled: " + config.getGatewayId());
            }
            return new ApiHttpTransport(
                gateway.getId(),
                gateway.getName(),
                firstText(gateway.getMethod(), "GET"),
                gateway.getUrlTemplate(),
                gateway.getHeadersJson(),
                gateway.getBodyTemplate(),
                gateway.getTimeoutMs() <= 0 ? 10000 : gateway.getTimeoutMs()
            );
        }
        return new ApiHttpTransport(
            null,
            null,
            firstText(config.getMethod(), "GET"),
            config.getUrlTemplate(),
            config.getHeadersJson(),
            config.getBodyTemplate(),
            config.getTimeoutMs() <= 0 ? 20000 : config.getTimeoutMs()
        );
    }

    private String firstText(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim();
    }

    private record ApiHttpTransport(
        String gatewayId,
        String gatewayName,
        String method,
        String urlTemplate,
        String headersJson,
        String bodyTemplate,
        int timeoutMs
    ) {
    }

    /**
     * Appends the query params.
     *
     * @param url the url value
     * @param args the args value
     * @param consumed the consumed value
     * @return the operation result
     */
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

    /**
     * Appends the query param.
     *
     * @param builder the builder value
     * @param hasQuery the has query value
     * @param key the key value
     * @param value the value value
     */
    private void appendQueryParam(StringBuilder builder, boolean hasQuery, String key, Object value) {
        if (value == null) {
            return;
        }
        builder.append(hasQuery ? '&' : '?')
            .append(urlEncode(key))
            .append('=')
            .append(urlEncode(String.valueOf(value)));
    }

    /**
     * Performs the replace single brace tokens operation.
     *
     * @param template the template value
     * @param args the args value
     * @param consumed the consumed value
     * @return the operation result
     */
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

    /**
     * Performs the replace tokens operation.
     *
     * @param template the template value
     * @param args the args value
     * @param consumed the consumed value
     * @param urlEncode the url encode value
     * @return the operation result
     */
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

    /**
     * Returns whether has request body.
     *
     * @param method the method value
     * @param body the body value
     * @return whether the condition is satisfied
     */
    private boolean hasRequestBody(String method, String body) {
        return body != null && !body.isBlank() && !method.equals("GET") && !method.equals("DELETE");
    }

    /**
     * Returns whether contains header.
     *
     * @param headers the headers value
     * @param name the name value
     * @return whether the condition is satisfied
     */
    private boolean containsHeader(Map<String, String> headers, String name) {
        return headers.keySet().stream().anyMatch(key -> key.equalsIgnoreCase(name));
    }

    /**
     * Performs the safe uri operation.
     *
     * @param uri the uri value
     * @return the operation result
     */
    private String safeUri(URI uri) {
        if (uri == null) {
            return null;
        }
        StringBuilder value = new StringBuilder();
        value.append(uri.getScheme()).append("://").append(uri.getAuthority()).append(uri.getPath());
        if (uri.getQuery() != null && !uri.getQuery().isBlank()) {
            value.append("?").append(queryKeySummary(uri.getQuery()));
        }
        return value.toString();
    }

    /**
     * Queries the key summary.
     *
     * @param query the query value
     * @return the operation result
     */
    private String queryKeySummary(String query) {
        if (query == null || query.isBlank()) {
            return "";
        }
        List<String> keys = java.util.Arrays.stream(query.split("&"))
            .map(item -> {
                int separator = item.indexOf('=');
                return separator >= 0 ? item.substring(0, separator) : item;
            })
            .filter(key -> key != null && !key.isBlank())
            .distinct()
            .limit(20)
            .toList();
        return "queryKeys=" + keys;
    }

    /**
     * Parses the body.
     *
     * @param body the body value
     * @return the parsed body
     */
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

    /**
     * Performs the url encode operation.
     *
     * @param value the value value
     * @return the operation result
     */
    private String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /**
     * Performs the escape json string operation.
     *
     * @param value the value value
     * @return the operation result
     */
    private String escapeJsonString(String value) {
        return ModelProtocolJson.jsonStringContent(value);
    }

    /**
     * Performs the argument keys operation.
     *
     * @param arguments the arguments value
     * @return the operation result
     */
    private List<String> argumentKeys(Map<String, Object> arguments) {
        if (arguments == null || arguments.isEmpty()) {
            return List.of();
        }
        return arguments.keySet().stream()
            .filter(key -> key != null && !key.isBlank())
            .sorted()
            .toList();
    }
}
