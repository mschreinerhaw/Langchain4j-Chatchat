package com.chatchat.runtime.news.collector;

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
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Collects exchange homepage sections, bounded secondary-page content, announcements, and official market snapshots. */
@Component
public class ExchangeHomeNewsCollector implements NewsCollector {
    private final NewsItemSink sink;
    private final ObjectMapper objectMapper;
    private final HttpClient client;
    private final DynamicPageDetector dynamicPageDetector;

    @Autowired
    public ExchangeHomeNewsCollector(NewsItemSink sink, ObjectMapper objectMapper) {
        this(sink, objectMapper, HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build(),
            new DynamicPageDetector());
    }

    ExchangeHomeNewsCollector(NewsItemSink sink, ObjectMapper objectMapper, HttpClient client) {
        this(sink, objectMapper, client, new DynamicPageDetector());
    }

    ExchangeHomeNewsCollector(NewsItemSink sink, ObjectMapper objectMapper, HttpClient client,
                              DynamicPageDetector dynamicPageDetector) {
        this.sink = sink;
        this.objectMapper = objectMapper;
        this.client = client;
        this.dynamicPageDetector = dynamicPageDetector;
    }

    @Override
    public boolean supports(NewsSourceType sourceType) {
        return sourceType == NewsSourceType.EXCHANGE_HOME || sourceType == NewsSourceType.NEWS_HOME;
    }

    @Override
    public NewsCollectResult collect(NewsSource source, NewsCollectContext context) {
        Counters counters = new Counters();
        List<String> errors = new ArrayList<>();
        Document home = null;
        try {
            home = collectHeadlines(source, counters);
        } catch (Exception ex) {
            counters.failed++;
            errors.add("headlines: " + ex.getMessage());
        }
        if ("SSE".equals(provider(source))) {
            if (home != null) {
                try {
                    collectSseHomepageSections(source, home, counters);
                } catch (Exception ex) {
                    counters.failed++;
                    errors.add("SSE homepage sections: " + ex.getMessage());
                }
            }
            try {
                collectSseAnnouncements(source, counters);
            } catch (Exception ex) {
                counters.failed++;
                errors.add("SSE announcements: " + ex.getMessage());
            }
            try {
                collectSseMarketData(source, counters);
            } catch (Exception ex) {
                counters.failed++;
                errors.add("SSE market data: " + ex.getMessage());
            }
        }
        if (hasMarketConfiguration(source)) {
            try {
                collectMarketSnapshot(source, counters);
            } catch (Exception ex) {
                counters.failed++;
                errors.add("market: " + ex.getMessage());
            }
        }
        return new NewsCollectResult(context.executionId(), source.id(), counters.discovered, counters.accepted,
            counters.duplicate, counters.rejected, counters.failed, errors.isEmpty() ? null : String.join("; ", errors));
    }

    private boolean hasMarketConfiguration(NewsSource source) {
        return stringConfig(source, "marketUrlTemplate", null) != null
            && !stringList(source.configuration().get("marketCodes")).isEmpty();
    }

    private Document collectHeadlines(NewsSource source, Counters counters) throws Exception {
        String html = get(source.entryUrl(), source);
        Document document = Jsoup.parse(html, source.entryUrl());
        String selector = stringConfig(source, "headlineSelector", null);
        if (selector == null) throw new IllegalArgumentException("headlineSelector is required");
        int limit = intConfig(source, "headlineLimit", 12);
        Map<String, String> headlines = new LinkedHashMap<>();
        for (Element element : document.select(selector)) {
            if (headlines.size() >= limit) break;
            String title = element.text().trim();
            String url = element.absUrl("href");
            if (!title.isBlank() && !url.isBlank()) headlines.putIfAbsent(url, title);
        }
        if (headlines.isEmpty()) {
            throw new IllegalStateException(dynamicPageDetector.selectorFailure(html, selector));
        }
        StringBuilder content = new StringBuilder("资讯首页当前要闻：\n");
        int index = 1;
        for (var item : headlines.entrySet()) {
            content.append(index++).append(". ").append(item.getValue()).append("；链接：").append(item.getKey()).append('\n');
        }
        counters.discovered++;
        counters.add(sink.accept(new RawNewsItem(source, source.name() + "要闻快照", content.toString(),
            "从资讯首页指定要闻区域提取的最新标题集合。", source.name(), snapshotUrl(source, "headlines"),
            Instant.now(), language(source), List.of("首页要闻"), List.of("首页", "要闻"),
            Map.of("transport", "selected-home", "provider", provider(source), "headlineCount", headlines.size()))));
        return document;
    }

