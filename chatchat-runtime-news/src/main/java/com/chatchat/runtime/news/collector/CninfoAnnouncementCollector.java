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
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Collects the structured announcement feed used by the official CNINFO disclosure page. */
@Component
public class CninfoAnnouncementCollector implements NewsCollector {
    private static final String DEFAULT_API_URL = "https://www.cninfo.com.cn/new/disclosure";
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
        int failed = 0;
        List<String> errors = new ArrayList<>();
        try {
            int perSectionLimit = Math.max(1, intConfig(source, "itemLimit", 50));
            List<Section> sections = sections(source);
            for (int sectionIndex = 0; sectionIndex < sections.size(); sectionIndex++) {
                Section section = sections.get(sectionIndex);
                if (discovered >= properties.getMaxItemsPerRun()) break;
                int sectionDiscovered = 0;
                try {
                    List<JsonNode> announcements = announcements(request(source, section.column()));
                    for (JsonNode announcement : announcements) {
                        if (discovered >= properties.getMaxItemsPerRun() || sectionDiscovered >= perSectionLimit) break;
                        if (!eligible(source, context, announcement)) continue;
                        discovered++;
                        sectionDiscovered++;
                        NewsAcceptance result = sink.accept(toRawItem(source, announcement, section));
                        if (result == NewsAcceptance.ACCEPTED) accepted++;
                        else if (result == NewsAcceptance.DUPLICATE) duplicate++;
                        else rejected++;
                    }
                } catch (Exception ex) {
                    failed++;
                    errors.add(section.section() + ": " + ex.getMessage());
                }
                if (sectionIndex + 1 < sections.size()) pause(source);
            }
            return new NewsCollectResult(context.executionId(), source.id(), discovered, accepted, duplicate,
                rejected, failed, errors.isEmpty() ? null : String.join("; ", errors));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return new NewsCollectResult(context.executionId(), source.id(), discovered, accepted, duplicate,
                rejected, failed + 1, "CNINFO collection interrupted");
        } catch (Exception ex) {
            return new NewsCollectResult(context.executionId(), source.id(), discovered, accepted, duplicate,
                rejected, failed + 1, ex.getMessage());
        }
    }

    private JsonNode request(NewsSource source, String column) throws Exception {
        int pageSize = Math.max(1, Math.min(intConfig(source, "itemLimit", 50),
            Math.min(100, properties.getMaxItemsPerRun())));
        String apiUrl = stringConfig(source, "apiUrl", DEFAULT_API_URL);
        boolean disclosureApi = apiUrl.replaceAll("[/?#]+$", "").endsWith("/new/disclosure");
        String body = disclosureApi
            ? disclosureForm(column, pageSize)
            : form(Map.ofEntries(
                Map.entry("pageNum", "1"), Map.entry("pageSize", String.valueOf(pageSize)),
                Map.entry("column", column),
                Map.entry("tabName", "fulltext"), Map.entry("plate", ""), Map.entry("stock", ""),
                Map.entry("searchkey", ""), Map.entry("secid", ""), Map.entry("category", ""),
                Map.entry("trade", ""), Map.entry("seDate", ""), Map.entry("sortName", ""),
                Map.entry("sortType", ""), Map.entry("isHLtitle", "true")));
        HttpRequest request = HttpRequest.newBuilder(URI.create(apiUrl))
            .timeout(Duration.ofMillis(intConfig(source, "timeoutMillis", 20_000)))
            .header("Accept", "application/json, text/plain, */*")
            .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            .header("Origin", origin(source.entryUrl()))
            .header("Referer", referer(source.entryUrl()))
            .header("X-Requested-With", "XMLHttpRequest")
            .header("User-Agent", "Mozilla/5.0 ChatChat-NewsCollector/1.0")
            .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("HTTP " + response.statusCode() + " from CNINFO announcement API");
        }
        return objectMapper.readTree(response.body());
    }

    /** CNINFO currently returns an empty body when these form fields are reordered. */
    private String disclosureForm(String column, int pageSize) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("column", column);
        values.put("pageNum", "1");
        values.put("pageSize", String.valueOf(pageSize));
        values.put("sortName", "");
        values.put("sortType", "");
        values.put("clusterFlag", "true");
        return form(values);
    }

    @SuppressWarnings("unchecked")
    private List<Section> sections(NewsSource source) {
        Object configured = source.configuration().get("columns");
        if (!(configured instanceof List<?> values) || values.isEmpty()) {
            return List.of(new Section(stringConfig(source, "column", "szse_latest"),
                stringConfig(source, "category", "上市公司公告"), stringConfig(source, "section", "深市")));
        }
        List<Section> sections = new ArrayList<>();
        for (Object value : values) {
            if (!(value instanceof Map<?, ?> raw)) continue;
            Map<String, Object> map = (Map<String, Object>) raw;
            String column = map.get("column") == null ? "" : map.get("column").toString().trim();
            if (column.isBlank()) continue;
            String category = map.get("category") == null ? "巨潮最新公告" : map.get("category").toString();
            String section = map.get("section") == null ? category : map.get("section").toString();
            sections.add(new Section(column, category, section));
        }
        if (sections.isEmpty()) throw new IllegalArgumentException("CNINFO columns configuration is empty");
        return List.copyOf(sections);
    }

    private List<JsonNode> announcements(JsonNode response) {
        if (response == null || !response.isObject()) {
            throw new IllegalStateException("CNINFO response is not a JSON object");
        }
        List<JsonNode> items = new ArrayList<>();
        JsonNode direct = response.path("announcements");
        boolean recognized = direct.isArray();
        if (direct.isArray()) direct.forEach(items::add);
        JsonNode classified = response.path("classifiedAnnouncements");
        recognized = recognized || classified.isArray();
        if (classified.isArray()) {
            classified.forEach(group -> {
                if (group.isArray()) group.forEach(items::add);
            });
        }
        if (items.isEmpty() && recognized) return List.of();
        if (items.isEmpty()) {
            String shape = "fields=" + java.util.stream.StreamSupport.stream(
                java.util.Spliterators.spliteratorUnknownSize(response.fieldNames(), 0), false).toList();
            throw new IllegalStateException("CNINFO response has no announcements (" + shape + ")");
        }
        return items;
    }

    private boolean eligible(NewsSource source, NewsCollectContext context, JsonNode item) {
        if (booleanConfig(source, "importantOnly", false) && !item.path("important").asBoolean(false)) {
            return false;
        }
        int lookbackHours = intConfig(source, "lookbackHours", 0);
        if (lookbackHours <= 0) return true;
        long timestamp = item.path("announcementTime").asLong(item.path("storageTime").asLong(0));
        if (timestamp <= 0) return false;
        Instant publishedAt = Instant.ofEpochMilli(timestamp);
        Instant triggeredAt = context == null || context.startedAt() == null ? Instant.now() : context.startedAt();
        ZoneId zone = ZoneId.of(stringConfig(source, "zoneId", "Asia/Shanghai"));
        return !publishedAt.isBefore(triggeredAt.minus(Duration.ofHours(lookbackHours)))
            && publishedAt.atZone(zone).toLocalDate().equals(triggeredAt.atZone(zone).toLocalDate())
            && !publishedAt.isAfter(triggeredAt);
    }

    private RawNewsItem toRawItem(NewsSource source, JsonNode item, Section section) {
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
        metadata.put("important", item.path("important").asBoolean(false));
        metadata.put("announcementTypeName", item.path("announcementTypeName").asText(""));
        metadata.put("section", section.section());
        metadata.put("column", section.column());
        if (!attachmentUrl.isBlank()) {
            metadata.put("attachmentUrls", List.of(attachmentUrl));
            metadata.put("attachmentAllowedDomains", List.of("static.cninfo.com.cn"));
        }
        long timestamp = item.path("announcementTime").asLong(0);
        return new RawNewsItem(source, title, content, null, "巨潮资讯网", detailUrl,
            timestamp > 0 ? Instant.ofEpochMilli(timestamp) : null, stringConfig(source, "language", "zh-CN"),
            item.path("important").asBoolean(false)
                ? List.of(section.category(), "重要公告") : List.of(section.category()),
            securityName.isBlank() ? List.of() : List.of(securityName), Map.copyOf(metadata));
    }

    private record Section(String column, String category, String section) { }

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

    private String referer(String entryUrl) {
        int fragment = entryUrl.indexOf('#');
        return fragment < 0 ? entryUrl : entryUrl.substring(0, fragment);
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

    private boolean booleanConfig(NewsSource source, String key, boolean fallback) {
        Object value = source.configuration().get(key);
        return value == null ? fallback
            : value instanceof Boolean flag ? flag : Boolean.parseBoolean(value.toString());
    }

    private void pause(NewsSource source) throws InterruptedException {
        long millis = Math.max(0, intConfig(source, "sleepMillis", 0));
        if (millis > 0) Thread.sleep(millis);
    }
}
