package com.chatchat.integration.mcp.service;

import com.chatchat.common.security.InternalCredentialProperties;
import com.chatchat.integration.mcp.config.McpCenterProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.time.LocalDate;
import java.util.Map;

/** Calls the trading-calendar decision endpoint maintained by the MCP server. */
@Service
@RequiredArgsConstructor
public class McpTradingCalendarClient {

    private static final String CHECK_PATH = "/api/v1/dynamic-date-params/trading-calendar/check";

    private final McpCenterProperties properties;
    private final InternalCredentialProperties internalCredentialProperties;
    private final WebClient webClient = WebClient.builder().build();
    private volatile CachedToken cachedToken;

    public TradingDayResult check(LocalDate date) {
        if (!properties.isEnabled()) {
            throw new IllegalStateException("MCP center integration is disabled");
        }
        try {
            return requestCheck(date);
        } catch (WebClientResponseException.Unauthorized ex) {
            cachedToken = null;
            return requestCheck(date);
        }
    }

    private TradingDayResult requestCheck(LocalDate date) {
        Object raw = block(webClient.get()
            .uri(buildUrl(CHECK_PATH) + "?date=" + date)
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken())
            .retrieve()
            .bodyToMono(Object.class));
        Map<?, ?> data = unwrapData(raw);
        Object tradingDay = data.get("tradingDay");
        if (!(tradingDay instanceof Boolean value)) {
            throw new IllegalStateException("MCP交易日接口返回为空或缺少 tradingDay 字段");
        }
        return new TradingDayResult(value, text(data.get("message")));
    }

    private synchronized String adminToken() {
        long now = System.currentTimeMillis();
        if (cachedToken != null && cachedToken.expiresAt() - 30_000 > now) {
            return cachedToken.value();
        }
        Object raw = block(webClient.post()
            .uri(buildUrl(properties.getAdminLoginPath()))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of(
                "username", properties.resolvedAdminUsername(internalCredentialProperties),
                "password", properties.resolvedAdminPassword(internalCredentialProperties)
            ))
            .retrieve()
            .bodyToMono(Object.class));
        Map<?, ?> data = unwrapData(raw);
        String token = text(data.get("token"));
        if (token.isBlank()) {
            throw new IllegalStateException("MCP center login did not return token");
        }
        long expiresAt = number(data.get("expiresAt"), now + 5 * 60_000L);
        cachedToken = new CachedToken(token, expiresAt);
        return token;
    }

    private Map<?, ?> unwrapData(Object raw) {
        if (!(raw instanceof Map<?, ?> response)) {
            throw new IllegalStateException("MCP交易日接口返回为空");
        }
        Object code = response.get("code");
        if (code instanceof Number number && number.intValue() != 200) {
            throw new IllegalStateException(text(response.get("message")));
        }
        Object data = response.get("data");
        if (!(data instanceof Map<?, ?> map) || map.isEmpty()) {
            throw new IllegalStateException("MCP交易日接口返回为空");
        }
        return map;
    }

    private Object block(reactor.core.publisher.Mono<Object> mono) {
        int timeoutMs = properties.getTimeoutMs();
        return timeoutMs <= 0 ? mono.block() : mono.timeout(Duration.ofMillis(Math.max(1000, timeoutMs))).block();
    }

    private String buildUrl(String path) {
        String base = normalizedBaseUrl();
        String suffix = path == null ? "" : path.trim();
        return base + (suffix.startsWith("/") ? suffix : "/" + suffix);
    }

    private String normalizedBaseUrl() {
        String base = properties.getBaseUrl();
        return (base == null || base.isBlank() ? "http://localhost:8090" : base.trim()).replaceAll("/+$", "");
    }

    private long number(Object value, long fallback) {
        return value instanceof Number number ? number.longValue() : fallback;
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    public record TradingDayResult(boolean tradingDay, String message) {
    }

    private record CachedToken(String value, long expiresAt) {
    }
}