    private void collectSseHomepageSections(NewsSource source, Document home, Counters counters) {
        Map<String, String> selectors = stringMap(source.configuration().get("sectionSelectors"));
        if (selectors.isEmpty()) return;
        int limit = intConfig(source, "sectionItemLimit", 12);
        Set<String> acceptedUrls = new LinkedHashSet<>();
        for (Map.Entry<String, String> section : selectors.entrySet()) {
            int sectionCount = 0;
            for (Element selected : home.select(section.getValue())) {
                if (sectionCount >= limit) break;
                Element anchor = "a".equals(selected.tagName()) ? selected : selected.selectFirst("a[href]");
                if (anchor == null) continue;
                String url = anchor.absUrl("href");
                if (url.isBlank() || "#".equals(anchor.attr("href")) || !acceptedUrls.add(url)) continue;
                String title = firstText(anchor.text(), anchor.attr("title"), recentListingTitle(section.getKey(), url));
                if (title == null) continue;
                Element context = anchor.closest("li, article, .swiper-slide, .list-group-item");
                String listText = context == null ? title : context.text().trim();
                String published = context == null ? null : text(context.selectFirst(".new_date, time, .date"));
                DetailPage detail = detailPage(source, url, title, listText);
                Map<String, Object> metadata = new LinkedHashMap<>();
                metadata.put("transport", "sse-home-section");
                metadata.put("provider", "SSE");
                metadata.put("section", section.getKey());
                if (!detail.attachments().isEmpty()) {
                    metadata.put("attachmentUrls", detail.attachments());
                    metadata.put("attachmentAllowedDomains", attachmentAllowedDomains(source));
                }
                counters.discovered++;
                counters.add(sink.accept(new RawNewsItem(source, detail.title(), detail.content(), listText,
                    "上海证券交易所", url, parseDate(published, source), language(source),
                    List.of(section.getKey()), List.of("上海证券交易所", "首页", section.getKey()), Map.copyOf(metadata))));
                sectionCount++;
            }
        }
    }

    private void collectSseAnnouncements(NewsSource source, Counters counters) throws Exception {
        List<Map<String, Object>> feeds = mapList(source.configuration().get("announcementFeeds"));
        if (feeds.isEmpty()) return;
        int limit = intConfig(source, "sectionItemLimit", 20);
        String baseUrl = stringConfig(source, "announcementBaseUrl", "https://static.sse.com.cn");
        for (Map<String, Object> feed : feeds) {
            String section = text(feed.get("section"));
            String feedUrl = text(feed.get("url"));
            if (section == null || feedUrl == null) continue;
            JsonNode items = objectMapper.readTree(get(feedUrl, source)).path("publishData");
            if (!items.isArray()) throw new IllegalStateException("invalid SSE announcement feed: " + section);
            int count = 0;
            for (JsonNode item : items) {
                if (count++ >= limit) break;
                String title = firstText(item.path("bulletinTitle").asText(null), item.path("discloseId").asText(null));
                String attachmentUrl = absolute(baseUrl, item.path("bulletinUrl").asText());
                if (title == null || attachmentUrl == null) continue;
                String securityCode = item.path("securityCode").asText("");
                String securityAbbr = item.path("securityAbbr").asText("");
                String content = String.format("%s：%s%s%s。公告原文：%s", section,
                    securityCode.isBlank() ? "" : "[" + securityCode + "] ",
                    securityAbbr.isBlank() ? "" : securityAbbr + "，", title, attachmentUrl);
                Map<String, Object> metadata = new LinkedHashMap<>();
                metadata.put("transport", "sse-announcement-json");
                metadata.put("provider", "SSE");
                metadata.put("section", section);
                metadata.put("securityCode", securityCode);
                metadata.put("securityAbbr", securityAbbr);
                metadata.put("attachmentUrls", List.of(attachmentUrl));
                metadata.put("attachmentAllowedDomains", attachmentAllowedDomains(source));
                counters.discovered++;
                counters.add(sink.accept(new RawNewsItem(source, title, content, null, "上海证券交易所",
                    attachmentUrl, parseDate(item.path("discloseDate").asText(null), source), language(source),
                    List.of(section), List.of("上海证券交易所", "公告", section), Map.copyOf(metadata))));
            }
        }
    }

