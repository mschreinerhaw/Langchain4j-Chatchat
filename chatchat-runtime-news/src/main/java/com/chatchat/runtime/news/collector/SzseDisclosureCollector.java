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
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Collects current first-page SZSE disclosure feeds and their second-level articles or documents. */
@Component
public class SzseDisclosureCollector implements NewsCollector {
    private static final Pattern CMS_HREF = Pattern.compile("var\\s+curHref\\s*=\\s*['\"]([^'\"]+)['\"]");
    private static final Pattern CMS_TITLE = Pattern.compile("var\\s+curTitle\\s*=\\s*['\"]([^'\"]+)['\"]");

    private final NewsItemSink sink;
    private final ObjectMapper objectMapper;
    private final NewsRuntimeProperties properties;
    private final HttpClient client;

    @Autowired
    public SzseDisclosureCollector(NewsItemSink sink, ObjectMapper objectMapper,
                                   NewsRuntimeProperties properties) {
        this(sink, objectMapper, properties,
            HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build());
    }

    SzseDisclosureCollector(NewsItemSink sink, ObjectMapper objectMapper,
                            NewsRuntimeProperties properties, HttpClient client) {
        this.sink = sink;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.client = client;
    }

    @Override
    public boolean supports(NewsSourceType sourceType) {
        return sourceType == NewsSourceType.SZSE_DISCLOSURE;
    }

    @Override
    public NewsCollectResult collect(NewsSource source, NewsCollectContext context) {
        Counters counters = new Counters();
        List<String> errors = new ArrayList<>();
        for (Map<String, Object> feed : feeds(source)) {
            if (counters.discovered >= properties.getMaxItemsPerRun()) break;
            String kind = text(feed, "kind", "").toUpperCase(java.util.Locale.ROOT);
            String category = text(feed, "category", kind);
            try {
                switch (kind) {
                    case "FUND" -> collectFund(source, feed, counters);
                    case "CMS_ARTICLES" -> collectCmsArticles(source, feed, counters);
                    case "AUCTION_REPORT" -> collectAuctionReport(source, feed, counters);
                    case "RAS_PROJECTS" -> collectRasProjects(source, feed, counters);
                    default -> throw new IllegalArgumentException("Unsupported SZSE disclosure feed kind: " + kind);
                }
            } catch (Exception ex) {
                counters.failed++;
                errors.add(category + ": " + ex.getMessage());
            }
        }
        return new NewsCollectResult(context.executionId(), source.id(), counters.discovered, counters.accepted,
            counters.duplicate, counters.rejected, counters.failed, errors.isEmpty() ? null : String.join("; ", errors));
    }

    private void collectFund(NewsSource source, Map<String, Object> feed, Counters counters) throws Exception {
        JsonNode items = objectMapper.readTree(get(text(feed, "url", source.entryUrl()), source)).path("data");
        if (!items.isArray()) throw new IllegalStateException("SZSE fund response has no data array");
        String category = text(feed, "category", "基金公告");
        String attachmentBase = text(feed, "attachmentBaseUrl", "https://disc.static.szse.cn");
        for (JsonNode item : limited(items, source, counters)) {
            String title = item.path("title").asText("").trim();
            if (title.isBlank()) continue;
            String code = item.path("secCode").asText("").trim();
            String name = item.path("secName").asText("").trim();
            String attachment = absolute(attachmentBase, item.path("attachPath").asText(""));
            Map<String, Object> metadata = metadata("szse-fund-disclosure-api", category, attachment);
            metadata.put("securityCode", code);
            metadata.put("securityName", name);
            String content = "深圳证券交易所" + category + "。证券代码：" + code + "；证券简称：" + name
                + "；公告标题：" + title + "。二级公告原文见深交所官方附件：" + attachment;
            accept(source, counters, new RawNewsItem(source, title, content, null, "深圳证券交易所",
                attachment.isBlank() ? source.entryUrl() : attachment, parseDate(item.path("publishTime").asText(""), source),
                language(source), List.of(category), tags(name, code), Map.copyOf(metadata)));
        }
    }

