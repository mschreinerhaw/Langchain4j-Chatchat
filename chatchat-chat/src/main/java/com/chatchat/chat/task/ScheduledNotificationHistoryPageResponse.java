package com.chatchat.chat.task;

import java.util.List;

public record ScheduledNotificationHistoryPageResponse(
    List<ScheduledNotificationHistoryResponse> records,
    long total,
    int page,
    int pageSize,
    int totalPages
) {
}
