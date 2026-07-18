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

class SseAnnouncementCollectorTest {
    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    @Test
    void collectsDynamicAnnouncementFeedAndQueuesOfficialPdf() throws Exception {
        AtomicReference<String> query = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/security/stock/queryCompanyBulletin.do", exchange -> {
            query.set(exchange.getRequestURI().getRawQuery());
            byte[] body = """
                {"pageHelp":{"data":[{
                  "SECURITY_CODE":"600008","SECURITY_NAME":"首创环保",
                  "TITLE":"首创环保关于银行间债务融资工具注册申请获准的公告",
                  "SSEDATE":"2026-07-18","BULLETIN_TYPE":"其它",
                  "URL":"/disclosure/listedinfo/announcement/c/new/2026-07-18/600008_test.pdf"
                }]}}
                """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        String base = "http://localhost:" + server.getAddress().getPort();
        List<RawNewsItem> items = new ArrayList<>();
        SseAnnouncementCollector collector = new SseAnnouncementCollector(
            item -> { items.add(item); return NewsAcceptance.ACCEPTED; }, new ObjectMapper(),
            new NewsRuntimeProperties());
        NewsSource source = new NewsSource(11L, "sse_announcements", "上海证券交易所公告",
            NewsSourceType.SSE_ANNOUNCEMENTS, base + "/announcement/index.shtml", "localhost", Map.of(),
            Map.of("apiUrl", base + "/security/stock/queryCompanyBulletin.do", "staticBaseUrl", base,
                "itemLimit", 20, "lookbackDays", 2, "zoneId", "Asia/Shanghai", "language", "zh-CN"), true);

        var result = collector.collect(source,
            new NewsCollectContext("test", Instant.parse("2026-07-18T04:00:00Z")));

        assertThat(result.failedCount()).isZero();
        assertThat(result.discoveredCount()).isOne();
        assertThat(result.acceptedCount()).isOne();
        assertThat(query.get()).contains("beginDate=2026-07-16", "endDate=2026-07-18",
            "pageHelp.pageSize=20", "reportType=ALL");
        assertThat(items).singleElement().satisfies(item -> {
            assertThat(item.title()).contains("债务融资工具注册申请获准");
            assertThat(item.content()).contains("一级页面由上交所动态公告接口提供", "二级文档");
            assertThat(item.publishTime()).isEqualTo(Instant.parse("2026-07-17T16:00:00Z"));
            assertThat(item.metadata().get("transport")).isEqualTo("sse-company-bulletin-api");
            assertThat(item.metadata().get("attachmentUrls")).isEqualTo(
                List.of(base + "/disclosure/listedinfo/announcement/c/new/2026-07-18/600008_test.pdf"));
        });
    }
}
