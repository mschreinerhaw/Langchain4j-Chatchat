package com.chatchat.runtime.news.collector;

import com.chatchat.runtime.news.config.NewsRuntimeProperties;
import com.chatchat.runtime.news.model.NewsCollectContext;
import com.chatchat.runtime.news.model.NewsCollectResult;
import com.chatchat.runtime.news.model.NewsSource;
import com.chatchat.runtime.news.model.NewsSourceType;
import com.chatchat.runtime.news.model.RawNewsItem;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rometools.rome.feed.synd.SyndContent;
import com.rometools.rome.feed.synd.SyndEntry;
import com.rometools.rome.feed.synd.SyndFeed;
import com.rometools.rome.io.SyndFeedInput;
import com.rometools.rome.io.XmlReader;
import org.jsoup.Jsoup;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Collects official HKEX RSS channels and the current HKEXnews listed-company disclosure file. */
@Component
public class HkexHomeNewsCollector implements NewsCollector {
    private static final DateTimeFormatter DISCLOSURE_TIME = DateTimeFormatter.ofPattern("dd/MM/uuuu HH:mm");
    private final NewsItemSink sink;
    private final ObjectMapper objectMapper;
    private final NewsRuntimeProperties properties;
    private final HttpClient client;

    @Autowired
    public HkexHomeNewsCollector(NewsItemSink sink, ObjectMapper objectMapper, NewsRuntimeProperties properties) {
        this(sink, objectMapper, properties,
            HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build());
    }

    HkexHomeNewsCollector(NewsItemSink sink, ObjectMapper objectMapper, NewsRuntimeProperties properties,
                          HttpClient client) {
        this.sink = sink;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.client = client;
    }

    @Override
    public boolean supports(NewsSourceType sourceType) {
        return sourceType == NewsSourceType.HKEX_HOME;
    }

    @Override
    public NewsCollectResult collect(NewsSource source, NewsCollectContext context) {
        Counters counters = new Counters();
        List<String> errors = new ArrayList<>();
        for (Map<String, Object> feed : mapList(source.configuration().get("rssFeeds"))) {
            String category = text(feed.get("category"));
            String url = text(feed.get("url"));
            if (category == null || url == null) continue;
            run(errors, counters, category, () -> collectRss(source, category, url, counters));
        }
        run(errors, counters, "HKEX listed-company disclosures", () -> collectDisclosures(source, counters));
        return new NewsCollectResult(context.executionId(), source.id(), counters.discovered, counters.accepted,
            counters.duplicate, counters.rejected, counters.failed, errors.isEmpty() ? null : String.join("; ", errors));
    }

    private void collectRss(NewsSource source, String category, String url, Counters counters) throws Exception {
        byte[] body = get(url, source);
        SyndFeed feed = new SyndFeedInput().build(new XmlReader(new ByteArrayInputStream(body)));
        int limit = limit(source, "rssItemLimit", 20);
        int count = 0;
        for (SyndEntry entry : feed.getEntries()) {
            if (count++ >= limit) break;
            String title = text(entry.getTitle());
            String link = text(entry.getLink());
            if (title == null || link == null) continue;
            String description = cleanHtml(description(entry));
            String content = description == null || title.equals(description)
                ? title + "。原文：" + link : description + "\n原文：" + link;
            Instant published = entry.getPublishedDate() == null
                ? entry.getUpdatedDate() == null ? null : entry.getUpdatedDate().toInstant()
                : entry.getPublishedDate().toInstant();
            counters.discovered++;
            String feedTitle = text(feed.getTitle());
            counters.add(sink.accept(new RawNewsItem(source, title, content, description, "香港交易所", link,
                published, language(source), List.of(category), List.of("香港交易所", category),
                Map.of("transport", "hkex-rss", "provider", "HKEX",
                    "feedTitle", feedTitle == null ? category : feedTitle))));
        }
    }

