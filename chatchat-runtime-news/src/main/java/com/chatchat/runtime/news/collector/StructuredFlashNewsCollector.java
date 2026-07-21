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

import java.math.BigInteger;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Generic, declarative collector for cursor-paginated JSON flash-news feeds. */
@Component
public class StructuredFlashNewsCollector implements NewsCollector {
    private final NewsItemSink sink;
    private final ObjectMapper objectMapper;
    private final Map<String, StructuredFlashRequestSigner> signers;
    private final HttpClient client;

    @Autowired
    public StructuredFlashNewsCollector(NewsItemSink sink, ObjectMapper objectMapper,
                                        List<StructuredFlashRequestSigner> signers) {
        this(sink, objectMapper, signers,
            HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build());
    }

    StructuredFlashNewsCollector(NewsItemSink sink, ObjectMapper objectMapper,
                                 List<StructuredFlashRequestSigner> signers, HttpClient client) {
        this.sink = sink;
        this.objectMapper = objectMapper;
        this.client = client;
        Map<String, StructuredFlashRequestSigner> available = new HashMap<>();
        signers.forEach(signer -> available.put(signer.name(), signer));
        this.signers = Map.copyOf(available);
    }

    @Override
    public boolean supports(NewsSourceType sourceType) {
        return sourceType == NewsSourceType.STRUCTURED_FLASH;
    }

    @Override
    public NewsCollectResult collect(NewsSource source, NewsCollectContext context) {
        Counters counters = new Counters();
        String nextCursor = null;
        try {
            Template template = Template.parse(source.configuration());
            Checkpoint checkpoint = template.snapshotMode ? null : Checkpoint.parse(context.lastCursor());
            Instant cutoff = context.startedAt().minus(Duration.ofHours(template.initialBackfillHours));
            Map<String, String> pageState = new LinkedHashMap<>(template.initialState);
            pageState.putIfAbsent("cursor", template.initialCursor);
            Set<String> seenIds = new HashSet<>();
            boolean boundaryReached = false;
            pageLoop:
            for (int page = 0; page < template.maxPagesPerRun; page++) {
                Page response = requestPage(source, template, pageState);
                if (response.items.isEmpty()) {
                    boundaryReached = true;
                    break;
                }
                String lastItemCursor = null;
                for (JsonNode item : response.items) {
                    String id = firstText(item, template.idPaths);
                    String itemCursor = firstText(item, template.cursorPaths);
                    Instant publishTime = parseTime(firstText(item, template.publishTimePaths), template);
                    if (!itemCursor.isBlank()) lastItemCursor = itemCursor;
                    if (checkpoint != null && checkpoint.reached(id, itemCursor, template.numericCursorBoundary)) {
                        boundaryReached = true;
                        break pageLoop;
                    }
                    if (!template.snapshotMode && checkpoint == null && publishTime.isBefore(cutoff)) {
                        boundaryReached = true;
                        break pageLoop;
                    }
                    if (shouldSkip(item, template)) continue;
                    if (nextCursor == null && !itemCursor.isBlank()) nextCursor = id + ":" + itemCursor;
                    if (!id.isBlank() && !seenIds.add(id)) continue;
                    counters.discovered++;
                    counters.add(sink.accept(toRawItem(source, template, item, id, itemCursor, publishTime)));
                }
                if (template.snapshotMode) {
                    boundaryReached = true;
                    break;
                }
                Map<String, String> candidateState = new LinkedHashMap<>(response.nextState);
                if (candidateState.isEmpty()) {
                    String candidate = response.nextCursor.isBlank() ? lastItemCursor : response.nextCursor;
                    if (candidate != null) candidateState.put("cursor", candidate);
                }
                if (candidateState.isEmpty() || candidateState.equals(pageState)
                    || candidateState.values().stream().allMatch(String::isBlank)) {
                    throw new IllegalStateException("Structured flash pagination made no cursor progress");
                }
                pageState = candidateState;
                if (template.sleepMillis > 0) Thread.sleep(template.sleepMillis);
            }
            if (!boundaryReached) {
                throw new IllegalStateException("Structured flash maxPagesPerRun reached before the saved cursor; cursor not advanced");
            }
            if (counters.rejected > 0) {
                return counters.result(context, source,
                    "Structured flash items were rejected; saved cursor was not advanced", null);
            }
            return counters.result(context, source, null,
                template.snapshotMode ? context.lastCursor()
                    : nextCursor == null ? context.lastCursor() : nextCursor);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return counters.result(context, source, "Structured flash collection interrupted", null);
        } catch (Exception ex) {
            return counters.result(context, source, ex.getMessage(), null);
        }
    }

