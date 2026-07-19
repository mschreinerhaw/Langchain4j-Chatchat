package com.chatchat.integration.mcp.service;

import com.chatchat.common.security.InternalCredentialProperties;
import com.chatchat.integration.mcp.config.McpCenterProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/** Reads enabled MCP notification channels and dispatches pre-authorized scheduler notifications. */
@Service
@RequiredArgsConstructor
public class McpNotificationClient {

    private static final String OPTIONS_PATH = "/api/v1/notifications/enabled-options";

    private final McpCenterProperties properties;
    private final InternalCredentialProperties internalCredentialProperties;
    private final WebClient webClient = WebClient.builder().build();
    private volatile CachedToken cachedToken;

    public List<NotificationChannelOption> listEnabled() {
        if (!properties.isEnabled()) {
            throw new IllegalStateException("MCP center integration is disabled");
        }
        try {
            return requestOptions();
        } catch (WebClientResponseException.Unauthorized ex) {
            cachedToken = null;
            return requestOptions();
        }
    }

    public NotificationChannelOption requireEnabled(String id) {
        String normalized = text(id);
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("完成后通知已启用，请选择通知类型");
        }
        return listEnabled().stream()
            .filter(option -> normalized.equals(option.id()))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("所选通知类型不存在或已停用，请重新选择"));
    }

    public void dispatch(String id, Map<String, Object> payload) {
        if (!properties.isEnabled()) {
            throw new IllegalStateException("MCP center integration is disabled");
        }
        try {
            requestDispatch(id, payload);
        } catch (WebClientResponseException.Unauthorized ex) {
            cachedToken = null;
            requestDispatch(id, payload);
        }
    }

    private List<NotificationChannelOption> requestOptions() {
        Map<String, Object> response = block(webClient.get()
            .uri(buildUrl(OPTIONS_PATH))
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken())
            .retrieve()
            .bodyToMono(new ParameterizedTypeReference<>() {}));
        Object data = unwrapData(response);
        if (!(data instanceof List<?> rows)) {
            return List.of();
        }
        return rows.stream().filter(Map.class::isInstance).map(Map.class::cast).map(row ->
            new NotificationChannelOption(
                text(row.get("id")), text(row.get("channel")), text(row.get("toolName")),
                text(row.get("title")), text(row.get("description")), text(row.get("deliveryMode")),
                Boolean.TRUE.equals(row.get("recipientAware"))
            )).filter(option -> !option.id().isBlank()).toList();
    }

    private void requestDispatch(String id, Map<String, Object> payload) {
        Map<String, Object> response = block(webClient.post()
            .uri(buildUrl("/api/v1/notifications/" + text(id) + "/dispatch"))
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken())
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(payload == null ? Map.of() : payload)
            .retrieve()
            .bodyToMono(new ParameterizedTypeReference<>() {}));
        Object data = unwrapData(response);
        if (data instanceof Map<?, ?> result && Boolean.FALSE.equals(result.get("success"))) {
            throw new IllegalStateException("MCP通知发送失败: " + text(result.get("errorMessage")));
        }
    }

    private synchronized String adminToken() {
        long now = System.currentTimeMillis();
        if (cachedToken != null && cachedToken.expiresAt() - 30_000 > now) {
            return cachedToken.value();
        }
        Map<String, Object> response = block(webClient.post()
            .uri(buildUrl(properties.getAdminLoginPath()))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of(
                "username", properties.resolvedAdminUsername(internalCredentialProperties),
                "password", properties.resolvedAdminPassword(internalCredentialProperties)
            ))
            .retrieve()
            .bodyToMono(new ParameterizedTypeReference<>() {}));
        Object rawData = unwrapData(response);
        if (!(rawData instanceof Map<?, ?> data)) {
            throw new IllegalStateException("MCP center login did not return data");
        }
        String token = text(data.get("token"));
        if (token.isBlank()) {
            throw new IllegalStateException("MCP center login did not return token");
        }
        long expiresAt = data.get("expiresAt") instanceof Number number
            ? number.longValue() : now + 5 * 60_000L;
        cachedToken = new CachedToken(token, expiresAt);
        return token;
    }

    private Object unwrapData(Map<String, Object> response) {
        if (response == null) {
            throw new IllegalStateException("MCP通知接口返回为空");
        }
        Object code = response.get("code");
        if (code instanceof Number number && number.intValue() != 200) {
            throw new IllegalStateException(text(response.get("message")));
        }
        return response.get("data");
    }

    private <T> T block(reactor.core.publisher.Mono<T> mono) {
        int timeoutMs = properties.getTimeoutMs();
        return timeoutMs <= 0 ? mono.block() : mono.timeout(Duration.ofMillis(Math.max(1000, timeoutMs))).block();
    }

    private String buildUrl(String path) {
        String base = properties.getBaseUrl();
        base = (base == null || base.isBlank() ? "http://localhost:8090" : base.trim()).replaceAll("/+$", "");
        return base + (path.startsWith("/") ? path : "/" + path);
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    public record NotificationChannelOption(
        String id,
        String channel,
        String toolName,
        String title,
        String description,
        String deliveryMode,
        boolean recipientAware
    ) {
    }

    private record CachedToken(String value, long expiresAt) {
    }
}
