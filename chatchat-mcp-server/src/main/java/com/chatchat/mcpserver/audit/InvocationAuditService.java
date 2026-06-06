package com.chatchat.mcpserver.audit;

import com.chatchat.mcpserver.api.ApiInvokeResult;
import com.chatchat.mcpserver.api.ApiServiceConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class InvocationAuditService {

    private static final int MAX_SUMMARY_LENGTH = 4000;

    private final InvocationAuditLogRepository repository;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public List<InvocationAuditLog> listRecent() {
        return repository.findTop100ByOrderByCreatedAtDesc();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordApiCall(ApiServiceConfig config, Map<String, Object> arguments,
                              ApiInvokeResult result, long durationMs) {
        InvocationAuditLog log = new InvocationAuditLog();
        log.setTargetType("API_SERVICE");
        log.setTargetId(config.getId());
        log.setTargetName(config.getToolName());
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
        repository.save(log);
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

    private String toJsonSummary(Object value) {
        try {
            return limit(objectMapper.writeValueAsString(value));
        } catch (Exception ex) {
            return limit(String.valueOf(value));
        }
    }

    private String limit(String value) {
        if (value == null) {
            return null;
        }
        if (value.length() <= MAX_SUMMARY_LENGTH) {
            return value;
        }
        return value.substring(0, MAX_SUMMARY_LENGTH) + "...";
    }
}
