package com.chatchat.mcpserver.notification;

import com.chatchat.agents.protocol.ModelProtocolJson;

import com.chatchat.common.response.ApiResponse;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/notifications")
public class NotificationAdminController {

    private final NotificationChannelConfigService configService;
    private final NotificationSendService sendService;
    private final NotificationMcpToolPublisher publisher;
    private final ObjectMapper objectMapper;

    @GetMapping
    public ApiResponse<List<NotificationChannelView>> list() {
        return ApiResponse.success(configService.listAll().stream().map(this::toView).toList());
    }

    @PostMapping
    public ApiResponse<NotificationChannelView> create(@RequestBody NotificationChannelUpsertRequest request) {
        validateHttpCreate(request);
        NotificationChannelConfig saved = configService.create(fromRequest(request));
        publisher.refresh();
        return ApiResponse.success(toView(saved), "Notification channel created");
    }

    @PutMapping("/{id}")
    public ApiResponse<NotificationChannelView> update(@PathVariable("id") String id,
                                                       @RequestBody NotificationChannelUpsertRequest request) {
        NotificationChannelConfig saved = configService.update(id, fromRequest(request));
        publisher.refresh();
        return ApiResponse.success(toView(saved), "Notification channel updated");
    }

    @PostMapping("/{id}/enabled")
    public ApiResponse<NotificationChannelView> setEnabled(@PathVariable("id") String id,
                                                           @RequestParam("enabled") boolean enabled) {
        NotificationChannelConfig saved = configService.setEnabled(id, enabled);
        publisher.refresh();
        return ApiResponse.success(toView(saved), "Notification channel status updated");
    }

    @PostMapping("/{id}/runtime-action")
    public ApiResponse<NotificationChannelView> setRuntimeAction(@PathVariable("id") String id,
                                                                 @RequestParam("runtimeAction") String runtimeAction) {
        NotificationChannelConfig saved = configService.setRuntimeAction(id, runtimeAction);
        publisher.refresh();
        return ApiResponse.success(toView(saved), "Notification channel runtime action updated");
    }

    @PostMapping("/{id}/test")
    public ApiResponse<NotificationSendResult> test(@PathVariable("id") String id,
                                                    @RequestBody(required = false) Map<String, Object> arguments) {
        return ApiResponse.success(sendService.send(configService.getById(id), arguments == null ? Map.of() : arguments));
    }

    @PostMapping("/refresh")
    public ApiResponse<Map<String, Object>> refresh() {
        publisher.refresh();
        return ApiResponse.success(Map.of("refreshed", true), "Notification MCP tools refreshed");
    }

    private NotificationChannelConfig fromRequest(NotificationChannelUpsertRequest request) {
        NotificationChannelConfig config = new NotificationChannelConfig();
        config.setChannel(request.channel());
        config.setToolName(request.toolName());
        config.setTitle(request.title());
        config.setDescription(request.description());
        config.setEnabled(request.enabled() != null && request.enabled());
        config.setRuntimeAction(request.runtimeAction());
        config.setDeliveryMode(request.deliveryMode());
        config.setMethod(request.method());
        config.setEndpointUrl(request.endpointUrl());
        config.setHeadersJson(writeJson(request.headers()));
        config.setBodyTemplate(request.bodyTemplate());
        config.setSecret(request.secret());
        config.setDefaultReceiver(request.defaultReceiver());
        config.setCcReceiver(request.ccReceiver());
        config.setSmtpHost(request.smtpHost());
        config.setSmtpPort(request.smtpPort());
        config.setSmtpUsername(request.smtpUsername());
        config.setSmtpPassword(request.smtpPassword());
        config.setSmtpFrom(request.smtpFrom());
        config.setSmtpAuthEnabled(request.smtpAuthEnabled() == null || request.smtpAuthEnabled());
        config.setSmtpStarttlsEnabled(request.smtpStarttlsEnabled() == null || request.smtpStarttlsEnabled());
        config.setSmtpSslEnabled(request.smtpSslEnabled() != null && request.smtpSslEnabled());
        config.setSmtpSslTrust(request.smtpSslTrust());
        config.setSmsAccount(request.smsAccount());
        config.setSmsToken(request.smsToken());
        config.setSmsPlainPassword(request.smsPlainPassword());
        config.setSmsMd5Password(request.smsMd5Password());
        config.setSmsPasswordMd5(request.smsPasswordMd5() == null || request.smsPasswordMd5());
        config.setSmsReturnType(request.smsReturnType());
        config.setSmsExtendCode(request.smsExtendCode());
        config.setTimeoutMs(request.timeoutMs() == null ? 10000 : request.timeoutMs());
        config.setMaxRetries(request.maxRetries() == null ? 1 : request.maxRetries());
        return config;
    }

