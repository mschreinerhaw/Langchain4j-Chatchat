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

import static org.assertj.core.api.Assertions.assertThat;

class ExchangeHomeNewsCollectorTest {
    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    @Test
    void collectsHomepageHeadlinesAndMarketSnapshotAsSeparateDocuments() throws Exception {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        serve("/home", "text/html; charset=UTF-8", "<ul class='news'><li><a href='/a'>交易所重要新闻一</a></li>"
            + "<li><a href='/b'>交易所重要新闻二</a></li></ul>");
        serve("/market/000001", "application/json; charset=UTF-8",
            "{\"code\":\"000001\",\"date\":20260717,\"time\":113000,\"snap\":[\"上证指数\",3882,3865,3869,3806,3818,-63,-1.64,381381835,744239719925]}");
        server.start();
        int port = server.getAddress().getPort();
        String base = "http://localhost:" + port;
        List<RawNewsItem> accepted = new ArrayList<>();
        NewsItemSink sink = item -> { accepted.add(item); return NewsAcceptance.ACCEPTED; };
        ExchangeHomeNewsCollector collector = new ExchangeHomeNewsCollector(sink, new ObjectMapper());
        NewsSource source = new NewsSource(1L, "sse_home", "上海证券交易所首页", NewsSourceType.EXCHANGE_HOME,
            base + "/home", "localhost", Map.of(), Map.of("provider", "SSE", "headlineSelector", ".news a",
                "headlineLimit", 10, "marketUrlTemplate", base + "/market/{code}", "marketCodes", List.of("000001"),
                "language", "zh-CN", "timeoutMillis", 5000), true);

        var result = collector.collect(source, new NewsCollectContext("test", Instant.now()));

        assertThat(result.failedCount()).isZero();
        assertThat(result.acceptedCount()).isEqualTo(2);
        assertThat(accepted).extracting(RawNewsItem::title)
            .containsExactly("上海证券交易所首页要闻快照", "上海证券交易所首页当日行情快照");
        assertThat(accepted.get(0).content()).contains("交易所重要新闻一", base + "/a");
        assertThat(accepted.get(1).content()).contains("上证指数", "3818", "-1.64%", "744239719925");
    }

    @Test
    void collectsNewsHomepageWithoutRequiringMarketConfiguration() throws Exception {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        serve("/home", "text/html; charset=UTF-8",
            "<a class='notice' href='/new/disclosure/detail?id=1'>上市公司重要公告</a>");
        server.start();
        String base = "http://localhost:" + server.getAddress().getPort();
        List<RawNewsItem> accepted = new ArrayList<>();
        ExchangeHomeNewsCollector collector = new ExchangeHomeNewsCollector(
            item -> { accepted.add(item); return NewsAcceptance.ACCEPTED; }, new ObjectMapper());
        NewsSource source = new NewsSource(2L, "cninfo_home", "巨潮资讯首页", NewsSourceType.NEWS_HOME,
            base + "/home", "localhost", Map.of(), Map.of("provider", "CNINFO",
                "headlineSelector", "a.notice", "headlineLimit", 10, "timeoutMillis", 5000), true);

        var result = collector.collect(source, new NewsCollectContext("test", Instant.now()));

        assertThat(result.failedCount()).isZero();
        assertThat(result.acceptedCount()).isOne();
        assertThat(accepted).singleElement().satisfies(item -> {
            assertThat(item.title()).isEqualTo("巨潮资讯首页要闻快照");
            assertThat(item.content()).contains("上市公司重要公告");
        });
    }

