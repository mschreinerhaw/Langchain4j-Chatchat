package com.chatchat.mcpserver.notification;

import com.chatchat.agents.protocol.ModelProtocolJson;

import com.chatchat.common.tool.ToolLogSummarizer;
import com.chatchat.mcpserver.audit.InvocationAuditService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationSendService {

    private static final Pattern TOKEN = Pattern.compile("\\{\\{\\s*([A-Za-z0-9_.-]+)\\s*}}");
    private static final Set<String> LEVELS = Set.of("INFO", "WARNING", "CRITICAL");

    private final ObjectMapper objectMapper;
    private final InvocationAuditService auditService;

    public NotificationSendResult send(NotificationChannelConfig config, Map<String, Object> arguments) {
        long startedAt = System.currentTimeMillis();
        Map<String, Object> normalized = normalizeArguments(config, arguments);
        NotificationSendResult result;
        try {
            if ("forbidden".equalsIgnoreCase(config.getRuntimeAction())) {
                result = failure(config, normalized, 0, 0, "Notification channel is forbidden");
            } else if ("SMTP".equalsIgnoreCase(config.getDeliveryMode())) {
                result = sendSmtp(config, normalized);
            } else {
                result = sendHttp(config, normalized);
            }
        } catch (Exception ex) {
            result = failure(config, normalized, 0, 1, ex.getMessage());
        }
        long durationMs = Math.max(0L, System.currentTimeMillis() - startedAt);
        auditService.recordNotificationCall(config, normalized, result, durationMs);
        if (result.success()) {
            log.info("Notification sent tool={} channel={} durationMs={} args={} response={}",
                config.getToolName(), config.getChannel(), durationMs,
                ToolLogSummarizer.summarize(normalized), ToolLogSummarizer.summarize(result.responseBody()));
        } else {
            log.warn("Notification failed tool={} channel={} durationMs={} error={} args={}",
                config.getToolName(), config.getChannel(), durationMs,
                result.errorMessage(), ToolLogSummarizer.summarize(normalized));
        }
        return result;
    }

    private NotificationSendResult sendSmtp(NotificationChannelConfig config, Map<String, Object> arguments) {
        requireText(config.getSmtpHost(), "SMTP host is required");
        requireText(config.getSmtpFrom(), "SMTP from address is required");
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(config.getSmtpHost());
        sender.setPort(config.getSmtpPort() == null ? 25 : config.getSmtpPort());
        if (config.isSmtpAuthEnabled()) {
            requireText(config.getSmtpUsername(), "SMTP username is required when auth is enabled");
            requireText(config.getSmtpPassword(), "SMTP password is required when auth is enabled");
            sender.setUsername(config.getSmtpUsername());
            sender.setPassword(config.getSmtpPassword());
        }
        Properties properties = sender.getJavaMailProperties();
        properties.put("mail.smtp.auth", String.valueOf(config.isSmtpAuthEnabled()));
        properties.put("mail.smtp.starttls.enable", String.valueOf(config.isSmtpStarttlsEnabled()));
        properties.put("mail.smtp.ssl.enable", String.valueOf(config.isSmtpSslEnabled()));
        if (config.getSmtpSslTrust() != null && !config.getSmtpSslTrust().isBlank()) {
            properties.put("mail.smtp.ssl.trust", config.getSmtpSslTrust().trim());
        }
        properties.put("mail.smtp.connectiontimeout", String.valueOf(config.getTimeoutMs()));
        properties.put("mail.smtp.timeout", String.valueOf(config.getTimeoutMs()));

        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(config.getSmtpFrom());
        message.setTo(splitReceivers(text(arguments, "receiver")));
        String[] cc = splitOptionalReceivers(config.getCcReceiver());
        if (cc.length > 0) {
            message.setCc(cc);
        }
        message.setSubject(text(arguments, "title"));
        message.setText(text(arguments, "content"));
        sender.send(message);

        return new NotificationSendResult(
            true,
            config.getChannel(),
            config.getToolName(),
            200,
            1,
            Map.of("message", "SMTP mail sent"),
            null,
            null,
            publicNotification(arguments)
        );
    }

    private NotificationSendResult sendHttp(NotificationChannelConfig config, Map<String, Object> arguments) throws IOException, InterruptedException {
        requireText(config.getEndpointUrl(), "Endpoint URL is required");
        int attempts = 0;
        NotificationSendResult last = null;
        int maxAttempts = Math.max(1, config.getMaxRetries() + 1);
        for (int index = 0; index < maxAttempts; index++) {
            attempts++;
            HttpRequest request = buildHttpRequest(config, arguments);
            HttpResponse<String> response = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(config.getTimeoutMs()))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build()
                .send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            Object body = parseBody(response.body());
            boolean success = response.statusCode() >= 200 && response.statusCode() < 300;
            last = new NotificationSendResult(
                success,
                config.getChannel(),
                config.getToolName(),
                response.statusCode(),
                attempts,
                body,
                response.body(),
                success ? null : "HTTP " + response.statusCode(),
                publicNotification(arguments)
            );
            if (success) {
                return last;
            }
        }
        return last == null ? failure(config, arguments, 0, attempts, "HTTP request failed") : last;
    }

    private HttpRequest buildHttpRequest(NotificationChannelConfig config, Map<String, Object> arguments) throws IOException {
        Map<String, Object> renderArgs = renderArguments(config, arguments);
        renderArgs.put("channelSecret", config.getSecret() == null ? "" : config.getSecret());
        String body = render(firstText(config.getBodyTemplate(), "{}"), renderArgs, false);
        HttpRequest.Builder builder = HttpRequest.newBuilder()
            .uri(URI.create(render(config.getEndpointUrl(), renderArgs, true)))
            .timeout(Duration.ofMillis(config.getTimeoutMs()))
            .method(config.getMethod(), HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        Map<String, String> headers = readHeaders(config.getHeadersJson(), renderArgs);
        headers.forEach(builder::header);
        if (headers.keySet().stream().noneMatch(name -> "content-type".equalsIgnoreCase(name))) {
            builder.header("Content-Type", "application/json");
        }
        return builder.build();
    }

    private Map<String, Object> normalizeArguments(NotificationChannelConfig config, Map<String, Object> arguments) {
        Map<String, Object> normalized = new LinkedHashMap<>(arguments == null ? Map.of() : arguments);
        requireText(text(normalized, "receiver"), "receiver is required");
        requireText(text(normalized, "title"), "title is required");
        requireText(text(normalized, "content"), "content is required");
        String level = text(normalized, "level");
        if (level == null || level.isBlank()) {
            level = "INFO";
        }
        level = level.trim().toUpperCase(Locale.ROOT);
        if (!LEVELS.contains(level)) {
            throw new IllegalArgumentException("level must be INFO, WARNING or CRITICAL");
        }
        normalized.put("level", level);
        normalized.putIfAbsent("sourceTaskId", "");
        return normalized;
    }

    private Map<String, Object> renderArguments(NotificationChannelConfig config, Map<String, Object> arguments) {
        Map<String, Object> renderArgs = new LinkedHashMap<>(arguments);
        renderArgs.put("ccReceiver", firstText(config.getCcReceiver(), ""));
        renderArgs.put("smsAccount", firstText(config.getSmsAccount(), ""));
        renderArgs.put("smsToken", firstText(config.getSmsToken(), firstText(config.getSecret(), "")));
        renderArgs.put("smsPlainPassword", firstText(config.getSmsPlainPassword(), ""));
        renderArgs.put("smsMd5Password", smsMd5Password(config));
        renderArgs.put("smsPasswordMd5", String.valueOf(config.isSmsPasswordMd5()));
        renderArgs.put("smsPassword", config.isSmsPasswordMd5()
            ? smsMd5Password(config)
            : firstText(config.getSmsPlainPassword(), ""));
        renderArgs.put("smsReturnType", firstText(config.getSmsReturnType(), "text"));
        renderArgs.put("smsExtendCode", firstText(config.getSmsExtendCode(), ""));
        return renderArgs;
    }

    private Map<String, String> readHeaders(String json, Map<String, Object> arguments) throws IOException {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        Map<String, Object> raw = objectMapper.readValue(json, new TypeReference<>() {});
        Map<String, String> headers = new LinkedHashMap<>();
        raw.forEach((key, value) -> {
            if (key != null && !key.isBlank() && value != null) {
                headers.put(key, render(String.valueOf(value), arguments, false));
            }
        });
        return headers;
    }

    private String render(String template, Map<String, Object> arguments, boolean urlMode) {
        if (template == null) {
            return "";
        }
        Matcher matcher = TOKEN.matcher(template);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            String key = matcher.group(1);
            Object value = arguments.get(key);
            String replacement = value == null ? "" : String.valueOf(value);
            if (!urlMode) {
                replacement = escapeJsonString(replacement);
            }
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    private Object parseBody(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        String trimmed = body.trim();
        if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) {
            return body;
        }
        try {
            return objectMapper.readValue(trimmed, Object.class);
        } catch (Exception ignored) {
            return body;
        }
    }

    private NotificationSendResult failure(NotificationChannelConfig config, Map<String, Object> arguments,
                                           int statusCode, int attempts, String errorMessage) {
        return new NotificationSendResult(
            false,
            config.getChannel(),
            config.getToolName(),
            statusCode,
            attempts,
            null,
            null,
            errorMessage,
            publicNotification(arguments)
        );
    }

    private Map<String, Object> publicNotification(Map<String, Object> arguments) {
        Map<String, Object> notification = new LinkedHashMap<>();
        notification.put("receiver", text(arguments, "receiver"));
        notification.put("title", text(arguments, "title"));
        notification.put("content", text(arguments, "content"));
        notification.put("level", text(arguments, "level"));
        notification.put("sourceTaskId", text(arguments, "sourceTaskId"));
        return notification;
    }

    private String[] splitReceivers(String receiver) {
        return Arrays.stream(requireText(receiver, "receiver is required").split("[,;，；\\s]+"))
            .map(String::trim)
            .filter(value -> !value.isBlank())
            .toArray(String[]::new);
    }

    private String[] splitOptionalReceivers(String receiver) {
        if (receiver == null || receiver.isBlank()) {
            return new String[0];
        }
        return Arrays.stream(receiver.split("[,;，；\\s]+"))
            .map(String::trim)
            .filter(value -> !value.isBlank())
            .toArray(String[]::new);
    }

    private String text(Map<String, Object> arguments, String key) {
        Object value = arguments == null ? null : arguments.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private String firstText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }

    private String smsMd5Password(NotificationChannelConfig config) {
        if (config.getSmsMd5Password() != null && !config.getSmsMd5Password().isBlank()) {
            return config.getSmsMd5Password().trim();
        }
        String plain = config.getSmsPlainPassword();
        if (plain == null || plain.isBlank()) {
            return "";
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            byte[] bytes = digest.digest(plain.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                builder.append(String.format("%02X", b));
            }
            return builder.toString();
        } catch (Exception ex) {
            return "";
        }
    }

    private String escapeJsonString(String value) {
        return ModelProtocolJson.jsonStringContent(value);
    }
}
