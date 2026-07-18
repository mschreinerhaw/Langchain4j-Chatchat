package com.chatchat.mcpserver.notification;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NotificationMcpToolPublisherTest {

    @Test
    void refreshPublishesEnabledNotificationChannels() {
        McpSyncServer mcpSyncServer = mock(McpSyncServer.class);
        NotificationChannelConfigService configService = mock(NotificationChannelConfigService.class);
        NotificationToolSpecFactory toolSpecFactory = mock(NotificationToolSpecFactory.class);
        NotificationChannelConfig config = notificationConfig("notify_email");
        McpServerFeatures.SyncToolSpecification specification = toolSpecification("notify_email");
        when(configService.listEnabled()).thenReturn(List.of(config));
        when(toolSpecFactory.toToolSpecification(config)).thenReturn(specification);

        NotificationMcpToolPublisher publisher = new NotificationMcpToolPublisher(
            mcpSyncServer,
            configService,
            toolSpecFactory
        );

        publisher.refresh();

        verify(mcpSyncServer).addTool(specification);
        verify(mcpSyncServer).notifyToolsListChanged();
    }

    @Test
    void refreshDoesNotPublishWhenNoNotificationChannelIsEnabled() {
        McpSyncServer mcpSyncServer = mock(McpSyncServer.class);
        NotificationChannelConfigService configService = mock(NotificationChannelConfigService.class);
        when(configService.listEnabled()).thenReturn(List.of());

        NotificationMcpToolPublisher publisher = new NotificationMcpToolPublisher(
            mcpSyncServer,
            configService,
            mock(NotificationToolSpecFactory.class)
        );

        publisher.refresh();

        verify(mcpSyncServer, never()).addTool(any());
        verify(mcpSyncServer).notifyToolsListChanged();
    }

    @Test
    void refreshRemovesPreviouslyManagedNotificationTools() {
        McpSyncServer mcpSyncServer = mock(McpSyncServer.class);
        NotificationChannelConfigService configService = mock(NotificationChannelConfigService.class);
        NotificationToolSpecFactory toolSpecFactory = mock(NotificationToolSpecFactory.class);
        NotificationChannelConfig config = notificationConfig("notify_email");
        McpServerFeatures.SyncToolSpecification specification = toolSpecification("notify_email");
        when(configService.listEnabled()).thenReturn(List.of(config), List.of());
        when(toolSpecFactory.toToolSpecification(config)).thenReturn(specification);

        NotificationMcpToolPublisher publisher = new NotificationMcpToolPublisher(
            mcpSyncServer,
            configService,
            toolSpecFactory
        );

        publisher.refresh();
        publisher.refresh();

        verify(mcpSyncServer).removeTool("notify_email");
    }

    @Test
    void refreshDoesNotPublishChannelsWhenAlertsAreDisabled() {
        McpSyncServer mcpSyncServer = mock(McpSyncServer.class);
        NotificationChannelConfigService configService = mock(NotificationChannelConfigService.class);
        NotificationMcpToolPublisher publisher = new NotificationMcpToolPublisher(
            mcpSyncServer,
            configService,
            mock(NotificationToolSpecFactory.class)
        );
        ReflectionTestUtils.setField(publisher, "notificationsEnabled", false);

        publisher.refresh();

        verify(configService, never()).listEnabled();
        verify(mcpSyncServer, never()).addTool(any());
        verify(mcpSyncServer).notifyToolsListChanged();
    }

    private NotificationChannelConfig notificationConfig(String toolName) {
        NotificationChannelConfig config = new NotificationChannelConfig();
        config.setId("notification-1");
        config.setChannel(NotificationChannel.EMAIL);
        config.setToolName(toolName);
        config.setTitle("邮件通知工具");
        config.setDescription("向指定邮箱发送 Agent 分析结果或告警通知。");
        config.setRuntimeAction("confirm_required");
        return config;
    }

    private McpServerFeatures.SyncToolSpecification toolSpecification(String toolName) {
        McpSchema.Tool tool = McpSchema.Tool.builder()
            .name(toolName)
            .title("邮件通知工具")
            .description("向指定邮箱发送 Agent 分析结果或告警通知。")
            .inputSchema(new McpSchema.JsonSchema("object", Map.of(), List.of(), false, null, null))
            .build();
        return McpServerFeatures.SyncToolSpecification.builder()
            .tool(tool)
            .callHandler((exchange, request) -> McpSchema.CallToolResult.builder()
                .addTextContent("ok")
                .build())
            .build();
    }
}
