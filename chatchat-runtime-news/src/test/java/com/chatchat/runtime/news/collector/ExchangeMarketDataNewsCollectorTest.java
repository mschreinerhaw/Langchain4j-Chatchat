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
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ExchangeMarketDataNewsCollectorTest {
    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    @Test
    void collectsSseMarginDividendAndBonusRows() throws Exception {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/margin", exchange -> {
            String query = exchange.getRequestURI().getRawQuery();
            respond(exchange, query.contains("RZRQ_MX_INFO") ? """
                {"result":[{"opDate":"20260720","stockCode":"600000","securityAbbr":"浦发银行",
                "rzye":100,"rzmre":20,"rzche":10,"rqyl":5,"rqmcl":2,"rqchl":1}]}
                """ : """
                {"result":[{"opDate":"20260720","rzye":1000,"rzmre":200,"rzche":100,
                "rqyl":50,"rqylje":80,"rqmcl":20,"rzrqjyzl":1080}]}
                """, StandardCharsets.UTF_8);
        });
        server.createContext("/distribution", exchange -> {
            String query = exchange.getRequestURI().getRawQuery();
            respond(exchange, query.contains("SSGSFHQK") ? """
                {"result":[{"A_STOCK_CODE":"600000","COMPANY_ABBR":"浦发银行","A_BEFR_TAX_DIV":0.3,
                "A_AFTR_TAX_DIV":0.27,"A_REG_DATE":"2026-07-18","A_DIV_DATE":"2026-07-20"}]}
                """ : """
                {"result":[{"A_STOCK_CODE":"600001","COMPANY_ABBR":"示例股份","BONUS_RATIO":2,
                "CONVERT_RATIO":3,"A_REG_DATE":"2026-07-19","A_DERIGHTS_DATE":"2026-07-20"}]}
                """, StandardCharsets.UTF_8);
        });
        server.start();
        String base = "http://localhost:" + server.getAddress().getPort();
        List<RawNewsItem> items = new ArrayList<>();
        var collector = collector(items);
        NewsSource source = source("SSE", base + "/page", Map.of(
            "marginApiUrl", base + "/margin", "distributionApiUrl", base + "/distribution",
            "dividendPageUrl", base + "/dividend", "bonusPageUrl", base + "/bonus",
            "marginSummaryLimit", 1, "marginDetailLimit", 1, "distributionLimit", 1,
            "legalRisk", true, "zoneId", "Asia/Shanghai"));

        var result = collector.collect(source, new NewsCollectContext("sse-test", Instant.now()));

        assertThat(result.failedCount()).isZero();
        assertThat(result.discoveredCount()).isEqualTo(4);
        assertThat(items).extracting(RawNewsItem::title).contains(
            "上交所融资融券汇总 2026-07-20", "浦发银行（600000）融资融券明细 2026-07-20",
            "上交所现金分红：浦发银行（600000）", "上交所送股转增：示例股份（600001）");
        assertThat(items).allSatisfy(item -> assertThat(item.metadata())
            .containsEntry("provider", "SSE").containsEntry("legalRisk", true));
        assertThat(items.get(1).metadata()).containsEntry("securityCode", "600000").containsEntry("rzye", 100L)
            .containsEntry("financingBalanceCny", 100L).containsEntry("amountUnit", "CNY");
    }

    @Test
    void collectsSzseMarginAndLatestMonthlyDistributionTable() throws Exception {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/margin", exchange -> respond(exchange, """
            [{"metadata":{"tabkey":"tab1","subname":"2026-07-20"},"data":[
              {"jrrzmr":"1.2","jrrzye":"13.2","jrrjmc":"0.1","jrrjyl":"0.2","jrrjye":"0.3","jrrzrjye":"13.5"}]},
             {"metadata":{"tabkey":"tab2","subname":"2026-07-20"},"data":[
              {"zqdm":"000001","zqjc":"平安银行","jrrzmr":"1.0","jrrzye":"49.0","jrrjmc":"9.0","jrrjyl":"177.0","jrrjye":"1954","jrrzrjye":"49.5"}]}]
            """, StandardCharsets.UTF_8));
        server.createContext("/article", exchange -> respond(exchange,
            "<html><a href='/table'>分红派息配股</a></html>", StandardCharsets.UTF_8));
        server.createContext("/table", exchange -> respond(exchange, """
            <html><table><tr><td>000333</td><td>美的集团</td><td>0</td><td>0.000</td>
            <td>26457843547</td><td>3.800</td><td></td><td></td><td></td><td></td>
            <td>2026/06/29</td><td>2026/06/26</td><td>74.830</td><td>78.550</td></tr></table></html>
            """, Charset.forName("GBK")));
        server.start();
        String base = "http://localhost:" + server.getAddress().getPort();
        List<RawNewsItem> items = new ArrayList<>();
        var collector = collector(items);
        NewsSource source = source("SZSE", base + "/page", Map.of(
            "marginApiUrl", base + "/margin", "monthlyArticleUrl", base + "/article",
            "marginDetailPages", 1, "distributionLimit", 10, "monthlyTableCharset", "GBK",
            "legalRisk", true, "zoneId", "Asia/Shanghai"));

        var result = collector.collect(source, new NewsCollectContext("szse-test", Instant.now()));

        assertThat(result.failedCount()).isZero();
        assertThat(result.discoveredCount()).isEqualTo(3);
        assertThat(items).extracting(RawNewsItem::title).containsExactly(
            "深交所融资融券汇总 2026-07-20", "平安银行（000001）融资融券明细 2026-07-20",
            "深交所分红送股：美的集团（000333）");
        assertThat(items.get(2).metadata()).containsEntry("bonusPerShare", "0.000")
            .containsEntry("cashDividendPerShare", "3.800").containsEntry("recordDate", "2026/06/26");
        assertThat(items.get(1).metadata()).containsEntry("financingBalance100MCny", "49.0")
            .containsEntry("securitiesLendingBalance10KCny", "1954");
    }

    @Test
    void paginatesAllSseEtfScaleRowsWithSavedDateAndPageCursor() throws Exception {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/fund-scale", exchange -> {
            boolean second = exchange.getRequestURI().getRawQuery().contains("pageHelp.pageNo=2");
            respond(exchange, second ? """
                {"pageHelp":{"pageCount":2},"result":[{"STAT_DATE":"2026-07-20","SEC_CODE":"510020",
                "SEC_NAME":"超大ETF","ETF_TYPE":"单市","TOT_VOL":"3584.8"}]}
                """ : """
                {"pageHelp":{"pageCount":2},"result":[{"STAT_DATE":"2026-07-20","SEC_CODE":"510010",
                "SEC_NAME":"治理ETF","ETF_TYPE":"单市","TOT_VOL":"13152.44"}]}
                """, StandardCharsets.UTF_8);
        });
        server.start();
        String base = "http://localhost:" + server.getAddress().getPort();
        List<RawNewsItem> items = new ArrayList<>();
        var collector = collector(items, 1);
        NewsSource source = source("SSE", base + "/page", Map.of(
            "mode", "FUND_SCALE", "fundScaleApiUrl", base + "/fund-scale", "fundScalePageSize", 1,
            "legalRisk", true, "zoneId", "Asia/Shanghai"));

        var first = collector.collect(source, new NewsCollectContext("sse-fund-1", Instant.now()));
        var second = collector.collect(source, new NewsCollectContext("sse-fund-2", Instant.now(), first.nextCursor()));

        assertThat(first.nextCursor()).isEqualTo("2026-07-20:2");
        assertThat(second.nextCursor()).isEqualTo("2026-07-20:complete");
        assertThat(items).extracting(RawNewsItem::title).containsExactly(
            "上交所ETF规模：治理ETF（510010）", "上交所ETF规模：超大ETF（510020）");
        assertThat(items.get(0).metadata()).containsEntry("fundScale10KUnits", "13152.44")
            .containsEntry("scaleUnit", "10K-fund-units");
    }

    @Test
    void paginatesSzseEtfScaleRows() throws Exception {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/fund-scale", exchange -> {
            boolean second = exchange.getRequestURI().getRawQuery().contains("PAGENO=2");
            String code = second ? "159003" : "159001";
            String name = second ? "招商快线ETF" : "货币ETF易方达";
            respond(exchange, "[{\"metadata\":{\"tabkey\":\"tab1\",\"pagecount\":2},\"data\":[{"
                + "\"size_date\":\"2026-07-20\",\"fund_code\":\"" + code + "\","
                + "\"security_short_name\":\"" + name + "\",\"current_size\":\"2,013.78\"}]}]",
                StandardCharsets.UTF_8);
        });
        server.start();
        String base = "http://localhost:" + server.getAddress().getPort();
        List<RawNewsItem> items = new ArrayList<>();
        var collector = collector(items, 1);
        NewsSource source = source("SZSE", base + "/page", Map.of(
            "mode", "FUND_SCALE", "fundScaleApiUrl", base + "/fund-scale", "fundScaleLookbackDays", 1,
            "legalRisk", true, "zoneId", "Asia/Shanghai"));

        var first = collector.collect(source, new NewsCollectContext("szse-fund-1", Instant.now()));
        var second = collector.collect(source, new NewsCollectContext("szse-fund-2", Instant.now(), first.nextCursor()));

        assertThat(first.nextCursor()).endsWith(":2");
        assertThat(second.nextCursor()).endsWith(":complete");
        assertThat(items).extracting(RawNewsItem::title).containsExactly(
            "深交所ETF规模：货币ETF易方达（159001）", "深交所ETF规模：招商快线ETF（159003）");
    }

    @Test
    void collectsComparableHongKongShanghaiAndShenzhenMarketHighlights() throws Exception {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/highlights", exchange -> respond(exchange, """
            {"title":"市场概况","MaxRefDate":"07/20/2026","data":[
              {"td":[[""],["香港交易所 (20/07/2026  )"],["上海证券交易所 (20/07/2026  )"],["深圳证券交易所 (20/07/2026  )"]]},
              {"td":[[""],["主板","创业板"],["A 股","B 股"],["A 股","B 股"]]},
              {"td":[["上市公司總數 (家)"],["2,457","306"],["1,698","41"],["2,892","38"]]},
              {"td":[["上市證券總數 (隻)"],["17,284","307"],["n.a.","n.a."],["n.a.","n.a."]]},
              {"td":[["總市值(億元)"],["HKD 455,355","HKD 653"],["RMB 504,552","RMB 989"],["RMB 419,889","RMB 360"]]},
              {"td":[["總流通市值(億元)"],["n.a.","n.a."],["RMB 485,558","RMB 688"],["RMB 358,256","RMB 359"]]},
              {"td":[["平均市盈率(倍)"],["12.45","29.12"],["13.44","10.23"],["29.11","9.33"]]},
              {"td":[["總成交股數(百萬股)"],["433,451","124"],["63,877","26"],["77,265","23"]]},
              {"td":[["總成交金額(百萬元)"],["HKD 306,413","HKD 53"],["RMB 850,089","RMB 103"],["RMB 1,408,136","RMB 103"]]},
              {"td":[["市場總成交金額(百萬元)"],["HKD 306,466"],["RMB 1,940,769"],["RMB 1,408,239"]]}
            ]}
            """, StandardCharsets.UTF_8));
        server.start();
        String base = "http://localhost:" + server.getAddress().getPort();
        List<RawNewsItem> items = new ArrayList<>();
        var collector = collector(items);
        NewsSource source = source("HKEX", base + "/page", Map.of(
            "mode", "THREE_MARKET_OVERVIEW", "marketHighlightsApiUrl", base + "/highlights",
            "legalRisk", true, "zoneId", "Asia/Hong_Kong"));

        var result = collector.collect(source, new NewsCollectContext("three-market-test", Instant.now()));

        assertThat(result.failedCount()).isZero();
        assertThat(result.discoveredCount()).isEqualTo(3);
        assertThat(result.nextCursor()).isEqualTo("2026-07-20");
        assertThat(items).extracting(RawNewsItem::title).containsExactly(
            "香港交易所市场汇总 2026-07-20", "上海证券交易所市场汇总 2026-07-20", "深圳证券交易所市场汇总 2026-07-20");
        assertThat(items).allSatisfy(item -> assertThat(item.metadata())
            .containsEntry("dataset", "沪深港市场汇总")
            .containsEntry("tradeDate", "2026-07-20")
            .containsEntry("legalRisk", true)
            .containsKeys("segments", "rawMetrics", "amountUnits"));
        assertThat(items.get(0).metadata().get("segments").toString())
            .contains("segment=主板", "marketCapitalization100MCurrency=HKD 455,355", "averagePeRatio=12.45");
        assertThat(items.get(2).metadata().get("segments").toString())
            .contains("segment=A 股", "turnoverMillionCurrency=RMB 1,408,136");
    }

    private ExchangeMarketDataNewsCollector collector(List<RawNewsItem> items) {
        return collector(items, 500);
    }

    private ExchangeMarketDataNewsCollector collector(List<RawNewsItem> items, int maxItems) {
        NewsRuntimeProperties properties = new NewsRuntimeProperties();
        properties.setMaxItemsPerRun(maxItems);
        return new ExchangeMarketDataNewsCollector(item -> {
            items.add(item);
            return NewsAcceptance.ACCEPTED;
        }, new ObjectMapper(), properties, HttpClient.newHttpClient());
    }

    private NewsSource source(String provider, String entryUrl, Map<String, Object> configuration) {
        var config = new java.util.LinkedHashMap<String, Object>(configuration);
        config.put("provider", provider);
        config.put("providerName", "SSE".equals(provider) ? "上海证券交易所" : "深圳证券交易所");
        return new NewsSource(20L, provider.toLowerCase() + "_market_data", provider + "市场表现数据",
            NewsSourceType.EXCHANGE_MARKET_DATA, entryUrl, "localhost", Map.of(), Map.copyOf(config), true);
    }

    private static void respond(HttpExchange exchange, String content, Charset charset) throws IOException {
        byte[] body = content.getBytes(charset);
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=" + charset.name());
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }
}
