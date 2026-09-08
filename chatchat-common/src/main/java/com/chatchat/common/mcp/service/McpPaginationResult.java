package com.chatchat.common.mcp.service;

import java.util.LinkedHashMap;
import java.util.Map;

/** Cursor continuation returned with a record-producing MCP result. */
public record McpPaginationResult(String nextPageToken, boolean hasMore,
                                  Integer pageSize, Long returnedCount) {
    public McpPaginationResult {
        nextPageToken = nextPageToken == null || nextPageToken.isBlank() ? null : nextPageToken;
        if (pageSize != null && pageSize < 1) throw new IllegalArgumentException("pageSize must be positive");
        if (returnedCount != null && returnedCount < 0) throw new IllegalArgumentException("returnedCount cannot be negative");
        if (hasMore && nextPageToken == null) throw new IllegalArgumentException("hasMore requires nextPageToken");
    }

    public static McpPaginationResult from(Object value) {
        if (value instanceof McpPaginationResult pagination) return pagination;
        if (!(value instanceof Map<?, ?> source)) return null;
        Map<String, Object> map = new LinkedHashMap<>();
        source.forEach((key, item) -> { if (key != null) map.put(String.valueOf(key), item); });
        String token = text(map.get("nextPageToken"));
        boolean more = booleanValue(map.get("hasMore"));
        Integer size = integer(map.get("pageSize"));
        Long count = longValue(map.get("returnedCount"));
        if (token == null && !more && size == null && count == null) return null;
        return new McpPaginationResult(token, more, size, count);
    }

    private static String text(Object value) { return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value); }
    private static boolean booleanValue(Object value) { return Boolean.TRUE.equals(value) || "true".equalsIgnoreCase(String.valueOf(value)); }
    private static Integer integer(Object value) { return value == null ? null : Integer.valueOf(String.valueOf(value)); }
    private static Long longValue(Object value) { return value == null ? null : Long.valueOf(String.valueOf(value)); }
}
