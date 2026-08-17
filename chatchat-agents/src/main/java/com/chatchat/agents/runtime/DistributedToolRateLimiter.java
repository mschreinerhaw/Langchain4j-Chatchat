package com.chatchat.agents.runtime;

import java.time.Instant;

/** Optional cluster-wide rate limiter. Implementations must atomically acquire all configured windows. */
public interface DistributedToolRateLimiter {
    boolean tryAcquire(String tenantId, String toolName, String actorId,
                       int maxCallsPerMinute, int maxCallsPerSecond, Instant now);
}
