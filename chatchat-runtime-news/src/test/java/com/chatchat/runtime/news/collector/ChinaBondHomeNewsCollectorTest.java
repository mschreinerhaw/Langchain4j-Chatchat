package com.chatchat.runtime.news.collector;

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

import static org.assertj.core.api.Assertions.assertThat;

class ChinaBondHomeNewsCollectorTest {
    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    @Test
    @SuppressWarnings("unchecked")
    void collectsYieldPointsSettlementRowsAndResearchArticles() throws Exception {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/home", exchange -> respond(exchange, "text/html", """
            <html><body><ul class="tabNewUl_data"><li>
              <span class="appendixs"><a href="/report.pdf"></a></span>
              <span class="tabNewContentWrap"><a href="/t20260721_1.html" title="2026年6月债券市场分析报告">报告</a></span>
            </li></ul></body></html>
            """));
        server.createContext("/t20260721_1.html", exchange -> respond(exchange, "text/html",
            "<html><body><div class='TRS_Editor'>债券收益率涨跌互现，托管量持续增长。</div></body></html>"));
        server.createContext("/metadata", exchange -> respond(exchange, "text/xml", """
            <?xml version="1.0" encoding="utf-8"?><data><qxmc>中债国债(到期)</qxmc><gxrq>2026-07-21</gxrq></data>
            """));
        server.createContext("/yield", exchange -> respond(exchange, "application/json", """
            [{},[10,[{"seriesData":[[0.5,1.1032],[1.0,1.1420]],"ycDefName":"ChinaBond Government Bond Yield Curve(YTM)",
            "ycDefId":"curve-1","worktime":"2026-07-21"}]]]
            """));
        server.createContext("/overview-headline", exchange -> respond(exchange, "application/json", """
            [{"tjny":"202606","xmmc":"债券托管量","exmmc":"Bond Outstanding","slStr":"1,335,296.53"},
             {"tjny":"202606","xmmc":"银行间市场投资者数量","exmmc":"Number of CIBM Investors","slStr":"44,206.00"}]
            """));
        server.createContext("/overview-monthly", exchange -> respond(exchange, "application/json", """
            [{"tjny":"202606","xmmc":"债券发行量","exmmc":"Bond Issuance","bym":"27,717.36","bnlj":"163,416.53"}]
            """));
        server.createContext("/counter", exchange -> respond(exchange, "application/json", """
            [["260014","26附息国债14","99.96","358天","1.1500%","记账式国债","Book-entry Treasury Bond",
              "1.1300","2026-07-22","0004","建设银行","China Construction Bank","1.1500","建设银行","China Construction Bank"]]
            """));
        server.createContext("/settlement", exchange -> respond(exchange, "application/json", """
            ["2026-07-22  16:02:40",["320","5,375.91","5,456.21","13,177","5,373.99"],
            ["hg_ywlx","38,171.58","36,374.19","8,393","38,171.36"],
            ["341","37,724.57","35,947.25","8,282","37,724.35"],
            ["331","447.01","426.95","111","447.01"],["343","23.90","---","18","23.90"],
            ["all_ywlx","43,571.39","41,830.40","21,588","43,569.25"]]
            """));
        server.createContext("/collateral", exchange -> respond(exchange, "application/json", """
            [["360,675.41","54,283","43,656.70","8,538.94","44,406.80","1,049.22","187.12","1,032.24",
              "0.00","0.00","0.00","6,019.79","1,108.70","6,234.36","977.09","7,744.80","1,021.60",
              "309.91","9.41","304.00","202606"]]
            """));
        server.start();
        String base = "http://localhost:" + server.getAddress().getPort();
        List<RawNewsItem> items = new ArrayList<>();
        ChinaBondHomeNewsCollector collector = new ChinaBondHomeNewsCollector(item -> {
            items.add(item);
            return NewsAcceptance.ACCEPTED;
        }, new ObjectMapper());
        NewsSource source = new NewsSource(50L, "chinabond_home", "中国债券信息网数据分析",
            NewsSourceType.CHINABOND_HOME, base + "/home", "localhost", Map.of(), Map.ofEntries(
                Map.entry("overviewHeadlineApiUrl", base + "/overview-headline"),
                Map.entry("overviewMonthlyApiUrl", base + "/overview-monthly"),
                Map.entry("yieldMetadataUrl", base + "/metadata"), Map.entry("yieldApiUrl", base + "/yield"),
                Map.entry("yieldReferer", base + "/home"), Map.entry("yieldPageUrl", base + "/yield-page"),
                Map.entry("counterQuoteApiUrl", base + "/counter"),
                Map.entry("settlementApiUrl", base + "/settlement"),
                Map.entry("settlementPageUrl", base + "/settlement-page"),
                Map.entry("collateralApiUrl", base + "/collateral"), Map.entry("sleepMillis", 0),
                Map.entry("zoneId", "Asia/Shanghai"), Map.entry("legalRisk", false)), true);

        var result = collector.collect(source, new NewsCollectContext("chinabond-test", Instant.now()));

        assertThat(result.failedCount()).isZero();
        assertThat(result.discoveredCount()).isEqualTo(6);
        assertThat(items).extracting(item -> item.metadata().get("datasetCode"))
            .containsExactly("bond_market_overview_monthly", "bond_yield_curve_daily", "bond_counter_quote_daily",
                "bond_settlement_daily", "bond_collateral_monthly", null);
        assertThat(items).allSatisfy(item -> assertThat(item.metadata()).containsEntry("legalRisk", false));
        Map<String, Object> overview = items.get(0).metadata();
        assertThat((List<Map<String, Object>>) overview.get("overviewRows")).hasSize(3);
        Map<String, Object> yield = items.get(1).metadata();
        List<Map<String, Object>> curvePoints = (List<Map<String, Object>>) yield.get("curvePoints");
        assertThat(curvePoints).hasSize(2);
        assertThat(curvePoints.get(0)).containsEntry("maturityYears", new java.math.BigDecimal("0.5"))
            .containsEntry("yieldPct", new java.math.BigDecimal("1.1032"));
        Map<String, Object> counter = items.get(2).metadata();
        List<Map<String, Object>> counterQuotes = (List<Map<String, Object>>) counter.get("counterQuotes");
        assertThat(counterQuotes).hasSize(1);
        assertThat(counterQuotes.get(0)).containsEntry("bondCode", "260014")
            .containsEntry("bestBuyInstitution", "建设银行");
        Map<String, Object> settlement = items.get(3).metadata();
        List<Map<String, Object>> settlements = (List<Map<String, Object>>) settlement.get("settlements");
        assertThat(settlements).hasSize(6);
        assertThat(settlements.get(5)).containsEntry("settlementType", "合计")
            .containsEntry("transactionCount", new java.math.BigDecimal("21588"));
        Map<String, Object> collateral = items.get(4).metadata();
        assertThat((List<Map<String, Object>>) collateral.get("collateralRows")).hasSize(7);
        assertThat(items.get(5).title()).isEqualTo("2026年6月债券市场分析报告");
        assertThat(items.get(5).content()).contains("债券收益率涨跌互现");
        assertThat(items.get(5).metadata().get("attachmentUrls")).asList().contains(base + "/report.pdf");
    }

    private static void respond(HttpExchange exchange, String contentType, String content) throws IOException {
        byte[] body = content.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType + "; charset=UTF-8");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }
}
