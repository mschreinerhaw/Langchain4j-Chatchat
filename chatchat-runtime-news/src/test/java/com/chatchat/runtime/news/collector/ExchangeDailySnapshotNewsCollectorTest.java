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
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ExchangeDailySnapshotNewsCollectorTest {
    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    @Test
    void collectsSseOverviewBondAndQuoteSnapshots() throws Exception {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/query", exchange -> {
            String query = exchange.getRequestURI().getRawQuery();
            String body = query.contains("SSEBOND")
                ? """
                  {"result":[{"TRADE_DATE":"2026-07-21","TYPE":"债券合计","TYPE_CODE":"TOTAL","VOLUME":"12","AMOUNT":"345.6","AVG_PRICE":"99.8"}]}
                  """
                : """
                  {"result":[{"TRADE_DATE":"20260721","PRODUCT_NAME":"股票","SECURITY_NUM":"2350","TOTAL_VALUE":"644996.68","NEGO_VALUE":"601804.01","TOTAL_TRADE_AMT":"7619"}]}
                  """;
            respond(exchange, body);
        });
        server.createContext("/quotes/equity", exchange -> respond(exchange,
            """
            {"date":"2026-07-21","total":1,"list":[["600000","浦发银行",10,11,9,10.5,10.2,2.94,1000,10500,"T",0.3,19.61]]}
            """));
        server.start();

        String base = "http://localhost:" + server.getAddress().getPort();
        List<RawNewsItem> items = new ArrayList<>();
        var collector = collector(items);
        NewsSource source = source("SSE", base + "/market", Map.of(
            "sseQueryUrl", base + "/query", "bondPageUrl", base + "/bond",
            "quotePageUrl", base + "/quote", "quoteApiBaseUrl", base + "/quotes/",
            "quoteCategories", List.of("equity")));

        var result = collector.collect(source, new NewsCollectContext("sse-daily", Instant.now()));

        assertThat(result.failedCount()).isZero();
        assertThat(result.discoveredCount()).isEqualTo(3);
        assertThat(result.nextCursor()).isEqualTo("2026-07-21");
        assertThat(items).extracting(item -> item.metadata().get("datasetCode"))
            .containsExactly("market_statistics_daily", "bond_market_daily", "market_quote_daily");
        assertThat(items.get(2).metadata()).containsEntry("snapshotMode", "OVERWRITE_BY_EXCHANGE_TYPE_DATE_SECURITY");
        assertThat(items.get(2).metadata().get("quotes").toString()).contains("600000", "浦发银行", "10.5");
    }

    @Test
    void advancesSzseQuoteCursorToNextInstrumentAfterCompletedPage() throws Exception {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/report", exchange -> {
            String query = exchange.getRequestURI().getRawQuery();
            String body;
            if (query.contains("1803_sczm")) body = tab("tab1", "[{\"lbmc\":\"股票\",\"zqsl\":\"2800\",\"cjje\":\"1000\",\"sjzz\":\"2000\",\"ltsz\":\"1800\"}]");
            else if (query.contains("scsj_gprdgk_after")) body = tab("tab1", "[{\"zbmc\":\"成交金额\",\"xz\":\"600\",\"cy\":\"400\",\"gp\":\"1000\"}]");
            else if (query.contains("scsj_jjrdgk")) body = tab("tab1", "[{\"lbmc\":\"基金\",\"cjl\":\"20\",\"cjje\":\"30\"}]");
            else if (query.contains("scsj_zqrdgk")) body = tab("tab1", "[{\"lbmc\":\"债券合计\",\"cjje\":\"40\"}]");
            else body = tab("tab1", "[{\"jyrq\":\"2026-07-21\",\"zqdm\":\"000001\",\"zqjc\":\"平安银行\",\"qss\":\"10\",\"ks\":\"10.1\",\"zg\":\"10.5\",\"zd\":\"9.9\",\"ss\":\"10.3\",\"sdf\":\"3\",\"cjgs\":\"100\",\"cjje\":\"1000\",\"syl1\":\"8\"}]");
            respond(exchange, body);
        });
        server.start();

        String base = "http://localhost:" + server.getAddress().getPort();
        List<RawNewsItem> items = new ArrayList<>();
        NewsSource source = source("SZSE", base + "/market", Map.of(
            "szseReportApiUrl", base + "/report", "quotePageUrl", base + "/quote",
            "snapshotPagesPerRun", 1, "lookbackDays", 1));

        var result = collector(items).collect(source, new NewsCollectContext("szse-daily", Instant.now()));

        assertThat(result.failedCount()).isZero();
        assertThat(result.nextCursor()).isEqualTo("2026-07-22|1|1");
        assertThat(items).extracting(item -> item.metadata().get("datasetCode"))
            .contains("market_statistics_daily", "bond_market_daily", "market_quote_daily");
    }

    private ExchangeDailySnapshotNewsCollector collector(List<RawNewsItem> items) {
        NewsRuntimeProperties properties = new NewsRuntimeProperties();
        properties.setMaxItemsPerRun(500);
        return new ExchangeDailySnapshotNewsCollector(item -> {
            items.add(item);
            return NewsAcceptance.ACCEPTED;
        }, new ObjectMapper(), properties, HttpClient.newHttpClient());
    }

    private NewsSource source(String provider, String entryUrl, Map<String, Object> configuration) {
        Map<String, Object> config = new LinkedHashMap<>(configuration);
        config.put("provider", provider);
        config.put("providerName", provider);
        config.put("zoneId", "Asia/Shanghai");
        return new NewsSource(30L, provider.toLowerCase() + "_daily_snapshot", provider + "每日快照",
            NewsSourceType.EXCHANGE_DAILY_SNAPSHOT, entryUrl, "localhost", Map.of(), Map.copyOf(config), true);
    }

    private static String tab(String key, String rows) {
        return "[{\"metadata\":{\"tabkey\":\"" + key + "\",\"pagecount\":1},\"data\":" + rows + "}]";
    }

    private static void respond(HttpExchange exchange, String content) throws IOException {
        byte[] body = content.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }
}
