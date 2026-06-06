package com.chatchat.mcpserver.cache;

import com.chatchat.mcpserver.api.ApiInvokeResult;

public record ApiResponseCacheEntry(
    ApiInvokeResult result,
    long createdAt,
    long expiresAt
) {
    public boolean isExpired(long now) {
        return expiresAt > 0 && expiresAt <= now;
    }
}
