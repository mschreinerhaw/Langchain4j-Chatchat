package com.chatchat.runtime.news.collector;

import com.chatchat.runtime.news.config.NewsRuntimeProperties;
import com.chatchat.runtime.news.model.NewsCollectContext;
import com.chatchat.runtime.news.model.NewsCollectResult;
import com.chatchat.runtime.news.model.NewsSource;
import com.chatchat.runtime.news.model.NewsSourceType;
import com.chatchat.runtime.news.model.RawNewsItem;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Collects SZSE homepage news, notices, listed-company announcements, and official index snapshots. */
@Component
public class SzseHomeNewsCollector implements NewsCollector {
    private final NewsItemSink sink;
    private final ObjectMapper objectMapper;
    private final NewsRuntimeProperties properties;
    private final HttpClient client;

    @Autowired
    public SzseHomeNewsCollector(NewsItemSink sink, ObjectMapper objectMapper, NewsRuntimeProperties properties) {
        this(sink, objectMapper, properties,
            HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build());
    }

    SzseHomeNewsCollector(NewsItemSink sink, ObjectMapper objectMapper, NewsRuntimeProperties properties,
                          HttpClient client) {
        this.sink = sink;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.client = client;
    }

    @Override
    public boolean supports(NewsSourceType sourceType) {
        return sourceType == NewsSourceType.SZSE_HOME;
    }

    @Override
    public NewsCollectResult collect(NewsSource source, NewsCollectContext context) {
        Counters counters = new Counters();
        List<String> errors = new ArrayList<>();
        run(errors, counters, "SZSE news", () -> collectNews(source, counters));
        run(errors, counters, "SZSE notices", () -> collectExchangeNotices(source, counters));
        run(errors, counters, "listed-company announcements", () -> collectCompanyAnnouncements(source, counters));
        run(errors, counters, "SZSE market snapshot", () -> collectMarketSnapshot(source, counters));
        return new NewsCollectResult(context.executionId(), source.id(), counters.discovered, counters.accepted,
            counters.duplicate, counters.rejected, counters.failed, errors.isEmpty() ? null : String.join("; ", errors));
    }

    private void collectNews(NewsSource source, Counters counters) throws Exception {
        Document home = Jsoup.parse(get(source.entryUrl(), source), source.entryUrl());
        String selector = stringConfig(source, "newsSelector", ".homem-news-wrap .title a[href]");
        String urlContains = stringConfig(source, "newsUrlContains", "/aboutus/trends/news/");
        int limit = limit(source, "newsLimit", 10);
        int count = 0;
        for (Element anchor : home.select(selector)) {
            String title = first(anchor.attr("title"), anchor.text());
            String url = anchor.absUrl("href");
            if (title == null || url.isBlank() || !url.contains(urlContains)) continue;
            if (count++ >= limit) break;
            String content = detailText(url, source, title);
            counters.discovered++;
            counters.add(sink.accept(new RawNewsItem(source, title, content, null, "深圳证券交易所", url,
                null, language(source), List.of("深交所要闻"), List.of("深圳证券交易所", "要闻"),
                Map.of("transport", "szse-home-html", "provider", "SZSE"))));
        }
        if (count == 0) throw new IllegalStateException("SZSE homepage news selector matched 0 items: " + selector);
    }

    private void collectExchangeNotices(NewsSource source, Counters counters) throws Exception {
        String indexUrl = stringConfig(source, "noticeIndexUrl", "https://www.szse.cn/disclosure/notice/index.json");
        JsonNode items = objectMapper.readTree(get(indexUrl, source)).path("data");
        if (!items.isArray()) throw new IllegalStateException("SZSE notice index has no data array");
        int count = 0;
        for (JsonNode item : items) {
            if (count++ >= limit(source, "noticeLimit", 20)) break;
            String title = item.path("title").asText("").trim();
            String detailUrl = absolute(source.entryUrl(), item.path("url").asText(""));
            String jsonUrl = absolute(source.entryUrl(), item.path("jsonPath").asText(""));
            if (title.isBlank() || detailUrl.isBlank()) continue;
            String content = title;
            if (!jsonUrl.isBlank()) {
                try {
                    String html = objectMapper.readTree(get(jsonUrl, source)).path("data").path("content").asText("");
                    content = first(Jsoup.parse(html).text(), title);
                } catch (Exception ignored) { }
            }
            counters.discovered++;
            counters.add(sink.accept(new RawNewsItem(source, title, content, null, "深圳证券交易所", detailUrl,
                millis(item.path("pubTime").asLong(0)), language(source), List.of("深交所公告"),
                List.of("深圳证券交易所", "公告"), Map.of("transport", "szse-notice-json", "provider", "SZSE"))));
        }
    }

