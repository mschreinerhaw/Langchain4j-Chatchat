package com.chatchat.common.tool;

import java.lang.reflect.Array;
import java.util.ArrayList;
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
        return text.substring(0, Math.max(0, maxChars)) + "...";
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
