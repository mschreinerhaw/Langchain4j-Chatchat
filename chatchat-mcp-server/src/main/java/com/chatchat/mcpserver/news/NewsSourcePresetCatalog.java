package com.chatchat.mcpserver.news;

import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.List;
import java.util.Map;

@Component
public class NewsSourcePresetCatalog {
    static final String SZSE_ENTRY_URL = "https://www.szse.cn/disclosure/listed/notice/";

    public List<Preset> presets() {
        return List.of(
            sseHome(),
            szseHome(),
            eastmoneyFinance(),
            clsTelegraph(),
            sseAnnouncements(),
            web("szse_announcements", "深圳证券交易所公告", SZSE_ENTRY_URL, "szse.cn",
                "a[href*='/disclosure/'], a[href$='.pdf']", "h1, .title", ".article-content, .content, body", ".date, .time", ".source",
                "https?://[^/]*szse\\.cn/.*", "0 */15 * * * *"),
            cninfoHome(),
            cninfoAnnouncements()
        );
    }

    private Preset cninfoHome() {
        String code = "cninfo_home";
        String name = "巨潮资讯首页";
        String url = "https://www.cninfo.com.cn/new/index";
        return new Preset(code, name, "采集巨潮首页最新公告、互动问答、调研信息、网络投票和公开信息。",
            new SourceUpsert(code, name, "CNINFO_HOME", url,
                URI.create(url).getHost(), "0 */5 * * * *", false,
                Map.ofEntries(
                    Map.entry("presetVersion", 2),
                    Map.entry("provider", "CNINFO"),
                    Map.entry("sectionItemLimit", 20),
                    Map.entry("announcementColumns", "szse,sse"),
                    Map.entry("announcementApiUrl", "https://www.cninfo.com.cn/new/hisAnnouncement/query"),
                    Map.entry("staticBaseUrl", "https://static.cninfo.com.cn/"),
                    Map.entry("answersUrl", "https://www.cninfo.com.cn/new/companyReplies/getAnswersList"),
                    Map.entry("researchUrl", "https://www.cninfo.com.cn/new/index/researchInformation"),
                    Map.entry("votingUrl", "https://www.cninfo.com.cn/new/votingCompany/getMeetings"),
                    Map.entry("publicInfoUrl", "https://www.cninfo.com.cn/data/centerSpecial/getIndexPublicInfo?market="),
                    Map.entry("sleepMillis", 1000), Map.entry("timeoutMillis", 20000),
                    Map.entry("zoneId", "Asia/Shanghai"), Map.entry("language", "zh-CN"))), null);
    }

    private Preset clsTelegraph() {
        return new Preset("cls_telegraph", "财联社电报", "通过财联社页面使用的 JSON 接口逐条采集快讯。",
            new SourceUpsert("cls_telegraph", "财联社电报",
                "CLS_TELEGRAPH", "https://www.cls.cn/telegraph", "cls.cn", "0 */2 * * * *", false,
                Map.of("apiUrl", "https://www.cls.cn/api/cache", "itemLimit", 30, "sleepMillis", 1000,
                    "timeoutMillis", 20000, "zoneId", "Asia/Shanghai", "language", "zh-CN")), null);
    }

    private Preset cninfoAnnouncements() {
        return new Preset("cninfo_announcements", "巨潮资讯公告",
            "通过巨潮资讯公告页使用的结构化接口采集公告，并异步解析公告 PDF 原文。",
            new SourceUpsert("cninfo_announcements", "巨潮资讯公告", "CNINFO_ANNOUNCEMENTS",
                "https://www.cninfo.com.cn/new/commonUrl/pageOfSearch?url=disclosure/list/search",
                "cninfo.com.cn", "0 */10 * * * *", false,
                Map.of("apiUrl", "https://www.cninfo.com.cn/new/hisAnnouncement/query",
                    "staticBaseUrl", "https://static.cninfo.com.cn/", "column", "szse", "itemLimit", 50,
                    "sleepMillis", 1000, "timeoutMillis", 20000, "zoneId", "Asia/Shanghai", "language", "zh-CN")), null);
    }