    private void collectCompanyAnnouncements(NewsSource source, Counters counters) throws Exception {
        String apiUrl = stringConfig(source, "announcementApiUrl",
            "https://www.cninfo.com.cn/new/hisAnnouncement/query");
        int pageSize = limit(source, "announcementLimit", 50);
        String body = form(Map.ofEntries(
            Map.entry("pageNum", "1"), Map.entry("pageSize", String.valueOf(pageSize)),
            Map.entry("column", "szse"), Map.entry("tabName", "fulltext"), Map.entry("plate", ""),
            Map.entry("stock", ""), Map.entry("searchkey", ""), Map.entry("secid", ""),
            Map.entry("category", ""), Map.entry("trade", ""), Map.entry("seDate", ""),
            Map.entry("sortName", ""), Map.entry("sortType", ""), Map.entry("isHLtitle", "true")));
        JsonNode items = postForm(apiUrl, body, source).path("announcements");
        if (!items.isArray()) throw new IllegalStateException("CNINFO announcement response has no announcements array");
        for (JsonNode item : items) {
            String title = item.path("announcementTitle").asText("").trim();
            String code = item.path("secCode").asText("").trim();
            String name = item.path("secName").asText("").trim();
            String attachment = absolute(stringConfig(source, "announcementStaticBaseUrl", "https://static.cninfo.com.cn/"),
                item.path("adjunctUrl").asText(""));
            if (title.isBlank()) continue;
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("transport", "cninfo-announcement-api");
            metadata.put("securityCode", code);
            metadata.put("securityName", name);
            if (!attachment.isBlank()) {
                metadata.put("attachmentUrls", List.of(attachment));
                metadata.put("attachmentAllowedDomains", List.of("static.cninfo.com.cn"));
            }
            String detail = cninfoDetail(source, item);
            String content = "深交所上市公司公告。证券代码：" + code + "；证券简称：" + name
                + "；公告标题：" + title + "。公告全文见官方 PDF：" + attachment;
            counters.discovered++;
            counters.add(sink.accept(new RawNewsItem(source, title, content, null, "巨潮资讯网", detail,
                millis(item.path("announcementTime").asLong(0)), language(source), List.of("上市公司公告"),
                name.isBlank() ? List.of(code) : List.of(name, code), Map.copyOf(metadata))));
        }
    }

    private void collectMarketSnapshot(NewsSource source, Counters counters) throws Exception {
        String template = stringConfig(source, "marketUrlTemplate", null);
        List<String> codes = stringList(source.configuration().get("marketCodes"));
        if (template == null || codes.isEmpty()) return;
        List<String> snapshots = new ArrayList<>();
        List<Map<String, Object>> quotes = new ArrayList<>();
        String marketTime = null;
        for (String code : codes) {
            JsonNode root = objectMapper.readTree(get(template.replace("{code}", code), source));
            JsonNode data = root.path("data");
            if (!"0".equals(root.path("code").asText()) || !data.isObject()) {
                throw new IllegalStateException("invalid SZSE market snapshot for " + code);
            }
            String itemTime = first(data.path("marketTime").asText(null), root.path("datetime").asText(null));
            marketTime = first(itemTime, marketTime);
            Map<String, Object> quote = new LinkedHashMap<>();
            quote.put("quoteCode", data.path("code").asText(code));
            quote.put("quoteName", data.path("name").asText(code));
            quote.put("marketTime", itemTime == null ? "" : itemTime);
            quote.put("close", data.path("now").asText(""));
            quote.put("change", data.path("delta").asText(""));
            quote.put("changePct", data.path("deltaPercent").asText(""));
            quote.put("open", data.path("open").asText(""));
            quote.put("high", data.path("high").asText(""));
            quote.put("low", data.path("low").asText(""));
            quote.put("volume", data.path("volume").asText(""));
            quote.put("amount", data.path("amount").asText(""));
            quotes.add(Map.copyOf(quote));
            snapshots.add(String.format(
                "%s（%s）：最新 %s，涨跌 %s，涨跌幅 %s%%，开盘 %s，最高 %s，最低 %s，成交量 %s，成交额 %s。",
                data.path("name").asText(code), data.path("code").asText(code), data.path("now").asText("-"),
                data.path("delta").asText("-"), data.path("deltaPercent").asText("-"),
                data.path("open").asText("-"), data.path("high").asText("-"), data.path("low").asText("-"),
                data.path("volume").asText("-"), data.path("amount").asText("-")));
        }
        String content = "深圳证券交易所主要指数行情：\n" + String.join("\n", snapshots)
            + (marketTime == null ? "" : "\n行情时间：" + marketTime + "。")
            + "\n数据来自深交所官方实时行情接口。";
        counters.discovered++;
        counters.add(sink.accept(new RawNewsItem(source, source.name() + "当日行情快照", content,
            "深证成指、创业板指、深证100和创业板50最新行情。", "深圳证券交易所",
            source.entryUrl() + "#market-snapshot", Instant.now(), language(source), List.of("市场行情"),
            List.of("深圳证券交易所", "首页", "行情", "指数"),
            Map.of("transport", "szse-market-api", "provider", "SZSE", "dataset", "行情",
                "datasetCode", "market_quote_daily",
                "indexCount", snapshots.size(), "quotes", List.copyOf(quotes)))));
    }

