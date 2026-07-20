package com.chatchat.mcpserver.notification;

import com.chatchat.agents.protocol.ModelProtocolJson;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class NotificationChannelConfigService {

    private final NotificationChannelConfigRepository repository;
    private final ObjectMapper objectMapper;

    @Transactional
    public List<NotificationChannelConfig> listAll() {
        ensureDefaults();
        return repository.findAll().stream()
            .sorted(Comparator
                .comparing((NotificationChannelConfig config) -> config.getChannel().ordinal())
                .thenComparing(NotificationChannelConfig::getToolName))
            .toList();
    }

    @Transactional
    public List<NotificationChannelConfig> listEnabled() {
        ensureDefaults();
        return repository.findByEnabledTrueOrderByToolNameAsc();
    }

    @Transactional
    public NotificationChannelConfig create(NotificationChannelConfig config) {
        normalize(config);
        requireUniqueToolName(config.getToolName(), null);
        return repository.save(config);
    }

    @Transactional
    public NotificationChannelConfig update(String id, NotificationChannelConfig config) {
        NotificationChannelConfig existing = getById(id);
        existing.setToolName(firstText(config.getToolName(), existing.getToolName()));
        existing.setTitle(firstText(config.getTitle(), existing.getTitle()));
        existing.setDescription(config.getDescription());
        existing.setEnabled(config.isEnabled());
        existing.setRuntimeAction(config.getRuntimeAction());
        existing.setDeliveryMode(config.getDeliveryMode());
        existing.setMethod(config.getMethod());
        existing.setEndpointUrl(config.getEndpointUrl());
        existing.setHeadersJson(config.getHeadersJson());
        existing.setBodyTemplate(config.getBodyTemplate());
        existing.setSecret(config.getSecret());
        existing.setDefaultReceiver(null);
        existing.setCcReceiver(null);
        existing.setSmtpHost(config.getSmtpHost());
        existing.setSmtpPort(config.getSmtpPort());
        existing.setSmtpUsername(config.getSmtpUsername());
        existing.setSmtpPassword(config.getSmtpPassword());
        existing.setSmtpFrom(config.getSmtpFrom());
        existing.setSmtpAuthEnabled(config.isSmtpAuthEnabled());
        existing.setSmtpStarttlsEnabled(config.isSmtpStarttlsEnabled());
        existing.setSmtpSslEnabled(config.isSmtpSslEnabled());
        existing.setSmtpSslTrust(config.getSmtpSslTrust());
        existing.setSmsAccount(config.getSmsAccount());
        existing.setSmsToken(config.getSmsToken());
        existing.setSmsPlainPassword(config.getSmsPlainPassword());
        existing.setSmsMd5Password(config.getSmsMd5Password());
        existing.setSmsPasswordMd5(config.isSmsPasswordMd5());
        existing.setSmsReturnType(config.getSmsReturnType());
        existing.setSmsExtendCode(config.getSmsExtendCode());
        existing.setTimeoutMs(config.getTimeoutMs());
        existing.setMaxRetries(config.getMaxRetries());
        normalize(existing);
        requireUniqueToolName(existing.getToolName(), existing.getId());
        return repository.save(existing);
    }

    @Transactional
    public NotificationChannelConfig setEnabled(String id, boolean enabled) {
        NotificationChannelConfig config = getById(id);
        config.setEnabled(enabled);
        return repository.save(config);
    }

    @Transactional
    public NotificationChannelConfig setRuntimeAction(String id, String runtimeAction) {
        NotificationChannelConfig config = getById(id);
        config.setRuntimeAction(normalizeRuntimeAction(runtimeAction));
        return repository.save(config);
    }

    public NotificationChannelConfig getById(String id) {
        return repository.findById(requireText(id, "Channel config ID cannot be empty"))
            .orElseThrow(() -> new IllegalArgumentException("Notification channel config not found: " + id));
    }

    @Transactional
    public void ensureDefaults() {
        for (NotificationChannel channel : NotificationChannel.values()) {
            if (!repository.existsByToolName(toolName(channel))) {
                repository.save(defaultConfig(channel));
            }
        }
        repository.findAll().forEach(config -> {
            boolean changed = false;
            if ((config.getDefaultReceiver() != null && !config.getDefaultReceiver().isBlank())
                || (config.getCcReceiver() != null && !config.getCcReceiver().isBlank())) {
                config.setDefaultReceiver(null);
                config.setCcReceiver(null);
                changed = true;
            }
            if (isLegacySharedRecipientTemplate(config)) {
                config.setBodyTemplate(defaultBodyTemplate(config.getChannel()));
                changed = true;
            }
            if (changed) {
                repository.save(config);
            }
        });
    }

    private NotificationChannelConfig defaultConfig(NotificationChannel channel) {
        NotificationChannelConfig config = new NotificationChannelConfig();
        config.setChannel(channel);
        config.setToolName(toolName(channel));
        config.setTitle(title(channel));
        config.setDescription(description(channel));
        config.setRuntimeAction("confirm_required");
        config.setDeliveryMode(channel == NotificationChannel.EMAIL ? "SMTP" : "HTTP");
        config.setMethod("POST");
        config.setHeadersJson(writeJson(Map.of("Content-Type", "application/json")));
        config.setBodyTemplate(defaultBodyTemplate(channel));
        config.setDefaultReceiver(null);
        config.setCcReceiver(null);
        config.setSmtpAuthEnabled(true);
        config.setSmtpStarttlsEnabled(true);
        config.setSmtpSslEnabled(false);
        config.setSmsPasswordMd5(true);
        config.setSmsReturnType("text");
        config.setTimeoutMs(10000);
        config.setMaxRetries(1);
        return config;
    }

    private void normalize(NotificationChannelConfig config) {
        if (config.getChannel() == null) {
            throw new IllegalArgumentException("Notification channel cannot be empty");
        }
        config.setToolName(requireToolName(firstText(config.getToolName(), toolName(config.getChannel()))));
        config.setTitle(firstText(config.getTitle(), title(config.getChannel())));
        config.setRuntimeAction(normalizeRuntimeAction(config.getRuntimeAction()));
        config.setDeliveryMode(normalizeDeliveryMode(config.getDeliveryMode()));
        config.setMethod(normalizeMethod(config.getMethod()));
        config.setTimeoutMs(Math.max(1000, config.getTimeoutMs()));
        config.setMaxRetries(Math.max(0, Math.min(config.getMaxRetries(), 5)));
    }

    private String normalizeRuntimeAction(String value) {
        String normalized = firstText(value, "confirm_required")
            .toLowerCase(Locale.ROOT)
            .replace('-', '_');
        return switch (normalized) {
            case "confirm_required", "ask_before_execute" -> "confirm_required";
            case "forbidden", "deny" -> "forbidden";
            default -> "confirm_required";
        };
    }

    private String normalizeDeliveryMode(String value) {
        String normalized = firstText(value, "HTTP").toUpperCase(Locale.ROOT);
        return "SMTP".equals(normalized) ? "SMTP" : "HTTP";
    }

    private String normalizeMethod(String value) {
        String normalized = firstText(value, "POST").toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "POST", "PUT", "PATCH" -> normalized;
            default -> "POST";
        };
    }

    private String requireToolName(String value) {
        String normalized = requireText(value, "Tool name cannot be empty").trim();
        if (!normalized.matches("[A-Za-z0-9_\\-]{2,128}")) {
            throw new IllegalArgumentException("Tool name only supports letters, numbers, underscore and dash");
        }
        return normalized;
    }

    private void requireUniqueToolName(String toolName, String currentId) {
        boolean exists = currentId == null || currentId.isBlank()
            ? repository.existsByToolName(toolName)
            : repository.existsByToolNameAndIdNot(toolName, currentId);
        if (exists) {
            throw new IllegalArgumentException("Notification MCP tool name already exists: " + toolName);
        }
    }

    private String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }

    private String firstText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private String toolName(NotificationChannel channel) {
        return switch (channel) {
            case EMAIL -> "email_send";
            case SMS -> "sms_send";
            case WECHAT_WORK -> "wechat_work_send";
            case DINGTALK -> "dingtalk_send";
        };
    }

    private String title(NotificationChannel channel) {
        return switch (channel) {
            case EMAIL -> "发送邮件告警";
            case SMS -> "发送短信告警";
            case WECHAT_WORK -> "发送企业微信告警";
            case DINGTALK -> "发送钉钉告警";
        };
    }

    private String description(NotificationChannel channel) {
        return switch (channel) {
            case EMAIL -> "向指定邮箱发送 Agent 分析结果或告警通知。";
            case SMS -> "向指定手机号发送 Agent 分析结果或告警短信。";
            case WECHAT_WORK -> "通过企业微信机器人或消息接口发送 Agent 告警通知。";
            case DINGTALK -> "通过钉钉机器人或消息接口发送 Agent 告警通知。";
        };
    }

    private String defaultBodyTemplate(NotificationChannel channel) {
        return switch (channel) {
            case EMAIL -> "";
            case SMS -> """
                {"phone":"{{receiver}}","account":"{{smsAccount}}","password":"{{smsPassword}}","token":"{{smsToken}}","content":"{{content}}","extno":"{{smsExtendCode}}","rt":"{{smsReturnType}}"}
                """;
            case WECHAT_WORK -> """
                {"msgtype":"markdown","markdown":{"content":"{{content}}\\n\\n> 级别：{{level}}\\n> 任务：{{sourceTaskId}}\\n\\n<@{{receiver}}>"}}
                """;
            case DINGTALK -> """
                {"msgtype":"markdown","markdown":{"title":"{{title}}","text":"{{content}}\\n\\n> 级别：{{level}}\\n> 任务：{{sourceTaskId}}"},"at":{"atMobiles":["{{receiver}}"],"isAtAll":false}}
                """;
        };
    }

    private boolean isLegacySharedRecipientTemplate(NotificationChannelConfig config) {
        if (config == null || config.getChannel() == null || config.getBodyTemplate() == null) {
            return false;
        }
        List<String> legacyTemplates = switch (config.getChannel()) {
            case WECHAT_WORK -> List.of(
                """
                    {"msgtype":"markdown","markdown":{"content":"### {{title}}\\n> {{level}}\\n\\n{{content}}\\n\\nsourceTaskId: {{sourceTaskId}}"}}
                    """,
                """
                    {"msgtype":"text","text":{"content":"{{title}}\\n[{{level}}]\\n{{content}}\\nsourceTaskId: {{sourceTaskId}}","mentioned_list":["{{receiver}}"]}}
                    """
            );
            case DINGTALK -> List.of(
                """
                    {"msgtype":"markdown","markdown":{"title":"{{title}}","text":"### {{title}}\\n\\n{{content}}\\n\\n级别：{{level}}\\n\\nsourceTaskId：{{sourceTaskId}}"}}
                    """,
                """
                    {"msgtype":"markdown","markdown":{"title":"{{title}}","text":"### {{title}}\\n\\n{{content}}\\n\\n级别：{{level}}\\n\\nsourceTaskId：{{sourceTaskId}}"},"at":{"atMobiles":["{{receiver}}"],"isAtAll":false}}
                    """
            );
            default -> List.of();
        };
        String current = config.getBodyTemplate().trim();
        return legacyTemplates.stream().map(String::trim).anyMatch(current::equals);
    }

    private String writeJson(Map<String, Object> value) {
        try {
            return ModelProtocolJson.compact(value);
        } catch (Exception ex) {
            return "{}";
        }
    }
}