    private Preset szseHome() {
        String code = "szse_home";
        String name = "深圳证券交易所首页";
        String url = "https://www.szse.cn/index/index.html";
        return new Preset(code, name, "采集深交所要闻、深交所公告和上市公司公告及其二级正文或 PDF。",
            new SourceUpsert(code, name, "SZSE_HOME", url,
                URI.create(url).getHost(), "0 */5 * * * *", false,
                Map.ofEntries(
                    Map.entry("presetVersion", 2), Map.entry("provider", "SZSE"),
                    Map.entry("newsSelector", ".homem-news-wrap .title a[href]"),
                    Map.entry("newsUrlContains", "/aboutus/trends/news/"), Map.entry("newsLimit", 10),
                    Map.entry("noticeIndexUrl", "https://www.szse.cn/disclosure/notice/index.json"),
                    Map.entry("noticeLimit", 20),
                    Map.entry("announcementApiUrl", "https://www.cninfo.com.cn/new/hisAnnouncement/query"),
                    Map.entry("announcementStaticBaseUrl", "https://static.cninfo.com.cn/"),
                    Map.entry("announcementLimit", 50), Map.entry("cninfoBaseUrl", "https://www.cninfo.com.cn"),
                    Map.entry("detailSelector", ".article-body,.news-detail-con,.des-content,.article-content,.text-content,.content,article,.g-content"),
                    Map.entry("sleepMillis", 1000), Map.entry("timeoutMillis", 20000),
                    Map.entry("zoneId", "Asia/Shanghai"), Map.entry("language", "zh-CN"))), null);
    }

    private Preset sseAnnouncements() {
        return new Preset("sse_announcements", "上海证券交易所公告",
            "通过上交所公司公告页使用的动态 JSON 接口采集一级公告，并异步解析二级 PDF 公告全文。",
            new SourceUpsert("sse_announcements", "上海证券交易所公告", "SSE_ANNOUNCEMENTS",
                "https://www.sse.com.cn/assortment/stock/list/info/announcement/index.shtml",
                "sse.com.cn", "0 */10 * * * *", false,
                Map.of("presetVersion", 2,
                    "apiUrl", "https://query.sse.com.cn/security/stock/queryCompanyBulletin.do",
                    "staticBaseUrl", "https://static.sse.com.cn", "itemLimit", 100, "lookbackDays", 3,
                    "securityType", "0101,120100,020100,020200,120200",
                    "sleepMillis", 1000, "timeoutMillis", 20000,
                    "zoneId", "Asia/Shanghai", "language", "zh-CN")), null);
    }

    private Preset eastmoneyFinance() {
        String url = "https://finance.eastmoney.com/";
        return new Preset("eastmoney_finance", "东方财富财经",
            "采集东方财富财经首页的数字编号文章详情，排除栏目和导航页面。",
            new SourceUpsert("eastmoney_finance", "东方财富财经", "WEB_LIST", url, "eastmoney.com",
                "0 */10 * * * *", false,
                Map.of("presetVersion", 2, "sleepMillis", 1000, "timeoutMillis", 20000,
                    "zoneId", "Asia/Shanghai", "language", "zh-CN")),
            new RuleUpsert(null, "a[href*='/a/'][href$='.html']", ".title",
                "#ContentBody", ".infos .item:nth-of-type(2)", ".infos .item:first-of-type",
                "https?://finance\\.eastmoney\\.com/a/\\d+\\.html(?:\\?.*)?"));
    }

