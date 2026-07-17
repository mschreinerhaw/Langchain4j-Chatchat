package com.chatchat.runtime.news.collector;

import com.chatchat.runtime.news.config.NewsRuntimeProperties;
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

class CninfoAnnouncementCollectorTest {
    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    @Test
    void collectsStructuredAnnouncementsWithEvidenceAndPdfAttachment() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/new/hisAnnouncement/query", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = ("{\"announcements\":[{\"announcementId\":\"1225000001\","+
                "\"announcementTitle\":\"关于召开董事会会议的重要公告\",\"announcementTime\":1784282400000,"+
                "\"secCode\":\"000001\",\"secName\":\"平安银行\",\"orgId\":\"gssz0000001\","+
                "\"adjunctUrl\":\"finalpage/2026-07-17/1225000001.PDF\"}]}" )
                .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        String base = "http://localhost:" + server.getAddress().getPort();
        List<RawNewsItem> items = new ArrayList<>();
        CninfoAnnouncementCollector collector = new CninfoAnnouncementCollector(
            item -> { items.add(item); return NewsAcceptance.ACCEPTED; }, new ObjectMapper(),
            new NewsRuntimeProperties());
        NewsSource source = new NewsSource(8L, "cninfo_announcements", "巨潮资讯公告",
            NewsSourceType.CNINFO_ANNOUNCEMENTS, base + "/new/disclosure/list", "localhost", Map.of(),
            Map.of("apiUrl", base + "/new/hisAnnouncement/query", "staticBaseUrl", base + "/",
                "itemLimit", 20, "language", "zh-CN"), true);

        var result = collector.collect(source, new NewsCollectContext("test", Instant.now()));

        assertThat(result.failedCount()).isZero();
        assertThat(result.discoveredCount()).isOne();
        assertThat(result.acceptedCount()).isOne();
        assertThat(requestBody.get()).contains("pageNum=1", "pageSize=20", "column=szse");
        assertThat(items).singleElement().satisfies(item -> {
            assertThat(item.title()).isEqualTo("关于召开董事会会议的重要公告");
            assertThat(item.sourceUrl()).contains("/new/disclosure/detail", "announcementId=1225000001");
            assertThat(item.publishTime()).isEqualTo(Instant.ofEpochMilli(1784282400000L));
            assertThat(item.metadata().get("attachmentUrls")).isEqualTo(
                List.of(base + "/finalpage/2026-07-17/1225000001.PDF"));
            assertThat(item.metadata().get("evidenceUrl")).isNull();
        });
    }
}