    private Page requestPage(NewsSource source, Template template, Map<String, String> pageState) throws Exception {
        Map<String, String> query = new LinkedHashMap<>();
        template.query.forEach((key, value) -> query.put(key, interpolate(value, pageState, template.pageSize)));
        if (template.omitBlankQueryParameters) query.values().removeIf(String::isBlank);
        Map<String, String> headers = new LinkedHashMap<>(template.headers);
        if (!"NONE".equals(template.requestSigner)) {
            StructuredFlashRequestSigner signer = signers.get(template.requestSigner);
            if (signer == null) {
                throw new IllegalArgumentException("Unknown structured flash requestSigner: " + template.requestSigner);
            }
            signer.sign(query, source);
            signer.signHeaders(headers, source);
        }
        String separator = template.apiUrl.contains("?") ? "&" : "?";
        String encoded = query.entrySet().stream()
            .map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue()))
            .collect(java.util.stream.Collectors.joining("&"));
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(template.apiUrl + separator + encoded))
            .timeout(Duration.ofMillis(template.timeoutMillis))
            .header("Accept", "application/json")
            .header("Referer", source.entryUrl())
            .header("User-Agent", "Mozilla/5.0 ChatChat-NewsCollector/1.0")
            .GET();
        headers.forEach(builder::header);
        HttpResponse<byte[]> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("HTTP " + response.statusCode() + " from structured flash API");
        }
        JsonNode root = objectMapper.readTree(response.body());
        if (!template.successPath.isBlank()
            && !template.successValue.equals(text(root, template.successPath))) {
            throw new IllegalStateException("Structured flash API error: "
                + text(root, template.errorMessagePath));
        }
        JsonNode itemNode = at(root, template.itemsPath);
        if (itemNode.isNull() || (itemNode.isTextual() && itemNode.asText().isBlank())) {
            return new Page(List.of(), "", Map.of());
        }
        if (!itemNode.isArray()) {
            throw new IllegalStateException("Structured flash itemsPath does not point to an array: " + template.itemsPath);
        }
        List<JsonNode> items = new ArrayList<>();
        itemNode.forEach(items::add);
        Map<String, String> nextState = new LinkedHashMap<>();
        template.nextStatePaths.forEach((key, path) -> nextState.put(key, text(root, path)));
        if (!items.isEmpty()) {
            JsonNode lastItem = items.get(items.size() - 1);
            template.nextStateItemPaths.forEach((key, path) -> nextState.put(key, text(lastItem, path)));
        }
        return new Page(items, text(root, template.nextCursorPath), nextState);
    }

    private RawNewsItem toRawItem(NewsSource source, Template template, JsonNode item,
                                  String id, String cursor, Instant publishTime) {
        String content = firstText(item, template.contentPaths);
        String title = firstText(item, template.titlePaths);
        if (title.isBlank()) title = abbreviate(content, template.generatedTitleLength);
        String summary = firstText(item, template.summaryPaths);
        String author = firstText(item, template.authorPaths);
        if (author.isBlank()) author = template.defaultAuthor;
        Set<String> tags = new LinkedHashSet<>(template.tags);
        template.tagPaths.forEach(path -> tags.addAll(stringsAt(item, path)));
        if (template.legalRisk) tags.add("法律风险");
        Map<String, Object> metadata = new LinkedHashMap<>(template.staticMetadata);
        metadata.put("transport", "structured-flash-api");
        metadata.put("itemId", id);
        metadata.put("itemCursor", cursor);
        template.metadataPaths.forEach((key, path) -> {
            Object value = valueAt(item, path);
            if (value != null) metadata.put(key, value);
        });
        metadata.put("legalRisk", template.legalRisk);
        if (!template.legalDisclaimer.isBlank()) metadata.put("legalDisclaimer", template.legalDisclaimer);
        return new RawNewsItem(source, title, content, summary, author,
            interpolateItem(template.sourceUrlTemplate, item, id), publishTime,
            template.language, template.categories, List.copyOf(tags), Map.copyOf(metadata));
    }

    private Instant parseTime(String value, Template template) {
        if (value == null || value.isBlank()) return Instant.now();
        return switch (template.publishTimeFormat) {
            case "EPOCH_SECONDS" -> Instant.ofEpochSecond(Long.parseLong(value));
            case "EPOCH_MILLIS" -> Instant.ofEpochMilli(Long.parseLong(value));
            default -> LocalDateTime.parse(value, DateTimeFormatter.ofPattern(template.publishTimeFormat))
                .atZone(ZoneId.of(template.zoneId)).toInstant();
        };
    }

    private String interpolate(String value, Map<String, String> state, int pageSize) {
        String result = value.replace("${cursor}", state.getOrDefault("cursor", ""))
            .replace("${pageSize}", Integer.toString(pageSize))
            .replace("${timestamp}", Long.toString(System.currentTimeMillis()));
        for (Map.Entry<String, String> entry : state.entrySet()) {
            result = result.replace("${state." + entry.getKey() + "}", entry.getValue());
        }
        return result;
    }

    private String interpolateItem(String template, JsonNode item, String id) {
        String result = template.replace("${id}", id);
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\\$\\{([^}]+)}").matcher(result);
        StringBuffer value = new StringBuffer();
        while (matcher.find()) matcher.appendReplacement(value,
            java.util.regex.Matcher.quoteReplacement(text(item, matcher.group(1))));
        matcher.appendTail(value);
        return value.toString();
    }

    private JsonNode at(JsonNode node, String dottedPath) {
        if (dottedPath == null || dottedPath.isBlank()) return node;
        JsonNode current = node;
        for (String part : dottedPath.split("\\.")) current = current.path(part);
        return current;
    }

    private String text(JsonNode node, String path) {
        JsonNode value = at(node, path);
        return value.isMissingNode() || value.isNull() ? "" : value.asText("").trim();
    }

    private String firstText(JsonNode item, List<String> paths) {
        for (String path : paths) {
            String value = text(item, path);
            if (!value.isBlank()) return value;
        }
        return "";
    }

    private List<String> stringsAt(JsonNode node, String path) {
        String[] parts = path.split("\\.");
        List<JsonNode> current = List.of(node);
        for (String part : parts) {
            List<JsonNode> next = new ArrayList<>();
            for (JsonNode candidate : current) expandPathPart(candidate, part, next);
            current = next;
        }
        List<String> values = new ArrayList<>();
        for (JsonNode candidate : current) {
            if (candidate.isArray()) candidate.forEach(value -> addText(values, value));
            else addText(values, candidate);
        }
        return values;
    }

    private void expandPathPart(JsonNode candidate, String part, List<JsonNode> values) {
        if (candidate.isArray()) candidate.forEach(child -> expandPathPart(child, part, values));
        else values.add(candidate.path(part));
    }

    private void addText(List<String> values, JsonNode node) {
        String value = node.asText("").trim();
        if (!value.isBlank()) values.add(value);
    }

    private Object valueAt(JsonNode item, String path) {
        JsonNode value = at(item, path);
        return objectMapper.convertValue(value, Object.class);
    }

    private boolean shouldSkip(JsonNode item, Template template) {
        return template.skipWhen.entrySet().stream()
            .anyMatch(entry -> entry.getValue().equals(text(item, entry.getKey())));
    }

    private String abbreviate(String value, int maxLength) {
        return value.length() > maxLength ? value.substring(0, maxLength) : value;
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private record Page(List<JsonNode> items, String nextCursor, Map<String, String> nextState) { }

    private record Checkpoint(String id, String cursor) {
        private boolean reached(String candidateId, String candidateCursor, boolean numericBoundary) {
            if (!id.isBlank() && id.equals(candidateId)) return true;
            if (cursor.isBlank() || candidateCursor.isBlank()) return false;
            if (!numericBoundary) return cursor.equals(candidateCursor);
            try {
                return new BigInteger(candidateCursor).compareTo(new BigInteger(cursor)) <= 0;
            } catch (NumberFormatException ignored) {
                return cursor.equals(candidateCursor);
            }
        }

        private static Checkpoint parse(String value) {
            if (value == null || value.isBlank()) return null;
            String[] parts = value.split(":", 2);
            return new Checkpoint(parts[0], parts.length > 1 ? parts[1] : "");
        }
    }

    private static final class Counters {
        int discovered;
        int accepted;
        int duplicate;
        int rejected;

        void add(NewsAcceptance result) {
            if (result == NewsAcceptance.ACCEPTED) accepted++;
            else if (result == NewsAcceptance.DUPLICATE) duplicate++;
            else rejected++;
        }

        NewsCollectResult result(NewsCollectContext context, NewsSource source, String error, String cursor) {
            return new NewsCollectResult(context.executionId(), source.id(), discovered, accepted, duplicate,
                rejected, error == null ? 0 : 1, error, cursor);
        }
    }

    private static final class Template {
        String apiUrl;
        Map<String, String> query;
        Map<String, String> headers;
        String requestSigner;
        boolean omitBlankQueryParameters;
        String successPath;
        String successValue;
        String errorMessagePath;
        String itemsPath;
        String nextCursorPath;
        Map<String, String> nextStatePaths;
        Map<String, String> nextStateItemPaths;
        List<String> idPaths;
        List<String> cursorPaths;
        List<String> titlePaths;
        List<String> contentPaths;
        List<String> summaryPaths;
        List<String> authorPaths;
        List<String> publishTimePaths;
        List<String> tagPaths;
        Map<String, String> metadataPaths;
        Map<String, String> skipWhen;
        String sourceUrlTemplate;
        String defaultAuthor;
        String publishTimeFormat;
        String zoneId;
        String language;
        List<String> categories;
        List<String> tags;
        Map<String, Object> staticMetadata;
        boolean legalRisk;
        String legalDisclaimer;
        boolean numericCursorBoundary;
        boolean snapshotMode;
        String initialCursor;
        Map<String, String> initialState;
        int pageSize;
        int maxPagesPerRun;
        int initialBackfillHours;
        int timeoutMillis;
        int sleepMillis;
        int generatedTitleLength;

        static Template parse(Map<String, Object> configuration) {
            Config root = new Config(configuration);
            Config request = root.child("request");
            Config response = root.child("response");
            Config mapping = root.child("mapping");
            Config compliance = root.child("compliance");
            Template value = new Template();
            value.apiUrl = request.requiredString("url");
            value.query = request.stringMap("query");
            value.headers = request.stringMap("headers");
            value.requestSigner = request.string("signer", "NONE").toUpperCase();
            value.omitBlankQueryParameters = request.bool("omitBlankQueryParameters", false);
            value.successPath = response.string("successPath", "");
            value.successValue = response.string("successValue", "");
            value.errorMessagePath = response.string("errorMessagePath", "message");
            value.itemsPath = response.requiredString("itemsPath");
            value.nextCursorPath = response.string("nextCursorPath", "");
            value.nextStatePaths = response.stringMap("nextState");
            value.nextStateItemPaths = response.stringMap("nextStateFromLastItem");
            value.idPaths = mapping.paths("id", List.of("id"));
            value.cursorPaths = mapping.paths("cursor", List.of("id"));
            value.titlePaths = mapping.paths("title", List.of("title"));
            value.contentPaths = mapping.paths("content", List.of("content"));
            value.summaryPaths = mapping.paths("summary", List.of("summary"));
            value.authorPaths = mapping.paths("author", List.of("author"));
            value.publishTimePaths = mapping.paths("publishTime", List.of("publishTime"));
            value.tagPaths = mapping.stringList("tagPaths");
            value.metadataPaths = mapping.stringMap("metadata");
            value.skipWhen = mapping.stringMap("skipWhen");
            value.sourceUrlTemplate = mapping.requiredString("sourceUrl");
            value.defaultAuthor = mapping.string("defaultAuthor", "");
            value.publishTimeFormat = mapping.string("publishTimeFormat", "yyyy-MM-dd HH:mm:ss");
            value.zoneId = root.string("zoneId", "Asia/Shanghai");
            value.language = root.string("language", "zh-CN");
            value.categories = compliance.stringList("categories");
            value.tags = compliance.stringList("tags");
            value.staticMetadata = mapping.objectMap("staticMetadata");
            value.legalRisk = compliance.bool("legalRisk", false);
            value.legalDisclaimer = compliance.string("legalDisclaimer", "");
            value.numericCursorBoundary = root.bool("numericCursorBoundary", true);
            value.snapshotMode = root.bool("snapshotMode", false);
            value.initialCursor = root.string("initialCursor", "");
            value.initialState = root.stringMap("initialState");
            value.pageSize = root.integer("itemLimit", 50, 1, 100);
            value.maxPagesPerRun = root.integer("maxPagesPerRun", 200, 1, 10_000);
            value.initialBackfillHours = root.integer("initialBackfillHours", 24, 1, 24 * 365);
            value.timeoutMillis = root.integer("timeoutMillis", 20_000, 1_000, 120_000);
            value.sleepMillis = root.integer("sleepMillis", 0, 0, 60_000);
            value.generatedTitleLength = root.integer("generatedTitleLength", 60, 1, 500);
            return value;
        }

    }

    private static final class Config {
        private final Map<String, Object> values;

        Config(Map<String, Object> values) {
            this.values = values == null ? Map.of() : values;
        }

        Config child(String key) {
            Object value = values.get(key);
            if (value instanceof Map<?, ?> map) {
                Map<String, Object> converted = new LinkedHashMap<>();
                map.forEach((itemKey, itemValue) -> converted.put(String.valueOf(itemKey), itemValue));
                return new Config(converted);
            }
            return new Config(Map.of());
        }

        String requiredString(String key) {
            String value = string(key, "");
            if (value.isBlank()) throw new IllegalArgumentException("Structured flash template requires " + key);
            return value;
        }

        String string(String key, String fallback) {
            Object value = values.get(key);
            return value == null || value.toString().isBlank() ? fallback : value.toString();
        }

        boolean bool(String key, boolean fallback) {
            Object value = values.get(key);
            return value instanceof Boolean bool ? bool : fallback;
        }

        int integer(String key, int fallback, int minimum, int maximum) {
            Object value = values.get(key);
            int result = value instanceof Number number ? number.intValue() : fallback;
            return Math.max(minimum, Math.min(result, maximum));
        }

        List<String> paths(String key, List<String> fallback) {
            List<String> result = stringList(key);
            if (!result.isEmpty()) return result;
            String single = string(key, "");
            return single.isBlank() ? fallback : List.of(single);
        }

        List<String> stringList(String key) {
            Object value = values.get(key);
            if (!(value instanceof Iterable<?> items)) return List.of();
            List<String> result = new ArrayList<>();
            for (Object item : items) if (item != null && !item.toString().isBlank()) result.add(item.toString());
            return List.copyOf(result);
        }

        Map<String, String> stringMap(String key) {
            Object value = values.get(key);
            if (!(value instanceof Map<?, ?> map)) return Map.of();
            Map<String, String> result = new LinkedHashMap<>();
            map.forEach((itemKey, itemValue) -> result.put(String.valueOf(itemKey), String.valueOf(itemValue)));
            return Map.copyOf(result);
        }

        Map<String, Object> objectMap(String key) {
            Object value = values.get(key);
            if (!(value instanceof Map<?, ?> map)) return Map.of();
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((itemKey, itemValue) -> result.put(String.valueOf(itemKey), itemValue));
            return Map.copyOf(result);
        }
    }
}
