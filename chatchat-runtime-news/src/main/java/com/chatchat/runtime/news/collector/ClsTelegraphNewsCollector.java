package com.chatchat.runtime.news.collector;

import com.chatchat.runtime.news.model.NewsCollectContext;
import com.chatchat.runtime.news.model.NewsCollectResult;
import com.chatchat.runtime.news.model.NewsSource;
import com.chatchat.runtime.news.model.NewsSourceType;
import com.chatchat.runtime.news.model.RawNewsItem;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Reads the same JSON cache used by the official CLS telegraph page. */
@Component
public class ClsTelegraphNewsCollector implements NewsCollector {
    private final NewsItemSink sink;
    private final ObjectMapper objectMapper;
    private final HttpClient client;

    @Autowired
    public ClsTelegraphNewsCollector(NewsItemSink sink, ObjectMapper objectMapper) {
        this(sink, objectMapper, HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build());
    }

    ClsTelegraphNewsCollector(NewsItemSink sink, ObjectMapper objectMapper, HttpClient client) {
        this.sink = sink;
        this.objectMapper = objectMapper;
        this.client = client;
    }

    @Override
    public boolean supports(NewsSourceType sourceType) {
        return sourceType == NewsSourceType.CLS_TELEGRAPH;
    }

    @Override
    public NewsCollectResult collect(NewsSource source, NewsCollectContext context) {
        int discovered = 0;
        int accepted = 0;
        int duplicate = 0;
        int rejected = 0;
        String nextCursor = null;
        try {
            int pageSize = Math.max(1, Math.min(intConfig(source, "itemLimit", 20), 100));
            int maxPages = Math.max(1, intConfig(source, "maxPagesPerRun", 200));
            Checkpoint checkpoint = Checkpoint.parse(context.lastCursor());
            long bootstrapCutoff = context.startedAt().minus(
                Duration.ofHours(Math.max(1, intConfig(source, "initialBackfillHours", 24)))).getEpochSecond();
            long pageCursor = 0;
            Set<Long> seenIds = new HashSet<>();
            boolean boundaryReached = false;
            pageLoop:
            for (int page = 0; page < maxPages; page++) {
                List<JsonNode> items = requestPage(source, pageCursor, pageSize);
                if (items.isEmpty()) {
                    if (checkpoint == null || page > 0) boundaryReached = true;
                    break;
                }
                long oldestTime = Long.MAX_VALUE;
                for (JsonNode item : items) {
                    long id = item.path("id").asLong(0);
                    long ctime = item.path("ctime").asLong(0);
                    if (nextCursor == null && id > 0 && ctime > 0) nextCursor = id + ":" + ctime;
                    if (checkpoint != null && (id == checkpoint.id()
                        || (ctime > 0 && ctime < checkpoint.ctime()))) {
                        boundaryReached = true;
                        break pageLoop;
                    }
                    if (checkpoint == null && ctime > 0 && ctime < bootstrapCutoff) {
                        boundaryReached = true;
                        break pageLoop;
                    }
                    if (id > 0 && !seenIds.add(id)) continue;
                    discovered++;
                    NewsAcceptance result = sink.accept(toRawItem(source, item));
                    if (result == NewsAcceptance.ACCEPTED) accepted++;
                    else if (result == NewsAcceptance.DUPLICATE) duplicate++;
                    else rejected++;
                    if (ctime > 0) oldestTime = Math.min(oldestTime, ctime);
                }
                if (oldestTime == Long.MAX_VALUE) {
                    throw new IllegalStateException("CLS pagination made no timestamp progress");
                }
                pageCursor = oldestTime;
                pause(source);
            }
            if (!boundaryReached) {
                throw new IllegalStateException("CLS maxPagesPerRun reached before the saved cursor; cursor not advanced");
            }
            if (rejected > 0) {
                return new NewsCollectResult(context.executionId(), source.id(), discovered, accepted, duplicate,
                    rejected, 1, "CLS items were rejected; saved cursor was not advanced");
            }
            return new NewsCollectResult(context.executionId(), source.id(), discovered, accepted, duplicate,
                rejected, 0, null, nextCursor == null ? context.lastCursor() : nextCursor);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return new NewsCollectResult(context.executionId(), source.id(), discovered, accepted, duplicate,
                rejected, 1, "CLS collection interrupted");
        } catch (Exception ex) {
            return new NewsCollectResult(context.executionId(), source.id(), discovered, accepted, duplicate,
                rejected, 1, ex.getMessage());
        }
    }

    private List<JsonNode> requestPage(NewsSource source, long lastTime, int limit) throws Exception {
        List<JsonNode> items = new ArrayList<>(request(source,
            stringConfig(source, "apiUrl", "https://www.cls.cn/api/cache"), Map.of(
                "rn", String.valueOf(limit), "lastTime", String.valueOf(lastTime), "name", "telegraph")));
        if (items.size() < limit) {
            long historyCursor = items.isEmpty() ? lastTime : items.get(items.size() - 1).path("ctime").asLong(lastTime);
            items.addAll(request(source,
                stringConfig(source, "rollApiUrl", "https://www.cls.cn/v1/roll/get_roll_list"), Map.of(
                    "refresh_type", "1", "rn", String.valueOf(limit - items.size()),
                    "last_time", String.valueOf(historyCursor))));
        }
        return items;
    }

