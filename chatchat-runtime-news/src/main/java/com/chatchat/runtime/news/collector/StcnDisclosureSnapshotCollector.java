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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Collects today's disclosures from page one of every configured Securities Times board. */
@Component
public class StcnDisclosureSnapshotCollector implements NewsCollector {
    private final NewsItemSink sink;
    private final ObjectMapper objectMapper;
    private final NewsRuntimeProperties properties;
    private final StcnWebStructuredFlashRequestSigner signer;
    private final HttpClient client;

    @Autowired
    public StcnDisclosureSnapshotCollector(NewsItemSink sink, ObjectMapper objectMapper,
                                           NewsRuntimeProperties properties,
                                           StcnWebStructuredFlashRequestSigner signer) {
        this(sink, objectMapper, properties, signer,
            HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build());
    }

    StcnDisclosureSnapshotCollector(NewsItemSink sink, ObjectMapper objectMapper,
                                    NewsRuntimeProperties properties,
                                    StcnWebStructuredFlashRequestSigner signer, HttpClient client) {
        this.sink = sink;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.signer = signer;
        this.client = client;
    }

    @Override
    public boolean supports(NewsSourceType sourceType) {
        return sourceType == NewsSourceType.STCN_DISCLOSURES;
    }

    @Override
    public NewsCollectResult collect(NewsSource source, NewsCollectContext context) {
        Counters counters = new Counters();
        List<String> errors = new ArrayList<>();
        ZoneId zone = ZoneId.of(stringConfig(source, "zoneId", "Asia/Shanghai"));
        LocalDate collectionDate = context.startedAt().atZone(zone).toLocalDate();
        Map<String, Draft> unique = new LinkedHashMap<>();
        for (Section section : sections(source)) {
            try {
                JsonNode root = request(source, section.type());
                if (root.path("state").asInt() != 1 || !root.path("data").isArray()) {
                    throw new IllegalStateException(root.path("msg").asText("response has no data array"));
                }
                for (JsonNode item : root.path("data")) {
                    String dateText = item.path("time").asText("").trim();
                    if (!dateText.startsWith(collectionDate.toString())) continue;
                    String title = item.path("title").asText("").trim();
                    String url = secureUrl(item.path("url").asText(""));
                    if (title.isBlank() || url.isBlank()) continue;
                    Draft draft = unique.computeIfAbsent(url, ignored -> new Draft(item, url));
                    draft.sections.add(section.name());
                    draft.sectionTypes.add(section.type());
                }
            } catch (Exception ex) {
                counters.failed++;
                errors.add(section.name() + ": " + ex.getMessage());
            }
        }
        for (Draft draft : unique.values()) {
            if (counters.discovered >= properties.getMaxItemsPerRun()) {
                counters.failed++;
                errors.add("达到 maxItemsPerRun，未能保存全部当日第一页披露");
                break;
            }
            accept(source, collectionDate, zone, draft, counters);
        }
        return new NewsCollectResult(context.executionId(), source.id(), counters.discovered, counters.accepted,
            counters.duplicate, counters.rejected, counters.failed,
            errors.isEmpty() ? null : String.join("; ", errors), context.lastCursor());
    }