    @Test
    void collectsAllConfiguredSseHomepageSectionsAndSecondaryContent() throws Exception {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        String homepage = """
            <section class='sse2020_lev1_block'><h1><span>要闻</span></h1><ul class='hot_dyn'>
              <li><a class='dynaTitle' href='/detail/news'>重要要闻</a><span class='new_date'>2026-07-18</span></li>
            </ul></section>
            <section class='js_news_more'><a class='dynaTitle' href='/detail/hot'>热点动态</a></section>
            <ul class='column_news'><li><a class='column_newsTitle' href='/detail/update'>各栏更新</a></li></ul>
            <div class='Derivatives'><a href='/detail/derivative'>衍生品公告</a></div>
            <div class='recentListing'><div class='swiper-slide'><a href='/detail/listing?companyId=688797'></a></div></div>
            """;
        serve("/home", "text/html; charset=UTF-8", homepage);
        serve("/detail/news", "text/html; charset=UTF-8", "<main>要闻二级页面完整正文</main>");
        serve("/detail/hot", "text/html; charset=UTF-8", "<main>热点二级页面完整正文</main>");
        serve("/detail/update", "text/html; charset=UTF-8", "<main>栏目更新二级正文<a href='/files/update.pdf'>附件</a></main>");
        serve("/detail/derivative", "text/html; charset=UTF-8", "<main>衍生品公告二级正文</main>");
        serve("/detail/listing", "text/html; charset=UTF-8", "<main>近期上市公司详情</main>");
        serve("/ipo/introduction", "application/javascript; charset=UTF-8",
            "callback({\"introduction\":\"<p>测试股份有限公司完整公司介绍</p>\"})");
        serve("/ipo/overview", "application/javascript; charset=UTF-8",
            "callback({\"pageHelp\":{\"data\":[{\"stockAbbrName\":\"测试股份\",\"stockCode\":\"688797\","
                + "\"listedDate\":\"20260624\",\"issuePrice\":44.56,\"issuancePriceEarningsRatio\":31.31}]}})");
        serve("/ann/stock", "application/json; charset=UTF-8", announcement("上市公司公告一", "600001", "/files/stock.pdf"));
        serve("/ann/bond", "application/json; charset=UTF-8", announcement("债券公告一", "019001", "/files/bond.pdf"));
        serve("/ann/fund", "application/json; charset=UTF-8", announcement("基金公告一", "510001", "/files/fund.pdf"));
        serve("/data/all", "text/javascript; charset=UTF-8", marketData("home_sjtj", "2311"));
        serve("/data/main", "text/javascript; charset=UTF-8", marketData("home_sjtj_zb", "1701"));
        serve("/data/star", "text/javascript; charset=UTF-8", marketData("home_sjtj_kcb", "610"));
        server.start();
        String base = "http://localhost:" + server.getAddress().getPort();
        List<RawNewsItem> accepted = new ArrayList<>();
        ExchangeHomeNewsCollector collector = new ExchangeHomeNewsCollector(
            item -> { accepted.add(item); return NewsAcceptance.ACCEPTED; }, new ObjectMapper());
        Map<String, Object> configuration = Map.ofEntries(
            Map.entry("provider", "SSE"),
            Map.entry("headlineSelector", ".hot_dyn a.dynaTitle"),
            Map.entry("headlineLimit", 10),
            Map.entry("sectionItemLimit", 10),
            Map.entry("detailSleepMillis", 0),
            Map.entry("sectionSelectors", Map.of(
                "要闻", ".sse2020_lev1_block:has(> h1:contains(要闻)) a.dynaTitle",
                "热点动态", ".js_news_more a.dynaTitle", "各栏更新", ".column_news a.column_newsTitle",
                "衍生品公告", ".Derivatives a", "近期上市", ".recentListing a")),
            Map.entry("detailContentSelector", "main"),
            Map.entry("detailAllowedDomains", List.of("localhost")),
            Map.entry("attachmentAllowedDomains", List.of("localhost")),
            Map.entry("ipoIntroductionUrlTemplate", base + "/ipo/introduction?companyId={companyId}"),
            Map.entry("ipoOverviewUrlTemplate", base + "/ipo/overview?stockCode={companyId}"),
            Map.entry("announcementBaseUrl", base),
            Map.entry("announcementFeeds", List.of(
                Map.of("section", "上市公司公告", "url", base + "/ann/stock"),
                Map.of("section", "债券公告", "url", base + "/ann/bond"),
                Map.of("section", "基金公告", "url", base + "/ann/fund"))),
            Map.entry("marketDataFeeds", List.of(
                Map.of("section", "数据总貌", "url", base + "/data/all", "variable", "home_sjtj"),
                Map.of("section", "主板", "url", base + "/data/main", "variable", "home_sjtj_zb"),
                Map.of("section", "科创板", "url", base + "/data/star", "variable", "home_sjtj_kcb"))),
            Map.entry("language", "zh-CN"),
            Map.entry("timeoutMillis", 5000)
        );
        NewsSource source = new NewsSource(3L, "sse_home", "上海证券交易所首页", NewsSourceType.EXCHANGE_HOME,
            base + "/home", "localhost", Map.of(), configuration, true);

        var result = collector.collect(source, new NewsCollectContext("test", Instant.now()));

        assertThat(result.failedCount()).isZero();
        assertThat(result.acceptedCount()).isEqualTo(10);
        assertThat(accepted).extracting(RawNewsItem::title).contains(
            "重要要闻", "热点动态", "各栏更新", "衍生品公告", "近期上市 688797",
            "上市公司公告一", "债券公告一", "基金公告一", "上海证券交易所首页市场数据快照");
        assertThat(accepted).filteredOn(item -> "重要要闻".equals(item.title())).singleElement()
            .satisfies(item -> assertThat(item.content()).contains("要闻二级页面完整正文"));
        assertThat(accepted).filteredOn(item -> "各栏更新".equals(item.title())).singleElement()
            .satisfies(item -> assertThat(item.metadata().get("attachmentUrls")).asList().contains(base + "/files/update.pdf"));
        assertThat(accepted).filteredOn(item -> "近期上市 688797".equals(item.title())).singleElement()
            .satisfies(item -> assertThat(item.content()).contains(
                "测试股份有限公司完整公司介绍", "股票简称 测试股份", "发行价 44.56 元/股"));
        assertThat(accepted).filteredOn(item -> item.title().endsWith("市场数据快照")).singleElement()
            .satisfies(item -> assertThat(item.content()).contains("数据总貌：上市公司 2311 家", "主板：上市公司 1701 家", "科创板：上市公司 610 家"));
    }