    private void collectRasProjects(NewsSource source, Map<String, Object> feed, Counters counters) throws Exception {
        JsonNode items = objectMapper.readTree(get(text(feed, "url", source.entryUrl()), source)).path("data");
        if (!items.isArray()) throw new IllegalStateException("SZSE RAS response has no data array");
        String category = text(feed, "category", "发行上市审核信息披露");
        String attachmentBase = text(feed, "attachmentBaseUrl", "https://reportdocs.static.szse.cn");
        for (JsonNode item : limited(items, source, counters)) {
            String company = first(item.path("cmpnm").asText(""), item.path("cmpsnm").asText(""));
            String shortName = item.path("cmpsnm").asText("").trim();
            String code = item.path("cmpcode").asText("").trim();
            if (company.isBlank()) continue;
            List<String> attachments = new ArrayList<>();
            List<String> documents = new ArrayList<>();
            for (JsonNode document : item.path("subInfoDisclosureList")) {
                String path = document.path("dfpth").asText("").trim();
                String documentName = first(document.path("configFileName").asText(""), document.path("dfnm").asText(""));
                if (!path.isBlank()) attachments.add(absolute(attachmentBase, path));
                if (!documentName.isBlank()) documents.add(documentName);
            }
            String title = category + "：" + company + "披露更新";
            String content = "深圳证券交易所" + category + "一级项目动态。企业：" + company
                + (code.isBlank() ? "" : "；证券代码：" + code) + "；披露日期：" + item.path("ddt").asText("")
                + "；二级披露文件：" + (documents.isEmpty() ? "暂无" : String.join("、", documents)) + "。";
            Map<String, Object> metadata = metadata("szse-ras-infodisc-api", category, "");
            metadata.put("projectId", item.path("prjid").asText(""));
            metadata.put("securityCode", code);
            if (!attachments.isEmpty()) {
                metadata.put("attachmentUrls", List.copyOf(attachments));
                metadata.put("attachmentAllowedDomains", List.of("reportdocs.static.szse.cn"));
                metadata.put("evidenceUrl", attachments.get(0));
            }
            accept(source, counters, new RawNewsItem(source, title, content, null, "深圳证券交易所",
                attachments.isEmpty() ? source.entryUrl() : attachments.get(0), parseDate(item.path("ddt").asText(""), source),
                language(source), List.of(category), tags(shortName, code), Map.copyOf(metadata)));
        }
    }

    private void collectCmsArticles(NewsSource source, Map<String, Object> feed, Counters counters) throws Exception {
        String listUrl = text(feed, "url", source.entryUrl());
        Document listPage = Jsoup.parse(get(listUrl, source), listUrl);
        String category = text(feed, "category", "业务公告");
        int count = 0;
        for (Element row : listPage.select(".newslist li")) {
            if (count >= itemLimit(source) || counters.discovered >= properties.getMaxItemsPerRun()) break;
            String script = row.select("script").stream().map(Element::data).collect(java.util.stream.Collectors.joining("\n"));
            String href = match(CMS_HREF, script);
            String title = match(CMS_TITLE, script);
            if (href.isBlank() || title.isBlank()) continue;
            String detailUrl = URI.create(listUrl).resolve(href).toString();
            Document detail = Jsoup.parse(get(detailUrl, source), detailUrl);
            String detailTitle = detail.select("h2.title").text().trim();
            String content = detail.select("#desContent").text().trim();
            if (content.isBlank()) content = title;
            List<String> attachments = detail.select("#desContent a[href]").stream()
                .map(anchor -> anchor.absUrl("href")).filter(url -> !url.isBlank()).distinct().toList();
            Map<String, Object> metadata = metadata("szse-cms-article", category, detailUrl);
            if (!attachments.isEmpty()) metadata.put("attachmentUrls", attachments);
            String date = first(detail.select(".time span").text(), row.select(".time").text());
            count++;
            accept(source, counters, new RawNewsItem(source, first(detailTitle, title), content, null,
                "深圳证券交易所", detailUrl, parseDate(date, source), language(source), List.of(category),
                List.of("深圳证券交易所"), Map.copyOf(metadata)));
            pause(source);
        }
        if (count == 0) throw new IllegalStateException("SZSE CMS list matched 0 articles");
    }

