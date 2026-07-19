package com.chatchat.mcpserver.notification;

import com.chatchat.mcpserver.audit.InvocationAuditService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class NotificationSendServiceTest {

    @Test
    void neverFallsBackToLegacyMcpDefaultReceiver() {
        NotificationSendService service = new NotificationSendService(
            new ObjectMapper(), mock(InvocationAuditService.class)
        );
        NotificationChannelConfig config = new NotificationChannelConfig();
        config.setChannel(NotificationChannel.EMAIL);
        config.setToolName("email_send");
        config.setDeliveryMode("SMTP");
        config.setDefaultReceiver("legacy@example.com");

        assertThatThrownBy(() -> service.send(config, Map.of(
                "title", "Tenant notification",
                "content", "content",
                "level", "INFO"
            )))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("receiver is required");
    }
}