    private void validateHttpCreate(NotificationChannelUpsertRequest request) {
        String deliveryMode = request.deliveryMode() == null || request.deliveryMode().isBlank()
            ? "HTTP"
            : request.deliveryMode().trim();
        if ("SMTP".equalsIgnoreCase(deliveryMode)) {
            throw new IllegalArgumentException("新增告警只允许 HTTP/Webhook 方式");
        }
        if (request.bodyTemplate() == null || request.bodyTemplate().isBlank()) {
            throw new IllegalArgumentException("HTTP 告警内容不能为空");
        }
    }

    private NotificationChannelView toView(NotificationChannelConfig config) {
        return new NotificationChannelView(
            config.getId(),
            config.getChannel(),
            config.getToolName(),
            config.getTitle(),
            config.getDescription(),
            config.isEnabled(),
            config.getRuntimeAction(),
            config.getDeliveryMode(),
            config.getMethod(),
            config.getEndpointUrl(),
            readJsonMap(config.getHeadersJson()),
            config.getBodyTemplate(),
            config.getSecret(),
            config.getDefaultReceiver(),
            config.getCcReceiver(),
            config.getSmtpHost(),
            config.getSmtpPort(),
            config.getSmtpUsername(),
            config.getSmtpPassword(),
            config.getSmtpFrom(),
            config.isSmtpAuthEnabled(),
            config.isSmtpStarttlsEnabled(),
            config.isSmtpSslEnabled(),
            config.getSmtpSslTrust(),
            config.getSmsAccount(),
            config.getSmsToken(),
            config.getSmsPlainPassword(),
            config.getSmsMd5Password(),
            config.isSmsPasswordMd5(),
            config.getSmsReturnType(),
            config.getSmsExtendCode(),
            config.getTimeoutMs(),
            config.getMaxRetries(),
            defaultTestPayload(config),
            config.getCreatedAt() == null ? null : config.getCreatedAt().toEpochMilli(),
            config.getUpdatedAt() == null ? null : config.getUpdatedAt().toEpochMilli()
        );
    }

    private Map<String, Object> defaultTestPayload(NotificationChannelConfig config) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("receiver", firstText(config.getDefaultReceiver(),
            config.getChannel() == NotificationChannel.SMS ? "13800000000" : "ops@example.com"));
        payload.put("title", "Agent 告警测试");
        payload.put("content", "这是一条来自 ChatChat MCP Server 的通知工具测试消息。");
        payload.put("level", "INFO");
        payload.put("sourceTaskId", "manual-test");
        return payload;
    }

    private String writeJson(Map<String, Object> map) {
        if (map == null || map.isEmpty()) {
            return null;
        }
        try {
            return ModelProtocolJson.compact(map);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("headers JSON is invalid");
        }
    }

    private Map<String, Object> readJsonMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception ex) {
            return Map.of();
        }
    }

    private String firstText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    public record NotificationChannelUpsertRequest(
        NotificationChannel channel,
        String toolName,
        String title,
        String description,
        Boolean enabled,
        String runtimeAction,
        String deliveryMode,
        String method,
        String endpointUrl,
        Map<String, Object> headers,
        String bodyTemplate,
        String secret,
        String defaultReceiver,
        String ccReceiver,
        String smtpHost,
        Integer smtpPort,
        String smtpUsername,
        String smtpPassword,
        String smtpFrom,
        Boolean smtpAuthEnabled,
        Boolean smtpStarttlsEnabled,
        Boolean smtpSslEnabled,
        String smtpSslTrust,
        String smsAccount,
        String smsToken,
        String smsPlainPassword,
        String smsMd5Password,
        Boolean smsPasswordMd5,
        String smsReturnType,
        String smsExtendCode,
        Integer timeoutMs,
        Integer maxRetries
    ) {
    }

    public record NotificationChannelView(
        String id,
        NotificationChannel channel,
        String toolName,
        String title,
        String description,
        boolean enabled,
        String runtimeAction,
        String deliveryMode,
        String method,
        String endpointUrl,
        Map<String, Object> headers,
        String bodyTemplate,
        String secret,
        String defaultReceiver,
        String ccReceiver,
        String smtpHost,
        Integer smtpPort,
        String smtpUsername,
        String smtpPassword,
        String smtpFrom,
        boolean smtpAuthEnabled,
        boolean smtpStarttlsEnabled,
        boolean smtpSslEnabled,
        String smtpSslTrust,
        String smsAccount,
        String smsToken,
        String smsPlainPassword,
        String smsMd5Password,
        boolean smsPasswordMd5,
        String smsReturnType,
        String smsExtendCode,
        int timeoutMs,
        int maxRetries,
        Map<String, Object> defaultTestPayload,
        Long createdAt,
        Long updatedAt
    ) {
    }
}