    private List<JsonNode> request(NewsSource source, String apiUrl, Map<String, String> parameters) throws Exception {
        Map<String, String> signed = new TreeMap<>(parameters);
        signed.put("app", "CailianpressWeb");
        signed.put("os", "web");
        signed.put("sv", stringConfig(source, "apiVersion", "8.7.9"));
        signed.put("sign", sign(signed));
        String separator = apiUrl.contains("?") ? "&" : "?";
        String query = signed.entrySet().stream()
            .map(entry -> URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8) + "="
                + URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8))
            .collect(java.util.stream.Collectors.joining("&"));
        String url = apiUrl + separator + query;
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofMillis(intConfig(source, "timeoutMillis", 20_000)))
            .header("Accept", "application/json")
            .header("Referer", source.entryUrl())
            .header("User-Agent", "Mozilla/5.0 ChatChat-NewsCollector/1.0")
            .GET().build();
        HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("HTTP " + response.statusCode() + " from CLS telegraph API");
        }
        JsonNode root = objectMapper.readTree(response.body());
        if (root.path("errno").asInt(-1) != 0) {
            throw new IllegalStateException("CLS API error: " + root.path("msg").asText(root.toString()));
        }
        JsonNode items = root.path("data").path("roll_data");
        if (!items.isArray()) throw new IllegalStateException("CLS response has no roll_data array");
        List<JsonNode> result = new ArrayList<>();
        items.forEach(result::add);
        return result;
    }

    private String sign(Map<String, String> parameters) throws Exception {
        String canonical = new TreeMap<>(parameters).entrySet().stream()
            .map(entry -> entry.getKey() + "=" + entry.getValue())
            .collect(java.util.stream.Collectors.joining("&"));
        byte[] sha1 = MessageDigest.getInstance("SHA-1").digest(canonical.getBytes(StandardCharsets.UTF_8));
        byte[] md5 = MessageDigest.getInstance("MD5").digest(hex(sha1).getBytes(StandardCharsets.UTF_8));
        return hex(md5);
    }

    private String hex(byte[] bytes) {
        StringBuilder value = new StringBuilder(bytes.length * 2);
        for (byte item : bytes) value.append(String.format("%02x", item & 0xff));
        return value.toString();
    }

    private RawNewsItem toRawItem(NewsSource source, JsonNode item) {
        long id = item.path("id").asLong();
        String content = firstText(item, "content", "brief", "title");
        String title = firstText(item, "title", "brief");
        if (title.isBlank()) title = content.length() > 60 ? content.substring(0, 60) : content;
        List<String> tags = new ArrayList<>();
        for (JsonNode subject : item.path("subjects")) {
            String tag = subject.path("subject_name").asText().trim();
            if (!tag.isBlank()) tags.add(tag);
        }
        tags = new ArrayList<>(new LinkedHashSet<>(tags));
        long ctime = item.path("ctime").asLong(Instant.now().getEpochSecond());
        return new RawNewsItem(source, title, content, item.path("brief").asText(null),
            item.path("author").asText("财联社"), "https://www.cls.cn/detail/" + id,
            Instant.ofEpochSecond(ctime), stringConfig(source, "language", "zh-CN"),
            List.of("财联社电报"), tags,
            Map.of("transport", "cls-cache-api", "telegraphId", id,
                "level", item.path("level").asText(""), "readingCount", item.path("reading_num").asLong(0)));
    }

    private String firstText(JsonNode item, String... fields) {
        for (String field : fields) {
            String value = item.path(field).asText("").trim();
            if (!value.isBlank()) return value;
        }
        return "";
    }

    private String stringConfig(NewsSource source, String key, String fallback) {
        Object value = source.configuration().get(key);
        return value == null || value.toString().isBlank() ? fallback : value.toString();
    }

    private int intConfig(NewsSource source, String key, int fallback) {
        Object value = source.configuration().get(key);
        return value instanceof Number number ? number.intValue() : fallback;
    }

    private void pause(NewsSource source) throws InterruptedException {
        long millis = Math.max(0, intConfig(source, "sleepMillis", 0));
        if (millis > 0) Thread.sleep(millis);
    }

    private record Checkpoint(long id, long ctime) {
        private static Checkpoint parse(String value) {
            if (value == null || value.isBlank()) return null;
            String[] parts = value.trim().split(":", 2);
            try {
                return new Checkpoint(Long.parseLong(parts[0]), parts.length > 1 ? Long.parseLong(parts[1]) : 0);
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException("Invalid CLS cursor: " + value, ex);
            }
        }
    }
}
