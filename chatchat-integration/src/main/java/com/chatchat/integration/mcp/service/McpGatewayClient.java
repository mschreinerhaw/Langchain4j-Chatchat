package com.chatchat.integration.mcp.service;

import com.chatchat.integration.mcp.entity.McpServiceConfig;
import com.chatchat.integration.mcp.model.McpToolDefinition;
import com.chatchat.integration.mcp.model.McpToolInvokeResult;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import reactor.netty.transport.ProxyProvider;

import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MCP gateway client.
 *
 * Supports three transport styles:
 * - mcp_stdio_proxy: local stdio proxy mode
 * - MCP legacy SSE: /sse endpoint (same model as python mcp.client.sse)
 * - MCP streamable HTTP: /mcp endpoint
 *
 * Keeps legacy REST fallback (/tools, /tools/call) for non-MCP servers.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class McpGatewayClient {

    private static final String PROTOCOL_STREAMABLE_HTTP = "mcp_streamable_http";
    private static final String PROTOCOL_STDIO_PROXY = "mcp_stdio_proxy";
    private static final String PROTOCOL_LEGACY_SSE = "mcp_legacy_sse";
    private static final String DEFAULT_STREAMABLE_HTTP_PATH = "/mcp";
    private static final String DEFAULT_LEGACY_SSE_PATH = "/sse";

    private final ObjectMapper objectMapper;
    private final McpStdioProxyService stdioProxyService;
    private final WebClient directWebClient = WebClient.builder().build();
    private final Map<String, ManagedSdkClient> sdkClientCache = new ConcurrentHashMap<>();

    public List<McpToolDefinition> discoverTools(McpServiceConfig config) {
        if (isStdioProxyProtocol(config)) {
            return discoverToolsViaStdioProxy(config);
        }

        TransportKind kind = resolveTransportKind(config);
        if (kind == TransportKind.LEGACY_HTTP) {
            return discoverToolsViaLegacyHttp(config);
        }
        log.info("Using MCP SDK transport {} for service {} (protocol={})",
            kind, config.getName(), config.getProtocol());

        try {
            McpSyncClient client = getOrCreateSdkClient(config, kind);
            Object raw = client.listTools();
            Map<String, Object> mapped = objectMapper.convertValue(raw, new TypeReference<>() {});
            return normalizeTools(mapped);
        } catch (Exception ex) {
            log.warn("Failed to discover MCP tools via SDK for {}: {}", config.getName(), ex.getMessage());
            return List.of();
        }
    }

    public McpToolInvokeResult invokeTool(McpServiceConfig config, String toolName, Map<String, Object> arguments) {
        if (isStdioProxyProtocol(config)) {
            return invokeToolViaStdioProxy(config, toolName, arguments);
        }

        TransportKind kind = resolveTransportKind(config);
        if (kind == TransportKind.LEGACY_HTTP) {
            return invokeToolViaLegacyHttp(config, toolName, arguments);
        }
        log.info("Using MCP SDK transport {} for service {} during invoke (protocol={})",
            kind, config.getName(), config.getProtocol());

        try {
            McpSyncClient client = getOrCreateSdkClient(config, kind);
            Object raw = client.callTool(new McpSchema.CallToolRequest(toolName,
                arguments == null ? Map.of() : arguments));
            Map<String, Object> mapped = objectMapper.convertValue(raw, new TypeReference<>() {});
            return normalizeInvokeResult(mapped);
        } catch (Exception ex) {
            log.warn("Failed to invoke MCP tool {} via SDK for {}: {}", toolName, config.getName(), ex.getMessage());
            return new McpToolInvokeResult(false, null, null, ex.getMessage());
        }
    }

    private McpSyncClient getOrCreateSdkClient(McpServiceConfig config, TransportKind kind) {
        String key = serviceKey(config);
        String fingerprint = transportFingerprint(config, kind);

        ManagedSdkClient cached = sdkClientCache.get(key);
        if (cached != null && cached.fingerprint().equals(fingerprint)) {
            return cached.client();
        }

        synchronized (sdkClientCache) {
            ManagedSdkClient current = sdkClientCache.get(key);
            if (current != null && current.fingerprint().equals(fingerprint)) {
                return current.client();
            }
            if (current != null) {
                closeQuietly(current.client(), key);
            }

            McpClientTransport transport = createSdkTransport(config, kind);
            McpSyncClient client = McpClient.sync(transport)
                .requestTimeout(Duration.ofMillis(Math.max(1000, config.getTimeoutMs())))
                .clientInfo(new McpSchema.Implementation("chatchat-mcp-client", "1.0.0"))
                .build();
            client.initialize();

            sdkClientCache.put(key, new ManagedSdkClient(fingerprint, client));
            return client;
        }
    }

    private McpClientTransport createSdkTransport(McpServiceConfig config, TransportKind kind) {
        EndpointParts endpoint = switch (kind) {
            case LEGACY_SSE -> endpointParts(resolveLegacySseEndpoint(config), DEFAULT_LEGACY_SSE_PATH);
            case STREAMABLE_HTTP -> endpointParts(resolveStreamableEndpoint(config), DEFAULT_STREAMABLE_HTTP_PATH);
            default -> throw new IllegalArgumentException("Unsupported SDK transport kind: " + kind);
        };

        java.net.http.HttpClient.Builder clientBuilder = buildHttpClientBuilder(config);
        HttpRequest.Builder requestBuilder = buildHttpRequestBuilder(config);
        Duration connectTimeout = Duration.ofMillis(Math.max(1000, config.getTimeoutMs()));

        if (kind == TransportKind.LEGACY_SSE) {
            return HttpClientSseClientTransport.builder(endpoint.baseUrl())
                .sseEndpoint(endpoint.endpointPath())
                .clientBuilder(clientBuilder)
                .requestBuilder(requestBuilder)
                .connectTimeout(connectTimeout)
                .build();
        }

        return HttpClientStreamableHttpTransport.builder(endpoint.baseUrl())
            .endpoint(endpoint.endpointPath())
            .clientBuilder(clientBuilder)
            .requestBuilder(requestBuilder)
            .connectTimeout(connectTimeout)
            .openConnectionOnStartup(false)
            .build();
    }

    private java.net.http.HttpClient.Builder buildHttpClientBuilder(McpServiceConfig config) {
        java.net.http.HttpClient.Builder builder = java.net.http.HttpClient.newBuilder()
            .version(java.net.http.HttpClient.Version.HTTP_1_1);

        if (!config.isProxyEnabled()) {
            return builder;
        }
        if (config.getProxyHost() == null || config.getProxyHost().isBlank() ||
            config.getProxyPort() == null || config.getProxyPort() <= 0) {
            log.warn("Proxy enabled for MCP service {} but host/port is invalid, fallback to direct access",
                config.getId());
            return builder;
        }

        String proxyType = config.getProxyType() == null ? "http" : config.getProxyType().trim().toLowerCase(Locale.ROOT);
        if (!"http".equals(proxyType)) {
            log.warn("SDK HTTP transport currently supports only HTTP proxy, but {} configured for service {}. Fallback to direct.",
                proxyType, config.getName());
            return builder;
        }

        builder.proxy(ProxySelector.of(new InetSocketAddress(config.getProxyHost().trim(), config.getProxyPort())));

        if (config.getProxyUsername() != null && !config.getProxyUsername().isBlank()) {
            String username = config.getProxyUsername().trim();
            String password = config.getProxyPassword() == null ? "" : config.getProxyPassword();
            builder.authenticator(new java.net.Authenticator() {
                @Override
                protected java.net.PasswordAuthentication getPasswordAuthentication() {
                    return new java.net.PasswordAuthentication(username, password.toCharArray());
                }
            });
        }

        return builder;
    }

    private HttpRequest.Builder buildHttpRequestBuilder(McpServiceConfig config) {
        HttpRequest.Builder builder = HttpRequest.newBuilder();

        if (config.getAuthToken() != null && !config.getAuthToken().isBlank()) {
            String token = config.getAuthToken().trim();
            if (!token.toLowerCase(Locale.ROOT).startsWith("bearer ")) {
                token = "Bearer " + token;
            }
            builder.header(HttpHeaders.AUTHORIZATION, token);
        }

        Map<String, String> headers = readCustomHeaders(config);
        headers.forEach(builder::header);
        return builder;
    }

    private Map<String, String> readCustomHeaders(McpServiceConfig config) {
        if (config.getCustomHeadersJson() == null || config.getCustomHeadersJson().isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(config.getCustomHeadersJson(), new TypeReference<>() {});
        } catch (Exception ex) {
            log.warn("Invalid customHeadersJson for MCP service {}: {}", config.getId(), ex.getMessage());
            return Map.of();
        }
    }

    private TransportKind resolveTransportKind(McpServiceConfig config) {
        String protocol = normalizeProtocol(config.getProtocol());
        if (PROTOCOL_STDIO_PROXY.equals(protocol)) {
            return TransportKind.STDIO_PROXY;
        }
        if (PROTOCOL_LEGACY_SSE.equals(protocol) || "sse".equals(protocol) ||
            looksLikeLegacySseEndpoint(config.getBaseUrl()) ||
            looksLikeLegacySseEndpoint(config.getToolInvokePath()) ||
            looksLikeLegacySseEndpoint(config.getToolDiscoveryPath())) {
            return TransportKind.LEGACY_SSE;
        }
        if (PROTOCOL_STREAMABLE_HTTP.equals(protocol) ||
            looksLikeStreamableEndpoint(config.getBaseUrl()) ||
            looksLikeStreamableEndpoint(config.getToolInvokePath()) ||
            looksLikeStreamableEndpoint(config.getToolDiscoveryPath())) {
            return TransportKind.STREAMABLE_HTTP;
        }
        return TransportKind.LEGACY_HTTP;
    }

    private String transportFingerprint(McpServiceConfig config, TransportKind kind) {
        return String.join("|",
            kind.name(),
            nullSafe(config.getBaseUrl()),
            nullSafe(config.getToolDiscoveryPath()),
            nullSafe(config.getToolInvokePath()),
            nullSafe(config.getAuthToken()),
            nullSafe(config.getCustomHeadersJson()),
            String.valueOf(config.getTimeoutMs()),
            String.valueOf(config.isProxyEnabled()),
            nullSafe(config.getProxyType()),
            nullSafe(config.getProxyHost()),
            String.valueOf(config.getProxyPort()),
            nullSafe(config.getProxyUsername()),
            nullSafe(config.getProxyPassword())
        );
    }

    private String resolveLegacySseEndpoint(McpServiceConfig config) {
        String baseUrl = trim(config.getBaseUrl());
        String invokePath = trim(config.getToolInvokePath());
        String discoverPath = trim(config.getToolDiscoveryPath());

        if (looksLikeLegacySseEndpoint(invokePath)) {
            return isAbsoluteHttpUrl(invokePath) ? invokePath : buildUrl(baseUrl, invokePath);
        }
        if (looksLikeLegacySseEndpoint(discoverPath)) {
            return isAbsoluteHttpUrl(discoverPath) ? discoverPath : buildUrl(baseUrl, discoverPath);
        }
        if (looksLikeLegacySseEndpoint(baseUrl)) {
            return baseUrl;
        }
        return buildUrl(baseUrl, DEFAULT_LEGACY_SSE_PATH);
    }

    private String resolveStreamableEndpoint(McpServiceConfig config) {
        String baseUrl = trim(config.getBaseUrl());
        String invokePath = trim(config.getToolInvokePath());

        if (isAbsoluteHttpUrl(invokePath)) {
            return invokePath;
        }
        if (invokePath.isBlank() || "/tools/call".equals(invokePath)) {
            invokePath = DEFAULT_STREAMABLE_HTTP_PATH;
        }
        if (isAbsoluteHttpUrl(baseUrl) && looksLikeStreamableEndpoint(baseUrl)) {
            return baseUrl;
        }
        return buildUrl(baseUrl, invokePath);
    }

    private EndpointParts endpointParts(String endpoint, String defaultPath) {
        if (!isAbsoluteHttpUrl(endpoint)) {
            throw new IllegalArgumentException("MCP endpoint must be absolute URL: " + endpoint);
        }
        URI uri = URI.create(endpoint.trim());
        String scheme = uri.getScheme();
        String authority = uri.getAuthority();
        String baseUrl = scheme + "://" + authority;

        String path = uri.getPath();
        if (path == null || path.isBlank()) {
            path = defaultPath;
        }
        if (!path.startsWith("/")) {
            path = "/" + path;
        }
        if (uri.getQuery() != null && !uri.getQuery().isBlank()) {
            path = path + "?" + uri.getQuery();
        }
        return new EndpointParts(baseUrl, path);
    }

    private boolean looksLikeLegacySseEndpoint(String value) {
        return pathEndsWith(value, "/sse");
    }

    private boolean looksLikeStreamableEndpoint(String value) {
        return pathEndsWith(value, "/mcp");
    }

    private boolean pathEndsWith(String value, String suffix) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String path = value.trim();
        if (isAbsoluteHttpUrl(path)) {
            try {
                URI uri = URI.create(path);
                path = uri.getPath();
            } catch (Exception ex) {
                return false;
            }
        }
        if (path == null || path.isBlank()) {
            return false;
        }
        String normalized = path.trim().toLowerCase(Locale.ROOT);
        while (normalized.endsWith("/") && normalized.length() > 1) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized.endsWith(suffix);
    }

    private String normalizeProtocol(String protocol) {
        if (protocol == null || protocol.isBlank()) {
            return "";
        }
        String normalized = protocol.trim().toLowerCase(Locale.ROOT);
        if (normalized.equals("stateless")
            || normalized.equals("stateful")
            || normalized.equals("streamable_http")
            || normalized.equals("streamable-http")) {
            return PROTOCOL_STREAMABLE_HTTP;
        }
        if (normalized.equals("stdio_proxy")) {
            return PROTOCOL_STDIO_PROXY;
        }
        return normalized;
    }

    private String serviceKey(McpServiceConfig config) {
        if (config.getId() != null && !config.getId().isBlank()) {
            return config.getId();
        }
        if (config.getName() != null && !config.getName().isBlank()) {
            return config.getName();
        }
        return config.getBaseUrl() == null ? "unknown" : config.getBaseUrl();
    }

    private boolean isStdioProxyProtocol(McpServiceConfig config) {
        return PROTOCOL_STDIO_PROXY.equals(normalizeProtocol(config.getProtocol()));
    }

    private List<McpToolDefinition> discoverToolsViaStdioProxy(McpServiceConfig config) {
        try {
            Object result = stdioProxyService.callForResult(config, "tools/list", Map.of());
            Object tools = result;
            if (result instanceof Map<?, ?> map && map.containsKey("tools")) {
                tools = map.get("tools");
            }
            return normalizeTools(tools);
        } catch (Exception ex) {
            log.warn("Failed to discover MCP tools via stdio proxy for {}: {}", config.getName(), ex.getMessage());
            return List.of();
        }
    }

    private McpToolInvokeResult invokeToolViaStdioProxy(McpServiceConfig config, String toolName,
                                                         Map<String, Object> arguments) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("name", toolName);
        params.put("arguments", arguments == null ? Map.of() : arguments);
        try {
            Object result = stdioProxyService.callForResult(config, "tools/call", params);
            return new McpToolInvokeResult(true, result, "MCP call success", null);
        } catch (Exception ex) {
            log.warn("Failed to invoke MCP tool {} via stdio proxy for {}: {}", toolName, config.getName(),
                ex.getMessage());
            return new McpToolInvokeResult(false, null, null, ex.getMessage());
        }
    }

    private List<McpToolDefinition> discoverToolsViaLegacyHttp(McpServiceConfig config) {
        WebClient webClient = webClientFor(config);
        String url = buildUrl(config.getBaseUrl(), config.getToolDiscoveryPath());
        try {
            Object raw = webClient.get()
                .uri(url)
                .headers(headers -> applyHeaders(headers, config))
                .retrieve()
                .bodyToMono(Object.class)
                .timeout(Duration.ofMillis(config.getTimeoutMs()))
                .block();
            return normalizeTools(raw);
        } catch (Exception ex) {
            log.warn("Failed to discover MCP tools from {}: {}", url, ex.getMessage());
            return List.of();
        }
    }

    private McpToolInvokeResult invokeToolViaLegacyHttp(McpServiceConfig config, String toolName,
                                                         Map<String, Object> arguments) {
        WebClient webClient = webClientFor(config);
        String url = buildUrl(config.getBaseUrl(), config.getToolInvokePath());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("toolName", toolName);
        payload.put("name", toolName);
        payload.put("arguments", arguments == null ? Map.of() : arguments);
        payload.put("input", arguments == null ? Map.of() : arguments);

        try {
            Object raw = webClient.post()
                .uri(url)
                .headers(headers -> applyHeaders(headers, config))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(payload)
                .retrieve()
                .bodyToMono(Object.class)
                .timeout(Duration.ofMillis(config.getTimeoutMs()))
                .block();
            return normalizeInvokeResult(raw);
        } catch (Exception ex) {
            log.warn("Failed to invoke MCP tool {} on {}: {}", toolName, url, ex.getMessage());
            return new McpToolInvokeResult(false, null, null, ex.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private List<McpToolDefinition> normalizeTools(Object raw) {
        Object source = raw;
        if (raw instanceof Map<?, ?> map) {
            if (map.containsKey("data")) {
                source = map.get("data");
            } else if (map.containsKey("tools")) {
                source = map.get("tools");
            } else if (map.containsKey("result")) {
                source = map.get("result");
                if (source instanceof Map<?, ?> resultMap && resultMap.containsKey("tools")) {
                    source = resultMap.get("tools");
                }
            }
        }
        if (source instanceof Map<?, ?> map && map.containsKey("tools")) {
            source = map.get("tools");
        }
        if (!(source instanceof List<?> list)) {
            return List.of();
        }

        List<McpToolDefinition> tools = new ArrayList<>();
        Set<String> dedupe = new LinkedHashSet<>();
        for (Object item : list) {
            if (item instanceof String name) {
                if (dedupe.add(name)) {
                    tools.add(new McpToolDefinition(name, "MCP tool", Map.of()));
                }
                continue;
            }
            if (!(item instanceof Map<?, ?> map)) {
                continue;
            }
            String name = stringValue(map.get("name"));
            if (name == null || name.isBlank()) {
                name = stringValue(map.get("toolName"));
            }
            if (name == null || name.isBlank()) {
                name = stringValue(map.get("id"));
            }
            if (name == null || name.isBlank() || !dedupe.add(name)) {
                continue;
            }

            String description = stringValue(map.get("description"));
            if (description == null) {
                description = "MCP tool";
            }

            Map<String, Object> inputSchema = Map.of();
            Object schema = map.get("inputSchema");
            if (schema == null) {
                schema = map.get("parameters");
            }
            if (schema instanceof Map<?, ?> schemaMap) {
                inputSchema = objectMapper.convertValue(schemaMap, new TypeReference<>() {});
            }

            tools.add(new McpToolDefinition(name, description, inputSchema));
        }
        return tools;
    }

    @SuppressWarnings("unchecked")
    private McpToolInvokeResult normalizeInvokeResult(Object raw) {
        if (!(raw instanceof Map<?, ?> rawMap)) {
            return new McpToolInvokeResult(true, raw, "ok", null);
        }
        Map<String, Object> map = objectMapper.convertValue(rawMap, new TypeReference<>() {});

        Integer code = asInteger(map.get("code"));
        if (code != null && code >= 400) {
            String msg = stringValue(map.get("message"));
            return new McpToolInvokeResult(false, null, msg, msg);
        }

        Object isError = map.get("isError");
        if (Boolean.TRUE.equals(isError)) {
            String message = stringValue(map.get("message"));
            if (message == null || message.isBlank()) {
                message = stringValue(map.get("content"));
            }
            return new McpToolInvokeResult(false, map, message, message == null ? "MCP tool error" : message);
        }

        if (map.containsKey("error")) {
            return new McpToolInvokeResult(false, null, stringValue(map.get("message")), String.valueOf(map.get("error")));
        }

        Object data = map.containsKey("data") ? map.get("data") : map.getOrDefault("result", map);
        return new McpToolInvokeResult(true, data, stringValue(map.get("message")), null);
    }

    private String buildUrl(String baseUrl, String path) {
        String left = baseUrl == null ? "" : baseUrl.trim();
        String right = path == null ? "" : path.trim();
        if (right.isBlank()) {
            return left;
        }
        if (!right.startsWith("/")) {
            right = "/" + right;
        }
        if (left.endsWith("/")) {
            left = left.substring(0, left.length() - 1);
        }
        return left + right;
    }

    private void applyHeaders(HttpHeaders headers, McpServiceConfig config) {
        headers.set(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE);
        if (config.getAuthToken() != null && !config.getAuthToken().isBlank()) {
            String token = config.getAuthToken().trim();
            if (!token.toLowerCase(Locale.ROOT).startsWith("bearer ")) {
                token = "Bearer " + token;
            }
            headers.set(HttpHeaders.AUTHORIZATION, token);
        }

        Map<String, String> extra = readCustomHeaders(config);
        extra.forEach(headers::set);
    }

    private WebClient webClientFor(McpServiceConfig config) {
        if (!config.isProxyEnabled()) {
            return directWebClient;
        }
        if (config.getProxyHost() == null || config.getProxyHost().isBlank() ||
            config.getProxyPort() == null || config.getProxyPort() <= 0) {
            log.warn("Proxy enabled for MCP service {} but host/port is invalid, fallback to direct access",
                config.getId());
            return directWebClient;
        }

        HttpClient httpClient = HttpClient.create().proxy(proxy -> {
            ProxyProvider.Builder builder = proxy.type(resolveProxyType(config.getProxyType()))
                .host(config.getProxyHost().trim())
                .port(config.getProxyPort());

            if (config.getProxyUsername() != null && !config.getProxyUsername().isBlank()) {
                String password = config.getProxyPassword() == null ? "" : config.getProxyPassword().trim();
                builder.username(config.getProxyUsername().trim())
                    .password(ignored -> password);
            }
        });
        return WebClient.builder()
            .clientConnector(new ReactorClientHttpConnector(httpClient))
            .build();
    }

    private ProxyProvider.Proxy resolveProxyType(String proxyType) {
        String type = proxyType == null ? "http" : proxyType.trim().toLowerCase(Locale.ROOT);
        return switch (type) {
            case "socks", "socks5" -> ProxyProvider.Proxy.SOCKS5;
            case "socks4" -> ProxyProvider.Proxy.SOCKS4;
            default -> ProxyProvider.Proxy.HTTP;
        };
    }

    private boolean isAbsoluteHttpUrl(String value) {
        if (value == null) {
            return false;
        }
        String lowered = value.trim().toLowerCase(Locale.ROOT);
        return lowered.startsWith("http://") || lowered.startsWith("https://");
    }

    private String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private String nullSafe(String value) {
        return value == null ? "" : value;
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private Integer asInteger(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text) {
            try {
                return Integer.parseInt(text);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private void closeQuietly(McpSyncClient client, String key) {
        try {
            boolean closed = client.closeGracefully();
            if (!closed) {
                client.close();
            }
        } catch (Exception ex) {
            log.debug("Failed to close old MCP client for {}: {}", key, ex.getMessage());
        }
    }

    private enum TransportKind {
        STDIO_PROXY,
        LEGACY_SSE,
        STREAMABLE_HTTP,
        LEGACY_HTTP
    }

    private record ManagedSdkClient(String fingerprint, McpSyncClient client) {
    }

    private record EndpointParts(String baseUrl, String endpointPath) {
    }
}
