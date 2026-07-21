package com.chatchat.runtime.news.collector;

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
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Collects the JSON feed used by Eastmoney's official 7x24 global finance page. */
@Component
public class Eastmoney724NewsCollector implements NewsCollector {
    private static final DateTimeFormatter SHOW_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String DEFAULT_DISCLAIMER =
        "内容版权归东方财富及原作者所有，仅用于内部资讯分析，不构成投资建议；使用前请确认已获得符合网站条款及适用法律的授权。";

    private final NewsItemSink sink;
    private final ObjectMapper objectMapper;
    private final HttpClient client;

    @Autowired
    public Eastmoney724NewsCollector(NewsItemSink sink, ObjectMapper objectMapper) {
        this(sink, objectMapper, HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build());
    }

    Eastmoney724NewsCollector(NewsItemSink sink, ObjectMapper objectMapper, HttpClient client) {
        this.sink = sink;
        this.objectMapper = objectMapper;
        this.client = client;
    }

    @Override
    public boolean supports(NewsSourceType sourceType) {
        return sourceType == NewsSourceType.EASTMONEY_724;
    }

    @Override
    public NewsCollectResult collect(NewsSource source, NewsCollectContext context) {
        int discovered = 0;
        int accepted = 0;
        int duplicate = 0;
        int rejected = 0;
        String nextCursor = null;
        try {
            int pageSize = Math.max(1, Math.min(intConfig(source, "itemLimit", 50), 100));
            int maxPages = Math.max(1, intConfig(source, "maxPagesPerRun", 200));
            Checkpoint checkpoint = Checkpoint.parse(context.lastCursor());
            Instant bootstrapCutoff = context.startedAt().minus(
                Duration.ofHours(Math.max(1, intConfig(source, "initialBackfillHours", 24))));
            String pageCursor = "";
            Set<String> seenCodes = new HashSet<>();
            boolean boundaryReached = false;
            pageLoop:
            for (int page = 0; page < maxPages; page++) {
                Page response = requestPage(source, pageCursor, pageSize);
                if (response.items().isEmpty()) {
                    boundaryReached = true;
                    break;
                }
                String lastSort = null;
                for (JsonNode item : response.items()) {
                    String code = item.path("code").asText("").trim();
                    String realSort = item.path("realSort").asText("").trim();
                    Instant publishTime = publishTime(source, item);
                    if (nextCursor == null && !realSort.isBlank()) nextCursor = code + ":" + realSort;
                    if (checkpoint != null && checkpoint.matches(code, realSort)) {
                        boundaryReached = true;
                        break pageLoop;
                    }
                    if (checkpoint == null && publishTime.isBefore(bootstrapCutoff)) {
                        boundaryReached = true;
                        break pageLoop;
                    }
                    if (!code.isBlank() && !seenCodes.add(code)) continue;
                    discovered++;
                    NewsAcceptance result = sink.accept(toRawItem(source, item, publishTime));
                    if (result == NewsAcceptance.ACCEPTED) accepted++;
                    else if (result == NewsAcceptance.DUPLICATE) duplicate++;
                    else rejected++;
                    if (!realSort.isBlank()) lastSort = realSort;
                }
                String candidate = response.sortEnd().isBlank() ? lastSort : response.sortEnd();
                if (candidate == null || candidate.isBlank() || candidate.equals(pageCursor)) {
                    throw new IllegalStateException("Eastmoney 7x24 pagination made no cursor progress");
                }
                pageCursor = candidate;
                pause(source);
            }
            if (!boundaryReached) {
                throw new IllegalStateException("Eastmoney 7x24 maxPagesPerRun reached before the saved cursor; cursor not advanced");
            }
            if (rejected > 0) {
                return new NewsCollectResult(context.executionId(), source.id(), discovered, accepted, duplicate,
                    rejected, 1, "Eastmoney 7x24 items were rejected; saved cursor was not advanced");
            }
            return new NewsCollectResult(context.executionId(), source.id(), discovered, accepted, duplicate,
                rejected, 0, null, nextCursor == null ? context.lastCursor() : nextCursor);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return new NewsCollectResult(context.executionId(), source.id(), discovered, accepted, duplicate,
                rejected, 1, "Eastmoney 7x24 collection interrupted");
        } catch (Exception ex) {
            return new NewsCollectResult(context.executionId(), source.id(), discovered, accepted, duplicate,
                rejected, 1, ex.getMessage());
        }
    }

