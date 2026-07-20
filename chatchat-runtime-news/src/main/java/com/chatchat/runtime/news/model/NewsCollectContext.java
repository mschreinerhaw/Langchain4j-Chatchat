package com.chatchat.runtime.news.model;

import java.time.Instant;

public record NewsCollectContext(String executionId, Instant startedAt, String lastCursor) {
    public NewsCollectContext(String executionId, Instant startedAt) {
        this(executionId, startedAt, null);
    }
}
