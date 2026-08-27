package com.chatchat.chat.task.schedule;

import java.util.List;

public record ScheduledTaskRunAuditPageResponse(
    List<ScheduledTaskRunAuditResponse> records,
    long total,
    int page,
    int pageSize,
    int totalPages
) {
}
