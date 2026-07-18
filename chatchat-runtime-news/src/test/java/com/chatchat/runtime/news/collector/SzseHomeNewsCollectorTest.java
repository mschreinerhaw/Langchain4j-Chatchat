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
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class SzseHomeNewsCollectorTest {
    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    @Test
    void collectsHomepageNewsExchangeNoticesAndListedCompanyPdf() throws Exception {
        AtomicReference<String> announcementRequest = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/index/index.html", exchange -> respond(exchange, "text/html", """
            <div class="homem-news-wrap"><h3 class="title"><a href="/news/1">深交所要闻标题</a></h3></div>
            """));
        server.createContext("/news/1", exchange -> respond(exchange, "text/html",
            "<div class=\"article-body\">深交所要闻二级页面完整正文内容，长度足够用于入库。</div>"));
        server.createContext("/disclosure/notice/index.json", exchange -> respond(exchange, "application/json", """
            {"data":[{"title":"深交所公告标题","url":"/notice/1.html","jsonPath":"/notice/1.json","pubTime":1784282400000}]}
            """));
        server.createContext("/notice/1.json", exchange -> respond(exchange, "application/json", """
            {"data":{"content":"<p>深交所公告二级页面正式内容，来自官方详情 JSON。</p>"}}
            """));
        server.createContext("/new/hisAnnouncement/query", exchange -> {
            announcementRequest.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, "application/json", """
                {"announcements":[{"announcementId":"123","announcementTitle":"上市公司公告标题",
                "announcementTime":1784282400000,"secCode":"000001","secName":"平安银行",
                "orgId":"gssz0000001","adjunctUrl":"finalpage/2026/notice.PDF"}]}
                """);
        });
        server.start();
        String base = "http://localhost:" + server.getAddress().getPort();
        List<RawNewsItem> items = new ArrayList<>();
        SzseHomeNewsCollector collector = new SzseHomeNewsCollector(
            item -> { items.add(item); return NewsAcceptance.ACCEPTED; }, new ObjectMapper(), new NewsRuntimeProperties());
        NewsSource source = new NewsSource(2L, "szse_home", "深圳证券交易所首页", NewsSourceType.SZSE_HOME,
            base + "/index/index.html", "localhost", Map.of(), Map.of(
                "noticeIndexUrl", base + "/disclosure/notice/index.json",
                "announcementApiUrl", base + "/new/hisAnnouncement/query",
                "announcementStaticBaseUrl", base + "/", "cninfoBaseUrl", base,
                "newsUrlContains", "/news/", "language", "zh-CN"), true);

        var result = collector.collect(source, new NewsCollectContext("test", Instant.now()));

        assertThat(result.failedCount()).isZero();
        assertThat(result.discoveredCount()).isEqualTo(3);
        assertThat(result.acceptedCount()).isEqualTo(3);
        assertThat(announcementRequest.get()).contains("column=szse", "pageNum=1");
        assertThat(items).extracting(item -> item.categories().get(0))
            .containsExactly("深交所要闻", "深交所公告", "上市公司公告");
        assertThat(items.get(0).content()).contains("二级页面完整正文");
        assertThat(items.get(1).content()).contains("官方详情 JSON");
        assertThat(items.get(2).metadata().get("attachmentUrls"))
            .isEqualTo(List.of(base + "/finalpage/2026/notice.PDF"));
    }

    private static void respond(HttpExchange exchange, String contentType, String content) throws IOException {
        byte[] body = content.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType + "; charset=UTF-8");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }
}
