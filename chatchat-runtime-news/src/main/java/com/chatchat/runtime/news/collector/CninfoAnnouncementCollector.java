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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Collects the structured announcement feed used by the official CNINFO disclosure page. */
@Component
public class CninfoAnnouncementCollector implements NewsCollector {
    private static final String DEFAULT_API_URL = "https://www.cninfo.com.cn/new/hisAnnouncement/query";
    private static final String DEFAULT_STATIC_BASE_URL = "https://static.cninfo.com.cn/";

    private final NewsItemSink sink;
    private final ObjectMapper objectMapper;
    private final NewsRuntimeProperties properties;
    private final HttpClient client;

    @Autowired
    public CninfoAnnouncementCollector(NewsItemSink sink, ObjectMapper objectMapper,
                                       NewsRuntimeProperties properties) {
        this(sink, objectMapper, properties,
            HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build());
    }

    CninfoAnnouncementCollector(NewsItemSink sink, ObjectMapper objectMapper,
                                NewsRuntimeProperties properties, HttpClient client) {
        this.sink = sink;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.client = client;
    }

    @Override
    public boolean supports(NewsSourceType sourceType) {
        return sourceType == NewsSourceType.CNINFO_ANNOUNCEMENTS;
    }

    @Override
    public NewsCollectResult collect(NewsSource source, NewsCollectContext context) {
        int discovered = 0;
        int accepted = 0;
        int duplicate = 0;
        int rejected = 0;
        try {
            JsonNode announcements = request(source).path("announcements");
            if (!announcements.isArray()) {
                throw new IllegalStateException("CNINFO response has no announcements array");
            }
            for (JsonNode announcement : announcements) {
                if (discovered >= properties.getMaxItemsPerRun()) break;
                discovered++;
                NewsAcceptance result = sink.accept(toRawItem(source, announcement));
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

    private JsonNode request(NewsSource source) throws Exception {
        int pageSize = Math.max(1, Math.min(intConfig(source, "itemLimit", 50),
            Math.min(100, properties.getMaxItemsPerRun())));
        String body = form(Map.ofEntries(
            Map.entry("pageNum", "1"), Map.entry("pageSize", String.valueOf(pageSize)),
            Map.entry("column", stringConfig(source, "column", "szse")),
            Map.entry("tabName", "fulltext"), Map.entry("plate", ""), Map.entry("stock", ""),
            Map.entry("searchkey", ""), Map.entry("secid", ""), Map.entry("category", ""),
            Map.entry("trade", ""), Map.entry("seDate", ""), Map.entry("sortName", ""),
            Map.entry("sortType", ""), Map.entry("isHLtitle", "true")
        ));
        HttpRequest request = HttpRequest.newBuilder(URI.create(stringConfig(source, "apiUrl", DEFAULT_API_URL)))
            .timeout(Duration.ofMillis(intConfig(source, "timeoutMillis", 20_000)))
            .header("Accept", "application/json, text/plain, */*")
            .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            .header("Origin", origin(source.entryUrl()))
            .header("Referer", source.entryUrl())
            .header("X-Requested-With", "XMLHttpRequest")
            .header("User-Agent", "Mozilla/5.0 ChatChat-NewsCollector/1.0")
            .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("HTTP " + response.statusCode() + " from CNINFO announcement API");
        }
        return objectMapper.readTree(response.body());
    }

    private RawNewsItem toRawItem(NewsSource source, JsonNode item) {
        String title = item.path("announcementTitle").asText("").trim();
        String securityCode = item.path("secCode").asText("").trim();
        String securityName = item.path("secName").asText("").trim();
        String announcementId = item.path("announcementId").asText("").trim();
        String attachmentUrl = absolute(stringConfig(source, "staticBaseUrl", DEFAULT_STATIC_BASE_URL),
            item.path("adjunctUrl").asText(""));
        String detailUrl = detailUrl(source.entryUrl(), announcementId, securityCode,
            item.path("orgId").asText(""), item.path("announcementTime").asLong(0));
        String content = "巨潮资讯公告。证券代码：" + securityCode + "；证券简称：" + securityName
            + "；公告标题：" + title + "。公告原文见附件。";
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("transport", "cninfo-announcement-api");
        metadata.put("announcementId", announcementId);
        metadata.put("securityCode", securityCode);
        metadata.put("securityName", securityName);
        if (!attachmentUrl.isBlank()) {
            metadata.put("attachmentUrls", List.of(attachmentUrl));
            metadata.put("attachmentAllowedDomains", List.of("static.cninfo.com.cn"));
        }
        long timestamp = item.path("announcementTime").asLong(0);
        return new RawNewsItem(source, title, content, null, "巨潮资讯网", detailUrl,
            timestamp > 0 ? Instant.ofEpochMilli(timestamp) : null, stringConfig(source, "language", "zh-CN"),
            List.of("上市公司公告"), securityName.isBlank() ? List.of() : List.of(securityName), Map.copyOf(metadata));
    }

    private String detailUrl(String entryUrl, String announcementId, String securityCode, String orgId, long time) {
        URI entry = URI.create(entryUrl);
        String base = entry.getScheme() + "://" + entry.getAuthority();
        return base + "/new/disclosure/detail?stockCode=" + encode(securityCode)
            + "&announcementId=" + encode(announcementId) + "&orgId=" + encode(orgId)
            + "&announcementTime=" + time;
    }

    private String origin(String entryUrl) {
        URI uri = URI.create(entryUrl);
        return uri.getScheme() + "://" + uri.getAuthority();
    }

    private String absolute(String base, String path) {
        if (path == null || path.isBlank()) return "";
        URI value = URI.create(path);
        if (value.isAbsolute()) return value.toString();
        return URI.create(base.endsWith("/") ? base : base + "/").resolve(path.replaceFirst("^/+", "")).toString();
    }

    private String form(Map<String, String> values) {
        return values.entrySet().stream().map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue()))
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
