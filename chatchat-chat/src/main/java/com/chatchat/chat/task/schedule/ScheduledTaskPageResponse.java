package com.chatchat.chat.task.schedule;

import java.util.List;

public record ScheduledTaskPageResponse(
    List<ScheduledTaskResponse> records,
    long total,
    int page,
    int pageSize,
    int totalPages
) {
}
