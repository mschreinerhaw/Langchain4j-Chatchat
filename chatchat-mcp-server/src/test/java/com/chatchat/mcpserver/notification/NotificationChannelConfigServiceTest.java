package com.chatchat.mcpserver.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NotificationChannelConfigServiceTest {

    @Test
    void ensureDefaultsCreatesReadableChineseNotificationChannelMetadata() {
        NotificationChannelConfigRepository repository = mock(NotificationChannelConfigRepository.class);
        when(repository.existsByToolName(anyString())).thenReturn(false);
        when(repository.findAll()).thenReturn(List.of());
        NotificationChannelConfigService service = new NotificationChannelConfigService(
            repository,
            new ObjectMapper()
        );

        service.listAll();

        ArgumentCaptor<NotificationChannelConfig> captor = ArgumentCaptor.forClass(NotificationChannelConfig.class);
        verify(repository, org.mockito.Mockito.times(NotificationChannel.values().length)).save(captor.capture());
        List<NotificationChannelConfig> saved = captor.getAllValues();
        assertThat(saved)
            .extracting(NotificationChannelConfig::getTitle)
            .contains("发送邮件告警", "发送短信告警", "发送企业微信告警", "发送钉钉告警");
        assertThat(saved)
            .extracting(NotificationChannelConfig::getDescription)
            .allSatisfy(description -> assertThat(description).contains("告警"));
        assertThat(saved)
            .allSatisfy(config -> assertThat(config.isEnabled()).isFalse());
    }
}