    private void collectAuctionReport(NewsSource source, Map<String, Object> feed, Counters counters) throws Exception {
        JsonNode root = objectMapper.readTree(get(text(feed, "url", source.entryUrl()), source));
        JsonNode items = root.isArray() && !root.isEmpty() ? root.get(0).path("data") : root.path("data");
        if (!items.isArray()) throw new IllegalStateException("SZSE auction report has no data array");
        String category = text(feed, "category", "竞价交易公开信息");
        String detailBase = text(feed, "detailBaseUrl", "https://www.szse.cn/api/report");
        for (JsonNode item : limited(items, source, counters)) {
            String code = item.path("zqdm").asText("").trim();
            String name = item.path("zqjc").asText("").trim();
            String date = item.path("dqrq").asText("").trim();
            String title = name + "（" + code + "）" + category;
            String detailPath = detailPath(item.path("bz").asText(""));
            String detailUrl = detailPath.isBlank() ? "" : absolute(detailBase, detailPath);
            String content = "深圳证券交易所" + category + "。日期：" + date + "；证券：" + name + "（" + code
                + "）；披露原因：" + item.path("plyy").asText("") + "；成交量：" + item.path("cjsl").asText("")
                + "；成交金额：" + item.path("cjje").asText("") + "。";
            if (!detailUrl.isBlank()) {
                try {
                    content += "二级交易明细：" + reportDetailText(objectMapper.readTree(get(detailUrl, source)));
                } catch (Exception ex) {
                    content += "二级明细地址：" + detailUrl;
                }
            }
            Map<String, Object> metadata = metadata("szse-auction-report-api", category,
                detailUrl.isBlank() ? source.entryUrl() : detailUrl);
            metadata.put("securityCode", code);
            metadata.put("securityName", name);
            accept(source, counters, new RawNewsItem(source, title, content, null, "深圳证券交易所",
                source.entryUrl(), parseDate(date, source), language(source), List.of(category), tags(name, code),
                Map.copyOf(metadata)));
            pause(source);
        }
    }

    private String reportDetailText(JsonNode root) {
        List<String> sections = new ArrayList<>();
        if (!root.isArray()) return "";
        for (JsonNode section : root) {
            JsonNode columns = section.path("metadata").path("cols");
            for (JsonNode row : section.path("data")) {
                List<String> values = new ArrayList<>();
                row.fields().forEachRemaining(field -> {
                    String label = columns.path(field.getKey()).asText(field.getKey()).replace("[none]", "");
                    String value = Jsoup.parse(field.getValue().asText("")).text();
                    if (!value.isBlank()) values.add((label.isBlank() ? field.getKey() : label) + "：" + value);
                });
                if (!values.isEmpty()) sections.add(String.join("，", values));
            }
        }
        return String.join("；", sections);
    }

    private Iterable<JsonNode> limited(JsonNode items, NewsSource source, Counters counters) {
        List<JsonNode> result = new ArrayList<>();
        int limit = itemLimit(source);
        for (JsonNode item : items) {
            if (result.size() >= limit || counters.discovered + result.size() >= properties.getMaxItemsPerRun()) break;
            result.add(item);
        }
        return result;
    }

