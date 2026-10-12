package com.chatchat.agents.runtime.plan.protocol;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Navigates values in tool protocol payloads without applying domain semantics.
 *
 * <p>The navigator understands the transport envelopes used by MCP tools and
 * JSON encoded payloads. Runtime policies can therefore consume one canonical
 * traversal contract instead of reimplementing envelope unwrapping.</p>
 */
public final class ToolProtocolPayloadNavigator {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /** A discovery bridge can return an asset-selection stage before its template stage. */
    public com.chatchat.common.tool.ToolWorkflowRole discoveryRole(
        Object output, com.chatchat.common.tool.ToolWorkflowRole declaredRole) {
        var asset = com.chatchat.common.tool.ToolWorkflowRole.ASSET_DISCOVERY;
        var template = com.chatchat.common.tool.ToolWorkflowRole.TEMPLATE_DISCOVERY;
        if (declaredRole != asset && declaredRole != template) return declaredRole;
        String stage = discoveryStage(output, 0);
        if ("asset".equals(stage) || "asset_selection".equals(stage)) return asset;
        if ("template".equals(stage) || "templates".equals(stage)) return template;
        return declaredRole;
    }

    private String discoveryStage(Object output, int depth) {
        if (output == null || depth > 8) return null;
        if (output instanceof com.chatchat.common.tool.ToolOutput receipt)
            return discoveryStage(receipt.getData(), depth + 1);
        Object normalized = normalize(output);
        if (normalized != output) return discoveryStage(normalized, depth + 1);
        if (!(output instanceof Map<?, ?> map)) return null;
        Object stage = map.get("stage");
        if (stage instanceof String value && List.of("asset", "asset_selection", "template", "templates").contains(value))
            return value;
        // Governed discovery responses also identify their stage by result schema.
        // Asset results can legitimately omit `stage`, including empty authorized sets.
        Object schema = map.get("schemaVersion");
        if (schema instanceof String value) {
            if (value.startsWith("asset_query_result.")) return "asset";
            if (value.startsWith("template_query_result.")) return "template";
        }
        for (String key : List.of("structuredContent", "structured_content", "data", "result", "payload", "body", "output")) {
            String nested = discoveryStage(map.get(key), depth + 1);
            if (nested != null) return nested;
        }
        return null;
    }

    public Object firstPresent(Map<String, Object> values, String... keys) {
        if (values == null || keys == null) {
            return null;
        }
        for (String key : keys) {
            Object value = values.get(key);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    public Object firstValue(Object output, String... paths) {
        if (paths == null) {
            return null;
        }
        for (String path : paths) {
            Object value = valueAtPath(output, path);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    public Object valueAtPath(Object output, String path) {
        return valueAtPath(output, path, 0);
    }

    public String fieldKey(String field) {
        List<String> tokens = pathTokens(field).stream()
            .filter(token -> !"data".equals(token))
            .toList();
        if (tokens.isEmpty()) {
            return "";
        }
        String last = tokens.get(tokens.size() - 1);
        if ("type".equals(last) && tokens.size() >= 2 && "asset".equals(tokens.get(tokens.size() - 2))) {
            return "asset.type";
        }
        return last.replace("_", "").replace("-", "").toLowerCase(java.util.Locale.ROOT);
    }

    public String normalizeFieldPath(String field) {
        if (field == null || field.isBlank()) {
            return "";
        }
        return field.replace("_", "")
            .replace("-", "")
            .replace("$", "")
            .replace("[", "")
            .replace("]", "")
            .replace(".", "")
            .trim()
            .toLowerCase(java.util.Locale.ROOT);
    }

    public List<String> pathTokens(String path) {
        if (path == null || path.isBlank()) {
            return List.of();
        }
        String normalized = path.trim();
        if (normalized.startsWith("$.")) {
            normalized = normalized.substring(2);
        } else if (normalized.startsWith("$")) {
            normalized = normalized.substring(1);
        }
        if (normalized.startsWith(".")) {
            normalized = normalized.substring(1);
        }
        normalized = normalized.replaceAll("\\[(\\d+)]", ".$1");
        return List.of(normalized.split("\\.")).stream()
            .map(String::trim)
            .filter(part -> !part.isBlank())
            .toList();
    }

    @SuppressWarnings("unchecked")
    public Object normalize(Object output) {
        if (output instanceof String text) {
            String trimmed = text.trim();
            if (trimmed.isEmpty()) {
                return output;
            }
            try {
                if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
                    return OBJECT_MAPPER.readValue(trimmed, new TypeReference<Map<String, Object>>() { });
                }
                if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                    return OBJECT_MAPPER.readValue(trimmed, new TypeReference<List<Object>>() { });
                }
            } catch (Exception ignored) {
                return output;
            }
        }
        if (output instanceof Map<?, ?> map && !(output instanceof LinkedHashMap<?, ?>)) {
            return new LinkedHashMap<>((Map<Object, Object>) map);
        }
        return output;
    }

    private Object valueAtPath(Object output, String path, int depth) {
        if (output == null || path == null || path.isBlank()) {
            return output;
        }
        if (depth > 6) {
            return null;
        }
        Object normalized = normalize(output);
        if (normalized != output) {
            return valueAtPath(normalized, path, depth + 1);
        }
        Object direct = valueAtPathDirect(output, path);
        if (direct != null) {
            return direct;
        }
        if (output instanceof Map<?, ?> map) {
            for (String wrapper : List.of(
                "structuredContent", "structured_content", "data", "result", "payload", "body", "output"
            )) {
                Object nested = firstMapValue(map, wrapper);
                if (nested != null) {
                    Object value = valueAtPath(nested, path, depth + 1);
                    if (value != null) {
                        return value;
                    }
                }
            }
            Object content = firstMapValue(map, "content");
            if (content instanceof List<?> list) {
                for (Object item : list) {
                    Object text = item instanceof Map<?, ?> itemMap
                        ? firstMapValue(itemMap, "text", "content", "data") : item;
                    Object value = valueAtPath(text, path, depth + 1);
                    if (value != null) {
                        return value;
                    }
                }
            }
        }
        return null;
    }

    private Object valueAtPathDirect(Object output, String path) {
        Object current = output;
        List<String> parts = pathTokens(path);
        int start = parts.size() > 1 && "data".equals(parts.get(0))
            && !(current instanceof Map<?, ?> map && map.containsKey("data")) ? 1 : 0;
        for (int i = start; i < parts.size(); i++) {
            String part = parts.get(i);
            if (current instanceof Map<?, ?> map) {
                current = map.get(part);
            } else if (current instanceof List<?> list) {
                try {
                    current = list.get(Integer.parseInt(part));
                } catch (RuntimeException ex) {
                    return null;
                }
            } else {
                if (isTemplateIdAlias(part) && current instanceof String) {
                    return current;
                }
                return null;
            }
        }
        return current;
    }

    private Object firstMapValue(Map<?, ?> map, String... keys) {
        if (map == null || keys == null) {
            return null;
        }
        for (String key : keys) {
            if (map.containsKey(key)) {
                return map.get(key);
            }
        }
        return null;
    }

    private boolean isTemplateIdAlias(String part) {
        return "templateId".equals(part)
            || "template_id".equals(part)
            || "templateCode".equals(part)
            || "code".equals(part);
    }
}
