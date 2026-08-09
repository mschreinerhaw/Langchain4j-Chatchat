package com.chatchat.mcpserver.cache;

import com.chatchat.common.tool.ToolOutput;

import java.util.Map;

public record DatabaseQueryCacheEntry(
    ToolOutput result,
    long createdAt,
    long expiresAt,
    Descriptor descriptor,
    long hitCount,
    long lastHitAt
) {
    public boolean isExpired(long now) {
        return expiresAt > 0 && expiresAt <= now;
    }

    public record Descriptor(
        String tenantId,
        String userId,
        String templateId,
        String toolName,
        String title,
        String datasourceId,
        Map<String, Object> parameters
    ) { }
}
