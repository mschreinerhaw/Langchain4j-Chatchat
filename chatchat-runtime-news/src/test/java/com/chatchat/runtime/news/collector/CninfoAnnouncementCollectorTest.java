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
import java.util.concurrent.CopyOnWriteArrayList;

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

    @Test
    void collectsCurrentImportantAnnouncementsWithoutTimeWindow() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        Instant triggeredAt = Instant.parse("2026-07-20T01:30:00Z");
        long recent = Instant.parse("2026-07-20T00:30:00Z").toEpochMilli();
        long old = Instant.parse("2026-07-19T19:00:00Z").toEpochMilli();
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/new/disclosure", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = ("{\"classifiedAnnouncements\":[["
                + item("1", "重要公告", recent, true) + ","
                + item("2", "普通公告", recent, false) + ","
                + item("3", "较早重要公告", old, true) + "]]}").getBytes(StandardCharsets.UTF_8);
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
        NewsSource source = new NewsSource(9L, "cninfo_important", "巨潮深市重要公告",
            NewsSourceType.CNINFO_ANNOUNCEMENTS, base + "/notice", "localhost", Map.of(),
            Map.of("apiUrl", base + "/new/disclosure", "staticBaseUrl", base + "/",
                "column", "szse_latest", "importantOnly", true,
                "itemLimit", 100, "zoneId", "Asia/Shanghai"), true);

        var result = collector.collect(source, new NewsCollectContext("recent", triggeredAt));

        assertThat(result.failedCount()).isZero();
        assertThat(result.discoveredCount()).isEqualTo(2);
        assertThat(result.acceptedCount()).isEqualTo(2);
        assertThat(requestBody.get()).isEqualTo(
            "column=szse_latest&pageNum=1&pageSize=100&sortName=&sortType=&clusterFlag=true");
        assertThat(items).extracting(RawNewsItem::title).containsExactly("重要公告", "较早重要公告");
        assertThat(items).allSatisfy(item -> {
            assertThat(item.categories()).containsExactly("上市公司公告", "重要公告");
            assertThat(item.metadata()).containsEntry("important", true);
        });
    }

    @Test
    void collectsLatestPageForEveryConfiguredSectionAndClassifiesItems() throws Exception {
        List<String> requestBodies = new CopyOnWriteArrayList<>();
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/new/disclosure", exchange -> {
            String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requestBodies.add(request);
            String title = request.contains("column=fund_latest") ? "基金公告" : "沪主板公告";
            String id = request.contains("column=fund_latest") ? "fund-1" : "sse-main-1";
            byte[] body = ("{\"classifiedAnnouncements\":[[" +
                item(id, title, Instant.parse("2026-07-20T02:00:00Z").toEpochMilli(), false) + "]]}")
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
        NewsSource source = new NewsSource(10L, "cninfo_sections", "巨潮分板块最新公告",
            NewsSourceType.CNINFO_ANNOUNCEMENTS, base + "/notice", "localhost", Map.of(),
            Map.of("apiUrl", base + "/new/disclosure", "staticBaseUrl", base + "/", "itemLimit", 1,
                "columns", List.of(
                    Map.of("column", "sse_main_latest", "category", "沪主板公告", "section", "沪主板"),
                    Map.of("column", "fund_latest", "category", "基金公告", "section", "基金"))), true);

        var result = collector.collect(source,
            new NewsCollectContext("sections", Instant.parse("2026-07-20T03:00:00Z")));

        assertThat(result.failedCount()).isZero();
        assertThat(result.acceptedCount()).isEqualTo(2);
        assertThat(requestBodies).containsExactly(
            "column=sse_main_latest&pageNum=1&pageSize=1&sortName=&sortType=&clusterFlag=true",
            "column=fund_latest&pageNum=1&pageSize=1&sortName=&sortType=&clusterFlag=true");
        assertThat(items).extracting(RawNewsItem::categories)
            .containsExactly(List.of("沪主板公告"), List.of("基金公告"));
        assertThat(items).extracting(item -> item.metadata().get("section"))
            .containsExactly("沪主板", "基金");
    }

    private String item(String id, String title, long time, boolean important) {
        return "{\"announcementId\":\"" + id + "\",\"announcementTitle\":\"" + title
            + "\",\"announcementTime\":" + time + ",\"secCode\":\"000001\","
            + "\"secName\":\"测试公司\",\"orgId\":\"gssz1\",\"important\":" + important
            + ",\"announcementTypeName\":\"其他\",\"adjunctUrl\":\"finalpage/" + id + ".PDF\"}";
    }
}
