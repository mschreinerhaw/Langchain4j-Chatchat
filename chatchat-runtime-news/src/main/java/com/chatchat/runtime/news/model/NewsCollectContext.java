package com.chatchat.runtime.news.model;

import java.time.Instant;

public record NewsCollectContext(String executionId, Instant startedAt) {
}
