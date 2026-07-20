package com.chatchat.mcpserver.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
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
        NotificationChannelConfig weCom = saved.stream()
            .filter(config -> config.getChannel() == NotificationChannel.WECHAT_WORK)
            .findFirst().orElseThrow();
        NotificationChannelConfig dingTalk = saved.stream()
            .filter(config -> config.getChannel() == NotificationChannel.DINGTALK)
            .findFirst().orElseThrow();
        assertThat(weCom.getBodyTemplate())
            .contains("\"msgtype\":\"markdown\"", "{{content}}", "{{receiver}}")
            .doesNotContain("### {{title}}");
        assertThat(dingTalk.getBodyTemplate())
            .contains("\"msgtype\":\"markdown\"", "{{content}}", "{{receiver}}")
            .doesNotContain("### {{title}}");
    }

    @Test
    void migratesPreviousDefaultDingTalkTemplate() {
        NotificationChannelConfigRepository repository = mock(NotificationChannelConfigRepository.class);
        when(repository.existsByToolName(anyString())).thenReturn(true);
        NotificationChannelConfig dingTalk = new NotificationChannelConfig();
        dingTalk.setChannel(NotificationChannel.DINGTALK);
        dingTalk.setToolName("dingtalk_send");
        dingTalk.setBodyTemplate("""
            {"msgtype":"markdown","markdown":{"title":"{{title}}","text":"### {{title}}\\n\\n{{content}}\\n\\n级别：{{level}}\\n\\nsourceTaskId：{{sourceTaskId}}"},"at":{"atMobiles":["{{receiver}}"],"isAtAll":false}}
            """);
        when(repository.findAll()).thenReturn(List.of(dingTalk));
        NotificationChannelConfigService service = new NotificationChannelConfigService(repository, new ObjectMapper());

        service.listAll();

        verify(repository, times(1)).save(dingTalk);
        assertThat(dingTalk.getBodyTemplate())
            .contains("\"text\":\"{{content}}")
            .doesNotContain("### {{title}}");
    }
}
