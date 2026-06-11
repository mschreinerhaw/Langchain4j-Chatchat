package com.chatchat.mcpserver.audit;

import com.chatchat.mcpserver.api.ApiInvokeResult;
import com.chatchat.mcpserver.api.ApiServiceConfig;
import com.chatchat.mcpserver.cache.McpRocksDbStore;
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

    private final McpRocksDbStore rocksDbStore;
    private final ObjectMapper objectMapper;

    public List<InvocationAuditLog> listRecent() {
        List<InvocationAuditLog> logs = new ArrayList<>();
        for (McpRocksDbStore.KeyValue entry : rocksDbStore.scan(INDEX_KEY_PREFIX, 100)) {
            String id = new String(entry.value(), StandardCharsets.UTF_8);
            findById(id).ifPresent(logs::add);
        }
        return logs;
    }

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

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private String textValue(JsonNode node) {
        if (node == null || node.isNull() || !node.isValueNode()) {
            return null;
        }
        return node.asText();
    }

    private void save(InvocationAuditLog auditLog) {
        try {
            rocksDbStore.put(DATA_KEY_PREFIX + auditLog.getId(), objectMapper.writeValueAsBytes(auditLog));
            rocksDbStore.put(indexKey(auditLog), auditLog.getId().getBytes(StandardCharsets.UTF_8));
        } catch (Exception ex) {
            log.warn("Failed to write MCP invocation audit log {}: {}", auditLog.getId(), ex.getMessage());
        }
    }

    private String indexKey(InvocationAuditLog log) {
        long createdAt = log.getCreatedAt() == null ? System.currentTimeMillis() : log.getCreatedAt().toEpochMilli();
        long reverseTime = Long.MAX_VALUE - createdAt;
        return INDEX_KEY_PREFIX + String.format("%019d", reverseTime) + ":" + log.getId();
    }

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

    private boolean isSensitiveKey(String key) {
        String normalized = key == null ? "" : key.toLowerCase(Locale.ROOT);
        return normalized.contains("token")
            || normalized.contains("password")
            || normalized.contains("secret")
            || normalized.contains("authorization")
            || normalized.contains("apikey")
            || normalized.contains("api_key");
    }

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

    private String toJsonSummary(Object value) {
        try {
            return limit(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(value));
        } catch (Exception ex) {
            return limit(String.valueOf(value));
        }
    }

    private String limit(String value) {
        if (value == null) {
            return null;
        }
        if (value.length() <= MAX_DETAIL_LENGTH) {
            return value;
        }
        return value.substring(0, MAX_DETAIL_LENGTH) + "...";
    }

}
