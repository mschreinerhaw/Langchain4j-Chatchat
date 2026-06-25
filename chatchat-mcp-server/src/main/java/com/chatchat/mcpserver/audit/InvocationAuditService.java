package com.chatchat.mcpserver.audit;

import com.chatchat.agents.protocol.ModelProtocolJson;

import com.chatchat.mcpserver.api.ApiInvokeResult;
import com.chatchat.mcpserver.api.ApiServiceConfig;
import com.chatchat.mcpserver.cache.McpRocksDbStore;
import com.chatchat.mcpserver.notification.NotificationChannelConfig;
import com.chatchat.mcpserver.notification.NotificationSendResult;
import com.chatchat.mcpserver.ops.HttpRequestToolResult;
import com.chatchat.mcpserver.ops.LinuxCommandResult;
import com.chatchat.mcpserver.ops.SshHostConfig;
import com.chatchat.mcpserver.sql.SqlDatasourceConfig;
import com.chatchat.mcpserver.sql.SqlQueryResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class InvocationAuditService {

    private static final String DATA_KEY_PREFIX = "audit:data:";
    private static final String INDEX_KEY_PREFIX = "audit:index:";
    private static final int MAX_DETAIL_LENGTH = 200_000;
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 200;

    private final McpRocksDbStore rocksDbStore;
    private final ObjectMapper objectMapper;

    /**
     * Lists the recent.
     *
     * @return the recent list
     */
    public List<InvocationAuditLog> listRecent() {
        return search(AuditLogSearchQuery.recent()).items();
    }

    /**
     * Searches the search.
     *
     * @param query the query value
     * @return the operation result
     */
    public AuditLogPage search(AuditLogSearchQuery query) {
        if (!rocksDbStore.isUsable()) {
            return AuditLogPage.empty(normalizePage(query == null ? null : query.page()),
                normalizePageSize(query == null ? null : query.pageSize()));
        }

        AuditLogSearchQuery normalized = normalize(query);
        int offset = (normalized.page() - 1) * normalized.pageSize();
        List<InvocationAuditLog> items = new ArrayList<>();
        Counter totalCount = new Counter();
        Counter filteredCount = new Counter();

        rocksDbStore.scan(INDEX_KEY_PREFIX, Integer.MAX_VALUE, entry -> {
            String id = new String(entry.value(), StandardCharsets.UTF_8);
            Optional<InvocationAuditLog> optionalLog = findById(id);
            if (optionalLog.isEmpty()) {
                return;
            }
            totalCount.increment();
            InvocationAuditLog log = optionalLog.get();
            if (!matches(log, normalized)) {
                return;
            }
            long matchedIndex = filteredCount.value();
            filteredCount.increment();
            if (matchedIndex >= offset && items.size() < normalized.pageSize()) {
                items.add(log);
            }
        });

        return new AuditLogPage(items, normalized.page(), normalized.pageSize(), totalCount.value(), filteredCount.value());
    }

    /**
     * Finds the by id.
     *
     * @param id the id value
     * @return the matching by id
     */
    public Optional<InvocationAuditLog> findById(String id) {
        if (id == null || id.isBlank() || !rocksDbStore.isUsable()) {
            return Optional.empty();
        }
        try {
            byte[] raw = rocksDbStore.get(DATA_KEY_PREFIX + id.trim());
            if (raw == null) {
                return Optional.empty();
            }
            return Optional.of(objectMapper.readValue(raw, InvocationAuditLog.class));
        } catch (Exception ex) {
            log.warn("Failed to read MCP invocation audit log {}: {}", id, ex.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Performs the record api call operation.
     *
     * @param config the config value
     * @param arguments the arguments value
     * @param result the result value
     * @param durationMs the duration ms value
     */
    public void recordApiCall(ApiServiceConfig config, Map<String, Object> arguments,
                              ApiInvokeResult result, long durationMs) {
        if (!rocksDbStore.isUsable()) {
            return;
        }
        InvocationAuditLog log = new InvocationAuditLog();
        log.setId(UUID.randomUUID().toString());
        log.setTargetType("API_SERVICE");
        log.setTargetId(config.getId());
        log.setTargetName(config.getToolName());
        log.setToolName(config.getToolName());
        log.setCaller("mcp-tool");
        log.setSuccess(result.success());
        log.setStatusCode(result.statusCode());
        log.setDurationMs(durationMs);
        log.setErrorMessage(limit(result.errorMessage()));
        log.setRequestSummary(toJsonSummary(redact(arguments)));
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("statusCode", result.statusCode());
        response.put("cacheHit", result.cacheHit());
        response.put("body", result.body());
        response.put("errorMessage", result.errorMessage());
        log.setResponseSummary(toJsonSummary(redact(response)));
        log.setCreatedAt(Instant.now());
        save(log);
    }

    public void recordNotificationCall(NotificationChannelConfig config, Map<String, Object> arguments,
                                       NotificationSendResult result, long durationMs) {
        if (!rocksDbStore.isUsable()) {
            return;
        }
        InvocationAuditLog log = new InvocationAuditLog();
        log.setId(UUID.randomUUID().toString());
        log.setTargetType("NOTIFICATION_TOOL");
        log.setTargetId(config.getId());
        log.setTargetName(config.getTitle());
        log.setToolName(config.getToolName());
        log.setCaller("mcp-tool");
        log.setSuccess(result.success());
        log.setStatusCode(result.statusCode());
        log.setDurationMs(durationMs);
        log.setErrorMessage(limit(result.errorMessage()));
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("channel", config.getChannel());
        request.put("deliveryMode", config.getDeliveryMode());
        request.put("receiver", arguments == null ? null : arguments.get("receiver"));
        request.put("title", arguments == null ? null : arguments.get("title"));
        request.put("level", arguments == null ? null : arguments.get("level"));
        request.put("sourceTaskId", arguments == null ? null : arguments.get("sourceTaskId"));
        request.put("content", arguments == null ? null : arguments.get("content"));
        log.setRequestSummary(toJsonSummary(redact(request)));
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("statusCode", result.statusCode());
        response.put("attempts", result.attempts());
        response.put("body", result.responseBody());
        response.put("errorMessage", result.errorMessage());
        log.setResponseSummary(toJsonSummary(redact(response)));
        log.setCreatedAt(Instant.now());
        save(log);
    }

    public void recordOpsHttpCall(Map<String, Object> arguments, HttpRequestToolResult result) {
        if (!rocksDbStore.isUsable()) {
            return;
        }
        InvocationAuditLog log = new InvocationAuditLog();
        log.setId(UUID.randomUUID().toString());
        log.setTargetType("OPS_HTTP_REQUEST");
        log.setTargetId(result.url());
        log.setTargetName(result.method() + " " + result.url());
        log.setToolName("http_request");
        log.setCaller("mcp-tool");
        log.setSuccess(result.success());
        log.setStatusCode(result.statusCode());
        log.setDurationMs(result.durationMs());
        log.setErrorMessage(limit(result.errorMessage()));
        log.setRequestSummary(toJsonSummary(redact(arguments)));
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("statusCode", result.statusCode());
        response.put("headers", result.headers());
        response.put("body", result.body());
        response.put("errorMessage", result.errorMessage());
        log.setResponseSummary(toJsonSummary(redact(response)));
        log.setCreatedAt(Instant.now());
        save(log);
    }

    public void recordLinuxCommandCall(SshHostConfig host, LinuxCommandResult result) {
        if (!rocksDbStore.isUsable()) {
            return;
        }
        InvocationAuditLog log = new InvocationAuditLog();
        log.setId(UUID.randomUUID().toString());
        log.setTargetType("LINUX_COMMAND");
        log.setTargetId(result.hostId());
        log.setTargetName((host == null ? result.host() : host.getName()) + " / " + result.template());
        log.setToolName(result.toolName() == null ? "linux_command_execute" : result.toolName());
        log.setCaller("mcp-tool");
        log.setSuccess(result.success());
        log.setStatusCode(result.exitCode());
        log.setDurationMs(result.durationMs());
        log.setErrorMessage(limit(result.errorMessage()));
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("hostId", result.hostId());
        request.put("host", result.host());
        request.put("environment", result.environment());
        request.put("template", result.template());
        request.put("command", result.command());
        request.put("commandHash", result.commandHash());
        request.put("failedStepIndex", result.failedStepIndex());
        request.put("failedCommand", result.failedCommand());
        request.put("sourceTaskId", result.request() == null ? null : result.request().get("sourceTaskId"));
        request.put("reason", result.request() == null ? null : result.request().get("reason"));
        log.setRequestSummary(toJsonSummary(redact(request)));
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("exitCode", result.exitCode());
        response.put("steps", result.steps());
        response.put("stdout", result.stdout());
        response.put("stderr", result.stderr());
        response.put("errorMessage", result.errorMessage());
        log.setResponseSummary(toJsonSummary(redact(response)));
        log.setCreatedAt(Instant.now());
        save(log);
    }

    public void recordSqlQueryCall(SqlDatasourceConfig datasource, SqlQueryResult result) {
        if (!rocksDbStore.isUsable()) {
            return;
        }
        InvocationAuditLog log = new InvocationAuditLog();
        log.setId(UUID.randomUUID().toString());
        log.setTargetType("SQL_QUERY");
        log.setTargetId(result.datasourceId());
        log.setTargetName((datasource == null ? result.datasourceName() : datasource.getName()) + " / " + result.environment());
        log.setToolName(result.toolName() == null ? "sql_query_execute" : result.toolName());
        log.setCaller("mcp-tool");
        log.setSuccess(result.success());
        log.setStatusCode(result.success() ? 200 : 0);
        log.setDurationMs(result.durationMs());
        log.setErrorMessage(limit(result.errorMessage()));
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("datasourceId", result.datasourceId());
        request.put("datasourceName", result.datasourceName());
        request.put("environment", result.environment());
        request.put("sql", result.sql());
        request.put("normalizedSql", result.normalizedSql());
        request.put("timeoutSeconds", result.timeoutSeconds());
        request.put("maxRows", result.maxRows());
        request.put("purpose", result.purpose());
        request.put("sourceTaskId", result.sourceTaskId());
        log.setRequestSummary(toJsonSummary(redact(request)));
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("columns", result.columns());
        response.put("rowCount", result.rowCount());
        response.put("possiblyTruncated", result.possiblyTruncated());
        response.put("errorMessage", result.errorMessage());
        log.setResponseSummary(toJsonSummary(redact(response)));
        log.setCreatedAt(Instant.now());
        save(log);
    }

    /**
     * Performs the record mcp transport request operation.
     *
     * @param method the method value
     * @param uri the uri value
     * @param queryString the query string value
     * @param caller the caller value
     * @param userAgent the user agent value
     * @param statusCode the status code value
     * @param durationMs the duration ms value
     * @param errorMessage the error message value
     * @param requestBody the request body value
     */
    public void recordMcpTransportRequest(String method, String uri, String queryString, String caller,
                                          String userAgent, Integer statusCode, long durationMs,
                                          String errorMessage, String requestBody) {
        if (!rocksDbStore.isUsable()) {
            return;
        }
        String toolName = extractToolName(requestBody);
        InvocationAuditLog log = new InvocationAuditLog();
        log.setId(UUID.randomUUID().toString());
        log.setTargetType("MCP_TRANSPORT");
        log.setTargetId(uri);
        log.setTargetName((method == null || method.isBlank() ? "MCP" : method.toUpperCase(Locale.ROOT)) + " " + uri);
        log.setToolName(toolName);
        log.setCaller(caller);
        log.setSuccess(errorMessage == null && statusCode != null && statusCode < 400);
        log.setStatusCode(statusCode);
        log.setDurationMs(durationMs);
        log.setErrorMessage(limit(errorMessage));
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("method", method);
        request.put("uri", uri);
        request.put("queryString", redactQueryString(queryString));
        request.put("toolName", toolName);
        request.put("remoteAddr", caller);
        request.put("userAgent", userAgent);
        log.setRequestSummary(toJsonSummary(redact(request)));
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("statusCode", statusCode);
        response.put("durationMs", durationMs);
        response.put("errorMessage", errorMessage);
        log.setResponseSummary(toJsonSummary(redact(response)));
        log.setCreatedAt(Instant.now());
        save(log);
    }

    /**
     * Performs the extract tool name operation.
     *
     * @param requestBody the request body value
     * @return the operation result
     */
    private String extractToolName(String requestBody) {
        if (requestBody == null || requestBody.isBlank()) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(requestBody);
            return extractToolName(root);
        } catch (Exception ex) {
            log.debug("Failed to parse MCP request body for tool name: {}", ex.getMessage());
            return null;
        }
    }

    /**
     * Performs the extract tool name operation.
     *
     * @param node the node value
     * @return the operation result
     */
    private String extractToolName(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        if (node.isArray()) {
            for (JsonNode item : node) {
                String toolName = extractToolName(item);
                if (toolName != null) {
                    return toolName;
                }
            }
            return null;
        }
        if (!"tools/call".equals(textValue(node.get("method")))) {
            return null;
        }
        JsonNode params = node.get("params");
        return firstNonBlank(
            textValue(params == null ? null : params.get("name")),
            textValue(params == null ? null : params.get("toolName")),
            textValue(params == null ? null : params.get("tool_name"))
        );
    }

    /**
     * Performs the first non blank operation.
     *
     * @param values the values value
     * @return the operation result
     */
    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    /**
     * Normalizes the normalize.
     *
     * @param query the query value
     * @return the operation result
     */
    private AuditLogSearchQuery normalize(AuditLogSearchQuery query) {
        if (query == null) {
            query = AuditLogSearchQuery.recent();
        }
        return new AuditLogSearchQuery(
            normalizePage(query.page()),
            normalizePageSize(query.pageSize()),
            trimToNull(query.keyword()),
            trimToNull(query.targetType()),
            trimToNull(query.targetId()),
            trimToNull(query.toolName()),
            trimToNull(query.caller()),
            query.success(),
            query.statusCode(),
            query.from(),
            query.to()
        );
    }

    /**
     * Normalizes the page.
     *
     * @param page the page value
     * @return the operation result
     */
    private int normalizePage(Integer page) {
        return page == null || page < 1 ? 1 : page;
    }

    /**
     * Normalizes the page size.
     *
     * @param pageSize the page size value
     * @return the operation result
     */
    private int normalizePageSize(Integer pageSize) {
        if (pageSize == null || pageSize < 1) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(pageSize, MAX_PAGE_SIZE);
    }

    /**
     * Returns whether matches.
     *
     * @param log the log value
     * @param query the query value
     * @return whether the condition is satisfied
     */
    private boolean matches(InvocationAuditLog log, AuditLogSearchQuery query) {
        if (query.targetType() != null && !equalsIgnoreCase(log.getTargetType(), query.targetType())) {
            return false;
        }
        if (query.targetId() != null && !containsIgnoreCase(log.getTargetId(), query.targetId())) {
            return false;
        }
        if (query.toolName() != null && !containsIgnoreCase(log.getToolName(), query.toolName())) {
            return false;
        }
        if (query.caller() != null && !containsIgnoreCase(log.getCaller(), query.caller())) {
            return false;
        }
        if (query.success() != null && log.isSuccess() != query.success()) {
            return false;
        }
        if (query.statusCode() != null && !query.statusCode().equals(log.getStatusCode())) {
            return false;
        }
        long createdAt = log.getCreatedAt() == null ? 0 : log.getCreatedAt().toEpochMilli();
        if (query.from() != null && createdAt < query.from()) {
            return false;
        }
        if (query.to() != null && createdAt > query.to()) {
            return false;
        }
        return query.keyword() == null || matchesKeyword(log, query.keyword());
    }

    /**
     * Returns whether matches keyword.
     *
     * @param log the log value
     * @param keyword the keyword value
     * @return whether the condition is satisfied
     */
    private boolean matchesKeyword(InvocationAuditLog log, String keyword) {
        return containsIgnoreCase(log.getId(), keyword)
            || containsIgnoreCase(log.getTargetType(), keyword)
            || containsIgnoreCase(log.getTargetId(), keyword)
            || containsIgnoreCase(log.getTargetName(), keyword)
            || containsIgnoreCase(log.getToolName(), keyword)
            || containsIgnoreCase(log.getCaller(), keyword)
            || containsIgnoreCase(log.getErrorMessage(), keyword)
            || containsIgnoreCase(log.getRequestSummary(), keyword)
            || containsIgnoreCase(log.getResponseSummary(), keyword)
            || containsIgnoreCase(log.getStatusCode(), keyword);
    }

    /**
     * Returns whether contains ignore case.
     *
     * @param value the value value
     * @param keyword the keyword value
     * @return whether the condition is satisfied
     */
    private boolean containsIgnoreCase(Object value, String keyword) {
        if (value == null || keyword == null) {
            return false;
        }
        return String.valueOf(value).toLowerCase(Locale.ROOT).contains(keyword.toLowerCase(Locale.ROOT));
    }

    /**
     * Returns whether equals ignore case.
     *
     * @param value the value value
     * @param expected the expected value
     * @return whether the condition is satisfied
     */
    private boolean equalsIgnoreCase(String value, String expected) {
        return value != null && expected != null && value.equalsIgnoreCase(expected);
    }

    /**
     * Performs the trim to null operation.
     *
     * @param value the value value
     * @return the operation result
     */
    private String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    /**
     * Performs the text value operation.
     *
     * @param node the node value
     * @return the operation result
     */
    private String textValue(JsonNode node) {
        if (node == null || node.isNull() || !node.isValueNode()) {
            return null;
        }
        return node.asText();
    }

    /**
     * Saves the save.
     *
     * @param auditLog the audit log value
     */
    private void save(InvocationAuditLog auditLog) {
        try {
            rocksDbStore.put(DATA_KEY_PREFIX + auditLog.getId(), objectMapper.writeValueAsBytes(auditLog));
            rocksDbStore.put(indexKey(auditLog), auditLog.getId().getBytes(StandardCharsets.UTF_8));
        } catch (Exception ex) {
            log.warn("Failed to write MCP invocation audit log {}: {}", auditLog.getId(), ex.getMessage());
        }
    }

    /**
     * Performs the index key operation.
     *
     * @param log the log value
     * @return the operation result
     */
    private String indexKey(InvocationAuditLog log) {
        long createdAt = log.getCreatedAt() == null ? System.currentTimeMillis() : log.getCreatedAt().toEpochMilli();
        long reverseTime = Long.MAX_VALUE - createdAt;
        return INDEX_KEY_PREFIX + String.format("%019d", reverseTime) + ":" + log.getId();
    }

    /**
     * Performs the redact operation.
     *
     * @param value the value value
     * @return the operation result
     */
    private Object redact(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> redacted = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                if (isSensitiveKey(key)) {
                    redacted.put(key, "***");
                } else {
                    redacted.put(key, redact(entry.getValue()));
                }
            }
            return redacted;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(this::redact).toList();
        }
        return value;
    }

    /**
     * Returns whether is sensitive key.
     *
     * @param key the key value
     * @return whether the condition is satisfied
     */
    private boolean isSensitiveKey(String key) {
        String normalized = key == null ? "" : key.toLowerCase(Locale.ROOT);
        return normalized.contains("token")
            || normalized.contains("password")
            || normalized.contains("secret")
            || normalized.contains("authorization")
            || normalized.contains("apikey")
            || normalized.contains("api_key");
    }

    /**
     * Performs the redact query string operation.
     *
     * @param queryString the query string value
     * @return the operation result
     */
    private String redactQueryString(String queryString) {
        if (queryString == null || queryString.isBlank()) {
            return null;
        }
        String[] pairs = queryString.split("&");
        for (int i = 0; i < pairs.length; i++) {
            String pair = pairs[i];
            int separator = pair.indexOf('=');
            String key = separator >= 0 ? pair.substring(0, separator) : pair;
            if (isSensitiveKey(key)) {
                pairs[i] = key + "=***";
            }
        }
        return String.join("&", pairs);
    }

    /**
     * Converts the value to json summary.
     *
     * @param value the value value
     * @return the converted json summary
     */
    private String toJsonSummary(Object value) {
        try {
            return limit(ModelProtocolJson.pretty(value));
        } catch (Exception ex) {
            return limit(String.valueOf(value));
        }
    }

    /**
     * Performs the limit operation.
     *
     * @param value the value value
     * @return the operation result
     */
    private String limit(String value) {
        if (value == null) {
            return null;
        }
        if (value.length() <= MAX_DETAIL_LENGTH) {
            return value;
        }
        return value.substring(0, MAX_DETAIL_LENGTH) + "...";
    }

    public record AuditLogSearchQuery(
        Integer page,
        Integer pageSize,
        String keyword,
        String targetType,
        String targetId,
        String toolName,
        String caller,
        Boolean success,
        Integer statusCode,
        Long from,
        Long to
    ) {
        /**
         * Performs the recent operation.
         *
         * @return the operation result
         */
        public static AuditLogSearchQuery recent() {
            return new AuditLogSearchQuery(1, 100, null, null, null, null, null, null, null, null, null);
        }
    }

    public record AuditLogPage(
        List<InvocationAuditLog> items,
        int page,
        int pageSize,
        long totalCount,
        long filteredCount
    ) {
        /**
         * Performs the empty operation.
         *
         * @param page the page value
         * @param pageSize the page size value
         * @return the operation result
         */
        public static AuditLogPage empty(int page, int pageSize) {
            return new AuditLogPage(List.of(), page, pageSize, 0, 0);
        }
    }

    private static class Counter {
        private long value;

        /**
         * Performs the increment operation.
         */
        void increment() {
            value++;
        }

        /**
         * Performs the value operation.
         *
         * @return the operation result
         */
        long value() {
            return value;
        }
    }

}
