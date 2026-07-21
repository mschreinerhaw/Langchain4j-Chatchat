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
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class StructuredFlashNewsCollectorTest {
    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    @Test
    void mapsDeclarativeTemplateAndStopsAtNumericCheckpoint() throws Exception {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        AtomicReference<String> secondQuery = new AtomicReference<>();
        server.createContext("/flash", exchange -> {
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
        StructuredFlashNewsCollector collector = new StructuredFlashNewsCollector(
            item -> { items.add(item); return NewsAcceptance.ACCEPTED; }, new ObjectMapper(), List.of());

        var result = collector.collect(source(eastmoneyTemplate()),
            new NewsCollectContext("test", Instant.parse("2026-07-21T01:00:00Z"), "old:800"));

        assertThat(result.failedCount()).isZero();
        assertThat(result.nextCursor()).isEqualTo("new:900");
        assertThat(secondQuery.get()).contains("sortEnd=900", "pageSize=1", "req_trace=");
        assertThat(items).singleElement().satisfies(item -> {
            assertThat(item.title()).isEqualTo("new");
            assertThat(item.sourceUrl()).isEqualTo("https://finance.example/a/new.html");
            assertThat(item.publishTime()).isEqualTo(Instant.parse("2026-07-21T00:30:00Z"));
            assertThat(item.tags()).containsExactly("东方财富", "7×24快讯", "法律风险");
            assertThat(item.metadata()).containsEntry("newsCode", "new")
                .containsEntry("legalDisclaimer", "测试声明");
        });
    }

    @Test
    void usesWhitelistedClsSignerAndMapsNestedTags() throws Exception {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        AtomicReference<String> query = new AtomicReference<>();
        server.createContext("/roll", exchange -> {
            query.set(exchange.getRequestURI().getQuery());
            boolean history = query.get().contains("last_time=100");
            write(exchange, history ? emptyClsPage() : clsPage());
        });
        server.start();
        List<RawNewsItem> items = new ArrayList<>();
        StructuredFlashNewsCollector collector = new StructuredFlashNewsCollector(
            item -> { items.add(item); return NewsAcceptance.ACCEPTED; }, new ObjectMapper(),
            List.of(new ClsWebStructuredFlashRequestSigner()));

        var result = collector.collect(source(clsTemplate()),
            new NewsCollectContext("cls", Instant.ofEpochSecond(200)));

        assertThat(result.failedCount()).isZero();
        assertThat(result.nextCursor()).isEqualTo("10:100");
        assertThat(query.get()).contains("app=CailianpressWeb", "os=web", "sign=", "last_time=100");
        assertThat(items).singleElement().satisfies(item -> {
            assertThat(item.tags()).contains("市场动态");
            assertThat(item.author()).isEqualTo("财联社");
            assertThat(item.sourceUrl()).isEqualTo("https://www.cls.cn/detail/10");
        });
    }

    @Test
    void rejectsUnknownSignerWithoutSendingRequest() throws Exception {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        Map<String, Object> config = new java.util.LinkedHashMap<>(eastmoneyTemplate());
        config.put("request", Map.of("url", baseUrl("/flash"), "signer", "ARBITRARY_SCRIPT",
            "query", Map.of("cursor", "${cursor}")));
        StructuredFlashNewsCollector collector = new StructuredFlashNewsCollector(
            item -> NewsAcceptance.ACCEPTED, new ObjectMapper(), List.of());

        var result = collector.collect(source(config), new NewsCollectContext("invalid", Instant.now()));

        assertThat(result.failedCount()).isOne();
        assertThat(result.errorMessage()).contains("Unknown structured flash requestSigner");
    }

    @Test
    void supportsCompositePaginationNestedTagsAndStcnHeaderSigner() throws Exception {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        AtomicReference<String> secondQuery = new AtomicReference<>();
        AtomicReference<String> firstQuery = new AtomicReference<>();
        AtomicReference<String> signedHeader = new AtomicReference<>();
        server.createContext("/stcn", exchange -> {
            signedHeader.set(exchange.getRequestHeaders().getFirst("STCN-TIMESTAMP"));
            String query = exchange.getRequestURI().getRawQuery();
            if (query.contains("page_time=2") && query.contains("last_time=100")) {
                secondQuery.set(query);
                write(exchange, "{\"state\":1,\"data\":[" + stcnItem("old", "100")
                    + "],\"page_time\":3,\"last_time\":50}");
            } else {
                firstQuery.set(query);
                write(exchange, "{\"state\":1,\"data\":[" + stcnItem("new", "200")
                    + "],\"page_time\":2,\"last_time\":100}");
            }
        });
        server.start();
        List<RawNewsItem> items = new ArrayList<>();
        StructuredFlashNewsCollector collector = new StructuredFlashNewsCollector(
            item -> { items.add(item); return NewsAcceptance.ACCEPTED; }, new ObjectMapper(),
            List.of(new StcnWebStructuredFlashRequestSigner()));

        var result = collector.collect(source(stcnTemplate()),
            new NewsCollectContext("stcn", Instant.ofEpochSecond(300), "old:100"));

        assertThat(result.failedCount()).isZero();
        assertThat(result.nextCursor()).isEqualTo("new:200");
        assertThat(firstQuery.get()).isEqualTo("type=kx");
        assertThat(secondQuery.get()).isNotNull();
        assertThat(signedHeader.get()).isNotBlank();
        assertThat(Base64.getDecoder().decode(signedHeader.get())).hasSize(128);
        assertThat(items).singleElement().satisfies(item -> {
            assertThat(item.sourceUrl()).isEqualTo("https://www.stcn.com/article/detail/new.html");
            assertThat(item.tags()).contains("证券时报", "宏观", "测试股份");
            assertThat(item.author()).isEqualTo("人民财讯");
        });
    }

    @Test
    void skipsLockedItemsAndBuildsNextStateFromLastItem() throws Exception {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        AtomicReference<String> secondQuery = new AtomicReference<>();
        AtomicReference<String> appId = new AtomicReference<>();
        server.createContext("/jin10", exchange -> {
            appId.set(exchange.getRequestHeaders().getFirst("x-app-id"));
            String query = exchange.getRequestURI().getRawQuery();
            if (query.contains("max_time=2026-07-21+08%3A59%3A00")) {
                secondQuery.set(query);
                write(exchange, "{\"status\":200,\"data\":[" + jin10Item("250", "08:59:00", true)
                    + "," + jin10Item("200", "08:58:00", false) + "]}");
            } else {
                write(exchange, "{\"status\":200,\"data\":[" + jin10Item("300", "09:00:00", false)
                    + "," + jin10Item("250", "08:59:00", true) + "]}");
            }
        });
        server.start();
        List<RawNewsItem> items = new ArrayList<>();
        StructuredFlashNewsCollector collector = new StructuredFlashNewsCollector(
            item -> { items.add(item); return NewsAcceptance.ACCEPTED; }, new ObjectMapper(), List.of());

        var result = collector.collect(source(jin10Template()),
            new NewsCollectContext("jin10", Instant.parse("2026-07-21T02:00:00Z"), "200:200"));

        assertThat(result.failedCount()).isZero();
        assertThat(result.rejectedCount()).isZero();
        assertThat(result.nextCursor()).isEqualTo("300:300");
        assertThat(secondQuery.get()).isNotNull();
        assertThat(appId.get()).isEqualTo("test-app");
        assertThat(items).singleElement().satisfies(item -> {
            assertThat(item.title()).contains("金十快讯 300");
            assertThat(item.sourceUrl()).isEqualTo("https://flash.jin10.com/detail/300");
            assertThat(item.tags()).contains("金十数据", "法律风险");
        });
    }

    @Test
    void scansEntireSinglePageSnapshotRegardlessOfSavedCursorOrSortOrder() throws Exception {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        AtomicInteger requests = new AtomicInteger();
        server.createContext("/important", exchange -> {
            requests.incrementAndGet();
            write(exchange, "{\"status\":200,\"data\":["
                + snapshotItem("300", "09:00:00") + ","
                + snapshotItem("100", "07:00:00") + ","
                + snapshotItem("250", "08:30:00") + "]}");
        });
        server.start();
        List<RawNewsItem> items = new ArrayList<>();
        StructuredFlashNewsCollector collector = new StructuredFlashNewsCollector(
            item -> { items.add(item); return NewsAcceptance.ACCEPTED; }, new ObjectMapper(), List.of());

        var result = collector.collect(source(snapshotTemplate()),
            new NewsCollectContext("important", Instant.parse("2026-07-21T02:00:00Z"), "100:100"));

        assertThat(result.failedCount()).isZero();
        assertThat(result.nextCursor()).isEqualTo("100:100");
        assertThat(requests.get()).isOne();
        assertThat(items).extracting(RawNewsItem::title)
            .containsExactly("重要事件 300", "重要事件 100", "重要事件 250");
    }

    private Map<String, Object> eastmoneyTemplate() {
        return Map.ofEntries(
            Map.entry("request", Map.of("url", baseUrl("/flash"), "signer", "NONE", "query",
                Map.of("sortEnd", "${cursor}", "pageSize", "${pageSize}", "req_trace", "${timestamp}"))),
            Map.entry("response", Map.of("successPath", "code", "successValue", "1",
                "itemsPath", "data.items", "nextCursorPath", "data.sortEnd")),
            Map.entry("mapping", Map.ofEntries(Map.entry("id", "code"), Map.entry("cursor", "sort"),
                Map.entry("title", "title"), Map.entry("content", "summary"), Map.entry("summary", "summary"),
                Map.entry("publishTime", "showTime"), Map.entry("publishTimeFormat", "yyyy-MM-dd HH:mm:ss"),
                Map.entry("sourceUrl", "https://finance.example/a/${id}.html"),
                Map.entry("metadata", Map.of("newsCode", "code")))),
            Map.entry("compliance", Map.of("categories", List.of("全球财经快讯"),
                "tags", List.of("东方财富", "7×24快讯"), "legalRisk", true,
                "legalDisclaimer", "测试声明")),
            Map.entry("itemLimit", 1), Map.entry("maxPagesPerRun", 3),
            Map.entry("initialBackfillHours", 24), Map.entry("zoneId", "Asia/Shanghai"));
    }

    private Map<String, Object> clsTemplate() {
        return Map.ofEntries(
            Map.entry("request", Map.of("url", baseUrl("/roll"), "signer", "CLS_WEB", "query",
                Map.of("refresh_type", "1", "rn", "${pageSize}", "last_time", "${cursor}"))),
            Map.entry("response", Map.of("successPath", "errno", "successValue", "0",
                "itemsPath", "data.roll_data")),
            Map.entry("mapping", Map.ofEntries(Map.entry("id", "id"), Map.entry("cursor", "ctime"),
                Map.entry("title", List.of("title", "content")), Map.entry("content", "content"),
                Map.entry("publishTime", "ctime"), Map.entry("publishTimeFormat", "EPOCH_SECONDS"),
                Map.entry("defaultAuthor", "财联社"), Map.entry("sourceUrl", "https://www.cls.cn/detail/${id}"),
                Map.entry("tagPaths", List.of("subjects.subject_name")))),
            Map.entry("compliance", Map.of("categories", List.of("财联社电报"), "tags", List.of())),
            Map.entry("initialCursor", "0"), Map.entry("itemLimit", 1),
            Map.entry("maxPagesPerRun", 3), Map.entry("initialBackfillHours", 1));
    }

    private Map<String, Object> stcnTemplate() {
        return Map.ofEntries(
            Map.entry("request", Map.of("url", baseUrl("/stcn"), "signer", "STCN_WEB",
                "omitBlankQueryParameters", true,
                "query", Map.of("type", "kx", "page_time", "${state.page}",
                    "last_time", "${state.cursor}"))),
            Map.entry("response", Map.of("successPath", "state", "successValue", "1",
                "itemsPath", "data", "nextState", Map.of("page", "page_time", "cursor", "last_time"))),
            Map.entry("mapping", Map.ofEntries(Map.entry("id", "id"), Map.entry("cursor", "show_time"),
                Map.entry("title", "title"), Map.entry("content", "content"), Map.entry("author", "source"),
                Map.entry("publishTime", "time"), Map.entry("publishTimeFormat", "EPOCH_MILLIS"),
                Map.entry("sourceUrl", "${share_url}"), Map.entry("tagPaths", List.of("tags.name")))),
            Map.entry("compliance", Map.of("categories", List.of("证券时报快讯"),
                "tags", List.of("证券时报"))),
            Map.entry("initialState", Map.of("page", "", "cursor", "")),
            Map.entry("maxPagesPerRun", 3), Map.entry("initialBackfillHours", 1));
    }

    private Map<String, Object> jin10Template() {
        return Map.ofEntries(
            Map.entry("request", Map.of("url", baseUrl("/jin10"), "signer", "NONE",
                "omitBlankQueryParameters", true, "headers", Map.of("x-app-id", "test-app"),
                "query", Map.of("channel", "-8200", "max_time", "${state.cursor}"))),
            Map.entry("response", Map.of("successPath", "status", "successValue", "200",
                "itemsPath", "data", "nextStateFromLastItem", Map.of("cursor", "time"))),
            Map.entry("mapping", Map.ofEntries(Map.entry("id", "id"), Map.entry("cursor", "id"),
                Map.entry("title", List.of("data.title", "data.content")),
                Map.entry("content", List.of("data.content", "data.title")),
                Map.entry("publishTime", "time"), Map.entry("publishTimeFormat", "yyyy-MM-dd HH:mm:ss"),
                Map.entry("defaultAuthor", "金十数据"),
                Map.entry("sourceUrl", "https://flash.jin10.com/detail/${id}"),
                Map.entry("skipWhen", Map.of("data.lock", "true")))),
            Map.entry("compliance", Map.of("categories", List.of("市场快讯"),
                "tags", List.of("金十数据"), "legalRisk", true)),
            Map.entry("initialState", Map.of("cursor", "")), Map.entry("maxPagesPerRun", 3),
            Map.entry("initialBackfillHours", 24), Map.entry("numericCursorBoundary", true));
    }

    private Map<String, Object> snapshotTemplate() {
        return Map.ofEntries(
            Map.entry("snapshotMode", true),
            Map.entry("request", Map.of("url", baseUrl("/important"), "signer", "NONE")),
            Map.entry("response", Map.of("successPath", "status", "successValue", "200", "itemsPath", "data")),
            Map.entry("mapping", Map.ofEntries(Map.entry("id", "item_id"), Map.entry("cursor", "item_id"),
                Map.entry("title", "data.data.title"), Map.entry("content", "data.data.content"),
                Map.entry("publishTime", "data.time"), Map.entry("publishTimeFormat", "yyyy-MM-dd HH:mm:ss"),
                Map.entry("sourceUrl", "https://flash.jin10.com/detail/${id}?from=important_news"))),
            Map.entry("compliance", Map.of("categories", List.of("重要事件"), "tags", List.of("金十数据"))),
            Map.entry("maxPagesPerRun", 1), Map.entry("initialBackfillHours", 1));
    }

    private NewsSource source(Map<String, Object> configuration) {
        return new NewsSource(1L, "template", "结构化快讯", NewsSourceType.STRUCTURED_FLASH,
            "https://example.com/flash", "example.com", Map.of(), configuration, true);
    }

    private String baseUrl(String path) {
        return "http://localhost:" + server.getAddress().getPort() + path;
    }

    private String page(String sortEnd, String item) {
        return "{\"code\":\"1\",\"data\":{\"sortEnd\":\"" + sortEnd + "\",\"items\":[" + item + "]}}";
    }

    private String item(String code, String sort, String time) {
        return "{\"code\":\"" + code + "\",\"sort\":\"" + sort + "\",\"showTime\":\""
            + time + "\",\"title\":\"" + code + "\",\"summary\":\"一条完整的测试快讯内容。\"}";
    }

    private String clsPage() {
        return "{\"errno\":0,\"data\":{\"roll_data\":[{\"id\":10,\"ctime\":100,"
            + "\"content\":\"财联社结构化模板测试快讯\",\"subjects\":[{\"subject_name\":\"市场动态\"}]}]}}";
    }

    private String emptyClsPage() {
        return "{\"errno\":0,\"data\":{\"roll_data\":[]}}";
    }

    private String stcnItem(String id, String cursor) {
        return "{\"id\":\"" + id + "\",\"show_time\":\"" + cursor
            + "\",\"time\":" + cursor + "000,\"title\":\"" + id
            + "\",\"content\":\"证券时报结构化模板测试快讯\",\"source\":\"人民财讯\","
            + "\"share_url\":\"https://www.stcn.com/article/detail/" + id
            + ".html\",\"tags\":[[{\"name\":\"宏观\"}],[{\"name\":\"测试股份\"}]]}";
    }

    private String jin10Item(String id, String hms, boolean locked) {
        return "{\"id\":\"" + id + "\",\"time\":\"2026-07-21 " + hms
            + "\",\"data\":{\"title\":\"\",\"content\":\"<b>金十快讯 " + id
            + "</b>\",\"lock\":" + locked + "}}";
    }

    private String snapshotItem(String id, String hms) {
        return "{\"item_id\":\"" + id + "\",\"data\":{\"time\":\"2026-07-21 " + hms
            + "\",\"data\":{\"title\":\"重要事件 " + id
            + "\",\"content\":\"金十数据重要事件测试正文\"}}}";
    }

    private void write(com.sun.net.httpserver.HttpExchange exchange, String json) throws java.io.IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }
}
