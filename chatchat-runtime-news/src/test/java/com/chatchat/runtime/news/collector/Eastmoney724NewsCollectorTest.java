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
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class Eastmoney724NewsCollectorTest {
    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    @Test
    void collectsItemsWithEvidenceAndLegalDeclaration() throws Exception {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/fast-news", exchange -> {
            String json = exchange.getRequestURI().getQuery().contains("sortEnd=900")
                ? "{\"code\":\"1\",\"message\":\"success\",\"data\":{\"sortEnd\":\"\",\"fastNewsList\":[]}}"
                : "{\"code\":\"1\",\"message\":\"success\",\"data\":{\"sortEnd\":\"900\",\"fastNewsList\":[{"
                    + "\"code\":\"202607213814045923\",\"realSort\":\"900\","
                    + "\"showTime\":\"2026-07-21 08:30:57\",\"title\":\"全球市场快讯\","
                    + "\"summary\":\"这是一条来自东方财富全球财经资讯直播的完整测试快讯。\","
                    + "\"stockList\":[\"1.600000\"]}]}}";
            write(exchange, json);
        });
        server.start();

        List<RawNewsItem> items = new ArrayList<>();
        Eastmoney724NewsCollector collector = new Eastmoney724NewsCollector(
            item -> { items.add(item); return NewsAcceptance.ACCEPTED; }, new ObjectMapper());
        NewsSource source = source(Map.of(
            "apiUrl", baseUrl(), "itemLimit", 1, "initialBackfillHours", 48,
            "legalRisk", true, "legalDisclaimer", "测试法律声明"));

        var result = collector.collect(source,
            new NewsCollectContext("test", Instant.parse("2026-07-21T01:00:00Z")));

        assertThat(result.failedCount()).isZero();
        assertThat(result.acceptedCount()).isOne();
        assertThat(result.nextCursor()).isEqualTo("202607213814045923:900");
        assertThat(items).singleElement().satisfies(item -> {
            assertThat(item.sourceUrl()).isEqualTo(
                "https://finance.eastmoney.com/a/202607213814045923.html");
            assertThat(item.publishTime()).isEqualTo(Instant.parse("2026-07-21T00:30:57Z"));
            assertThat(item.tags()).contains("东方财富", "7×24快讯", "法律风险");
            assertThat(item.metadata()).containsEntry("legalRisk", true)
                .containsEntry("legalDisclaimer", "测试法律声明")
                .containsEntry("newsCode", "202607213814045923");
        });
    }

    @Test
    void paginatesUntilSavedCursorAndKeepsNewestCheckpoint() throws Exception {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        AtomicReference<String> secondQuery = new AtomicReference<>();
        server.createContext("/fast-news", exchange -> {
            String query = exchange.getRequestURI().getQuery();
            if (query.contains("sortEnd=900")) {
                secondQuery.set(query);
                write(exchange, page("800", item("old", "800", "2026-07-21 08:00:00")));
            } else {
                write(exchange, page("900", item("new", "900", "2026-07-21 08:30:00")));
            }
        });
        server.start();

        List<RawNewsItem> items = new ArrayList<>();
        Eastmoney724NewsCollector collector = new Eastmoney724NewsCollector(
            item -> { items.add(item); return NewsAcceptance.ACCEPTED; }, new ObjectMapper());

        var result = collector.collect(source(Map.of(
                "apiUrl", baseUrl(), "itemLimit", 1, "maxPagesPerRun", 3)),
            new NewsCollectContext("resume", Instant.parse("2026-07-21T01:00:00Z"), "old:800"));

        assertThat(result.failedCount()).isZero();
        assertThat(items).extracting(RawNewsItem::title).containsExactly("new");
        assertThat(secondQuery.get()).contains("fastColumn=102", "sortEnd=900", "pageSize=1");
        assertThat(result.nextCursor()).isEqualTo("new:900");
    }

    private NewsSource source(Map<String, Object> configuration) {
        return new NewsSource(1L, "eastmoney_724", "东方财富全球财经资讯 7×24 小时直播",
            NewsSourceType.EASTMONEY_724, "https://kuaixun.eastmoney.com/", "eastmoney.com",
            Map.of(), configuration, true);
    }

    private String baseUrl() {
        return "http://localhost:" + server.getAddress().getPort() + "/fast-news";
    }

    private String page(String sortEnd, String item) {
        return "{\"code\":\"1\",\"message\":\"success\",\"data\":{\"sortEnd\":\""
            + sortEnd + "\",\"fastNewsList\":[" + item + "]}}";
    }

    private String item(String code, String sort, String time) {
        return "{\"code\":\"" + code + "\",\"realSort\":\"" + sort
            + "\",\"showTime\":\"" + time + "\",\"title\":\"" + code
            + "\",\"summary\":\"一条完整的东方财富测试快讯内容。\",\"stockList\":[]}";
    }

    private void write(com.sun.net.httpserver.HttpExchange exchange, String json) throws java.io.IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }
}
