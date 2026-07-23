package com.chatchat.chat.task;

import java.util.List;

public record ScheduledTaskRunAuditPageResponse(
    List<ScheduledTaskRunAuditResponse> records,
    long total,
    int page,
    int pageSize,
    int totalPages
) {
}
