package com.chatchat.runtime.news.compliance;

import com.chatchat.runtime.news.config.NewsRuntimeProperties;
import com.chatchat.runtime.news.model.NewsSource;
import com.chatchat.runtime.news.model.NewsSourceType;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RobotsTxtComplianceServiceTest {
    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    @Test
    void usesLongestMatchingRuleAndAllowsMoreSpecificPath() throws Exception {
        start(200, """
            User-agent: *
            Disallow: /private/
            Allow: /private/public$
            """);

        RobotsComplianceReport result = service().checkUrl(base() + "/private/public");

        assertThat(result.allowed()).isTrue();
        assertThat(result.status()).isEqualTo("ALLOWED");
        assertThat(result.matchedRule()).isEqualTo("/private/public$");
    }

    @Test
    void blocksDisallowedPathWithActionableMessage() throws Exception {
        start(200, """
            User-agent: ChatChat-NewsCollector
            Disallow: /private/
            """);

        RobotsComplianceReport result = service().checkUrl(base() + "/private/document.html");

        assertThat(result.allowed()).isFalse();
        assertThat(result.status()).isEqualTo("DISALLOWED");
        assertThat(result.message()).contains("机器人协议检测未通过", "Disallow: /private/", "/robots.txt");
    }

    @Test
    void treatsMissingRobotsFileAsNoDeclaredRestriction() throws Exception {
        start(404, "missing");

        RobotsComplianceReport result = service().checkUrl(base() + "/news");

        assertThat(result.allowed()).isTrue();
        assertThat(result.status()).isEqualTo("NOT_FOUND");
        assertThat(result.httpStatus()).isEqualTo(404);
    }

    @Test
    void failsClosedWhenRobotsFileIsUnavailable() throws Exception {
        start(503, "temporarily unavailable");

        RobotsComplianceReport result = service().checkUrl(base() + "/news");

        assertThat(result.allowed()).isFalse();
        assertThat(result.status()).isEqualTo("HTTP_ERROR");
        assertThat(result.message()).contains("HTTP 503", "采集已停止");
    }

    @Test
    void checksConfiguredApiUrlsInAdditionToEntryUrl() throws Exception {
        start(200, "User-agent: *\nDisallow: /api/private\n");
        NewsSource source = new NewsSource(1L, "test", "测试", NewsSourceType.API, base() + "/news",
            "localhost", Map.of(), Map.of("apiUrl", base() + "/api/private"), true);

        RobotsComplianceReport result = service().check(source);

        assertThat(result.allowed()).isFalse();
        assertThat(result.targetUrl()).endsWith("/api/private");
        assertThat(result.checkedUrlCount()).isEqualTo(2);
    }

    @Test
    void permitsDocumentedTemporaryOverrideWhilePreservingOriginalFailure() throws Exception {
        start(200, "User-agent: *\nDisallow: /private\n");
        NewsSource source = new NewsSource(1L, "test", "测试", NewsSourceType.API, base() + "/private",
            "localhost", Map.of(), Map.of(
                "robotsPolicyOverride", true,
                "robotsPolicyOverrideReason", "业务负责人已确认临时授权依据",
                "robotsPolicyOverrideUntil", Instant.now().plusSeconds(3600).toString()), true);

        RobotsComplianceReport result = service().check(source);

        assertThat(result.allowed()).isTrue();
        assertThat(result.status()).isEqualTo("OVERRIDDEN");
        assertThat(result.overridden()).isTrue();
        assertThat(result.overrideReason()).contains("临时授权依据");
        assertThat(result.message()).contains("原始检测结果", "Disallow: /private");
    }

    private RobotsTxtComplianceService service() {
        NewsRuntimeProperties.Robots properties = new NewsRuntimeProperties.Robots();
        properties.setCacheTtlMillis(0);
        return new RobotsTxtComplianceService(properties,
            HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build());
    }

    private void start(int status, String body) throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/robots.txt", exchange -> respond(exchange, status, body));
        server.start();
    }

    private String base() {
        return "http://localhost:" + server.getAddress().getPort();
    }

    private static void respond(HttpExchange exchange, int status, String content) throws IOException {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=UTF-8");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