    private void collectSseMarketData(NewsSource source, Counters counters) throws Exception {
        List<Map<String, Object>> feeds = mapList(source.configuration().get("marketDataFeeds"));
        if (feeds.isEmpty()) return;
        StringBuilder content = new StringBuilder("上海证券交易所首页市场数据：\n");
        List<Map<String, Object>> sectionData = new ArrayList<>();
        String statisticDate = null;
        int feedCount = 0;
        for (Map<String, Object> feed : feeds) {
            String section = text(feed.get("section"));
            String url = text(feed.get("url"));
            String variable = text(feed.get("variable"));
            if (section == null || url == null || variable == null) continue;
            Map<String, String> values = parseJavascriptAssignments(get(url, source), variable);
            if (values.isEmpty()) throw new IllegalStateException("invalid SSE market data feed: " + section);
            statisticDate = firstText(values.get("dataStatisticDate"), statisticDate);
            Map<String, Object> structured = new LinkedHashMap<>(values);
            structured.put("section", section);
            sectionData.add(Map.copyOf(structured));
            content.append(section).append("：上市公司 ").append(values.getOrDefault("companyNumber", "-"))
                .append(" 家，上市股票 ").append(values.getOrDefault("stockNumber", "-"))
                .append(" 只，总股本 ").append(values.getOrDefault("iss_vol", "-"))
                .append(" 亿股（份），流通股本 ").append(values.getOrDefault("ngt_vol", "-"))
                .append(" 亿股（份），总市值 ").append(values.getOrDefault("mkt_value", "-"))
                .append(" 亿元，流通市值 ").append(values.getOrDefault("negotiable_value", "-"))
                .append(" 亿元，平均市盈率 ").append(values.getOrDefault("ratioOfPe", "-"))
                .append(" 倍。\n");
            feedCount++;
        }
        if (feedCount == 0) return;
        if (statisticDate != null) content.append("数据日期：").append(statisticDate).append('。');
        counters.discovered++;
        counters.add(sink.accept(new RawNewsItem(source, source.name() + "市场数据快照", content.toString(),
            "数据总貌、主板和科创板市场统计。", "上海证券交易所", snapshotUrl(source, "market-data"),
            parseDate(statisticDate, source), language(source), List.of("市场数据"),
            List.of("上海证券交易所", "首页", "市场数据"),
            Map.of("transport", "sse-market-data", "provider", "SSE", "dataset", "市场统计",
                "datasetCode", "market_statistics_daily",
                "statisticDate", statisticDate == null ? "" : statisticDate, "feedCount", feedCount,
                "sections", List.copyOf(sectionData)))));
    }

    private void collectMarketSnapshot(NewsSource source, Counters counters) throws Exception {
        String template = stringConfig(source, "marketUrlTemplate", null);
        List<String> codes = stringList(source.configuration().get("marketCodes"));
        if (template == null || codes.isEmpty()) throw new IllegalArgumentException("market URL and codes are required");
        List<String> snapshots = new ArrayList<>();
        List<Map<String, Object>> snapshotData = new ArrayList<>();
        for (String code : codes) {
            JsonNode root = objectMapper.readTree(get(template.replace("{code}", code), source));
            snapshots.add("SSE".equals(provider(source)) ? sseSnapshot(root, code) : szseSnapshot(root, code));
            snapshotData.add("SSE".equals(provider(source)) ? sseSnapshotData(root, code) : szseSnapshotData(root, code));
        }
        String content = "交易所首页当日行情摘要：\n" + String.join("\n", snapshots)
            + "\n数据来自交易所首页使用的官方行情接口，数值以接口行情时间为准。";
        counters.discovered++;
        counters.add(sink.accept(new RawNewsItem(source, source.name() + "当日行情快照", content,
            "主要指数最新点位、涨跌幅、成交量和成交额。", source.name(), snapshotUrl(source, "market"),
            Instant.now(), language(source), List.of("市场行情"), List.of("首页", "行情", "指数"),
            Map.of("transport", "exchange-home", "provider", provider(source), "dataset", "行情",
                "datasetCode", "market_quote_daily",
                "tradeDate", LocalDate.now(ZoneId.of(stringConfig(source, "zoneId", "Asia/Shanghai"))).toString(), "indexCount", snapshots.size(),
                "quotes", List.copyOf(snapshotData)))));
    }