    @Test
    void classifiesDynamicExchangeHomepageWhenHeadlineSelectorMatchesNothing() throws Exception {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        serve("/home", "text/html; charset=UTF-8",
            "<div id='app'><div v-for='item in headlines'>{{item.title}}</div></div><script>webpackChunk=[]</script>");
        server.start();
        String base = "http://localhost:" + server.getAddress().getPort();
        ExchangeHomeNewsCollector collector = new ExchangeHomeNewsCollector(
            item -> NewsAcceptance.ACCEPTED, new ObjectMapper());
        NewsSource source = new NewsSource(2L, "exchange_dynamic", "动态交易所首页", NewsSourceType.NEWS_HOME,
            base + "/home", "localhost", Map.of(), Map.of("provider", "TEST",
                "headlineSelector", ".news a", "headlineLimit", 10, "timeoutMillis", 5000), true);

        var result = collector.collect(source, new NewsCollectContext("test", Instant.now()));

        assertThat(result.discoveredCount()).isZero();
        assertThat(result.failedCount()).isOne();
        assertThat(result.errorMessage()).contains("Dynamic page detected", "vue-template-markers");
    }

    private void serve(String path, String contentType, String body) {
        server.createContext(path, exchange -> {
            byte[] data = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.sendResponseHeaders(200, data.length);
            exchange.getResponseBody().write(data);
            exchange.close();
        });
    }

    private String announcement(String title, String code, String url) {
        return "{\"publishData\":[{\"discloseDate\":\"2026-07-18\",\"bulletinTitle\":\"" + title
            + "\",\"bulletinUrl\":\"" + url + "\",\"securityCode\":\"" + code
            + "\",\"securityAbbr\":\"测试证券\"}]}";
    }

    private String marketData(String variable, String companyNumber) {
        return "var " + variable + " = {};\n" + variable + ".dataStatisticDate = '2026-07-18';\n"
            + variable + ".companyNumber = '" + companyNumber + "';\n"
            + variable + ".stockNumber = '100';\n" + variable + ".iss_vol = '200';\n"
            + variable + ".ngt_vol = '180';\n" + variable + ".mkt_value = '300';\n"
            + variable + ".negotiable_value = '280';\n" + variable + ".ratioOfPe = '15.5';\n";
    }
}
