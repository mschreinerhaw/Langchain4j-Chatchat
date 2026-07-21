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

class CsindexHomeNewsCollectorTest {
    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    @Test
    void collectsIndexCardsWithCloseAndPeTtmChartSeries() throws Exception {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/series", exchange -> respond(exchange, """
            {"code":"200","msg":"Success","data":{"tradeDate":"20260720","indexMainAlls":[
              {"indexCode":"000300","indexNameCnAbbr":"沪深300","indexNameEnAbbr":"CSI 300",
               "close":4598.32,"changePct":"1.53%","tradingValue":9334,
               "indexTables":[{"tradeDate":"20260717","close":4529.01},{"tradeDate":"20260720","close":4598.32}]},
              {"indexCode":"000688","indexNameCnAbbr":"科创50","indexNameEnAbbr":"STAR 50",
               "close":1718.69,"changePct":"0.19%","tradingValue":1837,
               "indexTables":[{"tradeDate":"20260717","close":1715.40},{"tradeDate":"20260720","close":1718.69}]}
            ]},"success":true}
            """));
        server.createContext("/pe", exchange -> respond(exchange, """
            {"code":"200","msg":"Success","data":[
              {"tradeDate":"20260717","indexName":"指数","peg":14.00},
              {"tradeDate":"20260720","indexName":"指数","peg":14.29}
            ],"success":true}
            """));
        server.start();
        String base = "http://localhost:" + server.getAddress().getPort();
        List<RawNewsItem> items = new ArrayList<>();
        CsindexHomeNewsCollector collector = new CsindexHomeNewsCollector(
            item -> { items.add(item); return NewsAcceptance.ACCEPTED; }, new ObjectMapper());
        NewsSource source = new NewsSource(9L, "csindex_home", "中证指数首页指数图", NewsSourceType.CSINDEX_HOME,
            base + "/page#/", "localhost", Map.of(), Map.of(
                "indexSeriesUrl", base + "/series",
                "peHistoryUrlTemplate", base + "/pe?indexCode={code}&startDate={startDate}&endDate={endDate}",
                "zoneId", "Asia/Shanghai", "legalRisk", true), true);

        var result = collector.collect(source, new NewsCollectContext("test", Instant.now()));

        assertThat(result.failedCount()).isZero();
        assertThat(result.discoveredCount()).isEqualTo(2);
        assertThat(result.acceptedCount()).isEqualTo(2);
        assertThat(items).extracting(RawNewsItem::title)
            .containsExactly("沪深300（000300）指数图数据", "科创50（000688）指数图数据");
        assertThat(items.get(0).content()).contains("4598.32", "1.53%", "9334", "14.29", "2 个收盘点");
        assertThat(items.get(0).metadata()).containsEntry("indexCode", "000300")
            .containsEntry("tradeDate", "20260720").containsEntry("legalRisk", true);
        assertThat(items.get(0).metadata().get("closeHistory")).asList().hasSize(2);
        assertThat(items.get(0).metadata().get("peTtmHistory")).asList().hasSize(2);
        assertThat(items.get(0).publishTime()).isEqualTo(Instant.parse("2026-07-19T16:00:00Z"));
    }

    private static void respond(HttpExchange exchange, String content) throws IOException {
        byte[] body = content.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }
}