    private Preset sseHome() {
        String url = "https://www.sse.com.cn/";
        Map<String, Object> configuration = Map.ofEntries(
            Map.entry("presetVersion", 2),
            Map.entry("provider", "SSE"),
            Map.entry("headlineSelector", ".hot_dyn a.dynaTitle"),
            Map.entry("headlineLimit", 12),
            Map.entry("sectionItemLimit", 20),
            Map.entry("sectionSelectors", Map.of(
                "要闻", ".sse2020_lev1_block:has(> h1:contains(要闻)) a.dynaTitle",
                "热点动态", ".js_news_more a.dynaTitle",
                "各栏更新", ".column_news a.column_newsTitle",
                "衍生品公告", ".Derivatives a",
                "近期上市", ".recentListing a"
            )),
            Map.entry("detailContentSelector", "article, .article-content, .allZoom, .content, .detail_content, .sse_content, main"),
            Map.entry("detailAllowedDomains", List.of("sse.com.cn", "gov.cn")),
            Map.entry("attachmentAllowedDomains", List.of("sse.com.cn", "static.sse.com.cn")),
            Map.entry("ipoIntroductionUrlTemplate", "https://query.sse.com.cn/aboutsse/dynamic/getIPOIntrByCompanyId.do?companyId={companyId}&jsonCallBack=callback"),
            Map.entry("ipoOverviewUrlTemplate", "https://query.sse.com.cn/commonSoaQuery.do?sqlId=GP_GPHQ_GPFXLB&tradeMarket=SH&token=APPMQUERY&stockCode={companyId}&jsonCallBack=callback"),
            Map.entry("announcementBaseUrl", "https://static.sse.com.cn"),
            Map.entry("announcementFeeds", List.of(
                Map.of("section", "上市公司公告", "url", url + "disclosure/listedinfo/announcement/json/stock_bulletin_publish_order.json"),
                Map.of("section", "债券公告", "url", url + "disclosure/bond/bookentry/announcement/json/bond_bulletin_publish_order.json"),
                Map.of("section", "基金公告", "url", url + "disclosure/fund/announcement/json/fund_bulletin_publish_order.json")
            )),
            Map.entry("marketDataFeeds", List.of(
                Map.of("section", "数据总貌", "url", url + "home/data_1998.js", "variable", "home_sjtj"),
                Map.of("section", "主板", "url", url + "home/data_1999.js", "variable", "home_sjtj_zb"),
                Map.of("section", "科创板", "url", url + "home/data_2000.js", "variable", "home_sjtj_kcb")
            )),
            Map.entry("marketUrlTemplate", "https://yunhq.sse.com.cn:32042/v1/sh1/snap/{code}"),
            Map.entry("marketCodes", List.of("000001", "000680", "000888", "000016", "000688", "000010", "000300")),
            Map.entry("sleepMillis", 1000),
            Map.entry("timeoutMillis", 20000),
            Map.entry("zoneId", "Asia/Shanghai"),
            Map.entry("language", "zh-CN")
        );
        return new Preset("sse_home", "上海证券交易所首页",
            "采集首页要闻、热点、栏目更新、指数、市场数据、各类公告和近期上市，并抓取二级详情或公告附件。",
            new SourceUpsert("sse_home", "上海证券交易所首页", "EXCHANGE_HOME", url,
                URI.create(url).getHost(), "0 */5 * * * *", false, configuration), null);
    }

    private Preset web(String code, String name, String url, String domain, String link, String title,
                       String content, String time, String author, String pattern, String cron) {
        return new Preset(code, name, "常用财经资讯网站预置；站点改版后请在后台调整选择器。",
            new SourceUpsert(code, name, "WEB_LIST", url, domain, cron,
                false, Map.of("sleepMillis", 1000, "timeoutMillis", 20000, "zoneId", "Asia/Shanghai", "language", "zh-CN")),
            new RuleUpsert(null, link, title, content, author, time, pattern));
    }

    public record Preset(String code, String name, String description,
                         SourceUpsert source, RuleUpsert rule) { }
    public record SourceUpsert(String sourceCode, String sourceName, String sourceType, String entryUrl,
                               String allowedDomain, String scheduleCron, Boolean enabled,
                               Map<String, Object> configuration) { }
    public record RuleUpsert(String listSelector, String linkSelector, String titleSelector,
                             String contentSelector, String authorSelector, String publishTimeSelector,
                             String urlPattern) { }
}