    private Map<String, Object> sseSnapshotData(JsonNode root, String code) {
        JsonNode snap = root.path("snap");
        return Map.ofEntries(Map.entry("quoteCode", code), Map.entry("quoteName", snap.path(0).asText(code)),
            Map.entry("tradeDate", root.path("date").asText("")), Map.entry("marketTime", root.path("time").asText("")),
            Map.entry("close", snap.path(5).asText("")), Map.entry("change", snap.path(6).asText("")),
            Map.entry("changePct", snap.path(7).asText("")), Map.entry("volume", snap.path(8).asText("")),
            Map.entry("amount", snap.path(9).asText("")));
    }

    private Map<String, Object> szseSnapshotData(JsonNode root, String code) {
        JsonNode data = root.path("data");
        return Map.ofEntries(Map.entry("quoteCode", code), Map.entry("quoteName", data.path("name").asText(code)),
            Map.entry("marketTime", data.path("marketTime").asText(root.path("datetime").asText(""))),
            Map.entry("close", data.path("now").asText("")), Map.entry("change", data.path("delta").asText("")),
            Map.entry("changePct", data.path("deltaPercent").asText("")), Map.entry("open", data.path("open").asText("")),
            Map.entry("high", data.path("high").asText("")), Map.entry("low", data.path("low").asText("")),
            Map.entry("volume", data.path("volume").asText("")), Map.entry("amount", data.path("amount").asText("")));
    }

    private String sseSnapshot(JsonNode root, String code) {
        JsonNode snap = root.path("snap");
        if (!snap.isArray() || snap.size() < 10) throw new IllegalArgumentException("invalid SSE snapshot for " + code);
        return String.format("%s（%s）：最新 %s，涨跌 %s，涨跌幅 %s%%，成交量 %s，成交额 %s；行情日期 %s，时间 %s。",
            snap.path(0).asText(code), code, snap.path(5).asText(), snap.path(6).asText(), snap.path(7).asText(),
            snap.path(8).asText(), snap.path(9).asText(), root.path("date").asText(), root.path("time").asText());
    }

    private String szseSnapshot(JsonNode root, String code) {
        JsonNode data = root.path("data");
        if (!data.isObject()) throw new IllegalArgumentException("invalid SZSE snapshot for " + code);
        return String.format("%s（%s）：最新 %s，涨跌 %s，涨跌幅 %s%%，开盘 %s，最高 %s，最低 %s，成交量 %s，成交额 %s；行情时间 %s。",
            data.path("name").asText(code), code, data.path("now").asText(), data.path("delta").asText(),
            data.path("deltaPercent").asText(), data.path("open").asText(), data.path("high").asText(),
            data.path("low").asText(), data.path("volume").asText(), data.path("amount").asText(),
            data.path("marketTime").asText(root.path("datetime").asText()));
    }

    private DetailPage detailPage(NewsSource source, String url, String fallbackTitle, String fallbackContent) {
        Set<String> attachments = new LinkedHashSet<>();
        if (isAttachment(url)) attachments.add(url);
        if (!isAllowedDetailUrl(source, url) || isAttachment(url)) {
            return new DetailPage(fallbackTitle, firstText(fallbackContent, fallbackTitle), List.copyOf(attachments));
        }
        try {
            pause(source);
            Document detail = Jsoup.parse(get(url, source), url);
            String selector = stringConfig(source, "detailContentSelector",
                "article, .article-content, .allZoom, .content, .detail_content, .sse_content, main");
            String content = firstSelectedText(detail, selector);
            if (content.isBlank()) content = detail.body() == null ? "" : detail.body().text().trim();
            String ipoContent = sseIpoDetail(source, url);
            if (ipoContent != null) content = content.isBlank() ? ipoContent : content + "\n" + ipoContent;
            for (Element anchor : detail.select("a[href]")) {
                String attachment = anchor.absUrl("href");
                if (isAttachment(attachment) && isAllowedAttachmentUrl(source, attachment)) attachments.add(attachment);
            }
            return new DetailPage(fallbackTitle, firstText(content, fallbackContent, fallbackTitle), List.copyOf(attachments));
        } catch (Exception ignored) {
            return new DetailPage(fallbackTitle, firstText(fallbackContent, fallbackTitle), List.copyOf(attachments));
        }
    }

