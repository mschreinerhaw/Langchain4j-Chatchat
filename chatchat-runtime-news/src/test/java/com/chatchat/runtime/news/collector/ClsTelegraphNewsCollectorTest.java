package com.chatchat.runtime.news.collector;

import com.chatchat.runtime.news.model.NewsCollectContext;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ClsTelegraphNewsCollectorTest {
    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    @Test
    void collectsTelegraphsAsIndividualModelReadyItems() throws Exception {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/api/cache", exchange -> {
            byte[] body = ("{\"errno\":0,\"data\":{\"roll_data\":[{\"id\":123,\"title\":\"重要快讯\","
                + "\"brief\":\"快讯摘要\",\"content\":\"这是一条用于模型分析的重要快讯正文，包含足够完整的事件信息。\","
                + "\"ctime\":1784260800,\"author\":\"财联社记者\",\"level\":\"A\",\"reading_num\":8,"
                + "\"subjects\":[{\"subject_name\":\"市场动态\"}] }]}}")
                .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        List<RawNewsItem> items = new ArrayList<>();
        ClsTelegraphNewsCollector collector = new ClsTelegraphNewsCollector(
            item -> { items.add(item); return NewsAcceptance.ACCEPTED; }, new ObjectMapper());
        String apiUrl = "http://localhost:" + server.getAddress().getPort() + "/api/cache";
        NewsSource source = new NewsSource(1L, "cls_telegraph", "财联社电报", NewsSourceType.CLS_TELEGRAPH,
            "https://www.cls.cn/telegraph", "cls.cn", Map.of(),
            Map.of("apiUrl", apiUrl, "itemLimit", 20, "timeoutMillis", 5000), true);

        var result = collector.collect(source, new NewsCollectContext("test", Instant.now()));

        assertThat(result.acceptedCount()).isOne();
        assertThat(result.failedCount()).isZero();
        assertThat(items).hasSize(1);
        assertThat(items.get(0).sourceUrl()).isEqualTo("https://www.cls.cn/detail/123");
        assertThat(items.get(0).tags()).containsExactly("市场动态");
        assertThat(items.get(0).content()).contains("重要快讯正文");
    }
}