    private String detailText(String url, NewsSource source, String fallback) {
        try {
            Document page = Jsoup.parse(get(url, source), url);
            for (String selector : stringConfig(source, "detailSelector",
                ".article-body,.news-detail-con,.des-content,.article-content,.text-content,.content,article,.g-content").split(",")) {
                Element element = page.selectFirst(selector.trim());
                if (element != null && !element.text().isBlank()) return element.text().trim();
            }
        } catch (Exception ignored) { }
        return "深圳证券交易所要闻：" + fallback
            + "。该记录来自深圳证券交易所官网要闻栏目，二级页面正文暂未成功解析，详情以深交所官方页面为准。";
    }

    private JsonNode postForm(String url, String body, NewsSource source) throws Exception {
        HttpRequest request = request(url, source).header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        return objectMapper.readTree(send(request, url));
    }

    private String get(String url, NewsSource source) throws Exception {
        return send(request(url, source).GET().build(), url);
    }

    private HttpRequest.Builder request(String url, NewsSource source) {
        return HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofMillis(intConfig(source, "timeoutMillis", 20_000)))
            .header("Accept", "application/json,text/html,*/*")
            .header("Referer", source.entryUrl())
            .header("User-Agent", "Mozilla/5.0 ChatChat-NewsCollector/1.0");
    }

    private String send(HttpRequest request, String url) throws Exception {
        HttpResponse<String> response = client.send(request,
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("HTTP " + response.statusCode() + " from " + url);
        }
        return response.body();
    }

    private String cninfoDetail(NewsSource source, JsonNode item) {
        return stringConfig(source, "cninfoBaseUrl", "https://www.cninfo.com.cn")
            + "/new/disclosure/detail?stockCode=" + encode(item.path("secCode").asText(""))
            + "&announcementId=" + encode(item.path("announcementId").asText(""))
            + "&orgId=" + encode(item.path("orgId").asText(""))
            + "&announcementTime=" + item.path("announcementTime").asLong(0);
    }

    private String absolute(String base, String value) {
        if (value == null || value.isBlank()) return "";
        URI uri = URI.create(value);
        if (uri.isAbsolute()) return uri.toString().replaceFirst("^http:", "https:");
        URI root = URI.create(base).resolve("/");
        return root.resolve(value.replaceFirst("^/+", "")).toString();
    }

    private String form(Map<String, String> values) {
        return values.entrySet().stream().map(e -> encode(e.getKey()) + "=" + encode(e.getValue()))
            .collect(java.util.stream.Collectors.joining("&"));
    }

    private String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private Instant millis(long value) { return value > 0 ? Instant.ofEpochMilli(value) : null; }
    private String language(NewsSource source) { return stringConfig(source, "language", "zh-CN"); }
    private String stringConfig(NewsSource source, String key, String fallback) {
        Object value = source.configuration().get(key);
        return value == null || value.toString().isBlank() ? fallback : value.toString();
    }
    private int intConfig(NewsSource source, String key, int fallback) {
        Object value = source.configuration().get(key);
        return value instanceof Number number ? number.intValue() : fallback;
    }
    private int limit(NewsSource source, String key, int fallback) {
        return Math.max(1, Math.min(intConfig(source, key, fallback), properties.getMaxItemsPerRun()));
    }
    private List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream().map(String::valueOf).filter(item -> !item.isBlank()).toList();
    }
    private String first(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return value.trim();
        return null;
    }
    private void run(List<String> errors, Counters counters, String label, CheckedAction action) {
        try { action.run(); } catch (Exception ex) { counters.failed++; errors.add(label + ": " + ex.getMessage()); }
    }
    private interface CheckedAction { void run() throws Exception; }
    private static class Counters {
        int discovered, accepted, duplicate, rejected, failed;
        void add(NewsAcceptance result) {
            if (result == NewsAcceptance.ACCEPTED) accepted++;
            else if (result == NewsAcceptance.DUPLICATE) duplicate++;
            else rejected++;
        }
    }
}