    private JsonNode request(NewsSource source, String type) throws Exception {
        String apiUrl = stringConfig(source, "apiUrl", "https://www.stcn.com/xinpi/list-ajax.html");
        int pageSize = Math.min(20, Math.max(1, intConfig(source, "pageSize", 20)));
        String separator = apiUrl.contains("?") ? "&" : "?";
        String url = apiUrl + separator + "page=1&pageSize=" + pageSize + "&type="
            + URLEncoder.encode(type, StandardCharsets.UTF_8);
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("X-Requested-With", "XMLHttpRequest");
        signer.signHeaders(headers, source);
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofMillis(intConfig(source, "timeoutMillis", 20_000)))
            .header("Accept", "application/json")
            .header("Referer", source.entryUrl())
            .header("User-Agent", "Mozilla/5.0 ChatChat-NewsCollector/1.0")
            .GET();
        headers.forEach(builder::header);
        HttpResponse<byte[]> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("HTTP " + response.statusCode() + " from STCN disclosure API");
        }
        return objectMapper.readTree(response.body());
    }

    private void accept(NewsSource source, LocalDate collectionDate, ZoneId zone,
                        Draft draft, Counters counters) {
        JsonNode item = draft.item;
        String title = item.path("title").asText("").trim();
        String dateText = item.path("time").asText("").trim();
        List<String> sectionNames = List.copyOf(draft.sections);
        String content = "证券时报信息披露平台当日公告。板块：" + String.join("、", sectionNames)
            + "；发布日期：" + dateText + "；公告标题：" + title + "；公告原文：" + draft.url;
        String disclaimer = stringConfig(source, "legalDisclaimer",
            "公告版权归原披露主体及指定信息披露媒体所有，应以指定媒体披露的公告全文为准，不构成投资建议。");
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("transport", "stcn-xinpi-api");
        metadata.put("provider", "STCN");
        metadata.put("firstPageOnly", true);
        metadata.put("collectionDate", collectionDate.toString());
        metadata.put("sections", sectionNames);
        metadata.put("sectionTypes", List.copyOf(draft.sectionTypes));
        metadata.put("disclosureId", item.path("id").asText(""));
        metadata.put("securityCode", item.path("code").asText(""));
        metadata.put("securityName", item.path("name").asText(""));
        metadata.put("attachmentUrls", List.of(draft.url));
        metadata.put("attachmentAllowedDomains", stringList(source.configuration().get("attachmentAllowedDomains")));
        metadata.put("legalRisk", true);
        metadata.put("legalDisclaimer", disclaimer);
        List<String> tags = new ArrayList<>(List.of("证券时报", "信息披露", "当日公告", "法律风险"));
        tags.addAll(sectionNames);
        RawNewsItem raw = new RawNewsItem(source, title, content, null, "证券时报信息披露平台",
            draft.url, parseTime(dateText, zone), stringConfig(source, "language", "zh-CN"),
            List.of("证券时报信息披露"), List.copyOf(tags), Map.copyOf(metadata));
        counters.discovered++;
        NewsAcceptance result = sink.accept(raw);
        if (result == NewsAcceptance.ACCEPTED) counters.accepted++;
        else if (result == NewsAcceptance.DUPLICATE) counters.duplicate++;
        else counters.rejected++;
    }

    private Instant parseTime(String value, ZoneId zone) {
        for (String pattern : List.of("yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm")) {
            try {
                return LocalDateTime.parse(value, DateTimeFormatter.ofPattern(pattern)).atZone(zone).toInstant();
            } catch (Exception ignored) { }
        }
        return LocalDate.parse(value.substring(0, Math.min(10, value.length())))
            .atStartOfDay(zone).toInstant();
    }

    private String secureUrl(String value) {
        if (value == null || value.isBlank() || "javascript:;".equalsIgnoreCase(value.trim())) return "";
        try {
            URI uri = URI.create(value.trim());
            if (uri.getHost() == null || !("http".equalsIgnoreCase(uri.getScheme())
                || "https".equalsIgnoreCase(uri.getScheme()))) return "";
            if ("http".equalsIgnoreCase(uri.getScheme())) {
                return new URI("https", uri.getUserInfo(), uri.getHost(), uri.getPort(), uri.getPath(),
                    uri.getQuery(), null).toString();
            }
            return uri.toString();
        } catch (Exception ignored) {
            return "";
        }
    }

    @SuppressWarnings("unchecked")
    private List<Section> sections(NewsSource source) {
        Object configured = source.configuration().get("sections");
        if (!(configured instanceof Iterable<?> values)) {
            throw new IllegalArgumentException("STCN disclosure sections are required");
        }
        List<Section> result = new ArrayList<>();
        for (Object value : values) {
            if (!(value instanceof Map<?, ?> map)) continue;
            Object typeValue = map.get("type");
            Object nameValue = map.get("name");
            String type = typeValue == null ? "" : typeValue.toString().trim();
            String name = nameValue == null ? "" : nameValue.toString().trim();
            if (!type.isBlank() && !name.isBlank()) result.add(new Section(type, name));
        }
        if (result.isEmpty()) throw new IllegalArgumentException("STCN disclosure sections are empty");
        return List.copyOf(result);
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
        if (!(value instanceof Iterable<?> values)) return List.of();
        List<String> result = new ArrayList<>();
        for (Object item : values) if (item != null && !item.toString().isBlank()) result.add(item.toString());
        return List.copyOf(result);
    }

    private record Section(String type, String name) { }

    private static final class Draft {
        final JsonNode item;
        final String url;
        final Set<String> sections = new LinkedHashSet<>();
        final Set<String> sectionTypes = new LinkedHashSet<>();

        Draft(JsonNode item, String url) {
            this.item = item;
            this.url = url;
        }
    }

    private static final class Counters {
        int discovered;
        int accepted;
        int duplicate;
        int rejected;
        int failed;
    }
}
