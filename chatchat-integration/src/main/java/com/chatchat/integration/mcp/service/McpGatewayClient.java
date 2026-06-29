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
import io.modelcontextprotocol.spec.ProtocolVersions;
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
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
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
    private static final Duration UNBOUNDED_MCP_REQUEST_TIMEOUT = Duration.ofDays(36500);
    private static final String JSON_RPC_VERSION = "2.0";
    private static final String ERROR_TENANT_MISSING = "TENANT_MISSING";
    private static final String ERROR_SESSION_INVALID = "SESSION_INVALID";
    private static final String ERROR_MCP_HTTP = "MCP_HTTP_ERROR";
    private static final String ERROR_MCP_TOOL = "MCP_TOOL_ERROR";
    private static final String ERROR_TOOL_BUSY = "TOOL_BUSY";
    private static final String ACTION_STOP = "STOP";
    private static final String ACTION_REBUILD_SESSION = "REBUILD_SESSION";
    private static final String TOOL_DISCOVERY_SCOPE = "__tools_list";
    private static final String STATE_RUNNING = "RUNNING";
    private static final String STATE_REBUILDING = "REBUILDING";
    private static final String STATE_SUCCEEDED = "SUCCEEDED";
    private static final String STATE_FAILED = "FAILED";
    private static final String STATE_STOPPED = "STOPPED";

    private final ObjectMapper objectMapper;
    private final McpCenterProperties centerProperties;
    private final McpStdioProxyService stdioProxyService;
    private final WebClient directWebClient = WebClient.builder().build();
    private final Map<String, ManagedSdkClient> sdkClientCache = new ConcurrentHashMap<>();
    private final Map<String, Object> sdkSessionLocks = new ConcurrentHashMap<>();

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
        if (useDirectStreamableHttp(config, kind)) {
            return discoverToolsViaDirectStreamableHttp(config, null);
        }
        log.info("Using MCP SDK transport {} for service {} (protocol={})",
            kind, config.getName(), config.getProtocol());

        Object sessionLock = sdkSessionLock(config, 0, TOOL_DISCOVERY_SCOPE);
        synchronized (sessionLock) {
        try {
            McpSyncClient client = getOrCreateSdkClient(config, kind, 0, TOOL_DISCOVERY_SCOPE);
            Object raw = client.listTools();
            Map<String, Object> mapped = objectMapper.convertValue(raw, new TypeReference<>() {});
            return normalizeTools(mapped);
        } catch (Exception ex) {
            log.warn("Failed to discover MCP tools via SDK for {}: {}", config.getName(), ex.getMessage());
            if (kind == TransportKind.STREAMABLE_HTTP) {
                return discoverToolsViaDirectStreamableHttp(config, ex);
            }
            return List.of();
        }
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
        String tenantValidationError = validateTenantContext(arguments);
        if (tenantValidationError != null) {
            log.warn("MCP invoke rejected before transport serviceId={} service={} remoteTool={} error={} args={}",
                config == null ? null : config.getId(),
                config == null ? null : config.getName(),
                toolName,
                tenantValidationError,
                ToolLogSummarizer.summarize(arguments));
            return McpToolInvokeResult.failure(
                tenantValidationError,
                ERROR_TENANT_MISSING,
                false,
                ACTION_STOP,
                executionState(STATE_STOPPED, config, toolName, 0, 0, ERROR_TENANT_MISSING, ACTION_STOP, arguments)
            );
        }
        if (isStdioProxyProtocol(config)) {
            return invokeToolViaStdioProxy(config, toolName, arguments);
        }

        TransportKind kind = resolveTransportKind(config);
        if (kind == TransportKind.LEGACY_HTTP) {
            return invokeToolViaLegacyHttp(config, toolName, arguments);
        }
        int requestTimeoutMs = effectiveToolTimeoutMs(timeoutOverrideMs);
        if (useDirectStreamableHttp(config, kind)) {
            return invokeToolViaDirectStreamableHttp(config, requestTimeoutMs, toolName, arguments, null);
        }
        log.info("MCP SDK invoke started serviceId={} service={} remoteTool={} transport={} protocol={} timeoutMs={} args={}",
            config.getId(),
            config.getName(),
            toolName,
            kind,
            config.getProtocol(),
            requestTimeoutMs <= 0 ? "unbounded" : requestTimeoutMs,
            ToolLogSummarizer.summarize(arguments));

        Object sessionLock = sdkSessionLock(config, requestTimeoutMs, toolName);
        synchronized (sessionLock) {
        try {
            long startedAt = System.currentTimeMillis();
            McpToolInvokeResult result = invokeSdkTool(config, kind, requestTimeoutMs, toolName, arguments);
            result = withExecutionState(result, config, toolName, requestTimeoutMs, 0, arguments);
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
            if (isRecoverableMcpSessionFailure(ex)) {
                invalidateSdkClient(config, kind, requestTimeoutMs, toolName, ex);
                try {
                    long retryStartedAt = System.currentTimeMillis();
                    McpToolInvokeResult result = invokeSdkTool(config, kind, requestTimeoutMs, toolName, arguments);
                    result = withExecutionState(result, config, toolName, requestTimeoutMs, 1, arguments);
                    log.info("MCP SDK invoke recovered after session reset serviceId={} service={} remoteTool={} durationMs={} result={}",
                        config.getId(),
                        config.getName(),
                        toolName,
                        Math.max(0L, System.currentTimeMillis() - retryStartedAt),
                        ToolLogSummarizer.summarize(result.data()));
                    return result;
                } catch (Exception retryEx) {
                    log.warn("MCP SDK invoke retry after session reset failed serviceId={} service={} remoteTool={} timeoutMs={} error={}",
                        config.getId(),
                        config.getName(),
                        toolName,
                        requestTimeoutMs,
                        retryEx.getMessage(),
                        retryEx);
                    ex = retryEx;
                }
            }
            log.warn("MCP SDK invoke threw serviceId={} service={} remoteTool={} timeoutMs={} error={}",
                config.getId(),
                config.getName(),
                toolName,
                requestTimeoutMs,
                ex.getMessage(),
                ex);
            if (kind == TransportKind.STREAMABLE_HTTP) {
                return invokeToolViaDirectStreamableHttp(config, requestTimeoutMs, toolName, arguments, ex);
            }
            return withExecutionState(failureResult(ex), config, toolName, requestTimeoutMs, 1, arguments);
        }
        }
    }

    private boolean useDirectStreamableHttp(McpServiceConfig config, TransportKind kind) {
        return kind == TransportKind.STREAMABLE_HTTP && isStandaloneCenterService(config);
    }

    private Object sdkSessionLock(McpServiceConfig config, int requestTimeoutMs, String scope) {
        return sdkSessionLocks.computeIfAbsent(sdkClientKey(config, requestTimeoutMs, scope), ignored -> new Object());
    }

    private McpToolInvokeResult withExecutionState(McpToolInvokeResult result, McpServiceConfig config, String toolName,
                                                   int requestTimeoutMs, int retryCount, Map<String, Object> arguments) {
        if (result == null) {
            return McpToolInvokeResult.failure(
                "MCP tool call returned no result",
                ERROR_MCP_TOOL,
                false,
                ACTION_STOP,
                executionState(STATE_FAILED, config, toolName, requestTimeoutMs, retryCount, ERROR_MCP_TOOL,
                    ACTION_STOP, arguments)
            );
        }
        String state = result.success()
            ? STATE_SUCCEEDED
            : ACTION_STOP.equals(result.action()) ? STATE_STOPPED : STATE_FAILED;
        return result.withExecutionState(executionState(
            state,
            config,
            toolName,
            requestTimeoutMs,
            retryCount,
            result.errorCode(),
            result.action(),
            arguments
        ));
    }

    private Map<String, Object> executionState(String state, McpServiceConfig config, String toolName,
                                               int requestTimeoutMs, int retryCount, String errorCode, String action,
                                               Map<String, Object> arguments) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("state", firstText(state, STATE_RUNNING));
        values.put("step", toolName);
        values.put("toolName", toolName);
        values.put("serviceId", config == null ? null : config.getId());
        values.put("serviceName", config == null ? null : config.getName());
        values.put("timeoutMs", Math.max(0, requestTimeoutMs));
        values.put("retryCount", Math.max(0, retryCount));
        if (retryCount > 0) {
            values.put("previousState", STATE_REBUILDING);
        }
        values.put("requestId", requestIdFrom(arguments));
        values.put("tenantId", tenantIdFrom(arguments));
        values.put("errorCode", errorCode);
        values.put("action", action);
        return values;
    }

    private String requestIdFrom(Map<String, Object> arguments) {
        if (arguments == null || arguments.isEmpty()) {
            return null;
        }
        Map<String, Object> mcpContext = asMap(arguments.get("mcpContext"));
        return firstText(
            stringValue(arguments.get("requestId")),
            stringValue(arguments.get("request_id")),
            stringValue(arguments.get("traceId")),
            stringValue(arguments.get("trace_id")),
            stringValue(mcpContext.get("requestId")),
            stringValue(mcpContext.get("request_id")),
            stringValue(mcpContext.get("traceId")),
            stringValue(mcpContext.get("trace_id"))
        );
    }

    private String validateTenantContext(Map<String, Object> arguments) {
        String tenantId = tenantIdFrom(arguments);
        if (tenantId == null || tenantId.isBlank()) {
            return "MCP request missing tenantId";
        }
        return null;
    }

    private McpToolInvokeResult failureResult(Throwable throwable) {
        return failureResult(throwable == null ? null : throwable.getMessage());
    }

    private McpToolInvokeResult failureResult(String message) {
        String normalized = message == null ? "" : message.toLowerCase(Locale.ROOT);
        if (normalized.contains("tenant_required") || normalized.contains("tenantid")
            || normalized.contains("tenant id")) {
            return McpToolInvokeResult.failure(
                firstText(message, "MCP request missing tenantId"),
                ERROR_TENANT_MISSING,
                false,
                ACTION_STOP
            );
        }
        if (normalized.contains("session not found") || normalized.contains("not recognize session")
            || normalized.contains("unknown id") || normalized.contains("session invalid")
            || normalized.contains("mcptransportsessionnotfoundexception")) {
            return McpToolInvokeResult.failure(
                firstText(message, "MCP session invalid"),
                ERROR_SESSION_INVALID,
                true,
                ACTION_REBUILD_SESSION
            );
        }
        if (normalized.contains("tool_circuit_open") || normalized.contains("circuit is open")
            || normalized.contains("queue is full") || normalized.contains("rate limit exceeded")) {
            return McpToolInvokeResult.failure(
                firstText(message, "MCP tool is temporarily unavailable"),
                ERROR_TOOL_BUSY,
                false,
                ACTION_STOP
            );
        }
        if (normalized.contains("mcp http status")) {
            return McpToolInvokeResult.failure(
                firstText(message, "MCP HTTP request failed"),
                ERROR_MCP_HTTP,
                false,
                ACTION_STOP
            );
        }
        return McpToolInvokeResult.failure(
            firstText(message, "MCP tool call failed"),
            ERROR_MCP_TOOL,
            false,
            ACTION_STOP
        );
    }

    private String tenantIdFrom(Map<String, Object> arguments) {
        if (arguments == null || arguments.isEmpty()) {
            return null;
        }
        String direct = firstText(
            stringValue(arguments.get("tenantId")),
            stringValue(arguments.get("tenant_id")),
            stringValue(arguments.get("tenant"))
        );
        if (direct != null) {
            return direct;
        }
        Map<String, Object> mcpContext = asMap(arguments.get("mcpContext"));
        String contextTenant = firstText(
            stringValue(mcpContext.get("tenantId")),
            stringValue(mcpContext.get("tenant_id")),
            stringValue(mcpContext.get("tenant"))
        );
        if (contextTenant != null) {
            return contextTenant;
        }
        Map<String, Object> tenant = asMap(mcpContext.get("tenant"));
        return firstText(
            stringValue(tenant.get("tenantId")),
            stringValue(tenant.get("tenant_id")),
            stringValue(tenant.get("id"))
        );
    }

    private McpToolInvokeResult invokeSdkTool(McpServiceConfig config, TransportKind kind, int requestTimeoutMs,
                                              String toolName, Map<String, Object> arguments) {
        McpSyncClient client = getOrCreateSdkClient(config, kind, requestTimeoutMs, toolName);
        Object raw = client.callTool(new McpSchema.CallToolRequest(toolName,
            arguments == null ? Map.of() : arguments));
        Map<String, Object> mapped = objectMapper.convertValue(raw, new TypeReference<>() {});
        return normalizeInvokeResult(mapped);
    }

    private List<McpToolDefinition> discoverToolsViaDirectStreamableHttp(McpServiceConfig config, Exception sdkFailure) {
        try {
            DirectMcpSession session = openDirectStreamableHttpSession(config, 0);
            try {
                Object raw = directJsonRpcRequest(session, "tools/list", Map.of(), 0);
                return normalizeTools(raw);
            } finally {
                closeDirectStreamableHttpSession(session);
            }
        } catch (Exception ex) {
            log.warn("Direct MCP streamable HTTP tool discovery fallback failed serviceId={} service={} sdkError={} error={}",
                config.getId(),
                config.getName(),
                sdkFailure == null ? "" : sdkFailure.getMessage(),
                ex.getMessage(),
                ex);
            return List.of();
        }
    }

    private McpToolInvokeResult invokeToolViaDirectStreamableHttp(McpServiceConfig config, int requestTimeoutMs,
                                                                  String toolName, Map<String, Object> arguments,
                                                                  Exception sdkFailure) {
        try {
            DirectMcpSession session = openDirectStreamableHttpSession(config, requestTimeoutMs);
            try {
                Map<String, Object> params = new LinkedHashMap<>();
                params.put("name", toolName);
                params.put("arguments", arguments == null ? Map.of() : arguments);
                Object raw = directJsonRpcRequest(session, "tools/call", params, requestTimeoutMs);
                McpToolInvokeResult result = normalizeInvokeResult(raw);
                result = withExecutionState(
                    result,
                    config,
                    toolName,
                    requestTimeoutMs,
                    sdkFailure == null ? 0 : 1,
                    arguments
                );
                log.info("Direct MCP streamable HTTP invoke fallback completed serviceId={} service={} remoteTool={} success={} result={}",
                    config.getId(),
                    config.getName(),
                    toolName,
                    result.success(),
                    ToolLogSummarizer.summarize(result.data()));
                return result;
            } finally {
                closeDirectStreamableHttpSession(session);
            }
        } catch (Exception ex) {
            log.warn("Direct MCP streamable HTTP invoke fallback failed serviceId={} service={} remoteTool={} sdkError={} error={}",
                config.getId(),
                config.getName(),
                toolName,
                sdkFailure == null ? "" : sdkFailure.getMessage(),
                ex.getMessage(),
                ex);
            String message = ex.getMessage() == null ? (sdkFailure == null ? null : sdkFailure.getMessage()) : ex.getMessage();
            return withExecutionState(
                failureResult(message),
                config,
                toolName,
                requestTimeoutMs,
                sdkFailure == null ? 0 : 1,
                arguments
            );
        }
    }

    private DirectMcpSession openDirectStreamableHttpSession(McpServiceConfig config, int requestTimeoutMs) throws Exception {
        EndpointParts endpoint = endpointParts(resolveStreamableEndpoint(config), DEFAULT_STREAMABLE_HTTP_PATH);
        java.net.http.HttpClient client = buildHttpClientBuilder(config)
            .connectTimeout(Duration.ofMillis(positiveOrDefault(config.getTimeoutMs(), 20_000)))
            .build();
        URI uri = URI.create(endpoint.baseUrl() + endpoint.endpointPath());

        Map<String, Object> initializeParams = new LinkedHashMap<>();
        initializeParams.put("protocolVersion", ProtocolVersions.MCP_2025_11_25);
        initializeParams.put("capabilities", Map.of());
        initializeParams.put("clientInfo", Map.of(
            "name", "chatchat-mcp-client-direct",
            "version", "1.0.0"
        ));

        String requestId = nextDirectRequestId("initialize");
        HttpResponse<String> response = sendDirectJsonRpc(
            client,
            config,
            uri,
            null,
            ProtocolVersions.MCP_2025_11_25,
            jsonRpcRequest(requestId, McpSchema.METHOD_INITIALIZE, initializeParams),
            requestTimeoutMs
        );
        Object payload = directResponsePayload(response, requestId);
        Map<String, Object> initResult = asMap(payload);
        String negotiatedProtocol = firstText(stringValue(initResult.get("protocolVersion")), ProtocolVersions.MCP_2025_11_25);
        String sessionId = response.headers().firstValue(io.modelcontextprotocol.spec.HttpHeaders.MCP_SESSION_ID)
            .orElse(null);
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalStateException("MCP initialize response did not include mcp-session-id");
        }

        DirectMcpSession session = new DirectMcpSession(client, config, uri, sessionId, negotiatedProtocol);
        sendDirectNotification(session, McpSchema.METHOD_NOTIFICATION_INITIALIZED, null, requestTimeoutMs);
        return session;
    }

    private Object directJsonRpcRequest(DirectMcpSession session, String method, Object params, int requestTimeoutMs)
        throws Exception {
        String requestId = nextDirectRequestId(method);
        HttpResponse<String> response = sendDirectJsonRpc(
            session.client(),
            session.config(),
            session.uri(),
            session.sessionId(),
            session.protocolVersion(),
            jsonRpcRequest(requestId, method, params),
            requestTimeoutMs
        );
        return directResponsePayload(response, requestId);
    }

    private void sendDirectNotification(DirectMcpSession session, String method, Object params, int requestTimeoutMs)
        throws Exception {
        sendDirectJsonRpc(
            session.client(),
            session.config(),
            session.uri(),
            session.sessionId(),
            session.protocolVersion(),
            jsonRpcNotification(method, params),
            requestTimeoutMs
        );
    }

    private HttpResponse<String> sendDirectJsonRpc(java.net.http.HttpClient client, McpServiceConfig config, URI uri,
                                                   String sessionId, String protocolVersion, String body,
                                                   int requestTimeoutMs) throws Exception {
        HttpRequest.Builder builder = buildHttpRequestBuilder(config)
            .copy()
            .uri(uri)
            .header(io.modelcontextprotocol.spec.HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE + ", text/event-stream")
            .header(io.modelcontextprotocol.spec.HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .header(io.modelcontextprotocol.spec.HttpHeaders.CACHE_CONTROL, "no-cache")
            .header(io.modelcontextprotocol.spec.HttpHeaders.PROTOCOL_VERSION, protocolVersion)
            .POST(HttpRequest.BodyPublishers.ofString(body));
        if (sessionId != null && !sessionId.isBlank()) {
            builder.header(io.modelcontextprotocol.spec.HttpHeaders.MCP_SESSION_ID, sessionId);
        }
        applyDirectContextHeaders(builder, body);
        if (requestTimeoutMs > 0) {
            builder.timeout(Duration.ofMillis(requestTimeoutMs));
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private void applyDirectContextHeaders(HttpRequest.Builder builder, String body) {
        try {
            Map<String, Object> envelope = objectMapper.readValue(body, new TypeReference<>() {});
            Map<String, Object> params = asMap(envelope.get("params"));
            Map<String, Object> arguments = asMap(params.get("arguments"));
            Map<String, Object> mcpContext = asMap(arguments.get("mcpContext"));

            String tenantId = firstText(
                stringValue(arguments.get("tenantId")),
                stringValue(arguments.get("tenant_id")),
                stringValue(mcpContext.get("tenantId")),
                stringValue(mcpContext.get("tenant_id")),
                stringValue(mcpContext.get("tenant"))
            );
            String userId = firstText(
                stringValue(arguments.get("userId")),
                stringValue(arguments.get("user_id")),
                stringValue(mcpContext.get("userId")),
                stringValue(mcpContext.get("user_id"))
            );
            String requestId = firstText(
                stringValue(arguments.get("requestId")),
                stringValue(arguments.get("request_id")),
                stringValue(mcpContext.get("requestId")),
                stringValue(mcpContext.get("request_id"))
            );

            setHeaderIfPresent(builder, "X-Tenant-Id", tenantId);
            setHeaderIfPresent(builder, "X-User-Id", userId);
            setHeaderIfPresent(builder, "X-Request-Id", requestId);
            setHeaderIfPresent(builder, "X-Correlation-Id", requestId);
        } catch (Exception ex) {
            log.debug("Failed to attach direct MCP context headers: {}", ex.getMessage());
        }
    }

    private void setHeaderIfPresent(HttpRequest.Builder builder, String name, String value) {
        if (value != null && !value.isBlank()) {
            builder.setHeader(name, value.trim());
        }
    }

    private Object directResponsePayload(HttpResponse<String> response, String expectedRequestId) throws Exception {
        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            throw new IllegalStateException("MCP HTTP status " + status + ": " + response.body());
        }
        if (status == 202 || response.body() == null || response.body().isBlank()) {
            return Map.of();
        }
        String contentType = response.headers()
            .firstValue(io.modelcontextprotocol.spec.HttpHeaders.CONTENT_TYPE)
            .orElse("")
            .toLowerCase(Locale.ROOT);
        String json = contentType.contains("text/event-stream")
            ? firstJsonRpcSseData(response.body(), expectedRequestId)
            : response.body();
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        Map<String, Object> envelope = objectMapper.readValue(json, new TypeReference<>() {});
        Object error = envelope.get("error");
        if (error != null) {
            throw new IllegalStateException(String.valueOf(error));
        }
        return envelope.getOrDefault("result", envelope);
    }

    private String firstJsonRpcSseData(String body, String expectedRequestId) throws Exception {
        List<String> dataLines = new ArrayList<>();
        for (String rawLine : body.split("\\R")) {
            String line = rawLine == null ? "" : rawLine;
            if (line.isBlank()) {
                String data = String.join("\n", dataLines).trim();
                dataLines.clear();
                if (isExpectedJsonRpcData(data, expectedRequestId)) {
                    return data;
                }
                continue;
            }
            if (line.startsWith("data:")) {
                dataLines.add(line.substring("data:".length()).trim());
            }
        }
        String data = String.join("\n", dataLines).trim();
        if (isExpectedJsonRpcData(data, expectedRequestId)) {
            return data;
        }
        return null;
    }

    private boolean isExpectedJsonRpcData(String data, String expectedRequestId) throws Exception {
        if (data == null || data.isBlank()) {
            return false;
        }
        Map<String, Object> envelope = objectMapper.readValue(data, new TypeReference<>() {});
        Object id = envelope.get("id");
        return expectedRequestId == null || expectedRequestId.equals(String.valueOf(id));
    }

    private void closeDirectStreamableHttpSession(DirectMcpSession session) {
        if (session == null || session.sessionId() == null || session.sessionId().isBlank()) {
            return;
        }
        try {
            HttpRequest.Builder builder = buildHttpRequestBuilder(session.config())
                .copy()
                .uri(session.uri())
                .header(io.modelcontextprotocol.spec.HttpHeaders.MCP_SESSION_ID, session.sessionId())
                .header(io.modelcontextprotocol.spec.HttpHeaders.PROTOCOL_VERSION, session.protocolVersion())
                .DELETE();
            session.client().send(builder.build(), HttpResponse.BodyHandlers.discarding());
        } catch (Exception ex) {
            log.debug("Failed to close direct MCP streamable HTTP session {}: {}", session.sessionId(), ex.getMessage());
        }
    }

    private String jsonRpcRequest(String id, String method, Object params) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("jsonrpc", JSON_RPC_VERSION);
        payload.put("id", id);
        payload.put("method", method);
        payload.put("params", params == null ? Map.of() : params);
        return objectMapper.writeValueAsString(payload);
    }

    private String jsonRpcNotification(String method, Object params) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("jsonrpc", JSON_RPC_VERSION);
        payload.put("method", method);
        if (params != null) {
            payload.put("params", params);
        }
        return objectMapper.writeValueAsString(payload);
    }

    private String nextDirectRequestId(String method) {
        String prefix = method == null ? "mcp" : method.replace('/', '-');
        return prefix + "-" + UUID.randomUUID();
    }

    /**
     * Returns the or create sdk client.
     *
     * @param config the config value
     * @param kind the kind value
     * @return the or create sdk client
     */
    private McpSyncClient getOrCreateSdkClient(McpServiceConfig config, TransportKind kind) {
        return getOrCreateSdkClient(config, kind, 0, TOOL_DISCOVERY_SCOPE);
    }

    /**
     * Returns the or create sdk client.
     *
     * @param config the config value
     * @param kind the kind value
     * @param requestTimeoutMs the request timeout ms value
     * @return the or create sdk client
     */
    private McpSyncClient getOrCreateSdkClient(McpServiceConfig config, TransportKind kind, int requestTimeoutMs,
                                               String scope) {
        int normalizedRequestTimeoutMs = Math.max(0, requestTimeoutMs);
        String key = sdkClientKey(config, normalizedRequestTimeoutMs, scope);
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
            var clientBuilder = McpClient.sync(transport)
                .requestTimeout(sdkRequestTimeout(normalizedRequestTimeoutMs))
                .clientInfo(new McpSchema.Implementation("chatchat-mcp-client", "1.0.0"));
            McpSyncClient client = clientBuilder.build();
            client.initialize();

            sdkClientCache.put(key, new ManagedSdkClient(fingerprint, client));
            return client;
        }
    }

    private void invalidateSdkClient(McpServiceConfig config, TransportKind kind, int requestTimeoutMs, String scope,
                                     Throwable cause) {
        int normalizedRequestTimeoutMs = Math.max(0, requestTimeoutMs);
        String key = sdkClientKey(config, normalizedRequestTimeoutMs, scope);
        String fingerprint = transportFingerprint(config, kind, requestTimeoutMs);
        synchronized (sdkClientCache) {
            ManagedSdkClient current = sdkClientCache.get(key);
            if (current == null || !current.fingerprint().equals(fingerprint)) {
                return;
            }
            sdkClientCache.remove(key);
            closeQuietly(current.client(), key);
            log.info("MCP SDK client session invalidated serviceId={} service={} key={} reason={}",
                config.getId(),
                config.getName(),
                key,
                cause == null ? "" : cause.getMessage());
        }
    }

    private String sdkClientKey(McpServiceConfig config, int requestTimeoutMs, String scope) {
        return serviceKey(config) + ":" + sdkScope(scope) + ":" + Math.max(0, requestTimeoutMs);
    }

    private String sdkScope(String scope) {
        String normalized = normalizeText(scope);
        if (normalized == null) {
            return "_default";
        }
        return normalized.replaceAll("[^A-Za-z0-9_.-]", "_");
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
        Duration connectTimeout = Duration.ofMillis(positiveOrDefault(config.getTimeoutMs(), 20_000));

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
            .resumableStreams(false)
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
            String.valueOf(Math.max(0, requestTimeoutMs)),
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
            return withExecutionState(normalizeInvokeResult(result), config, toolName, 0, 0, arguments);
        } catch (Exception ex) {
            log.warn("Failed to invoke MCP tool {} via stdio proxy for {}: {}", toolName, config.getName(),
                ex.getMessage());
            return withExecutionState(failureResult(ex), config, toolName, 0, 0, arguments);
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
            var response = webClient.get()
                .uri(url)
                .headers(headers -> applyHeaders(headers, config))
                .retrieve()
                .bodyToMono(Object.class);
            Object raw = blockWithOptionalTimeout(response, config.getTimeoutMs());
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
            int requestTimeoutMs = effectiveToolTimeoutMs(null);
            log.info("MCP legacy HTTP invoke started serviceId={} service={} remoteTool={} url={} timeoutMs={} args={}",
                config.getId(),
                config.getName(),
                toolName,
                url,
                requestTimeoutMs <= 0 ? "unbounded" : requestTimeoutMs,
                ToolLogSummarizer.summarize(arguments));
            long startedAt = System.currentTimeMillis();
            var response = webClient.post()
                .uri(url)
                .headers(headers -> applyHeaders(headers, config))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(payload)
                .retrieve()
                .bodyToMono(Object.class);
            Object raw = blockWithOptionalTimeout(response, requestTimeoutMs);
            McpToolInvokeResult result = normalizeInvokeResult(raw);
            log.info("MCP legacy HTTP invoke completed serviceId={} service={} remoteTool={} success={} durationMs={} result={}",
                config.getId(),
                config.getName(),
                toolName,
                result.success(),
                Math.max(0L, System.currentTimeMillis() - startedAt),
                ToolLogSummarizer.summarize(result.data()));
            return withExecutionState(result, config, toolName, requestTimeoutMs, 0, arguments);
        } catch (Exception ex) {
            log.warn("Failed to invoke MCP tool {} on {}: {}", toolName, url, ex.getMessage());
            return withExecutionState(failureResult(ex), config, toolName, effectiveToolTimeoutMs(null), 0, arguments);
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
            String msg = firstText(stringValue(map.get("message")), stringValue(map.get("error")));
            if (msg == null) {
                msg = "MCP HTTP status " + code;
            }
            McpToolInvokeResult classified = failureResult(msg);
            String errorCode = ERROR_MCP_HTTP.equals(classified.errorCode())
                ? ERROR_MCP_HTTP + "_" + code
                : classified.errorCode();
            return McpToolInvokeResult.failure(msg, errorCode, classified.retryable(), classified.action());
        }

        Object isError = map.get("isError");
        if (Boolean.TRUE.equals(isError)) {
            String message = stringValue(map.get("message"));
            if (message == null || message.isBlank()) {
                message = stringValue(map.get("content"));
            }
            McpToolInvokeResult classified = failureResult(message == null ? "MCP tool error" : message);
            return new McpToolInvokeResult(
                false,
                map,
                message,
                message == null ? "MCP tool error" : message,
                classified.errorCode(),
                classified.retryable(),
                classified.action(),
                Map.of()
            );
        }

        if (map.containsKey("error")) {
            String message = firstText(stringValue(map.get("message")), stringValue(map.get("error")));
            return failureResult(message);
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

    private String firstText(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
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
     * Performs the effective tool timeout ms operation.
     *
     * @param timeoutOverrideMs the timeout override ms value
     * @return the operation result
     */
    private int effectiveToolTimeoutMs(Long timeoutOverrideMs) {
        if (timeoutOverrideMs == null || timeoutOverrideMs <= 0) {
            return 0;
        }
        return (int) Math.min(Integer.MAX_VALUE, timeoutOverrideMs);
    }

    private Object blockWithOptionalTimeout(reactor.core.publisher.Mono<Object> response, int timeoutMs) {
        if (timeoutMs <= 0) {
            return response.block();
        }
        return response.timeout(Duration.ofMillis(timeoutMs)).block();
    }

    private int positiveOrDefault(int value, int fallback) {
        return value > 0 ? value : fallback;
    }

    private Duration sdkRequestTimeout(int timeoutMs) {
        if (timeoutMs <= 0) {
            return UNBOUNDED_MCP_REQUEST_TIMEOUT;
        }
        return Duration.ofMillis(timeoutMs);
    }

    private boolean isRecoverableMcpSessionFailure(Throwable throwable) {
        Throwable current = throwable;
        int depth = 0;
        while (current != null && depth < 12) {
            String className = current.getClass().getSimpleName();
            String message = current.getMessage() == null ? "" : current.getMessage().toLowerCase(Locale.ROOT);
            if ("McpTransportSessionNotFoundException".equals(className)
                || message.contains("session not found")
                || message.contains("transport session not found")
                || message.contains("mcp session with server terminated")
                || message.contains("session with server terminated")) {
                return true;
            }
            current = current.getCause();
            depth++;
        }
        return false;
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

    private record DirectMcpSession(java.net.http.HttpClient client,
                                    McpServiceConfig config,
                                    URI uri,
                                    String sessionId,
                                    String protocolVersion) {
    }
}