    private String firstSelectedText(Document document, String selectors) {
        if (document == null || selectors == null) return "";
        for (String selector : selectors.split(",")) {
            Element element = document.selectFirst(selector.trim());
            if (element != null && !element.text().isBlank()) return element.text().trim();
        }
        return "";
    }

    private String sseIpoDetail(NewsSource source, String detailUrl) {
        Matcher matcher = Pattern.compile("(?:[?&])companyId=([A-Za-z0-9_-]+)").matcher(detailUrl == null ? "" : detailUrl);
        if (!matcher.find()) return null;
        String companyId = matcher.group(1);
        List<String> parts = new ArrayList<>();
        String introductionTemplate = stringConfig(source, "ipoIntroductionUrlTemplate", null);
        if (introductionTemplate != null) {
            try {
                JsonNode root = parseJsonp(get(introductionTemplate.replace("{companyId}", companyId), source));
                String introduction = Jsoup.parse(root.path("introduction").asText("")).text().trim();
                if (!introduction.isBlank()) parts.add("公司介绍：" + introduction);
            } catch (Exception ignored) { }
        }
        String overviewTemplate = stringConfig(source, "ipoOverviewUrlTemplate", null);
        if (overviewTemplate != null) {
            try {
                JsonNode data = parseJsonp(get(overviewTemplate.replace("{companyId}", companyId), source))
                    .path("pageHelp").path("data");
                if (data.isArray() && !data.isEmpty()) {
                    JsonNode item = data.get(0);
                    parts.add(String.format("发行概况：股票简称 %s，股票代码 %s，上市日 %s，发行价 %s 元/股，发行市盈率 %s 倍。",
                        item.path("stockAbbrName").asText("-"), item.path("stockCode").asText(companyId),
                        item.path("listedDate").asText("-"), item.path("issuePrice").asText("-"),
                        item.path("issuancePriceEarningsRatio").asText("-")));
                }
            } catch (Exception ignored) { }
        }
        return parts.isEmpty() ? null : String.join("\n", parts);
    }

