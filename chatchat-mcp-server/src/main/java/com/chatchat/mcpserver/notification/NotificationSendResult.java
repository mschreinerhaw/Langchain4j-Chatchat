package com.chatchat.mcpserver.notification;

import java.util.Map;

public record NotificationSendResult(
    boolean success,
    NotificationChannel channel,
    String toolName,
    int statusCode,
    int attempts,
    Object responseBody,
    String rawResponse,
    String errorMessage,
    Map<String, Object> notification
) {
}
