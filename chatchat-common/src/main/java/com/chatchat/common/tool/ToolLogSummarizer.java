package com.chatchat.common.tool;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Builds compact, redacted values for tool invocation logs.
 */
public final class ToolLogSummarizer {

    private static final int DEFAULT_MAX_CHARS = 2_000;
    private static final int MAX_DEPTH = 4;
    private static final int MAX_MAP_ENTRIES = 24;
    private static final int MAX_LIST_ITEMS = 8;
    private static final int MAX_STRING_CHARS = 320;

    /**
     * Creates a new ToolLogSummarizer instance.
     */
    private ToolLogSummarizer() {
    }

    /**
     * Performs the summarize operation.
     *
     * @param value the value value
     * @return the operation result
     */
    public static Object summarize(Object value) {
        return summarize(value, DEFAULT_MAX_CHARS);
    }

    /**
     * Performs the summarize operation.
     *
     * @param value the value value
     * @param maxChars the max chars value
     * @return the operation result
     */
    public static Object summarize(Object value, int maxChars) {
        Object summarized = summarizeValue(value, null, 0);
        String text = String.valueOf(summarized);
        if (text.length() <= maxChars) {
            return summarized;
        }
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("schemaVersion", "tool_result_summary.v1");
        envelope.put("summaryTruncated", true);
        envelope.put("resultPresent", true);
        envelope.put("originalType", value == null ? "null" : value.getClass().getSimpleName());
        envelope.put("originalSummaryChars", text.length());
        envelope.put("preview", text.substring(0, Math.max(0, maxChars)) + "...");
        return envelope;
    }

    /**
     * Produces a complete evidence copy while redacting values whose field names
     * identify credentials. Unlike {@link #summarize(Object)}, this method never
     * applies character, collection, or depth limits and is therefore suitable
     * for the Runtime-to-Agent evidence boundary rather than operational logs.
     */
    public static Object redactComplete(Object value) {
        return redactCompleteValue(value, null, new IdentityHashMap<>());
    }

    /**
     * Summarizes a tool result for operational logs. Enterprise metadata matching
     * results are represented by counts only because their field-level evidence is
     * intentionally consumed by Runtime rather than emitted repeatedly by each layer.
     */
    public static Object summarizeResult(String toolName, Object value) {
        Map<String, Object> runtimeReference = mapValue(value);
        if (Boolean.TRUE.equals(runtimeReference.get("outputTruncated"))
            && runtimeReference.containsKey("documentId")) {
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("schemaVersion", "externalized_tool_result_summary.v1");
            copyIfPresent(summary, runtimeReference, "outputExternal");
            copyIfPresent(summary, runtimeReference, "outputTruncated");
            copyIfPresent(summary, runtimeReference, "originalBytes");
            copyIfPresent(summary, runtimeReference, "maxInlineBytes");
            copyIfPresent(summary, runtimeReference, "reason");
            summary.put("resultPresent", true);
            summary.put("detailsLogged", false);
            return summary;
        }
        Map<String, Object> enterpriseMetadata = enterpriseMetadataPayload(toolName, value, 0);
        if (enterpriseMetadata.isEmpty()) {
            return summarize(value);
        }
        String schemaVersion = stringValue(enterpriseMetadata.get("schemaVersion"));
        if ("ENTERPRISE_METADATA_DISCOVERY".equals(enterpriseMetadata.get("operationMode"))
            || (schemaVersion != null && schemaVersion.startsWith("enterprise_metadata_search_result."))) {
            Map<String, Object> summary = new LinkedHashMap<>();
            copyIfPresent(summary, enterpriseMetadata, "schemaVersion");
            copyIfPresent(summary, enterpriseMetadata, "success");
            copyIfPresent(summary, enterpriseMetadata, "requestId");
            copyIfPresent(summary, enterpriseMetadata, "operationMode");
            copyIfPresent(summary, enterpriseMetadata, "backend");
            copyIfPresent(summary, enterpriseMetadata, "retrievalMode");
            copyIfPresent(summary, enterpriseMetadata, "count");
            summary.put("countsByType", summarizeValue(
                enterpriseMetadata.get("countsByType"), "countsByType", 0));
            summary.put("requiredRetrieval", summarizeValue(
                enterpriseMetadata.get("requiredRetrieval"), "requiredRetrieval", 0));
            summary.put("evidenceObjectCount", collectionSize(enterpriseMetadata.get("evidenceObjects")));
            summary.put("detailsLogged", false);
            return summary;
        }
        Map<String, Object> sourceSchema = mapValue(enterpriseMetadata.get("sourceSchema"));
        List<Map<String, Object>> fieldMatches = mapValues(enterpriseMetadata.get("fieldMatches"));
        Map<String, Integer> candidateCounts = new LinkedHashMap<>();
        candidateCounts.put("standardFields", candidateCount(fieldMatches, "standardFields"));
        candidateCounts.put("termRoots", candidateCount(fieldMatches, "termRoots"));
        candidateCounts.put("dictionaries", candidateCount(fieldMatches, "dictionaries"));
        Map<String, Object> summary = new LinkedHashMap<>();
        copyIfPresent(summary, enterpriseMetadata, "schemaVersion");
        copyIfPresent(summary, enterpriseMetadata, "success");
        copyIfPresent(summary, enterpriseMetadata, "requestId");
        copyIfPresent(summary, enterpriseMetadata, "purpose");
        copyIfPresent(summary, enterpriseMetadata, "retrievalMode");
        copyIfPresent(summary, enterpriseMetadata, "errorCode");
        summary.put("targetObject", summarizeValue(enterpriseMetadata.get("targetObject"), "targetObject", 0));
        summary.put("sourceFieldCount", firstNonNull(
            sourceSchema.get("fieldCount"), collectionSize(sourceSchema.get("fields"))));
        summary.put("matchedFieldCount", fieldMatches.size());
        summary.put("candidateCounts", candidateCounts);
        summary.put("evidenceObjectCount", collectionSize(enterpriseMetadata.get("evidenceObjects")));
        summary.put("detailsLogged", false);
        return summary;
    }

