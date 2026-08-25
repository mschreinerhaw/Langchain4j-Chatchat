package com.chatchat.common.bridge;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Collections;

/** Canonical outbound envelope; payload is never replaced by execution summaries. */
public record BridgeResponse<O>(
    String bridgeVersion,
    String requestId,
    BridgeStatus status,
    O data,
    String errorCode,
    String errorMessage,
    Map<String, Object> metadata,
    long completedAt
) {
    public BridgeResponse {
        if (bridgeVersion == null || bridgeVersion.isBlank()) {
            throw new IllegalArgumentException("bridgeVersion is required");
        }
        if (requestId == null || requestId.isBlank()) throw new IllegalArgumentException("requestId is required");
        status = status == null ? BridgeStatus.FAILURE : status;
        metadata = metadata == null ? Map.of()
            : Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
        completedAt = completedAt <= 0 ? System.currentTimeMillis() : completedAt;
    }

    public boolean successful() {
        return status == BridgeStatus.SUCCESS;
    }
}
