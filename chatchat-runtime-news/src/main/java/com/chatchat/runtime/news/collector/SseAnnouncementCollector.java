package com.chatchat.runtime.news.collector;

import com.chatchat.runtime.news.config.NewsRuntimeProperties;
import com.chatchat.runtime.news.model.NewsCollectContext;
import com.chatchat.runtime.news.model.NewsCollectResult;
import com.chatchat.runtime.news.model.NewsSource;
import com.chatchat.runtime.news.model.NewsSourceType;
import com.chatchat.runtime.news.model.RawNewsItem;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Collects the dynamic company-announcement feed used by the official SSE page. */
@Component
public class SseAnnouncementCollector implements NewsCollector {
    private static final String DEFAULT_API_URL =
        "https://query.sse.com.cn/security/stock/queryCompanyBulletin.do";
    private static final String DEFAULT_STATIC_BASE_URL = "https://static.sse.com.cn";
    private static final String DEFAULT_SECURITY_TYPES = "0101,120100,020100,020200,120200";

    private final NewsItemSink sink;
    private final ObjectMapper objectMapper;
    private final NewsRuntimeProperties properties;
    private final HttpClient client;

    @Autowired
    public SseAnnouncementCollector(NewsItemSink sink, ObjectMapper objectMapper,
                                    NewsRuntimeProperties properties) {
        this(sink, objectMapper, properties,
            HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build());
    }

    SseAnnouncementCollector(NewsItemSink sink, ObjectMapper objectMapper,
                             NewsRuntimeProperties properties, HttpClient client) {
        this.sink = sink;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.client = client;
    }

    @Override
    public boolean supports(NewsSourceType sourceType) {
        return sourceType == NewsSourceType.SSE_ANNOUNCEMENTS;
    }

    @Override
    public NewsCollectResult collect(NewsSource source, NewsCollectContext context) {
        if (source.configuration().get("feeds") instanceof List<?>) {
            return collectConfiguredFeeds(source, context);
        }
        int discovered = 0;
        int accepted = 0;
        int duplicate = 0;
        int rejected = 0;
        try {
            JsonNode items = request(source, context).path("pageHelp").path("data");
            if (!items.isArray()) {
                throw new IllegalStateException("SSE response has no pageHelp.data array");
            }
            for (JsonNode item : items) {
                if (discovered >= properties.getMaxItemsPerRun()) break;
                discovered++;
                NewsAcceptance result = sink.accept(toRawItem(source, item));
                if (result == NewsAcceptance.ACCEPTED) accepted++;
                else if (result == NewsAcceptance.DUPLICATE) duplicate++;
                else rejected++;
            }
            return new NewsCollectResult(context.executionId(), source.id(), discovered, accepted, duplicate,
                rejected, 0, null);
        } catch (Exception ex) {
            return new NewsCollectResult(context.executionId(), source.id(), discovered, accepted, duplicate,
                rejected, 1, ex.getMessage());
        }
    }