    /**
     * Performs the summarize value operation.
     *
     * @param value the value value
     * @param key the key value
     * @param depth the depth value
     * @return the operation result
     */
    @SuppressWarnings("unchecked")
    private static Object summarizeValue(Object value, String key, int depth) {
        if (isSensitiveKey(key)) {
            return "***";
        }
        if (value == null || value instanceof Number || value instanceof Boolean) {
            return value;
        }
        if (value instanceof CharSequence text) {
            return limitString(text.toString());
        }
        if (depth >= MAX_DEPTH) {
            return shape(value);
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> summarized = new LinkedHashMap<>();
            int count = 0;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (count >= MAX_MAP_ENTRIES) {
                    summarized.put("_truncated_entries", map.size() - count);
                    break;
                }
                String childKey = String.valueOf(entry.getKey());
                summarized.put(childKey, summarizeValue(entry.getValue(), childKey, depth + 1));
                count++;
            }
            return summarized;
        }
        if (value instanceof Iterable<?> iterable) {
            List<Object> summarized = new ArrayList<>();
            int count = 0;
            for (Object item : iterable) {
                if (count >= MAX_LIST_ITEMS) {
                    summarized.add("... truncated");
                    break;
                }
                summarized.add(summarizeValue(item, null, depth + 1));
                count++;
            }
            return summarized;
        }
        if (value.getClass().isArray()) {
            int length = Array.getLength(value);
            List<Object> summarized = new ArrayList<>();
            int limit = Math.min(length, MAX_LIST_ITEMS);
            for (int i = 0; i < limit; i++) {
                summarized.add(summarizeValue(Array.get(value, i), null, depth + 1));
            }
            if (length > limit) {
                summarized.add("... truncated");
            }
            return summarized;
        }
        return limitString(String.valueOf(value));
    }

    private static Object redactCompleteValue(Object value,
                                              String key,
                                              IdentityHashMap<Object, Boolean> ancestors) {
        if (isSensitiveKey(key)) {
            return "***";
        }
        if (value == null || value instanceof Number || value instanceof Boolean
            || value instanceof Character || value instanceof Enum<?>) {
            return value;
        }
        if (value instanceof CharSequence text) {
            return text.toString();
        }
        if (ancestors.put(value, Boolean.TRUE) != null) {
            return "[cyclic reference]";
        }
        try {
            if (value instanceof Map<?, ?> map) {
                Map<String, Object> redacted = new LinkedHashMap<>();
                map.forEach((childKey, item) -> {
                    String name = String.valueOf(childKey);
                    redacted.put(name, redactCompleteValue(item, name, ancestors));
                });
                return redacted;
            }
            if (value instanceof Iterable<?> iterable) {
                List<Object> redacted = new ArrayList<>();
                iterable.forEach(item -> redacted.add(redactCompleteValue(item, null, ancestors)));
                return redacted;
            }
            if (value.getClass().isArray()) {
                int length = Array.getLength(value);
                List<Object> redacted = new ArrayList<>(length);
                for (int i = 0; i < length; i++) {
                    redacted.add(redactCompleteValue(Array.get(value, i), null, ancestors));
                }
                return redacted;
            }
            return String.valueOf(value);
        } finally {
            ancestors.remove(value);
        }
    }

    private static Map<String, Object> enterpriseMetadataPayload(String toolName, Object value, int depth) {
        if (depth > 5) {
            return Map.of();
        }
        Map<String, Object> map = mapValue(value);
        if (map.isEmpty()) {
            return Map.of();
        }
        String schemaVersion = stringValue(map.get("schemaVersion"));
        if ("enterprise_metadata_field_discovery.v1".equals(schemaVersion)) {
            return map;
        }
        for (String key : List.of("data", "result", "payload", "structuredContent", "structured_content")) {
            Map<String, Object> nested = enterpriseMetadataPayload(toolName, map.get(key), depth + 1);
            if (!nested.isEmpty()) {
                return nested;
            }
        }
        return depth == 0 && isEnterpriseMetadataTool(toolName) ? map : Map.of();
    }

    private static boolean isEnterpriseMetadataTool(String toolName) {
        if (toolName == null || toolName.isBlank()) {
            return false;
        }
        return toolName.trim().toLowerCase(Locale.ROOT)
            .replace('-', '_')
            .endsWith("enterprise_metadata_search");
    }

    private static int candidateCount(List<Map<String, Object>> fieldMatches, String key) {
        int count = 0;
        for (Map<String, Object> fieldMatch : fieldMatches) {
            count += collectionSize(fieldMatch.get(key));
        }
        return count;
    }

    private static int collectionSize(Object value) {
        if (value instanceof Map<?, ?> map) {
            return map.size();
        }
        if (value instanceof java.util.Collection<?> collection) {
            return collection.size();
        }
        if (value != null && value.getClass().isArray()) {
            return Array.getLength(value);
        }
        return 0;
    }

    private static Object firstNonNull(Object first, Object second) {
        return first == null ? second : first;
    }

    private static void copyIfPresent(Map<String, Object> target,
                                      Map<String, Object> source,
                                      String key) {
        Object value = source.get(key);
        if (value != null) {
            target.put(key, value);
        }
    }

    private static String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static Map<String, Object> mapValue(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, item) -> {
            if (key != null) {
                result.put(String.valueOf(key), item);
            }
        });
        return result;
    }

    private static List<Map<String, Object>> mapValues(Object value) {
        if (!(value instanceof Iterable<?> iterable)) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : iterable) {
            Map<String, Object> map = mapValue(item);
            if (!map.isEmpty()) {
                result.add(map);
            }
        }
        return result;
    }

    /**
     * Performs the limit string operation.
     *
     * @param value the value value
     * @return the operation result
     */
    private static String limitString(String value) {
        if (value == null || value.length() <= MAX_STRING_CHARS) {
            return value;
        }
        return value.substring(0, MAX_STRING_CHARS) + "...";
    }

    /**
     * Performs the shape operation.
     *
     * @param value the value value
     * @return the operation result
     */
    private static String shape(Object value) {
        if (value instanceof Map<?, ?> map) {
            return "Map(size=" + map.size() + ")";
        }
        if (value instanceof Iterable<?>) {
            return "Iterable";
        }
        if (value != null && value.getClass().isArray()) {
            return "Array(length=" + Array.getLength(value) + ")";
        }
        return value == null ? null : value.getClass().getSimpleName();
    }

    /**
     * Returns whether is sensitive key.
     *
     * @param key the key value
     * @return whether the condition is satisfied
     */
    private static boolean isSensitiveKey(String key) {
        if (key == null || key.isBlank()) {
            return false;
        }
        String normalized = key.toLowerCase(Locale.ROOT).replace("-", "").replace("_", "");
        return normalized.contains("token")
            || normalized.contains("password")
            || normalized.contains("passwd")
            || normalized.contains("secret")
            || normalized.contains("authorization")
            || normalized.contains("apikey")
            || normalized.contains("credential")
            || normalized.contains("cookie")
            || normalized.contains("sessionid");
    }
}
