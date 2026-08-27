package com.chatchat.integration.mcp.service.routing;

import com.chatchat.common.mcp.service.McpResultRepairRequest;
import com.chatchat.common.mcp.service.McpResultRepairResult;
import com.chatchat.common.mcp.service.McpResultRepairer;
import com.chatchat.common.mcp.service.McpServiceResultStatus;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Lossless, deterministic fallback normalizer for JSON, MCP content blocks and plain stdout. */
@Component
public class StandardMcpResultRepairer implements McpResultRepairer {
    private static final List<String> WRAPPERS = List.of("structuredContent", "structured_content", "data", "result", "payload");
    private final ObjectMapper objectMapper;

    public StandardMcpResultRepairer(ObjectMapper objectMapper) { this.objectMapper = objectMapper; }
    @Override public String repairerId() { return "standard-lossless-normalizer"; }
    @Override public int priority() { return Integer.MIN_VALUE; }
    @Override public boolean supports(McpResultRepairRequest request) { return true; }

    @Override
    public McpResultRepairResult repair(McpResultRepairRequest request) {
        Map<String, Object> diagnostics = new LinkedHashMap<>();
        diagnostics.put("repairerId", repairerId());
        diagnostics.put("originalParseError", request.parseError());
        Object normalized = normalize(request.rawResult(), diagnostics);
        if (normalized == null) return result(request, McpServiceResultStatus.FAILED, null, diagnostics,
            "Raw MCP result is null");
        List<String> missing = missingRequired(normalized, request.expectedOutputSchema());
        diagnostics.put("missingRequired", missing);
        diagnostics.put("rawPreserved", true);
        return result(request, missing.isEmpty() ? McpServiceResultStatus.REPAIRED : McpServiceResultStatus.PARTIAL,
            normalized, diagnostics, missing.isEmpty() ? "MCP result normalized" : "MCP result normalized with schema gaps");
    }

    private Object normalize(Object raw, Map<String, Object> diagnostics) {
        if (raw == null) return null;
        if (raw instanceof String text) {
            try {
                Object parsed = objectMapper.readValue(text, Object.class);
                diagnostics.put("strategy", "JSON_TEXT");
                return unwrap(parsed, diagnostics);
            } catch (JsonProcessingException ignored) {
                diagnostics.put("strategy", "RAW_TEXT_ENVELOPE");
                return Map.of("contentType", "text/plain", "text", text);
            }
        }
        diagnostics.put("strategy", "STRUCTURED_VALUE");
        return unwrap(raw, diagnostics);
    }

    private Object unwrap(Object value, Map<String, Object> diagnostics) {
        if (!(value instanceof Map<?, ?> map)) return value;
        for (String wrapper : WRAPPERS) {
            Object nested = map.get(wrapper);
            if (nested != null) {
                diagnostics.put("unwrappedField", wrapper);
                return normalizeNested(nested, diagnostics);
            }
        }
        Object content = map.get("content");
        if (content instanceof List<?> blocks) {
            List<String> texts = blocks.stream().filter(Map.class::isInstance).map(Map.class::cast)
                .map(block -> block.get("text")).filter(String.class::isInstance).map(String.class::cast).toList();
            if (!texts.isEmpty()) {
                diagnostics.put("unwrappedField", "content[].text");
                String joined = String.join("\n", texts);
                Object parsed = normalizeNested(joined, diagnostics);
                return parsed instanceof Map<?, ?> parsedMap && parsedMap.containsKey("text")
                    ? Map.of("text", joined, "content", blocks) : parsed;
            }
        }
        return value;
    }

    private Object normalizeNested(Object value, Map<String, Object> diagnostics) {
        if (!(value instanceof String text)) return value;
        try { return objectMapper.readValue(text, Object.class); }
        catch (JsonProcessingException ignored) { return Map.of("contentType", "text/plain", "text", text); }
    }

    private List<String> missingRequired(Object normalized, Map<String, Object> schema) {
        Object requiredValue = schema.get("required");
        if (!(requiredValue instanceof Collection<?> required) || !(normalized instanceof Map<?, ?> map)) return List.of();
        List<String> missing = new ArrayList<>();
        required.stream().map(String::valueOf).filter(field -> !map.containsKey(field)).forEach(missing::add);
        return List.copyOf(missing);
    }

    private McpResultRepairResult result(McpResultRepairRequest request, McpServiceResultStatus status,
                                         Object normalized, Map<String, Object> diagnostics, String message) {
        return new McpResultRepairResult(null, request.requestId(), request.serviceId(), request.toolName(), status,
            normalized, request.rawResult(), diagnostics, message);
    }
}
