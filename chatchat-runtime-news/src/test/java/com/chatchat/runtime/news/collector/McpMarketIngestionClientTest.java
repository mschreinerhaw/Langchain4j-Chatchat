package com.chatchat.runtime.news.collector;

import com.chatchat.common.security.InternalCredentialProperties;
import com.chatchat.runtime.news.model.NewsSource;
import com.chatchat.runtime.news.model.NewsSourceType;
import com.chatchat.runtime.news.model.RawNewsItem;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class McpMarketIngestionClientTest {
    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    @Test
    void rejectsBusinessFailureReturnedWithHttp200() throws Exception {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/internal/v1/market/observations", exchange -> {
            byte[] body = "{\"code\":500,\"message\":\"row size too large\",\"data\":null}"
                .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        InternalCredentialProperties credentials = new InternalCredentialProperties();
        credentials.setSecret("test-secret");
        var client = new McpMarketIngestionClient(new ObjectMapper().findAndRegisterModules(), credentials,
            "http://localhost:" + server.getAddress().getPort());

        assertThatThrownBy(() -> client.accept(observation()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("MCP Market capability failed: HTTP 200, code=500, message=row size too large");
    }

    private RawNewsItem observation() {
        var source = new NewsSource(1L, "sse_home", "上海证券交易所首页", NewsSourceType.EXCHANGE_HOME,
            "https://www.sse.com.cn/", "sse.com.cn", Map.of(), Map.of(), true);
        return new RawNewsItem(source, "行情", "行情", null, "上交所",
            "https://www.sse.com.cn/?chatchatSnapshot=market", Instant.now(), "zh-CN",
            List.of("市场行情"), List.of("行情"), Map.of("datasetCode", "market_quote_daily"));
    }
}
