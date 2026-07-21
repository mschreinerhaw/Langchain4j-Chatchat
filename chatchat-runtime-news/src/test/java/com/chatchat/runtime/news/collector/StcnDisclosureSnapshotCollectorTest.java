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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class StcnDisclosureSnapshotCollectorTest {
    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    @Test
    void collectsOnlyTodayFromEachFirstPageAndMergesOverlappingSections() throws Exception {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        AtomicInteger requests = new AtomicInteger();
        AtomicReference<String> signature = new AtomicReference<>();
        server.createContext("/xinpi/list-ajax.html", exchange -> {
            requests.incrementAndGet();
            signature.set(exchange.getRequestHeaders().getFirst("STCN-TIMESTAMP"));
            String query = exchange.getRequestURI().getQuery();
            assertThat(query).contains("page=1", "pageSize=20");
            if (query.contains("type=sse")) {
                write(exchange, page(item("1", "共同公告", "http://xp.stcn.com/index/pdf?id=1", "2026-07-21"),
                    item("old", "昨日公告", "https://xp.stcn.com/index/pdf?id=old", "2026-07-20")));
            } else if (query.contains("type=hgt")) {
                write(exchange, page(item("1", "共同公告", "https://xp.stcn.com/index/pdf?id=1", "2026-07-21"),
                    item("2", "港股公告", "https://www1.hkexnews.hk/report.pdf", "2026-07-21 07:59")));
            } else {
                write(exchange, page(item("old2", "北交所昨日公告",
                    "https://xp.stcn.com/index/pdf?id=old2", "2026-07-20")));
            }
        });
        server.start();
        List<RawNewsItem> items = new ArrayList<>();
        StcnDisclosureSnapshotCollector collector = new StcnDisclosureSnapshotCollector(
            item -> { items.add(item); return NewsAcceptance.ACCEPTED; }, new ObjectMapper(),
            new NewsRuntimeProperties(), new StcnWebStructuredFlashRequestSigner());

        var result = collector.collect(source(),
            new NewsCollectContext("stcn-disclosures", Instant.parse("2026-07-21T01:00:00Z")));

        assertThat(result.failedCount()).isZero();
        assertThat(result.discoveredCount()).isEqualTo(2);
        assertThat(requests.get()).isEqualTo(3);
        assertThat(signature.get()).isNotBlank();
        assertThat(items).extracting(RawNewsItem::title).containsExactly("共同公告", "港股公告");
        assertThat(items.get(0).sourceUrl()).startsWith("https://xp.stcn.com/");
        assertThat(items.get(0).tags()).contains("沪市主板", "沪股通", "法律风险");
        assertThat(items.get(0).metadata().get("sections")).asList().containsExactly("沪市主板", "沪股通");
        assertThat(items.get(0).metadata()).containsEntry("firstPageOnly", true)
            .containsEntry("collectionDate", "2026-07-21")
            .containsEntry("legalRisk", true);
        assertThat(items.get(1).publishTime()).isEqualTo(Instant.parse("2026-07-20T23:59:00Z"));
    }

    private NewsSource source() {
        return new NewsSource(1L, "stcn_disclosures_today", "证券时报信息披露",
            NewsSourceType.STCN_DISCLOSURES, "https://www.stcn.com/xinpi/list.html?type=sse", "stcn.com",
            Map.of(), Map.ofEntries(
                Map.entry("apiUrl", "http://localhost:" + server.getAddress().getPort() + "/xinpi/list-ajax.html"),
                Map.entry("pageSize", 20),
                Map.entry("sections", List.of(Map.of("type", "sse", "name", "沪市主板"),
                    Map.of("type", "hgt", "name", "沪股通"), Map.of("type", "bse", "name", "北交所"))),
                Map.entry("attachmentAllowedDomains", List.of("xp.stcn.com", "hkexnews.hk")),
                Map.entry("legalDisclaimer", "测试法律声明"), Map.entry("zoneId", "Asia/Shanghai")), true);
    }

    private String item(String id, String title, String url, String time) {
        return "{\"id\":\"" + id + "\",\"title\":\"" + title + "\",\"url\":\""
            + url + "\",\"time\":\"" + time + "\"}";
    }

    private String page(String... items) {
        return "{\"state\":1,\"total\":20,\"data\":[" + String.join(",", items) + "]}";
    }

    private void write(com.sun.net.httpserver.HttpExchange exchange, String json) throws java.io.IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }
}