    private void accept(NewsSource source, Counters counters, RawNewsItem item) {
        counters.discovered++;
        NewsAcceptance result = sink.accept(item);
        if (result == NewsAcceptance.ACCEPTED) counters.accepted++;
        else if (result == NewsAcceptance.DUPLICATE) counters.duplicate++;
        else counters.rejected++;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> feeds(NewsSource source) {
        Object configured = source.configuration().get("feeds");
        if (!(configured instanceof List<?> values)) throw new IllegalArgumentException("SZSE disclosure feeds are required");
        List<Map<String, Object>> feeds = new ArrayList<>();
        for (Object value : values) if (value instanceof Map<?, ?> map) feeds.add((Map<String, Object>) map);
        if (feeds.isEmpty()) throw new IllegalArgumentException("SZSE disclosure feeds are empty");
        return feeds;
    }

    private Map<String, Object> metadata(String transport, String category, String evidenceUrl) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("transport", transport);
        metadata.put("provider", "SZSE");
        metadata.put("section", category);
        if (!evidenceUrl.isBlank()) metadata.put("evidenceUrl", evidenceUrl);
        if (isAttachment(evidenceUrl)) {
            metadata.put("attachmentUrls", List.of(evidenceUrl));
            metadata.put("attachmentAllowedDomains", List.of("disc.static.szse.cn", "reportdocs.static.szse.cn", "szse.cn"));
        }
        return metadata;
    }

    private String get(String url, NewsSource source) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofMillis(intConfig(source, "timeoutMillis", 30_000)))
            .header("Accept", "application/json,text/html,*/*")
            .header("Referer", source.entryUrl())
            .header("User-Agent", "Mozilla/5.0 ChatChat-NewsCollector/1.0")
            .GET().build();
        HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("HTTP " + response.statusCode() + " from " + url);
        }
        return new String(response.body(), StandardCharsets.UTF_8);
    }

    private String detailPath(String html) {
        Element link = Jsoup.parse(html).selectFirst("[a-param]");
        return link == null ? "" : link.attr("a-param").trim();
    }

    private String absolute(String base, String value) {
        if (value == null || value.isBlank()) return "";
        URI uri = URI.create(value);
        if (uri.isAbsolute()) return uri.toString().replaceFirst("^http:", "https:");
        URI root = URI.create(base.endsWith("/") ? base : base + "/");
        return root.resolve(value.replaceFirst("^/", "")).toString();
    }

    private Instant parseDate(String value, NewsSource source) {
        ZoneId zone = ZoneId.of(stringConfig(source, "zoneId", "Asia/Shanghai"));
        for (String pattern : List.of("yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm")) {
            try { return LocalDateTime.parse(value.trim(), DateTimeFormatter.ofPattern(pattern)).atZone(zone).toInstant(); }
            catch (Exception ignored) { }
        }
        try { return LocalDate.parse(value.trim()).atStartOfDay(zone).toInstant(); }
        catch (Exception ignored) { return null; }
    }

    private void pause(NewsSource source) throws InterruptedException {
        long millis = Math.max(0, intConfig(source, "sleepMillis", 300));
        if (millis > 0) Thread.sleep(millis);
    }

    private String match(Pattern pattern, String value) {
        var matcher = pattern.matcher(value);
        return matcher.find() ? matcher.group(1).trim() : "";
    }

    private List<String> tags(String name, String code) {
        List<String> values = new ArrayList<>();
        if (name != null && !name.isBlank()) values.add(name);
        if (code != null && !code.isBlank()) values.add(code);
        return List.copyOf(values);
    }

    private String first(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return value.trim();
        return "";
    }

    private boolean isAttachment(String url) {
        return url.toLowerCase(java.util.Locale.ROOT).matches(".*\\.(pdf|doc|docx|xls|xlsx|zip)(?:[?#].*)?$");
    }

    private String text(Map<String, Object> map, String key, String fallback) {
        Object value = map.get(key);
        return value == null || value.toString().isBlank() ? fallback : value.toString();
    }

    private String language(NewsSource source) { return stringConfig(source, "language", "zh-CN"); }
    private int itemLimit(NewsSource source) { return Math.max(1, intConfig(source, "itemLimit", 20)); }
    private String stringConfig(NewsSource source, String key, String fallback) {
        Object value = source.configuration().get(key);
        return value == null || value.toString().isBlank() ? fallback : value.toString();
    }
    private int intConfig(NewsSource source, String key, int fallback) {
        Object value = source.configuration().get(key);
        return value instanceof Number number ? number.intValue() : fallback;
    }

    private static class Counters { int discovered, accepted, duplicate, rejected, failed; }
}