    private NewsCollectResult collectConfiguredFeeds(NewsSource source, NewsCollectContext context) {
        int discovered = 0;
        int accepted = 0;
        int duplicate = 0;
        int rejected = 0;
        int failed = 0;
        List<String> errors = new ArrayList<>();
        int perFeedLimit = Math.max(1, intConfig(source, "itemLimit", 100));
        int runLimit = Math.max(1, properties.getMaxItemsPerRun());
        try {
            for (Map<String, Object> feed : configuredFeeds(source)) {
                if (discovered >= runLimit) break;
                try {
                    JsonNode items = at(requestJson(source, text(feed, "url", source.entryUrl())),
                        text(feed, "itemsPath", "publishData"));
                    if (!items.isArray()) throw new IllegalStateException("itemsPath is not an array");
                    int feedDiscovered = 0;
                    for (JsonNode item : items) {
                        if (discovered >= runLimit || feedDiscovered >= perFeedLimit) break;
                        discovered++;
                        feedDiscovered++;
                        NewsAcceptance result = sink.accept(toConfiguredRawItem(source, feed, item));
                        if (result == NewsAcceptance.ACCEPTED) accepted++;
                        else if (result == NewsAcceptance.DUPLICATE) duplicate++;
                        else rejected++;
                    }
                } catch (Exception ex) {
                    failed++;
                    errors.add(text(feed, "category", "上交所公告") + ": " + ex.getMessage());
                }
            }
            return new NewsCollectResult(context.executionId(), source.id(), discovered, accepted, duplicate,
                rejected, failed, errors.isEmpty() ? null : String.join("; ", errors));
        } catch (Exception ex) {
            return new NewsCollectResult(context.executionId(), source.id(), discovered, accepted, duplicate,
                rejected, failed + 1, ex.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> configuredFeeds(NewsSource source) {
        List<?> values = (List<?>) source.configuration().get("feeds");
        List<Map<String, Object>> feeds = new ArrayList<>();
        for (Object value : values) {
            if (value instanceof Map<?, ?> map) feeds.add((Map<String, Object>) map);
        }
        if (feeds.isEmpty()) throw new IllegalArgumentException("SSE feeds configuration is empty");
        return feeds;
    }

    private JsonNode requestJson(NewsSource source, String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofMillis(intConfig(source, "timeoutMillis", 20_000)))
            .header("Accept", "application/json, text/javascript, */*; q=0.01")
            .header("Referer", source.entryUrl())
            .header("User-Agent", "Mozilla/5.0 ChatChat-NewsCollector/1.0")
            .GET().build();
        HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("HTTP " + response.statusCode() + " from SSE feed");
        }
        return objectMapper.readTree(response.body());
    }

    private RawNewsItem toConfiguredRawItem(NewsSource source, Map<String, Object> feed, JsonNode item) {
        String title = field(item, text(feed, "titleField", "bulletinTitle"));
        String code = field(item, text(feed, "codeField", "securityCode"));
        String name = field(item, text(feed, "nameField", "securityAbbr"));
        String date = field(item, text(feed, "dateField", "discloseDate"));
        String category = text(feed, "category", "上交所公告");
        String baseUrl = text(feed, "baseUrl", "https://www.sse.com.cn");
        String rawUrl = field(item, text(feed, "urlField", "bulletinUrl"));
        String template = text(feed, "urlTemplate", "");
        if (!template.isBlank()) rawUrl = expand(template, item);
        String sourceUrl = rawUrl.isBlank() ? source.entryUrl() : absolute(baseUrl, rawUrl);
        String extra = joinFields(item, feed.get("contentFields"));
        String content = "上海证券交易所" + category + "。" + (code.isBlank() ? "" : "证券代码：" + code + "；")
            + (name.isBlank() ? "" : "证券简称：" + name + "；") + "标题：" + title
            + (date.isBlank() ? "" : "；发布日期：" + date) + (extra.isBlank() ? "" : "；" + extra)
            + "。一级数据来自该栏目最新一页，二级原文见上交所官方链接。";
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("transport", "sse-latest-page-feed");
        metadata.put("section", category);
        metadata.put("securityCode", code);
        metadata.put("securityName", name);
        metadata.put("evidenceUrl", sourceUrl);
        if (isAttachment(sourceUrl)) {
            metadata.put("attachmentUrls", List.of(sourceUrl));
            metadata.put("attachmentAllowedDomains", List.of("static.sse.com.cn", "sse.com.cn"));
        }
        List<String> tags = new ArrayList<>();
        if (!name.isBlank()) tags.add(name);
        if (!code.isBlank()) tags.add(code);
        return new RawNewsItem(source, title, content, null, "上海证券交易所", sourceUrl,
            parseDate(date, source), stringConfig(source, "language", "zh-CN"), List.of(category),
            List.copyOf(tags), Map.copyOf(metadata));
    }

    private JsonNode at(JsonNode node, String dottedPath) {
        JsonNode current = node;
        for (String part : dottedPath.split("\\.")) {
            current = part.matches("\\d+") ? current.path(Integer.parseInt(part)) : current.path(part);
        }
        return current;
    }

    private String field(JsonNode item, String path) {
        JsonNode value = at(item, path);
        return value.isMissingNode() || value.isNull() ? "" : value.asText("").trim();
    }

    private String expand(String template, JsonNode item) {
        String value = template;
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\\{([^}]+)}").matcher(template);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) matcher.appendReplacement(result,
            java.util.regex.Matcher.quoteReplacement(field(item, matcher.group(1))));
        matcher.appendTail(result);
        return result.toString();
    }

    private String joinFields(JsonNode item, Object configured) {
        if (!(configured instanceof List<?> fields)) return "";
        List<String> values = new ArrayList<>();
        for (Object field : fields) {
            String value = field(item, String.valueOf(field));
            if (!value.isBlank()) values.add(String.valueOf(field) + "：" + value);
        }
        return String.join("；", values);
    }

    private boolean isAttachment(String url) {
        String lower = url.toLowerCase(java.util.Locale.ROOT);
        return lower.matches(".*\\.(pdf|doc|docx|xls|xlsx|zip)(?:[?#].*)?$");
    }

    private String text(Map<String, Object> map, String key, String fallback) {
        Object value = map.get(key);
        return value == null || value.toString().isBlank() ? fallback : value.toString();
    }

    private JsonNode request(NewsSource source, NewsCollectContext context) throws Exception {
        int pageSize = Math.max(1, Math.min(intConfig(source, "itemLimit", 100),
            Math.min(100, properties.getMaxItemsPerRun())));
        ZoneId zone = ZoneId.of(stringConfig(source, "zoneId", "Asia/Shanghai"));
        LocalDate endDate = context.startedAt().atZone(zone).toLocalDate();
        LocalDate beginDate = endDate.minusDays(Math.max(0, intConfig(source, "lookbackDays", 3)));
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("isPagination", "true");
        parameters.put("productId", "");
        parameters.put("keyWord", "");
        parameters.put("securityType", stringConfig(source, "securityType", DEFAULT_SECURITY_TYPES));
        parameters.put("reportType2", "");
        parameters.put("reportType", "ALL");
        parameters.put("beginDate", beginDate.toString());
        parameters.put("endDate", endDate.toString());
        parameters.put("pageHelp.pageSize", String.valueOf(pageSize));
        parameters.put("pageHelp.pageCount", "50");
        parameters.put("pageHelp.pageNo", "1");
        parameters.put("pageHelp.beginPage", "1");
        parameters.put("pageHelp.cacheSize", "1");
        parameters.put("pageHelp.endPage", "5");
        String url = stringConfig(source, "apiUrl", DEFAULT_API_URL) + "?" + query(parameters);
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofMillis(intConfig(source, "timeoutMillis", 20_000)))
            .header("Accept", "application/json, text/javascript, */*; q=0.01")
            .header("Referer", source.entryUrl())
            .header("User-Agent", "Mozilla/5.0 ChatChat-NewsCollector/1.0")
            .GET().build();
        HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("HTTP " + response.statusCode() + " from SSE announcement API");
        }
        return objectMapper.readTree(response.body());
    }

    private RawNewsItem toRawItem(NewsSource source, JsonNode item) {
        String title = item.path("TITLE").asText("").trim();
        String securityCode = item.path("SECURITY_CODE").asText("").trim();
        String securityName = item.path("SECURITY_NAME").asText("").trim();
        String publishDate = item.path("SSEDATE").asText("").trim();
        String bulletinType = item.path("BULLETIN_TYPE").asText("").trim();
        String attachmentUrl = absolute(stringConfig(source, "staticBaseUrl", DEFAULT_STATIC_BASE_URL),
            item.path("URL").asText(""));
        String content = "上海证券交易所上市公司公告。证券代码：" + securityCode + "；证券简称：" + securityName
            + "；公告类型：" + bulletinType + "；公告标题：" + title + "；公告发布日期：" + publishDate
            + "。一级页面由上交所动态公告接口提供，公告全文及二级文档见官方 PDF 附件：" + attachmentUrl;
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("transport", "sse-company-bulletin-api");
        metadata.put("securityCode", securityCode);
        metadata.put("securityName", securityName);
        metadata.put("bulletinType", bulletinType);
        if (!attachmentUrl.isBlank()) {
            metadata.put("attachmentUrls", List.of(attachmentUrl));
            metadata.put("attachmentAllowedDomains", List.of("static.sse.com.cn", "sse.com.cn"));
        }
        return new RawNewsItem(source, title, content, null, "上海证券交易所",
            attachmentUrl.isBlank() ? source.entryUrl() : attachmentUrl, parseDate(publishDate, source),
            stringConfig(source, "language", "zh-CN"), List.of("上市公司公告"),
            securityName.isBlank() ? List.of(securityCode) : List.of(securityName, securityCode), Map.copyOf(metadata));
    }

    private Instant parseDate(String value, NewsSource source) {
        ZoneId zone = ZoneId.of(stringConfig(source, "zoneId", "Asia/Shanghai"));
        try {
            return LocalDateTime.parse(value, DateTimeFormatter.ofPattern("yyyyMMddHHmmss")).atZone(zone).toInstant();
        } catch (Exception ignored) { }
        try {
            return LocalDateTime.parse(value, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm[:ss]")).atZone(zone).toInstant();
        } catch (Exception ignored) { }
        try {
            return LocalDate.parse(value).atStartOfDay(zone).toInstant();
        } catch (Exception ignored) { return null; }
    }

    private String absolute(String base, String path) {
        if (path == null || path.isBlank()) return "";
        URI value = URI.create(path);
        if (value.isAbsolute()) return value.toString();
        URI root = URI.create(base.endsWith("/") ? base : base + "/");
        return root.resolve(path.startsWith("/") ? path.substring(1) : path).toString();
    }

    private String query(Map<String, String> values) {
        return values.entrySet().stream()
            .map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue()))
            .collect(java.util.stream.Collectors.joining("&"));
    }

    private String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private String stringConfig(NewsSource source, String key, String fallback) {
        Object value = source.configuration().get(key);
        return value == null || value.toString().isBlank() ? fallback : value.toString();
    }

    private int intConfig(NewsSource source, String key, int fallback) {
        Object value = source.configuration().get(key);
        return value instanceof Number number ? number.intValue() : fallback;
    }
}
