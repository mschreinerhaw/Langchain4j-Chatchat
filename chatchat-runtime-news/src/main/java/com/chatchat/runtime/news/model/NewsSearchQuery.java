package com.chatchat.runtime.news.model;

import java.time.Instant;
import java.util.List;

public record NewsSearchQuery(
    String query,
    List<Long> sourceIds,
    Instant startTime,
    Instant endTime,
    List<String> categories,
    int size
) {
}