    private Page requestPage(NewsSource source, String sortEnd, int pageSize) throws Exception {
        String apiUrl = stringConfig(source, "apiUrl",
            "https://np-weblist.eastmoney.com/comm/web/getFastNewsList");
        String separator = apiUrl.contains("?") ? "&" : "?";
        String query = "client=web&biz=web_724&fastColumn=" + encode(stringConfig(source, "fastColumn", "102"))
            + "&sortEnd=" + encode(sortEnd) + "&pageSize=" + pageSize
            + "&req_trace=" + contextTrace();
        HttpRequest request = HttpRequest.newBuilder(URI.create(apiUrl + separator + query))
            .timeout(Duration.ofMillis(intConfig(source, "timeoutMillis", 20_000)))
            .header("Accept", "application/json")
            .header("Referer", source.entryUrl())
            .header("User-Agent", "Mozilla/5.0 ChatChat-NewsCollector/1.0")
            .GET().build();
        HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("HTTP " + response.statusCode() + " from Eastmoney 7x24 API");
        }
        JsonNode root = objectMapper.readTree(response.body());
        if (!"1".equals(root.path("code").asText())) {
            throw new IllegalStateException("Eastmoney 7x24 API error: "
                + root.path("message").asText(root.toString()));
        }
        JsonNode data = root.path("data");
        JsonNode items = data.path("fastNewsList");
        if (!items.isArray()) throw new IllegalStateException("Eastmoney 7x24 response has no fastNewsList array");
        List<JsonNode> result = new ArrayList<>();
        items.forEach(result::add);
        return new Page(result, data.path("sortEnd").asText(""));
    }

    private RawNewsItem toRawItem(NewsSource source, JsonNode item, Instant publishTime) {
        String code = item.path("code").asText("").trim();
        String summary = item.path("summary").asText("").trim();
        String title = item.path("title").asText("").trim();
        if (title.isBlank()) title = summary.length() > 60 ? summary.substring(0, 60) : summary;
        Set<String> tags = new LinkedHashSet<>();
        tags.add("东方财富");
        tags.add("7×24快讯");
        if (booleanConfig(source, "legalRisk", true)) tags.add("法律风险");
        String disclaimer = stringConfig(source, "legalDisclaimer", DEFAULT_DISCLAIMER);
        return new RawNewsItem(source, title, summary, summary, "东方财富",
            "https://finance.eastmoney.com/a/" + code + ".html", publishTime,
            stringConfig(source, "language", "zh-CN"), List.of("东方财富全球财经快讯"),
            List.copyOf(tags), Map.of(
                "transport", "eastmoney-fast-news-api", "newsCode", code,
                "realSort", item.path("realSort").asText(""),
                "stockList", objectMapper.convertValue(item.path("stockList"), List.class),
                "legalRisk", booleanConfig(source, "legalRisk", true),
                "legalDisclaimer", disclaimer));
    }

    private Instant publishTime(NewsSource source, JsonNode item) {
        String value = item.path("showTime").asText("").trim();
        if (value.isBlank()) return Instant.now();
        return LocalDateTime.parse(value, SHOW_TIME)
            .atZone(ZoneId.of(stringConfig(source, "zoneId", "Asia/Shanghai"))).toInstant();
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private String contextTrace() {
        return Long.toString(System.currentTimeMillis());
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
        return value instanceof Boolean bool ? bool : fallback;
    }

    private void pause(NewsSource source) throws InterruptedException {
        long millis = Math.max(0, intConfig(source, "sleepMillis", 0));
        if (millis > 0) Thread.sleep(millis);
    }

    private record Page(List<JsonNode> items, String sortEnd) { }

    private record Checkpoint(String code, String realSort) {
        private boolean matches(String candidateCode, String candidateSort) {
            return (!code.isBlank() && code.equals(candidateCode))
                || (!realSort.isBlank() && realSort.equals(candidateSort));
        }

        private static Checkpoint parse(String value) {
            if (value == null || value.isBlank()) return null;
            String[] parts = value.trim().split(":", 2);
            return new Checkpoint(parts[0], parts.length > 1 ? parts[1] : "");
        }
    }
}
