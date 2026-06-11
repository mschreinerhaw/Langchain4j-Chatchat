package com.chatchat.mcpserver.cache;

import com.chatchat.mcpserver.api.ApiInvokeResult;

public record ApiResponseCacheEntry(
    ApiInvokeResult result,
    long createdAt,
    long expiresAt
) {
    /**
     * Returns whether is expired.
     *
     * @param now the now value
     * @return whether the condition is satisfied
     */
    public boolean isExpired(long now) {
        return expiresAt > 0 && expiresAt <= now;
    }
}
