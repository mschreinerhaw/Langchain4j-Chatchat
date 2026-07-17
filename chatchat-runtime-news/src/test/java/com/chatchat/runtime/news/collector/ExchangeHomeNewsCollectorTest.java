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

class ExchangeHomeNewsCollectorTest {
    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    @Test
    void collectsHomepageHeadlinesAndMarketSnapshotAsSeparateDocuments() throws Exception {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        serve("/home", "text/html; charset=UTF-8", "<ul class='news'><li><a href='/a'>交易所重要新闻一</a></li>"
            + "<li><a href='/b'>交易所重要新闻二</a></li></ul>");
        serve("/market/000001", "application/json; charset=UTF-8",
            "{\"code\":\"000001\",\"date\":20260717,\"time\":113000,\"snap\":[\"上证指数\",3882,3865,3869,3806,3818,-63,-1.64,381381835,744239719925]}");
        server.start();
        int port = server.getAddress().getPort();
        String base = "http://localhost:" + port;
        List<RawNewsItem> accepted = new ArrayList<>();
        NewsItemSink sink = item -> { accepted.add(item); return NewsAcceptance.ACCEPTED; };
        ExchangeHomeNewsCollector collector = new ExchangeHomeNewsCollector(sink, new ObjectMapper());
        NewsSource source = new NewsSource(1L, "sse_home", "上海证券交易所首页", NewsSourceType.EXCHANGE_HOME,
            base + "/home", "localhost", Map.of(), Map.of("provider", "SSE", "headlineSelector", ".news a",
                "headlineLimit", 10, "marketUrlTemplate", base + "/market/{code}", "marketCodes", List.of("000001"),
                "language", "zh-CN", "timeoutMillis", 5000), true);

        var result = collector.collect(source, new NewsCollectContext("test", Instant.now()));

        assertThat(result.failedCount()).isZero();
        assertThat(result.acceptedCount()).isEqualTo(2);
        assertThat(accepted).extracting(RawNewsItem::title)
            .containsExactly("上海证券交易所首页要闻快照", "上海证券交易所首页当日行情快照");
        assertThat(accepted.get(0).content()).contains("交易所重要新闻一", base + "/a");
        assertThat(accepted.get(1).content()).contains("上证指数", "3818", "-1.64%", "744239719925");
    }

    @Test
    void collectsNewsHomepageWithoutRequiringMarketConfiguration() throws Exception {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        serve("/home", "text/html; charset=UTF-8",
            "<a class='notice' href='/new/disclosure/detail?id=1'>上市公司重要公告</a>");
        server.start();
        String base = "http://localhost:" + server.getAddress().getPort();
        List<RawNewsItem> accepted = new ArrayList<>();
        ExchangeHomeNewsCollector collector = new ExchangeHomeNewsCollector(
            item -> { accepted.add(item); return NewsAcceptance.ACCEPTED; }, new ObjectMapper());
        NewsSource source = new NewsSource(2L, "cninfo_home", "巨潮资讯首页", NewsSourceType.NEWS_HOME,
            base + "/home", "localhost", Map.of(), Map.of("provider", "CNINFO",
                "headlineSelector", "a.notice", "headlineLimit", 10, "timeoutMillis", 5000), true);

        var result = collector.collect(source, new NewsCollectContext("test", Instant.now()));

        assertThat(result.failedCount()).isZero();
        assertThat(result.acceptedCount()).isOne();
        assertThat(accepted).singleElement().satisfies(item -> {
            assertThat(item.title()).isEqualTo("巨潮资讯首页要闻快照");
            assertThat(item.content()).contains("上市公司重要公告");
        });
    }

    private void serve(String path, String contentType, String body) {
        server.createContext(path, exchange -> {
            byte[] data = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.sendResponseHeaders(200, data.length);
            exchange.getResponseBody().write(data);
            exchange.close();
        });
    }
}
