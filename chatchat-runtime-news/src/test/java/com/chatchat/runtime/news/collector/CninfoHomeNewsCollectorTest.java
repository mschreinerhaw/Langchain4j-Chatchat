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
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CninfoHomeNewsCollectorTest {
    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    @Test
    void collectsAllFiveDynamicHomepageSections() throws Exception {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/new/hisAnnouncement/query", exchange -> respond(exchange, """
            {"announcements":[{"announcementId":"123","announcementTitle":"最新公告标题",
            "announcementTime":1784282400000,"secCode":"000001","secName":"平安银行",
            "orgId":"gssz1","adjunctUrl":"finalpage/latest.PDF"}]}
            """));
        server.createContext("/new/companyReplies/getAnswersList", exchange -> respond(exchange, """
            [{"shortName":"平安银行","stockCode":"000001","qContent":"投资者问题是什么？",
            "rContent":"公司已正式回复该问题。","qrDetailUrl":"/new/answers/1","rCreatedDate":"2026-07-18 10:00:00"}]
            """));
        server.createContext("/new/index/researchInformation", exchange -> respond(exchange, """
            [{"announcementId":"456","announcementTitle":"投资者关系活动记录表","announcementTime":1784282400000,
            "secCode":"000002","secName":"万科A","orgId":"gssz2","adjunctUrl":"finalpage/research.PDF"}]
            """));
        server.createContext("/new/votingCompany/getMeetings", exchange -> respond(exchange, """
            [{"meetingId":"789","meetingName":"2026年第一次临时股东大会","firstProposalName":"审议第一项议案",
            "startDate":1784282400000,"endDate":1784368800000,"isVoting":true,"linkUrlNew":"/new/voting/1"}]
            """));
        server.createContext("/data/centerSpecial/getIndexPublicInfo", exchange -> respond(exchange, """
            {"publicInfo":[{"SECCODE":"000003","SECNAME":"测试公司","F002V":"日涨幅偏离值达到规定标准",
            "TRADEDATE":"2026-07-18"}]}
            """));
        server.start();
        String base = "http://localhost:" + server.getAddress().getPort();
        List<RawNewsItem> items = new ArrayList<>();
        CninfoHomeNewsCollector collector = new CninfoHomeNewsCollector(
            item -> { items.add(item); return NewsAcceptance.ACCEPTED; }, new ObjectMapper(), new NewsRuntimeProperties());
        NewsSource source = new NewsSource(3L, "cninfo_home", "巨潮资讯首页", NewsSourceType.CNINFO_HOME,
            base + "/new/index", "localhost", Map.of(), Map.ofEntries(
                Map.entry("announcementColumns", "szse"),
                Map.entry("announcementApiUrl", base + "/new/hisAnnouncement/query"),
                Map.entry("staticBaseUrl", base + "/"),
                Map.entry("answersUrl", base + "/new/companyReplies/getAnswersList"),
                Map.entry("researchUrl", base + "/new/index/researchInformation"),
                Map.entry("votingUrl", base + "/new/votingCompany/getMeetings"),
                Map.entry("publicInfoUrl", base + "/data/centerSpecial/getIndexPublicInfo?market="),
                Map.entry("language", "zh-CN")), true);

        var result = collector.collect(source, new NewsCollectContext("test", Instant.now()));

        assertThat(result.failedCount()).isZero();
        assertThat(result.discoveredCount()).isEqualTo(5);
        assertThat(result.acceptedCount()).isEqualTo(5);
        assertThat(items).extracting(item -> item.categories().get(0)).containsExactly(
            "最新公告", "互动问答", "调研信息", "网络投票", "公开信息");
        assertThat(items.get(0).metadata().get("attachmentUrls"))
            .isEqualTo(List.of(base + "/finalpage/latest.PDF"));
        assertThat(items.get(2).metadata().get("attachmentUrls"))
            .isEqualTo(List.of(base + "/finalpage/research.PDF"));
    }

    private static void respond(HttpExchange exchange, String content) throws IOException {
        byte[] body = content.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }
}
