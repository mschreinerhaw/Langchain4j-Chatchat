package com.chatchat.chat.task;

import java.util.List;

public record ScheduledTaskPageResponse(
    List<ScheduledTaskResponse> records,
    long total,
    int page,
    int pageSize,
    int totalPages
) {
}
