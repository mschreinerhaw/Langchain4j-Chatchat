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
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

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
        try {
            JsonNode root = request(source);
            JsonNode items = root.path("data").path("roll_data");
            if (!items.isArray()) throw new IllegalStateException("CLS response has no roll_data array");
            for (JsonNode item : items) {
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

    private JsonNode request(NewsSource source) throws Exception {
        int limit = Math.max(1, Math.min(intConfig(source, "itemLimit", 30), 100));
        long lastTime = Instant.now().getEpochSecond();
        String apiUrl = stringConfig(source, "apiUrl", "https://www.cls.cn/api/cache");
        String separator = apiUrl.contains("?") ? "&" : "?";
        String url = apiUrl + separator + "rn=" + limit + "&lastTime=" + lastTime + "&name="
            + URLEncoder.encode("telegraph", StandardCharsets.UTF_8);
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
        return root;
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
}
