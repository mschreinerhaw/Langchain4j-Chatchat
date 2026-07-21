package com.chatchat.runtime.news.collector;

import com.chatchat.runtime.news.config.NewsRuntimeProperties;
import com.chatchat.runtime.news.model.NewsCollectContext;
import com.chatchat.runtime.news.model.NewsSource;
import com.chatchat.runtime.news.model.NewsSourceType;
import com.chatchat.runtime.news.model.RawNewsItem;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class HkexHomeNewsCollectorTest {
    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    @Test
    void collectsOfficialRssChannelsAndCurrentListedCompanyDisclosures() throws Exception {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/rss/news", exchange -> rss(exchange, "香港交易所新闻稿", "新闻稿标题"));
        server.createContext("/rss/regulatory", exchange -> rss(exchange, "监管公告", "监管公告标题"));
        server.createContext("/rss/market", exchange -> rss(exchange, "市场通讯", "市场通讯标题"));
        server.createContext("/disclosures", exchange -> respond(exchange, "application/json", """
            {"genDate":"1784599984660","newsInfoLst":[{"newsId":12250444,
            "lTxt":"公告及通告 - [內幕消息]","title":"上市公司公告标题","ext":"pdf","size":"304KB",
            "webPath":"/listedco/2026/notice_c.pdf","market":"SEHK",
            "stock":[{"sc":"00388","sn":"香港交易所"}],"relTime":"21/07/2026 07:59"}]}
            """));
        server.start();
        String base = "http://localhost:" + server.getAddress().getPort();
        List<RawNewsItem> items = new ArrayList<>();
        HkexHomeNewsCollector collector = new HkexHomeNewsCollector(
            item -> { items.add(item); return NewsAcceptance.ACCEPTED; },
            new ObjectMapper(), new NewsRuntimeProperties());
        NewsSource source = new NewsSource(8L, "hkex_home", "香港交易所首页", NewsSourceType.HKEX_HOME,
            base + "/", "localhost", Map.of(), Map.of(
                "rssFeeds", List.of(
                    Map.of("category", "港交所新闻稿", "url", base + "/rss/news"),
                    Map.of("category", "港交所监管公告", "url", base + "/rss/regulatory"),
                    Map.of("category", "港交所市场通讯", "url", base + "/rss/market")),
                "disclosureUrl", base + "/disclosures", "disclosureBaseUrl", base,
                "attachmentAllowedDomains", List.of("localhost"), "zoneId", "Asia/Hong_Kong"), true);

        var result = collector.collect(source, new NewsCollectContext("test", Instant.now()));

        assertThat(result.failedCount()).isZero();
        assertThat(result.discoveredCount()).isEqualTo(4);
        assertThat(result.acceptedCount()).isEqualTo(4);
        assertThat(items).extracting(item -> item.categories().get(0)).containsExactly(
            "港交所新闻稿", "港交所监管公告", "港交所市场通讯", "港交所上市公司公告");
        assertThat(items.get(3).content()).contains("00388 香港交易所", "內幕消息", "304KB");
        assertThat(items.get(3).metadata().get("attachmentUrls"))
            .isEqualTo(List.of(base + "/listedco/2026/notice_c.pdf"));
        assertThat(items.get(3).publishTime()).isNotNull();
    }

    private void rss(HttpExchange exchange, String channel, String title) throws IOException {
        String base = "http://localhost:" + server.getAddress().getPort();
        respond(exchange, "application/rss+xml", """
            <rss version="2.0"><channel><title>%s</title><language>zh-HK</language>
            <item><guid>item-1</guid><link>%s/detail/1</link><title>%s</title>
            <description><![CDATA[<p>%s详细摘要</p>]]></description>
            <pubDate>Tue, 21 Jul 2026 08:30:00 +0800</pubDate></item></channel></rss>
            """.formatted(channel, base, title, title));
    }

    private static void respond(HttpExchange exchange, String contentType, String content) throws IOException {
        byte[] body = content.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType + "; charset=UTF-8");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }
}