    private void collectDisclosures(NewsSource source, Counters counters) throws Exception {
        String url = stringConfig(source, "disclosureUrl", null);
        if (url == null) return;
        JsonNode root = objectMapper.readTree(get(url, source));
        JsonNode items = root.path("newsInfoLst");
        if (!items.isArray()) throw new IllegalStateException("HKEX disclosure response has no newsInfoLst array");
        String baseUrl = stringConfig(source, "disclosureBaseUrl", "https://www1.hkexnews.hk");
        int limit = limit(source, "disclosureItemLimit", 100);
        int count = 0;
        for (JsonNode item : items) {
            if (count++ >= limit) break;
            String title = text(item.path("title").asText(null));
            String path = text(item.path("webPath").asText(null));
            if (title == null || path == null) continue;
            String documentUrl = URI.create(baseUrl).resolve(path).toString();
            List<String> stocks = new ArrayList<>();
            List<String> tags = new ArrayList<>(List.of("香港交易所", "上市公司公告"));
            for (JsonNode stock : item.path("stock")) {
                String code = text(stock.path("sc").asText(null));
                String name = text(stock.path("sn").asText(null));
                String label = String.join(" ", List.of(code == null ? "" : code, name == null ? "" : name)).trim();
                if (!label.isBlank()) stocks.add(label);
                if (code != null) tags.add(code);
                if (name != null) tags.add(name);
            }
            String classification = text(item.path("lTxt").asText(null));
            String size = text(item.path("size").asText(null));
            String content = "港交所上市公司公告。"
                + (stocks.isEmpty() ? "" : "证券：" + String.join("、", stocks) + "；")
                + (classification == null ? "" : "分类：" + classification + "；")
                + "标题：" + title + (size == null ? "" : "；文件大小：" + size) + "。原文：" + documentUrl;
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("transport", "hkexnews-json");
            metadata.put("provider", "HKEX");
            metadata.put("newsId", item.path("newsId").asLong());
            metadata.put("market", item.path("market").asText("SEHK"));
            metadata.put("documentType", item.path("ext").asText(""));
            metadata.put("stocks", stocks);
            if (isAttachment(documentUrl)) {
                metadata.put("attachmentUrls", List.of(documentUrl));
                metadata.put("attachmentAllowedDomains", stringList(source.configuration().get("attachmentAllowedDomains")));
            }
            counters.discovered++;
            counters.add(sink.accept(new RawNewsItem(source, title, content, classification, "香港交易所披露易",
                documentUrl, disclosureTime(item.path("relTime").asText(null), source), language(source),
                List.of("港交所上市公司公告"), List.copyOf(tags), Map.copyOf(metadata))));
        }
    }

    private byte[] get(String url, NewsSource source) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofMillis(intConfig(source, "timeoutMillis", 30_000)))
            .header("Accept", "application/json,application/rss+xml,application/xml,text/xml,*/*")
            .header("Referer", source.entryUrl())
            .header("User-Agent", "Mozilla/5.0 ChatChat-NewsCollector/1.0")
            .GET().build();
        HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("HTTP " + response.statusCode() + " from " + url);
        }
        return response.body();
    }

    private String description(SyndEntry entry) {
        if (entry.getContents() != null) {
            for (SyndContent content : entry.getContents()) {
                if (content != null && content.getValue() != null && !content.getValue().isBlank()) return content.getValue();
            }
        }
        return entry.getDescription() == null ? null : entry.getDescription().getValue();
    }

    private String cleanHtml(String value) {
        if (value == null || value.isBlank()) return null;
        String text = Jsoup.parse(value).text().trim();
        return text.isBlank() ? null : text;
    }

    private Instant disclosureTime(String value, NewsSource source) {
        if (value == null || value.isBlank()) return null;
        try {
            return LocalDateTime.parse(value.trim(), DISCLOSURE_TIME)
                .atZone(ZoneId.of(stringConfig(source, "zoneId", "Asia/Hong_Kong"))).toInstant();
        } catch (Exception ignored) {
            return null;
        }
    }

    private boolean isAttachment(String url) {
        return url != null && url.matches("(?i).+\\.(pdf|docx?|xlsx?|csv)(?:[?#].*)?$");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> mapList(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream().filter(Map.class::isInstance).map(item -> (Map<String, Object>) item).toList();
    }

    private List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream().map(String::valueOf).filter(item -> !item.isBlank()).toList();
    }

    private String language(NewsSource source) { return stringConfig(source, "language", "zh-HK"); }
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
    private String text(Object value) {
        return value == null || value.toString().isBlank() ? null : value.toString().trim();
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
