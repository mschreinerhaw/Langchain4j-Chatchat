package com.chatchat.integration.mcp.service;

import com.chatchat.integration.mcp.entity.McpServiceConfig;
import com.chatchat.integration.mcp.config.McpCenterProperties;
import com.chatchat.integration.mcp.model.McpToolDefinition;
import com.chatchat.integration.mcp.model.McpToolInvokeResult;
import com.chatchat.common.tool.ToolLogSummarizer;
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
    private final McpCenterProperties centerProperties;
    private final McpStdioProxyService stdioProxyService;
    private final WebClient directWebClient = WebClient.builder().build();
    private final Map<String, ManagedSdkClient> sdkClientCache = new ConcurrentHashMap<>();

    /**
     * Performs the discover tools operation.
     *
     * @param config the config value
     * @return the operation result
     */
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

    /**
     * Performs the invoke tool operation.
     *
     * @param config the config value
     * @param toolName the tool name value
     * @param arguments the arguments value
     * @return the operation result
     */
    public McpToolInvokeResult invokeTool(McpServiceConfig config, String toolName, Map<String, Object> arguments) {
        return invokeTool(config, toolName, arguments, null);
    }

    /**
     * Performs the invoke tool operation.
     *
     * @param config the config value
     * @param toolName the tool name value
     * @param arguments the arguments value
     * @param timeoutOverrideMs the timeout override ms value
     * @return the operation result
     */
    public McpToolInvokeResult invokeTool(McpServiceConfig config, String toolName, Map<String, Object> arguments,
                                          Long timeoutOverrideMs) {
        if (isStdioProxyProtocol(config)) {
            return invokeToolViaStdioProxy(config, toolName, arguments);
        }

        TransportKind kind = resolveTransportKind(config);
        if (kind == TransportKind.LEGACY_HTTP) {
            return invokeToolViaLegacyHttp(config, toolName, arguments);
        }
        int requestTimeoutMs = effectiveTimeoutMs(config, timeoutOverrideMs);
        log.info("MCP SDK invoke started serviceId={} service={} remoteTool={} transport={} protocol={} timeoutMs={} args={}",
            config.getId(),
            config.getName(),
            toolName,
            kind,
            config.getProtocol(),
            requestTimeoutMs,
            ToolLogSummarizer.summarize(arguments));

        try {
            long startedAt = System.currentTimeMillis();
            McpSyncClient client = getOrCreateSdkClient(config, kind, requestTimeoutMs);
            Object raw = client.callTool(new McpSchema.CallToolRequest(toolName,
                arguments == null ? Map.of() : arguments));
            Map<String, Object> mapped = objectMapper.convertValue(raw, new TypeReference<>() {});
            McpToolInvokeResult result = normalizeInvokeResult(mapped);
            if (result.success()) {
                log.info("MCP SDK invoke succeeded serviceId={} service={} remoteTool={} durationMs={} result={}",
                    config.getId(),
                    config.getName(),
                    toolName,
                    Math.max(0L, System.currentTimeMillis() - startedAt),
                    ToolLogSummarizer.summarize(result.data()));
            } else {
                log.warn("MCP SDK invoke returned error serviceId={} service={} remoteTool={} durationMs={} error={} result={}",
                    config.getId(),
                    config.getName(),
                    toolName,
                    Math.max(0L, System.currentTimeMillis() - startedAt),
                    result.errorMessage(),
                    ToolLogSummarizer.summarize(result.data()));
            }
            return result;
        } catch (Exception ex) {
            log.warn("MCP SDK invoke threw serviceId={} service={} remoteTool={} timeoutMs={} error={}",
                config.getId(),
                config.getName(),
                toolName,
                requestTimeoutMs,
                ex.getMessage(),
                ex);
            return new McpToolInvokeResult(false, null, null, ex.getMessage());
        }
    }

    /**
     * Returns the or create sdk client.
     *
     * @param config the config value
     * @param kind the kind value
     * @return the or create sdk client
     */
    private McpSyncClient getOrCreateSdkClient(McpServiceConfig config, TransportKind kind) {
        return getOrCreateSdkClient(config, kind, effectiveTimeoutMs(config, null));
    }

    /**
     * Returns the or create sdk client.
     *
     * @param config the config value
     * @param kind the kind value
     * @param requestTimeoutMs the request timeout ms value
     * @return the or create sdk client
     */
    private McpSyncClient getOrCreateSdkClient(McpServiceConfig config, TransportKind kind, int requestTimeoutMs) {
        String key = serviceKey(config) + ":" + Math.max(1000, requestTimeoutMs);
        String fingerprint = transportFingerprint(config, kind, requestTimeoutMs);

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
                .requestTimeout(Duration.ofMillis(Math.max(1000, requestTimeoutMs)))
                .clientInfo(new McpSchema.Implementation("chatchat-mcp-client", "1.0.0"))
                .build();
            client.initialize();

            sdkClientCache.put(key, new ManagedSdkClient(fingerprint, client));
            return client;
        }
    }

    /**
     * Creates the sdk transport.
     *
     * @param config the config value
     * @param kind the kind value
     * @return the created sdk transport
     */
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

    /**
     * Builds the http client builder.
     *
     * @param config the config value
     * @return the built http client builder
     */
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
                /**
                 * Returns the password authentication.
                 *
                 * @return the password authentication
                 */
                @Override
                protected java.net.PasswordAuthentication getPasswordAuthentication() {
                    return new java.net.PasswordAuthentication(username, password.toCharArray());
                }
            });
        }

        return builder;
    }

    /**
     * Builds the http request builder.
     *
     * @param config the config value
     * @return the built http request builder
     */
    private HttpRequest.Builder buildHttpRequestBuilder(McpServiceConfig config) {
        HttpRequest.Builder builder = HttpRequest.newBuilder();
        String fallbackToken = fallbackStandaloneInvocationToken(config);

        if (config.getAuthToken() != null && !config.getAuthToken().isBlank()) {
            String token = config.getAuthToken().trim();
            if (!token.toLowerCase(Locale.ROOT).startsWith("bearer ")) {
                token = "Bearer " + token;
            }
            builder.header(HttpHeaders.AUTHORIZATION, token);
        } else if (fallbackToken != null) {
            builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + fallbackToken);
        }

        Map<String, String> headers = readCustomHeaders(config);
        headers.forEach(builder::header);
        return builder;
    }

    /**
     * Reads the custom headers.
     *
     * @param config the config value
     * @return the operation result
     */
    private Map<String, String> readCustomHeaders(McpServiceConfig config) {
        Map<String, String> headers = new LinkedHashMap<>();
        try {
            if (config.getCustomHeadersJson() != null && !config.getCustomHeadersJson().isBlank()) {
                headers.putAll(objectMapper.readValue(config.getCustomHeadersJson(), new TypeReference<>() {}));
            }
        } catch (Exception ex) {
            log.warn("Invalid customHeadersJson for MCP service {}: {}", config.getId(), ex.getMessage());
        }
        String fallbackToken = fallbackStandaloneInvocationToken(config);
        if (fallbackToken != null) {
            headers.putIfAbsent("X-MCP-TOKEN", fallbackToken);
        }
        return headers.isEmpty() ? Map.of() : headers;
    }

    /**
     * Resolves the transport kind.
     *
     * @param config the config value
     * @return the resolved transport kind
     */
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

    /**
     * Performs the transport fingerprint operation.
     *
     * @param config the config value
     * @param kind the kind value
     * @param requestTimeoutMs the request timeout ms value
     * @return the operation result
     */
    private String transportFingerprint(McpServiceConfig config, TransportKind kind, int requestTimeoutMs) {
        return String.join("|",
            kind.name(),
            nullSafe(config.getBaseUrl()),
            nullSafe(config.getToolDiscoveryPath()),
            nullSafe(config.getToolInvokePath()),
            nullSafe(config.getAuthToken()),
            nullSafe(config.getCustomHeadersJson()),
            nullSafe(fallbackStandaloneInvocationToken(config)),
            String.valueOf(Math.max(1000, requestTimeoutMs)),
            String.valueOf(config.isProxyEnabled()),
            nullSafe(config.getProxyType()),
            nullSafe(config.getProxyHost()),
            String.valueOf(config.getProxyPort()),
            nullSafe(config.getProxyUsername()),
            nullSafe(config.getProxyPassword())
        );
    }

    private String fallbackStandaloneInvocationToken(McpServiceConfig config) {
        if (config == null || centerProperties == null) {
            return null;
        }
        String token = normalizeText(centerProperties.getInvocationToken());
        if (token == null || !isStandaloneCenterService(config)) {
            return null;
        }
        return token;
    }

    private boolean isStandaloneCenterService(McpServiceConfig config) {
        String standaloneId = normalizeText(centerProperties.getStandaloneServiceId());
        String standaloneName = normalizeText(centerProperties.getStandaloneServiceName());
        return (standaloneId != null && standaloneId.equalsIgnoreCase(normalizeText(config.getId())))
            || (standaloneName != null && standaloneName.equalsIgnoreCase(normalizeText(config.getName())));
    }

    private String normalizeText(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    /**
     * Resolves the legacy sse endpoint.
     *
     * @param config the config value
     * @return the resolved legacy sse endpoint
     */
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

    /**
     * Resolves the streamable endpoint.
     *
     * @param config the config value
     * @return the resolved streamable endpoint
     */
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

    /**
     * Performs the endpoint parts operation.
     *
     * @param endpoint the endpoint value
     * @param defaultPath the default path value
     * @return the operation result
     */
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

    /**
     * Returns whether looks like legacy sse endpoint.
     *
     * @param value the value value
     * @return whether the condition is satisfied
     */
    private boolean looksLikeLegacySseEndpoint(String value) {
        return pathEndsWith(value, "/sse");
    }

    /**
     * Returns whether looks like streamable endpoint.
     *
     * @param value the value value
     * @return whether the condition is satisfied
     */
    private boolean looksLikeStreamableEndpoint(String value) {
        return pathEndsWith(value, "/mcp");
    }

    /**
     * Returns whether path ends with.
     *
     * @param value the value value
     * @param suffix the suffix value
     * @return whether the condition is satisfied
     */
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

    /**
     * Normalizes the protocol.
     *
     * @param protocol the protocol value
     * @return the operation result
     */
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

    /**
     * Performs the service key operation.
     *
     * @param config the config value
     * @return the operation result
     */
    private String serviceKey(McpServiceConfig config) {
        if (config.getId() != null && !config.getId().isBlank()) {
            return config.getId();
        }
        if (config.getName() != null && !config.getName().isBlank()) {
            return config.getName();
        }
        return config.getBaseUrl() == null ? "unknown" : config.getBaseUrl();
    }

    /**
     * Returns whether is stdio proxy protocol.
     *
     * @param config the config value
     * @return whether the condition is satisfied
     */
    private boolean isStdioProxyProtocol(McpServiceConfig config) {
        return PROTOCOL_STDIO_PROXY.equals(normalizeProtocol(config.getProtocol()));
    }

    /**
     * Performs the discover tools via stdio proxy operation.
     *
     * @param config the config value
     * @return the operation result
     */
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

    /**
     * Performs the invoke tool via stdio proxy operation.
     *
     * @param config the config value
     * @param toolName the tool name value
     * @param arguments the arguments value
     * @return the operation result
     */
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

    /**
     * Performs the discover tools via legacy http operation.
     *
     * @param config the config value
     * @return the operation result
     */
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

    /**
     * Performs the invoke tool via legacy http operation.
     *
     * @param config the config value
     * @param toolName the tool name value
     * @param arguments the arguments value
     * @return the operation result
     */
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
            log.info("MCP legacy HTTP invoke started serviceId={} service={} remoteTool={} url={} timeoutMs={} args={}",
                config.getId(),
                config.getName(),
                toolName,
                url,
                config.getTimeoutMs(),
                ToolLogSummarizer.summarize(arguments));
            long startedAt = System.currentTimeMillis();
            Object raw = webClient.post()
                .uri(url)
                .headers(headers -> applyHeaders(headers, config))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(payload)
                .retrieve()
                .bodyToMono(Object.class)
                .timeout(Duration.ofMillis(config.getTimeoutMs()))
                .block();
            McpToolInvokeResult result = normalizeInvokeResult(raw);
            log.info("MCP legacy HTTP invoke completed serviceId={} service={} remoteTool={} success={} durationMs={} result={}",
                config.getId(),
                config.getName(),
                toolName,
                result.success(),
                Math.max(0L, System.currentTimeMillis() - startedAt),
                ToolLogSummarizer.summarize(result.data()));
            return result;
        } catch (Exception ex) {
            log.warn("Failed to invoke MCP tool {} on {}: {}", toolName, url, ex.getMessage());
            return new McpToolInvokeResult(false, null, null, ex.getMessage());
        }
    }

    /**
     * Normalizes the tools.
     *
     * @param raw the raw value
     * @return the operation result
     */
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
            Map<String, Object> governance = governanceMap(map);

            tools.add(new McpToolDefinition(
                name,
                description,
                inputSchema,
                firstText(governanceText(map, governance, "category", "tool_category"), null),
                governanceText(map, governance, "risk_level", "riskLevel"),
                governanceText(map, governance, "operation_type", "operationType"),
                governanceText(map, governance, "runtime_level", "runtimeLevel"),
                asBoolean(firstPresent(
                    map.get("user_visible"),
                    map.get("userVisible"),
                    governance.get("user_visible"),
                    governance.get("userVisible")
                )),
                firstMap(map, governance, "confirmation", "confirmation_policy"),
                firstMap(map, governance, "permissions", "permission", "permission_policy"),
                firstMap(map, governance, "input_policy", "inputPolicy"),
                firstMap(map, governance, "output_policy", "outputPolicy"),
                firstLong(map, governance, "timeoutMillis", "timeout_ms", "timeoutMs")
            ));
        }
        return tools;
    }

    /**
     * Performs the as map operation.
     *
     * @param value the value value
     * @return the operation result
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return Map.of();
        }
        return objectMapper.convertValue(map, new TypeReference<>() {});
    }

    /**
     * Performs the governance map operation.
     *
     * @param toolMap the tool map value
     * @return the operation result
     */
    private Map<String, Object> governanceMap(Map<?, ?> toolMap) {
        Map<String, Object> direct = asMap(toolMap.get("governance"));
        if (!direct.isEmpty()) {
            return direct;
        }
        Map<String, Object> meta = asMap(firstPresent(toolMap.get("_meta"), toolMap.get("meta")));
        return asMap(meta.get("governance"));
    }

    /**
     * Performs the governance text operation.
     *
     * @param toolMap the tool map value
     * @param governance the governance value
     * @param keys the keys value
     * @return the operation result
     */
    private String governanceText(Map<?, ?> toolMap, Map<String, Object> governance, String... keys) {
        for (String key : keys) {
            String value = stringValue(toolMap.get(key));
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        for (String key : keys) {
            String value = stringValue(governance.get(key));
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        Map<String, Object> meta = asMap(firstPresent(toolMap.get("_meta"), toolMap.get("meta")));
        for (String key : keys) {
            String value = stringValue(meta.get(key));
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    /**
     * Performs the first map operation.
     *
     * @param toolMap the tool map value
     * @param governance the governance value
     * @param keys the keys value
     * @return the operation result
     */
    private Map<String, Object> firstMap(Map<?, ?> toolMap, Map<String, Object> governance, String... keys) {
        for (String key : keys) {
            Map<String, Object> value = asMap(toolMap.get(key));
            if (!value.isEmpty()) {
                return value;
            }
        }
        for (String key : keys) {
            Map<String, Object> value = asMap(governance.get(key));
            if (!value.isEmpty()) {
                return value;
            }
        }
        Map<String, Object> meta = asMap(firstPresent(toolMap.get("_meta"), toolMap.get("meta")));
        for (String key : keys) {
            Map<String, Object> value = asMap(meta.get(key));
            if (!value.isEmpty()) {
                return value;
            }
        }
        return Map.of();
    }

    /**
     * Performs the first long operation.
     *
     * @param toolMap the tool map value
     * @param governance the governance value
     * @param keys the keys value
     * @return the operation result
     */
    private Long firstLong(Map<?, ?> toolMap, Map<String, Object> governance, String... keys) {
        Map<String, Object> meta = asMap(firstPresent(toolMap.get("_meta"), toolMap.get("meta")));
        for (String key : keys) {
            Long value = asLong(toolMap.get(key));
            if (value != null) {
                return value;
            }
        }
        for (String key : keys) {
            Long value = asLong(meta.get(key));
            if (value != null) {
                return value;
            }
        }
        for (String key : keys) {
            Long value = asLong(governance.get(key));
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    /**
     * Normalizes the invoke result.
     *
     * @param raw the raw value
     * @return the operation result
     */
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

        Object data = firstPresent(
            map.get("structuredContent"),
            map.get("structured_content"),
            map.get("data"),
            map.get("result"),
            map
        );
        return new McpToolInvokeResult(true, data, stringValue(map.get("message")), null);
    }

    /**
     * Performs the first present operation.
     *
     * @param values the values value
     * @return the operation result
     */
    private Object firstPresent(Object... values) {
        if (values == null) {
            return null;
        }
        for (Object value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    /**
     * Builds the url.
     *
     * @param baseUrl the base url value
     * @param path the path value
     * @return the built url
     */
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

    /**
     * Performs the apply headers operation.
     *
     * @param headers the headers value
     * @param config the config value
     */
    private void applyHeaders(HttpHeaders headers, McpServiceConfig config) {
        headers.set(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE);
        String fallbackToken = fallbackStandaloneInvocationToken(config);
        if (config.getAuthToken() != null && !config.getAuthToken().isBlank()) {
            String token = config.getAuthToken().trim();
            if (!token.toLowerCase(Locale.ROOT).startsWith("bearer ")) {
                token = "Bearer " + token;
            }
            headers.set(HttpHeaders.AUTHORIZATION, token);
        } else if (fallbackToken != null) {
            headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + fallbackToken);
        }

        Map<String, String> extra = readCustomHeaders(config);
        extra.forEach(headers::set);
    }

    /**
     * Performs the web client for operation.
     *
     * @param config the config value
     * @return the operation result
     */
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

    /**
     * Resolves the proxy type.
     *
     * @param proxyType the proxy type value
     * @return the resolved proxy type
     */
    private ProxyProvider.Proxy resolveProxyType(String proxyType) {
        String type = proxyType == null ? "http" : proxyType.trim().toLowerCase(Locale.ROOT);
        return switch (type) {
            case "socks", "socks5" -> ProxyProvider.Proxy.SOCKS5;
            case "socks4" -> ProxyProvider.Proxy.SOCKS4;
            default -> ProxyProvider.Proxy.HTTP;
        };
    }

    /**
     * Returns whether is absolute http url.
     *
     * @param value the value value
     * @return whether the condition is satisfied
     */
    private boolean isAbsoluteHttpUrl(String value) {
        if (value == null) {
            return false;
        }
        String lowered = value.trim().toLowerCase(Locale.ROOT);
        return lowered.startsWith("http://") || lowered.startsWith("https://");
    }

    /**
     * Performs the trim operation.
     *
     * @param value the value value
     * @return the operation result
     */
    private String trim(String value) {
        return value == null ? "" : value.trim();
    }

    /**
     * Performs the null safe operation.
     *
     * @param value the value value
     * @return the operation result
     */
    private String nullSafe(String value) {
        return value == null ? "" : value;
    }

    /**
     * Performs the string value operation.
     *
     * @param value the value value
     * @return the operation result
     */
    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /**
     * Performs the first text operation.
     *
     * @param value the value value
     * @param fallback the fallback value
     * @return the operation result
     */
    private String firstText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    /**
     * Returns whether as boolean.
     *
     * @param value the value value
     * @return whether the condition is satisfied
     */
    private Boolean asBoolean(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isBlank() ? null : Boolean.parseBoolean(text);
    }

    /**
     * Performs the as integer operation.
     *
     * @param value the value value
     * @return the operation result
     */
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

    /**
     * Performs the as long operation.
     *
     * @param value the value value
     * @return the operation result
     */
    private Long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text) {
            try {
                return Long.parseLong(text.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    /**
     * Performs the effective timeout ms operation.
     *
     * @param config the config value
     * @param timeoutOverrideMs the timeout override ms value
     * @return the operation result
     */
    private int effectiveTimeoutMs(McpServiceConfig config, Long timeoutOverrideMs) {
        long serviceTimeout = config == null ? 20000L : config.getTimeoutMs();
        long override = timeoutOverrideMs == null ? 0L : timeoutOverrideMs;
        return (int) Math.min(Integer.MAX_VALUE, Math.max(1000L, Math.max(serviceTimeout, override)));
    }

    /**
     * Closes the quietly.
     *
     * @param client the client value
     * @param key the key value
     */
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
