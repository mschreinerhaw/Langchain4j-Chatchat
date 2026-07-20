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

import static org.assertj.core.api.Assertions.assertThat;

class SzseDisclosureCollectorTest {
    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    @Test
    void collectsFundPdfAndRasProjectDocuments() throws Exception {
        server = server(Map.of(
            "/fund", """
                {"data":[{"title":"测试基金公告","publishTime":"2026-07-20 00:00:00",
                "attachPath":"/files/fund.pdf","secCode":"160001","secName":"测试基金"}]}
                """,
            "/ras", """
                {"data":[{"prjid":101,"cmpnm":"测试科技股份有限公司","cmpsnm":"测试科技",
                "cmpcode":"301001","ddt":"2026-07-19","subInfoDisclosureList":[
                {"configFileName":"招股说明书","dfpth":"/files/ipo.pdf"},
                {"configFileName":"发行保荐书","dfpth":"/files/sponsor.pdf"}]}]}
                """));
        String base = base();
        List<RawNewsItem> items = new ArrayList<>();
        SzseDisclosureCollector collector = collector(items);
        NewsSource source = source(base + "/entry", List.of(
            Map.of("kind", "FUND", "category", "基金公告", "url", base + "/fund", "attachmentBaseUrl", base),
            Map.of("kind", "RAS_PROJECTS", "category", "IPO审核信息披露", "url", base + "/ras", "attachmentBaseUrl", base)));

        var result = collector.collect(source, new NewsCollectContext("test", Instant.now()));

        assertThat(result.failedCount()).isZero();
        assertThat(result.acceptedCount()).isEqualTo(2);
        assertThat(items.get(0).metadata().get("attachmentUrls")).isEqualTo(List.of(base + "/files/fund.pdf"));
        assertThat(items.get(1).content()).contains("招股说明书", "发行保荐书");
        assertThat(items.get(1).metadata().get("attachmentUrls")).isEqualTo(
            List.of(base + "/files/ipo.pdf", base + "/files/sponsor.pdf"));
    }

    @Test
    void followsCmsArticleAndAuctionReportDetails() throws Exception {
        server = server(Map.of(
            "/cms/index.html", """
                <ul class="newslist"><li><script>
                var curHref = './detail.html'; var curTitle = '融资融券测试公告';
                </script><span class="time">2026-07-18</span></li></ul>
                """,
            "/cms/detail.html", """
                <h2 class="title">融资融券测试公告</h2><div class="time"><span>2026-07-18</span></div>
                <div id="desContent"><p>这是二级公告正文。</p></div>
                """,
            "/auction", """
                [{"metadata":{"catalogid":"test"},"data":[{"dqrq":"2026-07-17","zqdm":"000001",
                "zqjc":"平安银行","cjje":"10.00","cjsl":"20.00","plyy":"日涨幅偏离",
                "bz":"<a a-param='/auction-detail'>查看详情</a>"}]}]
                """,
            "/auction-detail", """
                [{"metadata":{"cols":{"mmlb":"买/卖","zsmc":"营业部"}},
                "data":[{"mmlb":"买1","zsmc":"测试证券营业部"}]}]
                """));
        String base = base();
        List<RawNewsItem> items = new ArrayList<>();
        SzseDisclosureCollector collector = collector(items);
        NewsSource source = source(base + "/cms/index.html", List.of(
            Map.of("kind", "CMS_ARTICLES", "category", "融资融券业务公告", "url", base + "/cms/index.html"),
            Map.of("kind", "AUCTION_REPORT", "category", "竞价交易公开信息", "url", base + "/auction",
                "detailBaseUrl", base)));

        var result = collector.collect(source, new NewsCollectContext("test", Instant.now()));

        assertThat(result.failedCount()).isZero();
        assertThat(result.acceptedCount()).isEqualTo(2);
        assertThat(items.get(0).sourceUrl()).isEqualTo(base + "/cms/detail.html");
        assertThat(items.get(0).content()).contains("二级公告正文");
        assertThat(items.get(1).content()).contains("测试证券营业部", "日涨幅偏离");
        assertThat(items.get(1).metadata().get("evidenceUrl")).isEqualTo(base + "/auction-detail");
    }

    private SzseDisclosureCollector collector(List<RawNewsItem> items) {
        return new SzseDisclosureCollector(item -> {
            items.add(item);
            return NewsAcceptance.ACCEPTED;
        }, new ObjectMapper(), new NewsRuntimeProperties());
    }

    private NewsSource source(String entryUrl, List<Map<String, Object>> feeds) {
        return new NewsSource(30L, "szse_disclosure", "深交所披露", NewsSourceType.SZSE_DISCLOSURE,
            entryUrl, "localhost", Map.of(), Map.of("feeds", feeds, "itemLimit", 10,
                "sleepMillis", 0, "timeoutMillis", 5000, "zoneId", "Asia/Shanghai", "language", "zh-CN"), true);
    }

    private HttpServer server(Map<String, String> responses) throws Exception {
        HttpServer value = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        responses.forEach((path, response) -> value.createContext(path, exchange -> {
            byte[] body = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", path.contains("cms") ? "text/html; charset=UTF-8" : "application/json; charset=UTF-8");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        }));
        value.start();
        server = value;
        return value;
    }

    private String base() { return "http://localhost:" + server.getAddress().getPort(); }
}
