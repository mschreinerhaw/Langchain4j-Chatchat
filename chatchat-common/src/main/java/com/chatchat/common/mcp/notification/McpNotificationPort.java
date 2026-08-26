package com.chatchat.common.mcp.notification;

import com.chatchat.common.runtime.protocol.RuntimeProtocolPort;

import java.util.List;
import java.util.Map;

/** Notification capability supplied by the MCP control plane. */
public interface McpNotificationPort extends RuntimeProtocolPort {
    String PROTOCOL_VERSION = "runtime_os.mcp.notification.v1";

    List<NotificationChannel> listEnabled();

    NotificationChannel requireEnabled(String id);

    void dispatch(String id, Map<String, Object> payload);

    record NotificationChannel(String id, String channel, String toolName, String title,
                               String description, String deliveryMode, boolean recipientAware) {
    }
}
