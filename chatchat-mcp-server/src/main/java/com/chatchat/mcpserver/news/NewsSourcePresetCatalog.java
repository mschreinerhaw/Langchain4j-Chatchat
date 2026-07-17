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
            exchangeHome("sse_home", "上海证券交易所首页", "https://www.sse.com.cn/", "SSE",
                ".column_news a.column_newsTitle", "https://yunhq.sse.com.cn:32042/v1/sh1/snap/{code}",
                List.of("000001", "000688")),
            exchangeHome("szse_home", "深圳证券交易所首页", "https://www.szse.cn/index/index.html", "SZSE",
                ".homem-news-wrap:not(.currentAffairs) a.art-list-link", "https://www.szse.cn/api/market/ssjjhq/getTimeData?marketId=1&code={code}",
                List.of("399001", "399006")),
            web("eastmoney_finance", "东方财富财经", "https://finance.eastmoney.com/", "eastmoney.com",
                "a[href*='/a/']", "h1", "#ContentBody, .Body, .article-body", ".time, .info", ".source",
                "https?://[^/]*eastmoney\\.com/a/.*", "0 */10 * * * *"),
            clsTelegraph(),
            web("sse_announcements", "上海证券交易所公告", "https://www.sse.com.cn/disclosure/listedinfo/announcement/", "sse.com.cn",
                "a[href*='/disclosure/listedinfo/announcement/'], a[href$='.pdf']", "h1, .title", ".article-content, .content, body", ".date, .time", ".source",
                "https?://[^/]*sse\\.com\\.cn/.*", "0 */15 * * * *"),
            web("szse_announcements", "深圳证券交易所公告", SZSE_ENTRY_URL, "szse.cn",
                "a[href*='/disclosure/'], a[href$='.pdf']", "h1, .title", ".article-content, .content, body", ".date, .time", ".source",
                "https?://[^/]*szse\\.cn/.*", "0 */15 * * * *"),
            newsHome("cninfo_home", "巨潮资讯首页", "https://www.cninfo.com.cn/new/index", "CNINFO",
                "a[href*='/new/disclosure/detail']", 10, "0 */5 * * * *"),
            web("cninfo_announcements", "巨潮资讯公告", "https://www.cninfo.com.cn/new/commonUrl/pageOfSearch?url=disclosure/list/search", "cninfo.com.cn",
                "a[href*='/new/disclosure/detail'], a[href*='static.cninfo.com.cn']", "h1, .title", ".detail-content, .article-content, body", ".date, .time", ".source",
                "https?://([^/]*\\.)?cninfo\\.com\\.cn/.*", "0 */10 * * * *")
        );
    }

    private Preset newsHome(String code, String name, String url, String provider, String headlineSelector,
                            int headlineLimit, String cron) {
        return new Preset(code, name, "资讯首页关键内容快照，仅采集指定要闻区域。",
            new SourceUpsert(code, name, "NEWS_HOME", url,
                URI.create(url).getHost(), cron, false,
                Map.of("provider", provider, "headlineSelector", headlineSelector, "headlineLimit", headlineLimit,
                    "sleepMillis", 1000, "timeoutMillis", 20000, "zoneId", "Asia/Shanghai", "language", "zh-CN")), null);
    }

    private Preset clsTelegraph() {
        return new Preset("cls_telegraph", "财联社电报", "通过财联社页面使用的 JSON 接口逐条采集快讯。",
            new SourceUpsert("cls_telegraph", "财联社电报",
                "CLS_TELEGRAPH", "https://www.cls.cn/telegraph", "cls.cn", "0 */2 * * * *", false,
                Map.of("apiUrl", "https://www.cls.cn/api/cache", "itemLimit", 30, "sleepMillis", 1000,
                    "timeoutMillis", 20000, "zoneId", "Asia/Shanghai", "language", "zh-CN")), null);
    }

    private Preset exchangeHome(String code, String name, String url, String provider, String headlineSelector,
                                String marketUrlTemplate, List<String> marketCodes) {
        return new Preset(code, name, "交易所首页要闻和当日行情快照。",
            new SourceUpsert(code, name, "EXCHANGE_HOME", url,
                URI.create(url).getHost(), "0 */5 * * * *", false,
                Map.of("provider", provider, "headlineSelector", headlineSelector, "headlineLimit", 12,
                    "marketUrlTemplate", marketUrlTemplate, "marketCodes", marketCodes,
                    "sleepMillis", 1000, "timeoutMillis", 20000, "zoneId", "Asia/Shanghai", "language", "zh-CN")), null);
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