    private JsonNode parseJsonp(String value) throws Exception {
        String text = value == null ? "" : value.trim();
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end < start) throw new IllegalArgumentException("invalid JSONP response");
        return objectMapper.readTree(text.substring(start, end + 1));
    }

    private boolean isAllowedDetailUrl(NewsSource source, String value) {
        try {
            String host = URI.create(value).getHost();
            if (host == null) return false;
            return stringList(source.configuration().get("detailAllowedDomains")).stream()
                .anyMatch(domain -> host.equalsIgnoreCase(domain)
                    || host.toLowerCase().endsWith("." + domain.toLowerCase()));
        } catch (Exception ex) {
            return false;
        }
    }

    private boolean isAllowedAttachmentUrl(NewsSource source, String value) {
        try {
            String host = URI.create(value).getHost();
            if (host == null) return false;
            return attachmentAllowedDomains(source).stream().anyMatch(domain -> host.equalsIgnoreCase(domain)
                || host.toLowerCase().endsWith("." + domain.toLowerCase()));
        } catch (Exception ex) {
            return false;
        }
    }

    private List<String> attachmentAllowedDomains(NewsSource source) {
        LinkedHashSet<String> domains = new LinkedHashSet<>(stringList(source.configuration().get("attachmentAllowedDomains")));
        if (source.allowedDomain() != null && !source.allowedDomain().isBlank()) domains.add(source.allowedDomain());
        return List.copyOf(domains);
    }

    private boolean isAttachment(String value) {
        if (value == null) return false;
        try {
            String path = URI.create(value).getPath();
            return path != null && path.toLowerCase().matches(".*\\.(pdf|doc|docx|xls|xlsx|csv)$");
        } catch (Exception ex) {
            return false;
        }
    }

    private String recentListingTitle(String section, String url) {
        if (!"近期上市".equals(section) || url == null) return null;
        Matcher matcher = Pattern.compile("(?:[?&])companyId=([^&#]+)").matcher(url);
        return matcher.find() ? "近期上市 " + matcher.group(1) : "近期上市";
    }

    private Map<String, String> parseJavascriptAssignments(String javascript, String variable) {
        Map<String, String> values = new LinkedHashMap<>();
        Pattern pattern = Pattern.compile(Pattern.quote(variable) + "\\.([A-Za-z0-9_]+)\\s*=\\s*['\"]([^'\"]*)['\"]");
        Matcher matcher = pattern.matcher(javascript == null ? "" : javascript);
        while (matcher.find()) values.put(matcher.group(1), matcher.group(2));
        return values;
    }

    private String absolute(String baseUrl, String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return URI.create(baseUrl.endsWith("/") ? baseUrl : baseUrl + "/").resolve(value.trim()).toString();
        } catch (Exception ex) {
            return null;
        }
    }

    private Instant parseDate(String value, NewsSource source) {
        if (value == null || value.isBlank()) return null;
        Matcher matcher = Pattern.compile("(\\d{4})[-/](\\d{1,2})[-/](\\d{1,2})").matcher(value);
        if (!matcher.find()) return null;
        try {
            LocalDate date = LocalDate.of(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)),
                Integer.parseInt(matcher.group(3)));
            return date.atStartOfDay(ZoneId.of(stringConfig(source, "zoneId", "Asia/Shanghai"))).toInstant();
        } catch (Exception ex) {
            return null;
        }
    }

    private void pause(NewsSource source) throws InterruptedException {
        int millis = intConfig(source, "detailSleepMillis", 200);
        if (millis > 0) Thread.sleep(Math.min(millis, 2000));
    }

    private String get(String url, NewsSource source) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofMillis(intConfig(source, "timeoutMillis", 20_000)))
            .header("Accept", "text/html,application/json")
            .header("Referer", source.entryUrl())
            .header("User-Agent", "Mozilla/5.0 ChatChat-NewsCollector/1.0")
            .GET().build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("HTTP " + response.statusCode() + " from " + url);
        }
        return response.body();
    }

    private String snapshotUrl(NewsSource source, String kind) {
        return source.entryUrl() + (source.entryUrl().contains("?") ? "&" : "?") + "chatchatSnapshot=" + kind;
    }

    private String provider(NewsSource source) {
        return stringConfig(source, "provider", "").toUpperCase();
    }

    private String language(NewsSource source) {
        return stringConfig(source, "language", "zh-CN");
    }

    private String stringConfig(NewsSource source, String key, String fallback) {
        Object value = source.configuration().get(key);
        return value == null || value.toString().isBlank() ? fallback : value.toString();
    }

    private int intConfig(NewsSource source, String key, int fallback) {
        Object value = source.configuration().get(key);
        return value instanceof Number number ? number.intValue() : fallback;
    }

    private List<String> stringList(Object value) {
        if (!(value instanceof List<?> values)) return List.of();
        return values.stream().map(String::valueOf).filter(item -> !item.isBlank()).toList();
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> stringMap(Object value) {
        if (!(value instanceof Map<?, ?> values)) return Map.of();
        Map<String, String> result = new LinkedHashMap<>();
        values.forEach((key, item) -> {
            if (key != null && item != null && !item.toString().isBlank()) result.put(key.toString(), item.toString());
        });
        return result;
    }

    private List<Map<String, Object>> mapList(Object value) {
        if (!(value instanceof List<?> values)) return List.of();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : values) {
            if (!(item instanceof Map<?, ?> map)) continue;
            Map<String, Object> converted = new LinkedHashMap<>();
            map.forEach((key, entry) -> { if (key != null) converted.put(key.toString(), entry); });
            result.add(converted);
        }
        return result;
    }

    private String firstText(String... values) {
        if (values == null) return null;
        for (String value : values) if (value != null && !value.isBlank()) return value.trim();
        return null;
    }

    private String text(Object value) {
        return value == null || value.toString().isBlank() ? null : value.toString().trim();
    }

    private String text(Element value) {
        return value == null || value.text().isBlank() ? null : value.text().trim();
    }

    private record DetailPage(String title, String content, List<String> attachments) { }

    private static class Counters {
        int discovered;
        int accepted;
        int duplicate;
        int rejected;
        int failed;

        void add(NewsAcceptance acceptance) {
            if (acceptance == NewsAcceptance.ACCEPTED) accepted++;
            else if (acceptance == NewsAcceptance.DUPLICATE) duplicate++;
            else rejected++;
        }
    }
}
