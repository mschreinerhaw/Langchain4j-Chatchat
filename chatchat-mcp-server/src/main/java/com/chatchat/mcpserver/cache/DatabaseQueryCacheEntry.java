package com.chatchat.mcpserver.cache;

import com.chatchat.common.tool.ToolOutput;

public record DatabaseQueryCacheEntry(
    ToolOutput result,
    long createdAt,
    long expiresAt
) {
    public boolean isExpired(long now) {
        return expiresAt > 0 && expiresAt <= now;
    }
}
